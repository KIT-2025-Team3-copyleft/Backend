package com.copyleft.GodsChoice.game.controller;

import com.copyleft.GodsChoice.game.service.GamePlayService;
import com.copyleft.GodsChoice.global.websocket.WebSocketCommandHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
public class SelectCardHandler implements WebSocketCommandHandler {

    private final GamePlayService gamePlayService;

    @Override
    public String getAction() {
        return "SELECT_CARD";
    }

    @Override
    public void handle(WebSocketSession session, JsonNode payload) {
        if (payload != null && payload.has("card")) {
            String card = payload.get("card").asText();
            gamePlayService.selectCard(session.getId(), card);
        }
    }
}