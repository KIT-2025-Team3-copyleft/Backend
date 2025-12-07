package com.copyleft.GodsChoice.infra.websocket.handler;

import com.copyleft.GodsChoice.feature.nickname.NicknameService;
import com.copyleft.GodsChoice.feature.nickname.dto.SetNicknameRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class SetNicknameHandler implements WebSocketCommandHandler {

    private final NicknameService nicknameService;
    private final ObjectMapper objectMapper;

    @Override
    public String getAction() {
        return "SET_NICKNAME";
    }

    @Override
    public void handle(WebSocketSession session, JsonNode payload) {
        try {
            SetNicknameRequest dto = objectMapper.treeToValue(payload, SetNicknameRequest.class);
            if (dto != null) {
                nicknameService.setNickname(session.getId(), dto.getNickname());
            }
        } catch (Exception e) {
            log.error("[SET_NICKNAME] 처리 중 오류: session={}, msg={}", session.getId(), e.getMessage(), e);
        }
    }
}