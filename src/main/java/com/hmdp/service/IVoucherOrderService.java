package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 优惠券秒杀
     * @param voucherId 优惠券id
     * @return
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 用户扣减库存、下单逻辑封装，便于使用声明式事务
     * @param userId
     * @param voucherId
     * @return
     */
    long createVoucherOrder(long userId, long voucherId);
}
