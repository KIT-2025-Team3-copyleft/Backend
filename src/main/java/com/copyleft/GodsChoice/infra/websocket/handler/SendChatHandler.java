package com.copyleft.GodsChoice.infra.websocket.handler;

import com.copyleft.GodsChoice.feature.chat.ChatService;
import com.copyleft.GodsChoice.feature.chat.dto.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class SendChatHandler implements WebSocketCommandHandler {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    @Override
    public String getAction() {
        return "SEND_CHAT";
    }

    @Override
    public void handle(WebSocketSession session, JsonNode payload) {
        try {
            ChatRequest dto = objectMapper.treeToValue(payload, ChatRequest.class);
            if (dto != null) {
                chatService.processChat(session.getId(), dto.getMessage());
            }
        } catch (Exception e) {
            log.error("[SEND_CHAT] 처리 중 오류: session={}, msg={}", session.getId(), e.getMessage(), e);
        }
    }
}