package com.copyleft.GodsChoice.infra.websocket.handler;

import com.copyleft.GodsChoice.feature.lobby.LobbyService;
import com.copyleft.GodsChoice.feature.lobby.dto.LobbyRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class JoinByCodeHandler implements WebSocketCommandHandler {

    private final LobbyService lobbyService;
    private final ObjectMapper objectMapper;

    @Override
    public String getAction() {
        return "JOIN_BY_CODE";
    }

    @Override
    public void handle(WebSocketSession session, JsonNode payload) {
        try {
            LobbyRequest dto = objectMapper.treeToValue(payload, LobbyRequest.class);
            if (dto != null && dto.getRoomCode() != null) {
                lobbyService.joinRoomByCode(session.getId(), dto.getRoomCode());
            }
        } catch (Exception e) {
            log.error("[JOIN_BY_CODE] 처리 중 오류: session={}, msg={}", session.getId(), e.getMessage(), e);

        }
    }
}
