package com.copyleft.GodsChoice.global.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum GameCode {

    ROOM_CREATE_SUCCESS("성공적으로 방을 생성했습니다."),
    ROOM_JOIN_SUCCESS("방 입장에 성공했습니다."),
    ROOM_LEAVE_SUCCESS("방에서 퇴장했습니다."),

    GAME_START_SOON("잠시 후 게임이 시작됩니다."),
    GAME_COUNTDOWN_START("게임 시작 3초 전!"),
    GAME_TIMER_CANCELLED("플레이어 퇴장으로 게임 시작이 취소되었습니다."),

    CARD_SELECTED("카드를 선택했습니다."),
    ALL_USERS_SELECTED("모든 플레이어가 선택을 완료했습니다. 신의 심판을 기다리세요...");

    private final String message;
}