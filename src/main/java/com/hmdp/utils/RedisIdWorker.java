package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 生成id
 */
@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 2022/01/01/00:00:00
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP=1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS=32;

    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now= LocalDateTime.now();
        long nowSecond=now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=nowSecond-BEGIN_TIMESTAMP;
        //2.生成序列号
        //2.1获取当前日期，精确到天（这样设计key的好处便于统计当天的数据，在redis中key设计:会自动分层）
        String date= now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2自增长(当key不存在时会自动创建key，不存在空指针异常)
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //3.拼接并返回（因为id由时间戳+序列号形式（32位），因此左移32位，|与0进行运算的结果是保留当前数）
        return timestamp << COUNT_BITS | count;

    }

}

