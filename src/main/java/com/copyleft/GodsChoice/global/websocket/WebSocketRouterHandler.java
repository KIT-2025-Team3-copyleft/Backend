package com.copyleft.GodsChoice.global.websocket;

import com.copyleft.GodsChoice.lobby.service.LobbyService;
import com.copyleft.GodsChoice.user.service.NicknameService;
import com.copyleft.GodsChoice.global.websocket.dto.WebSocketRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class WebSocketRouterHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    private final NicknameService nicknameService;
    private final LobbyService lobbyService;

    private final Map<String, WebSocketCommandHandler> handlerMap;

    public WebSocketRouterHandler(
            WebSocketSessionManager sessionManager,
            ObjectMapper objectMapper,
            NicknameService nicknameService,
            LobbyService lobbyService,
            List<WebSocketCommandHandler> handlers
    ) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.nicknameService = nicknameService;
        this.lobbyService = lobbyService;
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(WebSocketCommandHandler::getAction, Function.identity()));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session){
        log.info("새로운 세션 연결: {}", session.getId());
        sessionManager.registerSession(session);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message){
        String payload = message.getPayload();

        try {
            WebSocketRequest request = objectMapper.readValue(payload, WebSocketRequest.class);
            String action = request.getAction();
            log.info("Action 수신: {}, Session: {}", action, session.getId());

            WebSocketCommandHandler handler = handlerMap.get(action);

            if (handler != null) {
                handler.handle(session, request.getPayload());
            } else {
                log.warn("알 수 없는 Action 입니다: {}", action);
            }

        } catch (Exception e) {
            log.error("메시지 처리 중 오류: {}", e.getMessage(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, @NonNull CloseStatus status){
        log.info("세션 연결 종료: {} (사유: {})", session.getId(), status);
        sessionManager.removeSession(session);
        lobbyService.leaveRoom(session.getId());
        nicknameService.handleDisconnect(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception){
        log.error("전송 오류 발생: [세션 ID: {}], [오류: {}]", session.getId(), exception.getMessage());
    }
}