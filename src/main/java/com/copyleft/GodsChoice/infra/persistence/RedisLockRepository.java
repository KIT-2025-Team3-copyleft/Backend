package com.copyleft.GodsChoice.infra.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RedisLockRepository {

    private final StringRedisTemplate redisTemplate;

    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "    return redis.call('del', KEYS[1]) " +
                    "else " +
                    "    return 0 " +
                    "end";

    /**
     * 락 획득 시도
     * return: 락 획득 성공 시 '생성된 토큰(UUID)', 실패 시 null
     */
    public String lock(String key) {
        String token = UUID.randomUUID().toString();

        Boolean success = redisTemplate
                .opsForValue()
                .setIfAbsent("lock:" + key, token, Duration.ofSeconds(3));

        return Boolean.TRUE.equals(success) ? token : null;
    }

    /**
     * 락 해제
     * token: lock()에서 반환받은 토큰
     */
    public void unlock(String key, String token) {
        if (token == null) return;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
        redisTemplate.execute(redisScript, Collections.singletonList("lock:" + key), token);
    }
}