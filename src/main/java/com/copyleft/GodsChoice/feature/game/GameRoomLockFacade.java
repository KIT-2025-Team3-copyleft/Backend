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

    public void execute(String roomId, Runnable action) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.warn("락 획득 실패 (실행 건너뜀): roomId={}", roomId);
            return;
        }

        try {
            action.run();
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public <T> T execute(String roomId, Supplier<T> action) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.warn("락 획득 실패 (null 반환): roomId={}", roomId);
            return null;
        }

        try {
            return action.get();
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }
}