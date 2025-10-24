package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商铺信息
     *
     * @param id 商铺id
     * @return
     */
    Result queryShopById(Long id);

    /**
     * 逻辑过期方案解决缓存击穿问题——延时一致性，最终一致性
     * @param id
     * @return
     */
    Result queryShopByIdLogicExpire(Long id);

    /**
     * 快速更新构建逻辑缓存，只需要传入商铺id和过期时间
     * @param id
     * @param expireSeconds
     */
    void saveShop2Redis(Long id, Long expireSeconds);

    /**
     * 互斥锁——强一致性查询，解决缓存击穿
     * @param id
     * @return
     */
    Result queryShopByIdMutex(Long id);

    /**
     * 查询商铺——缓存+缓存null值解决缓存穿透
     * @param id
     * @return
     */
    Result queryShopByIdCacheThrough(Long id);

    /**
     * 根据传入的shop对商铺信息进行一个更新。
     * @param shop 修改后的商铺信息
     * @return
     */
    Result updateShop(Shop shop);

    /**
     * 查询商铺——普通缓存
     * @param id
     * @return
     */
    Result queryShopByIdCommon(Long id);
}
