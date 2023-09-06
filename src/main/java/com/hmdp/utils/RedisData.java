package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 封装需要设定过期时间的对象，从而存入redis中
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
