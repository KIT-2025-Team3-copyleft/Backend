package com.copyleft.GodsChoice.infra.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NicknameRepository {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String KEY_ACTIVE_NICKNAMES = "active_nicknames";
    private static final String KEY_SESSION_PREFIX = "session:";

    public boolean isNicknameExists(String nickname) {
        Boolean result = redisTemplate.opsForSet().isMember(KEY_ACTIVE_NICKNAMES, nickname);
        return result != null && result;
    }

    public boolean reserveNickname(String nickname) {
        Long addedCount = redisTemplate.opsForSet().add(KEY_ACTIVE_NICKNAMES, nickname);
        return addedCount != null && addedCount > 0;
    }

    public void removeNickname(String nickname) {
        redisTemplate.opsForSet().remove(KEY_ACTIVE_NICKNAMES, nickname);
    }

    public void saveSessionNicknameMapping(String sessionId, String nickname) {
        redisTemplate.opsForValue().set(KEY_SESSION_PREFIX + sessionId, nickname);
    }

    public String getNicknameBySessionId(String sessionId) {
        return redisTemplate.opsForValue().get(KEY_SESSION_PREFIX + sessionId);
    }

    public void deleteSessionMapping(String sessionId) {
        redisTemplate.delete(KEY_SESSION_PREFIX + sessionId);
    }
}