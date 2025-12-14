package com.copyleft.GodsChoice.global.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionManager {

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private static final int SEND_TIME_LIMIT = 5000;
    private static final int BUFFER_SIZE_LIMIT = 1024 * 64;

    public void registerSession(WebSocketSession session) {
        WebSocketSession concurrentSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT, BUFFER_SIZE_LIMIT);
        sessions.put(session.getId(), concurrentSession);
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session.getId());
    }

    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }
}