package com.copyleft.GodsChoice.infra.websocket.handler;

import com.copyleft.GodsChoice.feature.lobby.LobbyService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
public class GetRoomListHandler implements WebSocketCommandHandler {

    private final LobbyService lobbyService;

    @Override
    public String getAction() {
        return "GET_ROOM_LIST";
    }

    @Override
    public void handle(WebSocketSession session, JsonNode payload) {
        lobbyService.getRoomList(session.getId());
    }
}
