package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IShopService shopService;
    @Resource
    CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        Shop shop = cacheClient.queryWithPassThrough
                (CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
//        Shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire
//                (CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 更新数据
     *
     * @param shop
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) return Result.fail("店铺ID不能为空");
        //先更新数据库，再删除缓存
        shopService.updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }


    // 缓存穿透(客户端请求的数据在缓存中和数据库中都不存在，这样缓存永远不会生效，这些请求都会打到数据库)

    /**
     * 缓存穿透解决方案 设置逻辑过期字段
     *
     * @param id
     */
    private Shop queryWithPassThrough(Long id) {
        //1.从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        2、判断是否存在,存在则直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中是否空值
        if (shopJson != null) return null;
//          3 不存在，从数据库中取
        Shop shop = getById(id);
//        如果数据库中不存在，返回错误码，
//        同时将空值传递给Redis，避免缓存击透
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(""), CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库中存在，写入redis中，再返回数据 设定缓存30min过期
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }


    //缓存击穿：（热点Key问题，被高并发访问并且缓存重建业务较复杂的key突然失效了，无数的请求访问会在瞬间给数据库带来巨大的冲击。）

    /**
     * 缓存击穿解决方案1互斥锁
     *
     * @param id
     */
    private Shop queryWithMutex(Long id) {
        //1.从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2、判断是否存在,存在则直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) return null;
        // 3、 实现缓存重建
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        //判断命中是否空值
        try {
            boolean isLock = tryLock(lockKey);
            //如果获取失败，则说明锁已经被使用，线程等待一段时间后重新获取
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //不存在，从数据库中取
            shop = getById(id);
            //如果数据库中不存在，将空值传递给Redis，避免缓存击透
            if (shop == null) {
                stringRedisTemplate.opsForValue().set
                        (CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(""), CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //数据库中存在，写入redis中，再返回数据 设定缓存30min过期
            stringRedisTemplate.opsForValue().set
                    (CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unlock(lockKey);
        }
        //最后返回数据
        return shop;
    }

    //线程池开启用于重建缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR
            = Executors.newFixedThreadPool(10);


    /**
     * 缓存击穿解决方案 2:逻辑过期 (解决热点key问题，不存在缓存穿透问题)
     */
    private Shop queryWithLogicalExpire(Long id) {
        //1.从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2、判断是否存在,不存在直接返回
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);//获取数据
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断时间是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //没有过期，直接返回缓存中数据
            return shop;
        }

//        已经过期了，缓存重建
        String lockKey = LOCK_SHOP_KEY + id;

        //获取互斥锁
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //开始重建，开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopToRedis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //如果没有得到锁，则代表缓存重建中，直接返回过期数据
        return shop;
    }


    /**
     * 获取互斥锁 （基于Redis setnx命令实现）
     *
     * @param key
     */
    private boolean tryLock(String key) {
        Boolean result = stringRedisTemplate
                .opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //调用工具类方法进行自动拆包Boolean转换boolean
        return BooleanUtil.isTrue(result);
    }

    /**
     * 释放互斥锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 设置逻辑过期时间存入redis
     *
     * @param id
     * @param expireSeconds
     */
    public void saveShopToRedis(Long id, Long expireSeconds) {
        //查询店铺数据
        Shop shop = getById(id);
        //封装逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate
                .opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
