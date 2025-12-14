package com.copyleft.GodsChoice.game.service;

import lombok.Getter;

@Getter
public class LockResult<T> {
    private final T data;
    private final Status status;

    public enum Status {
        SUCCESS,            // 성공
        LOCK_FAILED,        // 락 획득 실패 (재시도 필요)
        BUSINESS_SKIPPED    // 방 없음, 상태 불일치 등으로 실행 안 함 (재시도 불필요)
    }

    private LockResult(T data, Status status) {
        this.data = data;
        this.status = status;
    }

    public static <T> LockResult<T> success(T data) {
        return new LockResult<>(data, Status.SUCCESS);
    }

    public static <T> LockResult<T> lockFailed() {
        return new LockResult<>(null, Status.LOCK_FAILED);
    }

    public static <T> LockResult<T> skipped() {
        return new LockResult<>(null, Status.BUSINESS_SKIPPED);
    }

    public boolean isLockFailed() { return status == Status.LOCK_FAILED; }
    public boolean isSkipped() { return status == Status.BUSINESS_SKIPPED; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
}