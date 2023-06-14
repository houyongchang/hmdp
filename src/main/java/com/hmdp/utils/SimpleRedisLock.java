package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


//使用Redisson代替自定义分布式锁

public class SimpleRedisLock implements Ilock {

    private String name;

    private StringRedisTemplate stringRedisTemplate;

    private static final String LOCK_KEY = "Lock:";

    private static final String VALUE_KEY = UUID.randomUUID().toString(true)+"-";

    private static final DefaultRedisScript<Long> REDIS_SCRIPT;
    static {
        REDIS_SCRIPT=new DefaultRedisScript<>();
        REDIS_SCRIPT.setLocation(new ClassPathResource("UnLock.lua"));
        REDIS_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeOut) {
        String threadId = VALUE_KEY + Thread.currentThread().getId();
        //添加锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY + this.name, threadId, timeOut, TimeUnit.SECONDS);
//        return Boolean.TRUE.equals(success);
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unLock() {
        //未能保证删除锁的原子性
        String threadId = VALUE_KEY + Thread.currentThread().getId();
        String redisValue = stringRedisTemplate.opsForValue().get(LOCK_KEY + this.name);
        //防止业务阻塞时间过长，出现误删锁
        if (threadId.equals(redisValue)) {
            stringRedisTemplate.delete(LOCK_KEY + this.name);
        }
//        //使用lua脚本保证原子性
//                String threadId = VALUE_KEY + Thread.currentThread().getId();
//        stringRedisTemplate.execute(REDIS_SCRIPT, Collections.singletonList(LOCK_KEY + name),threadId);

    }
}
