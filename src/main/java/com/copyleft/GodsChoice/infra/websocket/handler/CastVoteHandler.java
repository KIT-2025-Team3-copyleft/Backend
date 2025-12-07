package com.copyleft.GodsChoice.infra.websocket.handler;

import com.copyleft.GodsChoice.feature.game.GamePlayService;
import com.copyleft.GodsChoice.feature.game.dto.VoteRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class CastVoteHandler implements WebSocketCommandHandler {

    private final GamePlayService gamePlayService;
    private final ObjectMapper objectMapper;

    @Override
    public String getAction() {
        return "CAST_VOTE";
    }

    @Override
    public void handle(WebSocketSession session, JsonNode payload) {
        try {
            VoteRequest dto = objectMapper.treeToValue(payload, VoteRequest.class);
            if (dto != null && dto.getTargetSessionId() != null) {
                gamePlayService.castVote(session.getId(), dto.getTargetSessionId());
            }
        } catch (Exception e) {
            log.error("[CAST_VOTE] 처리 중 오류: session={}, msg={}", session.getId(), e.getMessage(), e);
        }
    }
}
