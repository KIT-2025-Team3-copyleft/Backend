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

    private static final long WAIT_TIME = 2L;        // 락 대기 최대 시간
    private static final long LEASE_TIME = 5L;      // 락 점유 최대 시간
    private static final int MAX_RETRY = 3;          // 최대 3번 재시도
    private static final long RETRY_DELAY_MS = 300L; // 재시도 사이 0.3초 휴식

    public LockResult<Void> execute(String roomId, Runnable action) {
        return executeInternal(roomId, () -> {
            action.run();
            return null;
        });
    }

    public <T> LockResult<T> execute(String roomId, Supplier<T> action) {
        return executeInternal(roomId, action);
    }

    private <T> LockResult<T> executeInternal(String roomId, Supplier<T> action) {
        RLock lock = redissonClient.getLock("room-lock:" + roomId);

        // 최대 N번 반복 시도
        for (int i = 0; i < MAX_RETRY; i++) {
            try {
                boolean available = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);

                if (available) {
                    try {
                        T result = action.get();
                        return (result == null) ? LockResult.skipped() : LockResult.success(result);
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                } else {
                    log.warn("락 획득 실패, 재시도 대기중 ({}/{}): roomId={}", i + 1, MAX_RETRY, roomId);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return LockResult.lockFailed();
                    }
                }

            } catch (InterruptedException e) {
                log.error("락 인터럽트 발생", e);
                Thread.currentThread().interrupt();
                return LockResult.lockFailed();
            } catch (RuntimeException e) {
                log.error("비즈니스 로직 오류: roomId={}", roomId, e);
                throw e;
            }
        }

        log.error("락 획득 최종 실패 (Timeout): roomId={}", roomId);
        return LockResult.lockFailed();
    }
}