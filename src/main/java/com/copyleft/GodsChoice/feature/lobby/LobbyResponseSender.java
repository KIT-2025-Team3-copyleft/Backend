package com.copyleft.GodsChoice.feature.lobby;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.Player; // Player import 확인
import com.copyleft.GodsChoice.domain.type.ConnectionStatus; // Enum import 확인
import com.copyleft.GodsChoice.feature.lobby.dto.LobbyResponse;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.global.constant.GameCode; // [변경] GameMessage -> GameCode
import com.copyleft.GodsChoice.global.constant.SocketEvent;
import com.copyleft.GodsChoice.infra.websocket.WebSocketSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LobbyResponseSender {

    private final WebSocketSender webSocketSender;


    public void sendJoinSuccess(String sessionId, Room room) {
        LobbyResponse response = createResponse(
                SocketEvent.JOIN_SUCCESS,
                room,
                GameCode.ROOM_JOIN_SUCCESS.getMessage(),
                null
        );
        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void sendCreateSuccess(String sessionId, Room room) {
        LobbyResponse response = createResponse(
                SocketEvent.JOIN_SUCCESS,
                room,
                GameCode.ROOM_CREATE_SUCCESS.getMessage(),
                null
        );
        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void broadcastLobbyUpdate(String roomId, Room room) {
        LobbyResponse response = createResponse(
                SocketEvent.LOBBY_UPDATE,
                room,
                null,
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastTimerCancelled(String roomId, Room room) {
        LobbyResponse response = createResponse(
                SocketEvent.TIMER_CANCELLED,
                null,
                GameCode.GAME_TIMER_CANCELLED.getMessage(),
                null
        );
        broadcastToRoom(room, response);
    }

    public void sendLeaveSuccess(String sessionId) {
        LobbyResponse response = createResponse(
                SocketEvent.LEAVE_SUCCESS,
                null,
                GameCode.ROOM_LEAVE_SUCCESS.getMessage(),
                null
        );
        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void sendError(String sessionId, ErrorCode errorCode) {
        LobbyResponse response = createResponse(
                SocketEvent.JOIN_FAILED,
                null,
                errorCode.getMessage(),
                errorCode.name()
        );
        webSocketSender.sendEventToSession(sessionId, response);
    }


    private LobbyResponse createResponse(SocketEvent event, Room room, String message, String code) {
        return LobbyResponse.builder()
                .event(event.name())
                .room(room)
                .message(message)
                .code(code)
                .build();
    }

    private void broadcastToRoom(Room room, LobbyResponse response) {
        if (room.getPlayers() != null) {
            for (Player player : room.getPlayers()) {
                if (player.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    webSocketSender.sendEventToSession(player.getSessionId(), response);
                }
            }
        }
    }
}