package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    /**
     * 开始时间戳
     */
    public static final Long BEGIN_TIMESTAMP = 1693526400L;
    /**
     * 序列号位数
     */
    public static final int COUNT_BITS = 32;

    /**
     * @param keyPrefix 缓存key前缀
     */
    public Long nextId(String keyPrefix) {
        // 1  生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        //2 生成序列号  incr命令使得key自增
        //获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //获取Redis自增长id
        long count = stringRedisTemplate.opsForValue().increment("icr" + "" + keyPrefix + "" + date);

        //3 拼接返回  <<  左移位运算
        return timeStamp << COUNT_BITS | count;
    }

}
