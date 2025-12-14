package com.copyleft.GodsChoice.global.websocket;

import com.copyleft.GodsChoice.global.websocket.dto.ClusterMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
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
    private final RedissonClient redissonClient;

    private static final String TOPIC_NAME = "ws-cluster-topic";

    @PostConstruct
    public void init() {
        RTopic topic = redissonClient.getTopic(TOPIC_NAME);

        topic.addListener(ClusterMessage.class, (channel, msg) -> {
            try {
                sendLocal(msg.getSessionId(), msg.getContent());
            } catch (Exception e) {
                log.error("Cluster 메시지 처리 중 오류", e);
            }
        });
        log.info("Redis Pub/Sub 구독 시작: Topic={}", TOPIC_NAME);
    }

    public void sendEventToSession(String sessionId, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            if (sessionManager.getSession(sessionId) != null) {
                sendLocal(sessionId, payload);
            }
            else {
                publishToCluster(sessionId, payload);
            }
        } catch (IOException e) {
            log.error("메시지 변환/전송 실패: session={}", sessionId, e);
        }
    }

    private void sendLocal(String sessionId, String payload) {
        WebSocketSession session = sessionManager.getSession(sessionId);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(payload));
                log.debug("전송 성공 (Local): {}", sessionId);
            } catch (IOException e) {
                log.error("전송 실패 (Local): {}", sessionId, e);
            }
        }
    }

    private void publishToCluster(String sessionId, String payload) {
        RTopic topic = redissonClient.getTopic(TOPIC_NAME);
        topic.publish(new ClusterMessage(sessionId, payload));
    }
}