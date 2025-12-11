package com.copyleft.GodsChoice.feature.game;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameRoomLockFacade {

    private final RedissonClient redissonClient;

    private static final long WAIT_TIME = 5L;
    private static final long LEASE_TIME = 10L;

    public LockResult<Void> execute(String roomId, Runnable action) {
        RLock lock = redissonClient.getLock("room-lock:" + roomId);

        try {
            boolean available = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);

            if (!available) {
                log.warn("락 획득 실패 (Timeout): roomId={}", roomId);
                return LockResult.lockFailed();
            }

            try {
                action.run();
                return LockResult.success(null);
            } catch (Exception e) {
                log.error("비즈니스 로직 실행 중 오류: roomId={}, msg={}", roomId, e.getMessage(), e);
                throw e;
            }
        } catch (InterruptedException e) {
            log.error("락 대기 중 인터럽트 발생", e);
            Thread.currentThread().interrupt();
            return LockResult.lockFailed();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public <T> LockResult<T> execute(String roomId, Supplier<T> action) {
        RLock lock = redissonClient.getLock("room-lock:" + roomId);

        try {
            boolean available = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);

            if (!available) {
                log.warn("락 획득 실패 (Timeout): roomId={}", roomId);
                return LockResult.lockFailed();
            }

            try {
                T result = action.get();
                return (result == null) ? LockResult.skipped() : LockResult.success(result);
            } catch (Exception e) {
                log.error("비즈니스 로직 실행 중 오류: roomId={}, msg={}", roomId, e.getMessage(), e);
                throw e;
            }
        } catch (InterruptedException e) {
            log.error("락 대기 중 인터럽트 발생", e);
            Thread.currentThread().interrupt();
            return LockResult.lockFailed();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}