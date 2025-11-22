package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.type.ConnectionStatus;
import com.copyleft.GodsChoice.feature.game.dto.GameResponse; // [변경] LobbyResponse -> GameResponse
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.global.constant.GameCode;
import com.copyleft.GodsChoice.global.constant.SocketEvent;
import com.copyleft.GodsChoice.infra.websocket.WebSocketSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GameResponseSender {

    private final WebSocketSender webSocketSender;

    public void broadcastGameStartTimer(Room room) {
        GameResponse response = createResponse(
                SocketEvent.GAME_START_TIMER,
                null,
                GameCode.GAME_COUNTDOWN_START.getMessage(),
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastLoadGameScene(Room room) {
        GameResponse response = createResponse(
                SocketEvent.LOAD_GAME_SCENE,
                room,
                null,
                null
        );
        broadcastToRoom(room, response);
    }

    public void sendError(String sessionId, ErrorCode errorCode) {
        GameResponse response = createResponse(
                SocketEvent.ERROR_MESSAGE,
                null,
                errorCode.getMessage(),
                errorCode.name()
        );
        webSocketSender.sendEventToSession(sessionId, response);
    }

    private GameResponse createResponse(SocketEvent event, Room room, String message, String code) {
        return GameResponse.builder()
                .event(event.name())
                .room(room)
                .message(message)
                .code(code)
                .build();
    }

    private void broadcastToRoom(Room room, GameResponse response) {
        if (room.getPlayers() != null) {
            for (Player player : room.getPlayers()) {
                if (player.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    webSocketSender.sendEventToSession(player.getSessionId(), response);
                }
            }
        }
    }
}