package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {
    @Autowired
    private StringRedisTemplate redisTemplate;

    public static final long BEGIN_TIMESTAMP = LocalDateTime.of(LocalDate.of(2024, 10, 12), LocalTime.MIN).toEpochSecond(ZoneOffset.UTC);

    public static final int COUNT_BITS = 32;

    public long nextId(String key) {
        // 1. 获取时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd")); // 依据当前日期生成key， 防止序列号大小超过32位
        long id = redisTemplate.opsForValue().increment("icr:" + key + ":" + date);

        // 3. 拼接并返回
        return timeStamp << COUNT_BITS | id;
    }
}
