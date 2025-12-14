package com.copyleft.GodsChoice.lobby.service;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.type.ConnectionStatus;
import com.copyleft.GodsChoice.lobby.dto.LobbyPayloads;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.global.constant.GameCode;
import com.copyleft.GodsChoice.global.constant.SocketEvent;
import com.copyleft.GodsChoice.global.websocket.WebSocketSender;
import com.copyleft.GodsChoice.global.websocket.dto.WebSocketResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LobbyResponseSender {

    private final WebSocketSender webSocketSender;

    public void sendJoinSuccess(String sessionId, Room room) {
        WebSocketResponse<Room> response = WebSocketResponse.<Room>builder()
                .event(SocketEvent.JOIN_SUCCESS.name())
                .message(GameCode.ROOM_JOIN_SUCCESS.getMessage())
                .data(room)
                .build();
        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void sendCreateSuccess(String sessionId, Room room) {
        WebSocketResponse<Room> response = WebSocketResponse.<Room>builder()
                .event(SocketEvent.JOIN_SUCCESS.name())
                .message(GameCode.ROOM_CREATE_SUCCESS.getMessage())
                .data(room)
                .build();
        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void sendRoomList(String sessionId, List<LobbyPayloads.RoomInfo> rooms) {
        LobbyPayloads.RoomList data = LobbyPayloads.RoomList.builder()
                .rooms(rooms)
                .build();

        WebSocketResponse<LobbyPayloads.RoomList> response = WebSocketResponse.<LobbyPayloads.RoomList>builder()
                .event(SocketEvent.ROOM_LIST.name())
                .data(data)
                .build();

        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void broadcastLobbyUpdate(Room room) {
        WebSocketResponse<Room> response = WebSocketResponse.<Room>builder()
                .event(SocketEvent.LOBBY_UPDATE.name())
                .data(room)
                .build();
        broadcastToRoom(room, response);
    }

    public void broadcastTimerCancelled(Room room) {
        WebSocketResponse<Room> response = WebSocketResponse.<Room>builder()
                .event(SocketEvent.TIMER_CANCELLED.name())
                .message(GameCode.GAME_TIMER_CANCELLED.getMessage())
                .data(null)
                .build();
        broadcastToRoom(room, response);
    }

    public void sendLeaveSuccess(String sessionId) {
        WebSocketResponse<Void> response = WebSocketResponse.<Void>builder()
                .event(SocketEvent.LEAVE_SUCCESS.name())
                .message(GameCode.ROOM_LEAVE_SUCCESS.getMessage())
                .build();
        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void sendError(String sessionId, ErrorCode errorCode) {
        WebSocketResponse<Void> response = WebSocketResponse.<Void>builder()
                .event(SocketEvent.JOIN_FAILED.name())
                .message(errorCode.getMessage())
                .code(errorCode.name())
                .build();
        webSocketSender.sendEventToSession(sessionId, response);
    }

    private void broadcastToRoom(Room room, WebSocketResponse<?> response) {
        if (room.getPlayers() != null) {
            for (Player player : room.getPlayers()) {
                if (player.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    webSocketSender.sendEventToSession(player.getSessionId(), response);
                }
            }
        }
    }
}