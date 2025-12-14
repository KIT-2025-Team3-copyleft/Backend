package com.copyleft.GodsChoice.game.controller;

import com.copyleft.GodsChoice.game.service.GameFlowService;
import com.copyleft.GodsChoice.global.websocket.WebSocketCommandHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
public class BackToRoomHandler implements WebSocketCommandHandler {

    private final GameFlowService gameFlowService;

    @Override
    public String getAction() {
        return "BACK_TO_ROOM";
    }

    @Override
    public void handle(WebSocketSession session, JsonNode payload) {
        gameFlowService.backToRoom(session.getId());
    }
}
