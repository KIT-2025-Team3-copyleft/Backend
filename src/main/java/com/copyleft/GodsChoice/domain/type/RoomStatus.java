package com.copyleft.GodsChoice.domain.type;

public enum RoomStatus {
    WAITING,   // 대기 중 (입장 가능)
    STARTING,  // 게임 시작 카운트다운 중 (입장 불가)
    PLAYING    // 게임 진행 중 (입장 불가)
}