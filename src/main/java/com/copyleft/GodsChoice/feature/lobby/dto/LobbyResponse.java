package com.copyleft.GodsChoice.feature.lobby.dto;

import com.copyleft.GodsChoice.domain.Room;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LobbyResponse {
    private String event; // JOIN_SUCCESS, JOIN_FAILED, LOBBY_UPDATE
    private Room room;    // 성공 시 방 정보 (실패 시 null)
    private String message; // 실패 시 메시지
    private String code;    // 에러 코드
}