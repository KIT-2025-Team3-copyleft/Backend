package com.copyleft.GodsChoice.global.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum GameCode {

    ROOM_CREATE_SUCCESS("성공적으로 방을 생성했습니다."),
    ROOM_JOIN_SUCCESS("방 입장에 성공했습니다."),
    ROOM_LEAVE_SUCCESS("방에서 퇴장했습니다."),

    GAME_START_SOON("잠시 후 게임이 시작됩니다.");

    private final String message;
}