package com.copyleft.GodsChoice.global.constant;

public enum SocketEvent {
    NICKNAME_SUCCESS,
    NICKNAME_DUPLICATE,

    JOIN_SUCCESS,
    JOIN_FAILED,
    LOBBY_UPDATE,
    LEAVE_SUCCESS,

    GAME_START_TIMER,
    TIMER_CANCELLED,
    LOAD_GAME_SCENE,

    ROUND_START,
    RECEIVE_CARDS,
    CARD_SELECTED_CONFIRMED, // 내 선택 확인
    ALL_CARDS_SELECTED,      // 전원 선택 완료 (심판 대기)
    ROUND_RESULT,            // 라운드 결과 (AI 점수)

    ERROR_MESSAGE
}