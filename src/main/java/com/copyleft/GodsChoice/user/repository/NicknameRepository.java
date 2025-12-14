package com.copyleft.GodsChoice.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Repository
@RequiredArgsConstructor
public class NicknameRepository {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_ACTIVE_NICKNAMES = "active_nicknames";
    private static final String KEY_SESSION_PREFIX = "session:";

    public boolean reserveNickname(String nickname) {
        Long addedCount = redisTemplate.opsForSet().add(KEY_ACTIVE_NICKNAMES, nickname);
        return addedCount != null && addedCount > 0;
    }

    public void removeNickname(String nickname) {
        redisTemplate.opsForSet().remove(KEY_ACTIVE_NICKNAMES, nickname);
    }

    public void saveSessionNicknameMapping(String sessionId, String nickname) {
        redisTemplate.opsForValue().set(
                KEY_SESSION_PREFIX + sessionId,
                nickname,
                Duration.ofHours(24)
        );
    }

    public String getNicknameBySessionId(String sessionId) {
        return redisTemplate.opsForValue().get(KEY_SESSION_PREFIX + sessionId);
    }

    public void deleteSessionMapping(String sessionId) {
        redisTemplate.delete(KEY_SESSION_PREFIX + sessionId);
    }
}