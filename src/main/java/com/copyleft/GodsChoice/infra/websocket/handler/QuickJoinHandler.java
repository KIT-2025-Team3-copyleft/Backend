package com.copyleft.GodsChoice.infra.websocket.handler;

import com.copyleft.GodsChoice.feature.lobby.LobbyService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
public class QuickJoinHandler implements WebSocketCommandHandler {

    private final LobbyService lobbyService;

    @Override
    public String getAction() {
        return "QUICK_JOIN";
    }

    @Override
    public void handle(WebSocketSession session, JsonNode payload) {
        lobbyService.quickJoin(session.getId());
    }
}
