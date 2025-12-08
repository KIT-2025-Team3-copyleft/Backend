package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameRoomLockFacade {

    private final RedisLockRepository redisLockRepository;

    public LockResult<Void> execute(String roomId, Runnable action) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.warn("락 획득 실패: roomId={}", roomId);
            return LockResult.lockFailed();
        }

        try {
            action.run();
            return LockResult.success(null);
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public <T> LockResult<T> execute(String roomId, Supplier<T> action) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.warn("락 획득 실패: roomId={}", roomId);
            return LockResult.lockFailed();
        }

        try {
            T result = action.get();
            if (result == null) {
                return LockResult.skipped();
            }
            return LockResult.success(result);
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }
}