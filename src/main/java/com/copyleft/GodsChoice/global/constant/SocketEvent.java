package com.copyleft.GodsChoice.global.constant;

public enum SocketEvent {
    NICKNAME_SUCCESS,
    NICKNAME_DUPLICATE,

    JOIN_SUCCESS,
    JOIN_FAILED,
    LOBBY_UPDATE,
    LEAVE_SUCCESS,
    ROOM_LIST,

    GAME_START_TIMER,
    TIMER_CANCELLED,
    LOAD_GAME_SCENE,

    CHAT_MESSAGE,   // 채팅 메시지

    SHOW_ORACLE,     // 신탁 공개 (공통)
    SHOW_ROLE,       // 내 역할 및 성향 확인 (개인)

    ROUND_START,
    RECEIVE_CARDS,
    CARD_SELECTED_CONFIRMED, // 내 선택 확인
    ALL_CARDS_SELECTED,      // 전원 선택 완료 (심판 대기)
    ROUND_RESULT,            // 라운드 결과 (AI 점수)

    VOTE_PROPOSAL_START, // 찬반 투표 시작
    VOTE_PROPOSAL_UPDATE,// 찬반 투표 현황 (몇 명 참여했는지)
    VOTE_PROPOSAL_FAILED,// 찬반 투표 부결
    TRIAL_START,         // 이단 심문(본 투표) 시작
    TRIAL_VOTE_UPDATE,   // 이단 심문 현황 (누가 몇 표 받았는지)
    TRIAL_RESULT,        // 심판 결과
    NEXT_ROUND_START,    // 다음 라운드
    GAME_OVER,           // 게임 종료

    ERROR_MESSAGE
}