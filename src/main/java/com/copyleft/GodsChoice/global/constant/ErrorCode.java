package com.copyleft.GodsChoice.global.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    INVALID_NICKNAME("닉네임은 2~6자 사이여야 합니다."),
    NICKNAME_ALREADY_USE("이미 사용 중인 닉네임입니다."),

    ROOM_NOT_FOUND("존재하지 않는 방입니다."),
    ROOM_FULL("방의 정원이 초과되었습니다."),
    ROOM_ALREADY_PLAYING("이미 게임이 진행 중인 방입니다."),
    ROOM_JOIN_FAILED("방 입장에 실패했습니다. 다시 시도해주세요."),
    ROOM_LEAVE_FAILED("방 퇴장 처리 중 오류가 발생했습니다."),

    CHAT_BLOCKED_PHASE("현재 단계에서는 채팅을 할 수 없습니다."), // 추가
    CHAT_EMPTY("메시지 내용을 입력해주세요."),

    GAME_START_FAILED("게임 시작 처리에 실패했습니다. 잠시 후 다시 시도해주세요."),

    NOT_HOST("방장만 게임을 시작할 수 있습니다."),
    NOT_ENOUGH_PLAYERS("게임 시작을 위해 정원(4명)이 모두 모여야 합니다."),


    UNKNOWN_ERROR("알 수 없는 오류가 발생했습니다.");

    private final String message;
}