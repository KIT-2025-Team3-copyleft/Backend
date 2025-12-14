package com.copyleft.GodsChoice.lobby.controller;

import com.copyleft.GodsChoice.global.websocket.WebSocketCommandHandler;
import com.copyleft.GodsChoice.lobby.service.LobbyService;
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
