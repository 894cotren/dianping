package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //用于获取代理类
    @Resource
    private ApplicationContext applicationContext;


    @Override
    public Result seckillVoucher(Long voucherId){
        Result result = seckillVoucherByMutexOneUserOneOrder(voucherId);
        return result;
    }


    /**
     * 乐观锁+互斥锁实现一人一单
     * @param voucherId
     * @return
     */
    public Result seckillVoucherByMutexOneUserOneOrder(Long voucherId){
        //查询库存
        //判断库存是否大于0
        //秒杀扣减一个,然后下单
        //判断秒杀券是否有效
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(endTime) || now.isBefore(beginTime)){
            return Result.fail("秒杀时间不合规，请在规定时间内秒杀");
        }

        if (seckillVoucher.getStock()<=0){
            return Result.fail("库存不足秒杀失败");
        }
        //插入一人一单限制
        UserDTO user = UserHolder.getUser();
        long userId = user.getId();
        RLock rLock = redissonClient.getLock("seckill:user:lock" + userId);
        //尝试获取锁
        boolean hasLock = rLock.tryLock();
        if (!hasLock){
            return Result.fail("点击繁忙，请勿重复点击");
        }
        //拿到互斥锁
        //判断是否已经下单
        long orderId = 0;
        try {
            //用户扣减库存、下订单；  代理类调用一个事务，防止自调用。 也可以使用编程式事务；
            IVoucherOrderService  proxy = applicationContext.getBean(IVoucherOrderService.class);
            orderId = proxy.createVoucherOrder(userId, voucherId);
        } catch (RuntimeException e) {
            log.error("秒杀异常："+e.getMessage());
            return Result.fail("秒杀失败:"+e.getMessage());
        } finally {
            //释放锁
            rLock.unlock();
        }
        return Result.ok(orderId);
    }

    /**
     * 用户扣减库存下订单逻辑
     * 返回订单id
     * @param userId 用户ID
     * @param voucherId 券id
     * @return
     */
    @Transactional
    @Override
    public long createVoucherOrder(long userId, long voucherId){
        int count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count>0){
            throw new RuntimeException("每个用户仅能秒杀一次，请勿重复秒杀");
        }
        //走正常业务逻辑
        //扣减库存 （乐观锁）
        boolean update = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                //设置当大于0的时候才可以扣减库存
                .gt("stock", 0)
                .update();
        if (!update){
            throw new RuntimeException("秒杀下单失败");
        }
        //下订单
        //生成订单号
        RedisIdWorker redisIdWorker = new RedisIdWorker(stringRedisTemplate);
        long orderId = redisIdWorker.nextId("seckill:order");
        VoucherOrder voucherOrder = new VoucherOrder();
        //手动设置进去咱们生成的唯一id
        voucherOrder.setId(orderId);
        //获取下单人id;
        voucherOrder.setUserId(userId);
        //设置关联秒杀券id
        voucherOrder.setVoucherId(voucherId);
        boolean save = this.save(voucherOrder);
        if (!save){
            throw new RuntimeException("秒杀下单失败");
        }
        return orderId;
    }


    /**
     * 最简陋、问题最多的秒杀，我200个线程秒杀100个能全成功。
     * @param voucherId
     * @return
     */
    @Transactional
    public Result seckillVoucherByCommon(Long voucherId) {
        //实现一个最简陋的、有问题的库存扣减
        //查询库存
        //判断库存是否大于0
        //秒杀扣减一个,然后下单
        //判断秒杀券是否有效
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(endTime) || now.isBefore(beginTime)){
            return Result.fail("秒杀时间不合规，请在规定时间内秒杀");
        }

        if (seckillVoucher.getStock()<=0){
            return Result.fail("库存不足秒杀失败");
        }
        //扣减库存
        seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id",voucherId)
                .update();
        //下订单
        //生成订单号
        RedisIdWorker redisIdWorker = new RedisIdWorker(stringRedisTemplate);
        long orderId = redisIdWorker.nextId("seckill:order");
        VoucherOrder voucherOrder = new VoucherOrder();
        //手动设置进去咱们生成的唯一id
        voucherOrder.setId(orderId);
        //获取下单人id;
        UserDTO user = UserHolder.getUser();
        voucherOrder.setUserId(user.getId());
        //设置关联秒杀券id
        voucherOrder.setVoucherId(voucherId);
        boolean save = this.save(voucherOrder);
        if (!save){
            return  Result.fail("下单失败");
        }
        return Result.ok(orderId);
    }

    /**
     *  乐观锁思想，通过sql里面判断库存是否>0来扣减，实现秒杀不会库存超卖。
     * @param voucherId
     * @return
     */
    @Transactional
    public Result seckillVoucherByOptimistic(Long voucherId) {
        //查询库存
        //判断库存是否大于0
        //秒杀扣减一个,然后下单
        //判断秒杀券是否有效
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(endTime) || now.isBefore(beginTime)){
            return Result.fail("秒杀时间不合规，请在规定时间内秒杀");
        }

        if (seckillVoucher.getStock()<=0){
            return Result.fail("库存不足秒杀失败");
        }
        //扣减库存 （乐观锁）
        boolean update = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                //设置当大于0的时候才可以扣减库存
                .gt("stock", 0)
                .update();
        if (!update){
            return  Result.fail("秒杀失败");
        }
        //下订单
        //生成订单号
        RedisIdWorker redisIdWorker = new RedisIdWorker(stringRedisTemplate);
        long orderId = redisIdWorker.nextId("seckill:order");
        VoucherOrder voucherOrder = new VoucherOrder();
        //手动设置进去咱们生成的唯一id
        voucherOrder.setId(orderId);
        //获取下单人id;
        UserDTO user = UserHolder.getUser();
        voucherOrder.setUserId(user.getId());
        //设置关联秒杀券id
        voucherOrder.setVoucherId(voucherId);
        boolean save = this.save(voucherOrder);
        if (!save){
            throw new RuntimeException("秒杀下单失败");
        }
        return Result.ok(orderId);
    }

}
