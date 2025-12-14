package com.copyleft.GodsChoice.game.controller;

import com.copyleft.GodsChoice.game.service.GameFlowService;
import com.copyleft.GodsChoice.global.websocket.WebSocketCommandHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
public class StartGameHandler implements WebSocketCommandHandler {

    private final GameFlowService gameFlowService;

    @Override
    public String getAction() {
        return "START_GAME";
    }

    @Override
    public void handle(WebSocketSession session, JsonNode payload) {
        gameFlowService.tryStartGame(session.getId());
    }
}