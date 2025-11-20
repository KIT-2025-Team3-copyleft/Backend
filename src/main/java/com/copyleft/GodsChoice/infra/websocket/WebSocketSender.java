package com.copyleft.GodsChoice.infra.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSender {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public void sendEventToSession(String sessionId, Object event) {
        WebSocketSession session = sessionManager.getSession(sessionId);
        if (session != null && session.isOpen()) {
            try {
                String payload = objectMapper.writeValueAsString(event); // 객체를 JSON 문자열로 변환
                session.sendMessage(new TextMessage(payload));
                log.debug("이벤트 전송 (1:1): [세션 ID: {}], [페이로드: {}]", sessionId, payload);
            } catch (IOException e) {
                log.error("1:1 이벤트 전송 실패: [세션 ID: {}], [오류: {}]", sessionId, e.getMessage());
            }
        } else {
            log.warn("세션을 찾을 수 없거나 닫혀있습니다: [세션 ID: {}]", sessionId);
        }
    }

    public void broadcastEventToRoom(String roomId, Object event) {
        log.debug("이벤트 브로드캐스트 (1:N): [방 ID: {}], [이벤트: {}]", roomId, event.getClass().getSimpleName());
    }
}