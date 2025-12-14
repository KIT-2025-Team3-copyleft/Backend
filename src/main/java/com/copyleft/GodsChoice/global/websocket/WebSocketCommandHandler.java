package com.copyleft.GodsChoice.global.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.socket.WebSocketSession;

public interface WebSocketCommandHandler {

    String getAction();

    void handle(WebSocketSession session, JsonNode payload);
}