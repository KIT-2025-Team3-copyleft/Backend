package com.copyleft.GodsChoice.feature.nickname.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import com.copyleft.GodsChoice.domain.Player;

@Getter
@Builder
@AllArgsConstructor
public class NicknameResponse {
    private String event; // "NICKNAME_SUCCESS", "NICKNAME_DUPLICATE" 등
    private Player player; // 성공 시 플레이어 정보
    private String message; // 실패 시 메시지
    private String code; // 에러 코드
}