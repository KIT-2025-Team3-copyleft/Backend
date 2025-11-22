package com.copyleft.GodsChoice.feature.game.dto;

import com.copyleft.GodsChoice.domain.Room;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GameResponse {
    private String event;
    private Room room;      // 방 정보 (게임 상태 포함)
    private String message; // 안내 메시지
    private String code;    // 에러 코드 or 성공 코드
}