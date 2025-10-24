package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryShopById(Long id) {
        //普通缓存方式
        Result result = this.queryShopByIdCommon(id);
        //缓存&&缓存穿透（缓存null值）
//        Result result = this.queryShopByIdCacheThrough(id);
        //互斥锁解决缓存击穿问题
//        Result result =  this.queryShopByIdMutex(id);
        //逻辑过期解决缓存击穿问题
//        Result result = this.queryShopByIdLogicExpire(id);
        return result;
    }


    /**
     * 逻辑过期解决缓存击穿问题——最终一致性
     * @param id
     * @return
     */
    @Override
    public Result queryShopByIdLogicExpire(Long id) {
        //逻辑过期解决缓存击穿问题； PS ，逻辑过期解决我们需要进行一个缓存预热的； 我们没有添加缓存的逻辑，只有更新缓存的逻辑；
        //而且逻辑过期需要一个新字段：逻辑过期字段，我们需要开闭原则只增代码不改代码，所以我们定义个RedisData封装一下。
        //缓存就存储RedisData对象。
        // 1.查询缓存
        String redisCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.如果为空直接返回失败
        if(StrUtil.isBlank(redisCache)){
            return Result.fail("逻辑过期缓存为空？ 是否是没有预热缓存？");
        }
        // 3. 缓存存在，反序列化对象，判断缓存是否逻辑过期。
        RedisData redisData = JSONUtil.toBean(redisCache, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shopCache = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        // 4. 判断是否过期
        // 5. 已过期，获取锁，进行一个异步缓存重建，然后构建完成了释放锁
        if (LocalDateTime.now().isAfter(expireTime)){
            //已过期
            String lockKey=LOCK_SHOP_KEY+id;
            boolean ret = tryLock(lockKey);
            //抢锁异步构建
            if (ret){
                CompletableFuture.runAsync(()->{
                    try {
                        //构建新缓存
                        this.saveShop2Redis(id,20L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        //释放锁
                        unLock(lockKey);
                    }
                });
            }
        }
        // 6. 未过期和已过期、已过期抢锁成功或者失败都会返回旧数据的，这里直接返回旧数据了。
        return Result.ok(shopCache);
    }

    /**
     * 快速更新构建逻辑缓存，只需要传入商铺id和过期时间
     * @param id
     * @param expireSeconds
     */
    @Override
    public void saveShop2Redis(Long id, Long expireSeconds){
        //查询店铺信息
        Shop shop = this.getById(id);
        //模拟构建延时
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        //设置逻辑过期时间，通过当前时间加上过期时间长度
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }



    /**
     * 互斥锁解决缓存击穿问题——强一致性方案。
     * @param id
     * @return
     */
    @Override
    public Result queryShopByIdMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.查询缓存是否命中
        String redisCache = stringRedisTemplate.opsForValue().get(key);
        //2.命中返回
        if (StrUtil.isNotBlank(redisCache)){
            Shop shop = JSONUtil.toBean(redisCache, Shop.class);
            return Result.ok(shop);
        }
        //3. 未命中 获取互斥锁,查询数据库
        String lockKey=LOCK_SHOP_KEY+id;
        if (this.tryLock(lockKey)){
            try {
                //获取锁成功，进行一个缓存更新
                //查询数据库
                Shop shop = this.getById(id);
                //模拟重建缓存延时
                Thread.sleep(200);
                //非空判断
                if (shop==null){
                    return Result.fail("商铺不存在");
                }
                //回填缓存
                stringRedisTemplate.opsForValue()
                        .set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),60,TimeUnit.SECONDS);
                return Result.ok(shop);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }finally {
                //释放锁
                unLock(lockKey);
            }
        }else{
            //这里可以实现一个重试逻辑。可以递归，但是我们实现一个简单的重试吧
            int count =3;
            while (count-- > 0){
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                //重新尝试获取缓存
                redisCache = stringRedisTemplate.opsForValue().get(key);
                //命中返回
                if (StrUtil.isNotBlank(redisCache)){
                    Shop shop = JSONUtil.toBean(redisCache, Shop.class);
                    return Result.ok(shop);
                }
            }
        }
        //如果重试3次完成后还是获取不到，我们直接一个返回失败
        return Result.fail("获取数据超时，请稍后再试");
    }


    /**
     * 自定义一个尝试获取锁的方法
     * @param key
     * @return
     */
    public boolean tryLock(String key){
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }

    /**
     * 自定义简易释放锁逻辑
     * @param key
     */
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 缓存null解决缓存击穿问题
     * @param id
     * @return
     */
    @Override
    public Result queryShopByIdCacheThrough(Long id) {
        //1.查询缓存是否命中
        String redisCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.命中返回
        if (StrUtil.isNotBlank(redisCache)){
            Shop shop = JSONUtil.toBean(redisCache, Shop.class);
            return Result.ok(shop);
        }
        //命中缓存的空值，但是是我们缓存的空字符串""，但是上面判断没进
        if(redisCache !=null){
            return Result.fail("商品信息不存在");
        }
        //3.未命中查询数据库
        Shop shop = this.getById(id);
        // 数据库查询耗时夸张一下 ，睡0.2s，方便与有缓存时作对比。
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (shop==null){
            //如果不存在，缓存空值
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",60,TimeUnit.SECONDS);
            return Result.fail("商品信息不存在");
        }
        //4.如果存在，回填缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),60,TimeUnit.SECONDS);
        //5.返回给前端
        return Result.ok(shop);
    }

    @Transactional
    @Override
    public Result updateShop(Shop shop) {
        //因为有缓存，我们考虑到一致性问题，所以进行一个先更新数据库，再删除缓存的操作。
        if (shop.getId()==null){
            return Result.fail("商铺id为空，更新失败");
        }
        //更新数据库
        boolean ret = this.updateById(shop);
        if (!ret){
            return Result.fail("更新失败");
        }
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }

    /**
     * 最普通的缓存数据方式
     * @param id 商铺id
     * @return
     */
    @Override
    public Result queryShopByIdCommon(Long id) {
        //添加缓存查询
        //1.查询缓存是否命中
        String redisCache = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.命中返回
        if (StrUtil.isNotEmpty(redisCache)){
            Shop shop = JSONUtil.toBean(redisCache, Shop.class);
            return Result.ok(shop);
        }
        //3.未命中查询数据库
        Shop shop = this.getById(id);
        // 数据库查询耗时夸张一下 ，睡0.2s，方便与有缓存时作对比。
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (shop==null){
            return Result.fail("数据不存在");
        }
        //4.回填缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),60,TimeUnit.SECONDS);
        //5.返回给前端
        return Result.ok(shop);
    }
}
