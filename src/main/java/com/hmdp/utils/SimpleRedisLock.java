package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 自定义Redis分布式锁
 */
public class SimpleRedisLock implements ILock {
    private String name;
    StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    /**
     * 尝试获取锁
     *
     * @param timeOutSec 设定超时时间
     */
    @Override
    public boolean tryLock(long timeOutSec) {
        //获取线程标识  通过UUID加上线程Id避免标识冲突
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String key = KEY_PREFIX + name;
        //redis命令 setnx 存入线程标识
        Boolean success = stringRedisTemplate
                .opsForValue()
                .setIfAbsent(key, threadId, timeOutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁
     */
    @Override
    public void unLock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String key = KEY_PREFIX + name;
        String id = stringRedisTemplate.opsForValue().get(key);
        if (threadId.equals(id)) {
            //释放锁
            stringRedisTemplate.delete(key);
        }
    }
}
