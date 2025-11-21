package com.copyleft.GodsChoice.infra.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Repository
@RequiredArgsConstructor
public class RedisLockRepository {

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 락 획득 시도 (Spin Lock 방식 아님, 1회성 시도)
     * key: 락을 걸 대상 (예: room:123)
     * return: 락 획득 성공 여부
     */
    public Boolean lock(String key) {
        return redisTemplate
                .opsForValue()
                .setIfAbsent("lock:" + key, "LOCKED", Duration.ofSeconds(3));
    }

    /**
     * 락 해제
     */
    public void unlock(String key) {
        redisTemplate.delete("lock:" + key);
    }
}