package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

/**
 * Redis缓存存储方法
 */
@Component
@Slf4j
public class CacheClient {

    //    也可以通过@Resource注解注入StringRedisTemplate
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 数据直接存储Redis
     *
     * @param key
     * @param value
     * @param time  过期时间
     * @param unit  时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 数据存储Redis（逻辑删除字段）
     *
     * @param key
     * @param value
     * @param time  过期时间
     * @param unit  时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存传递空值解决缓存穿透，（客户端请求的数据在缓存中和数据库中都不存在，缓存永不生效，这些请求都会打到数据库）
     *
     * @param keyPrefix  Redis缓存key前缀
     * @param id         缓存key后缀
     * @param type       实体类型
     * @param DBFallBack 数据查询调用函数
     */
    public <Entity, ID> Entity queryWithPassThrough(
            String keyPrefix, ID id, Class<Entity> type, Function<ID, Entity> DBFallBack, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis查询缓存
        String entityJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在,不存在直接返回
        if (StrUtil.isNotBlank(entityJson)) {
            return JSONUtil.toBean(entityJson, type);
        }
        //判断命中是否空值
        if (entityJson != null) return null;
        //将查询数据库的逻辑给调用方，接受传递的函数
        Entity entity = DBFallBack.apply(id);
        //如果数据库中不存在，返回错误码，同时将空字符串传递给Redis，避免缓存击透问题
        if (entity == null) {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(""), CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库中存在，写入redis中，最后返回数据
        this.set(key, entity, time, unit);
        return entity;
    }

    /**
     * 线程池开启用于重建缓存
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR
            = Executors.newFixedThreadPool(10);


    /**
     * 逻辑过期方案解决缓存击穿
     * 解决热点key问题，不存在缓存穿透问题
     */
    public <Entity, ID> Entity queryWithLogicalExpire(
            String keyPrefix, String lockKeyPrefix, ID id, Class<Entity> type, Function<ID, Entity> DBFallBack, Long time, TimeUnit unit) {
        String key = keyPrefix + id.toString();
        //从redis查询缓存
        String entityJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在,不存在直接返回
        if (StrUtil.isBlank(entityJson)) return null;

        RedisData redisData = JSONUtil.toBean(entityJson, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        Entity entity = JSONUtil.toBean(jsonObject, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断时间是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //没有过期，直接返回缓存中数据
            return entity;
        }

        //已经过期了，缓存重建
        String lockKey = lockKeyPrefix + id.toString();
        //获取互斥锁
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //开始重建，开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {   //调用参数传递中的函数用于查询数据库
                    Entity data = DBFallBack.apply(id);
                    //重新放入缓存
                    this.setWithLogicalExpire(key, data, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //如果没有得到锁，则代表缓存重建中，直接返回缓存中的过期数据
        return entity;
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

}
