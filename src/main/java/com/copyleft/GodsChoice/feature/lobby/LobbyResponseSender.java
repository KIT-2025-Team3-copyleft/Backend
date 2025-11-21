package com.copyleft.GodsChoice.feature.lobby;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.ConnectionStatus;
import com.copyleft.GodsChoice.feature.lobby.dto.LobbyResponse;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.global.constant.GameCode;
import com.copyleft.GodsChoice.global.constant.SocketEvent;
import com.copyleft.GodsChoice.infra.websocket.WebSocketSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LobbyResponseSender {

    private final WebSocketSender webSocketSender;

    public void sendJoinSuccess(String sessionId, Room room) {
        LobbyResponse response = LobbyResponse.builder()
                .event(SocketEvent.JOIN_SUCCESS.name())
                .room(room)
                .message(GameCode.ROOM_JOIN_SUCCESS.getMessage())
                .build();

        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void sendCreateSuccess(String sessionId, Room room) {
        LobbyResponse response = LobbyResponse.builder()
                .event(SocketEvent.JOIN_SUCCESS.name())
                .room(room)
                .message(GameCode.ROOM_CREATE_SUCCESS.getMessage())
                .build();

        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void broadcastLobbyUpdate(String roomId, Room room) {
        LobbyResponse response = LobbyResponse.builder()
                .event(SocketEvent.LOBBY_UPDATE.name())
                .room(room)
                .build();

        if (room.getPlayers() != null) {
            for (var player : room.getPlayers()) {
                if (player.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    webSocketSender.sendEventToSession(player.getSessionId(), response);
                }
            }
        }
    }

    public void sendError(String sessionId, ErrorCode errorCode) {
        LobbyResponse response = LobbyResponse.builder()
                .event(SocketEvent.JOIN_FAILED.name()) // 혹은 ERROR_MESSAGE
                .code(errorCode.name())
                .message(errorCode.getMessage())
                .build();

        webSocketSender.sendEventToSession(sessionId, response);
    }
}