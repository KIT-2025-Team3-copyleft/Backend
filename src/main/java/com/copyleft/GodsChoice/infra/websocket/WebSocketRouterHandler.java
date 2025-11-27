package com.copyleft.GodsChoice.infra.websocket;

import com.copyleft.GodsChoice.feature.game.GameService;
import com.copyleft.GodsChoice.feature.lobby.LobbyService;
import com.copyleft.GodsChoice.feature.lobby.dto.LobbyRequest;
import com.copyleft.GodsChoice.feature.nickname.NicknameService;
import com.copyleft.GodsChoice.feature.nickname.dto.SetNicknameRequest;
import com.copyleft.GodsChoice.infra.websocket.dto.WebSocketRequest;
import com.copyleft.GodsChoice.feature.game.dto.VoteRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketRouterHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    private final NicknameService nicknameService;
    private final LobbyService lobbyService;
    private final GameService gameService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("새로운 세션 연결: {}", session.getId());
        sessionManager.registerSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        try {
            WebSocketRequest request = objectMapper.readValue(payload, WebSocketRequest.class);
            log.info("Action 수신: {}, Session: {}", request.getAction(), session.getId());

            switch (request.getAction()) {
                case "SET_NICKNAME":
                    SetNicknameRequest nicknameDto = objectMapper.treeToValue(request.getPayload(), SetNicknameRequest.class);
                    nicknameService.setNickname(session.getId(), nicknameDto.getNickname());
                    break;

                case "START_GAME":
                    gameService.tryStartGame(session.getId());
                    break;

                case "CREATE_ROOM":
                    lobbyService.createRoom(session.getId());
                    break;

                case "QUICK_JOIN":
                    lobbyService.quickJoin(session.getId());
                    break;

                case "JOIN_BY_CODE":
                    LobbyRequest lobbyDto = objectMapper.treeToValue(request.getPayload(), LobbyRequest.class);
                    if (lobbyDto != null && lobbyDto.getRoomCode() != null) {
                        lobbyService.joinRoomByCode(session.getId(), lobbyDto.getRoomCode());
                    } else {
                        log.warn("JOIN_BY_CODE 요청에 roomCode가 없습니다.");
                    }
                    break;

                case "LEAVE_ROOM":
                    lobbyService.leaveRoom(session.getId());
                    break;

                case "GAME_READY":
                    gameService.processGameReady(session.getId());
                    break;

                case "SELECT_CARD":
                    if (request.getPayload() != null && request.getPayload().has("card")) {
                        String card = request.getPayload().get("card").asText();
                        gameService.selectCard(session.getId(), card);
                    } else {
                        log.warn("SELECT_CARD 요청 오류: payload가 없거나 card 필드 누락. session={}", session.getId());
                    }
                    break;

                case "PROPOSE_VOTE":
                    if (request.getPayload().has("agree")) {
                        boolean agree = request.getPayload().get("agree").asBoolean();
                        gameService.voteProposal(session.getId(), agree);
                    }
                    break;

                case "CAST_VOTE":
                    if (request.getPayload().has("targetSessionId")) {
                        String targetId = request.getPayload().get("targetSessionId").asText();
                        gameService.castVote(session.getId(), targetId);
                    }
                    break;

                case "BACK_TO_ROOM":
                    gameService.backToRoom(session.getId());
                    break;

                default:
                    log.warn("알 수 없는 Action입니다: {}", request.getAction());
            }

        } catch (Exception e) {
            log.error("메시지 처리 중 오류: {}", e.getMessage(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("세션 연결 종료: {} (사유: {})", session.getId(), status);
        sessionManager.removeSession(session);
        lobbyService.leaveRoom(session.getId());
        nicknameService.handleDisconnect(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("전송 오류 발생: [세션 ID: {}], [오류: {}]", session.getId(), exception.getMessage());
    }
}