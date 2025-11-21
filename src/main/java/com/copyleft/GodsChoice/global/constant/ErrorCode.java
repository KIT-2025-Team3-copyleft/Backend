package com.copyleft.GodsChoice.global.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    INVALID_NICKNAME("닉네임은 2~6자 사이여야 합니다."),
    NICKNAME_ALREADY_USE("이미 사용 중인 닉네임입니다."),

    UNKNOWN_ERROR("알 수 없는 오류가 발생했습니다.");

    private final String message;
}