package com.copyleft.GodsChoice.feature.chat;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.ConnectionStatus;
import com.copyleft.GodsChoice.feature.chat.dto.ChatResponse;
import com.copyleft.GodsChoice.global.constant.SocketEvent;
import com.copyleft.GodsChoice.infra.websocket.WebSocketSender;
import com.copyleft.GodsChoice.infra.websocket.dto.WebSocketResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatResponseSender {

    private final WebSocketSender webSocketSender;

    public void broadcastChat(Room room, ChatResponse chatData) {
        WebSocketResponse<ChatResponse> response = WebSocketResponse.<ChatResponse>builder()
                .event(SocketEvent.CHAT_MESSAGE.name())
                .data(chatData)
                .build();

        if (room != null && room.getPlayers() != null) {
            for (Player player : room.getPlayers()) {
                if (player.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    webSocketSender.sendEventToSession(player.getSessionId(), response);
                }
            }
        }
    }

    public void sendError(String sessionId, String errorCode, String errorMessage) {
        WebSocketResponse<Void> response = WebSocketResponse.<Void>builder()
                .event(SocketEvent.ERROR_MESSAGE.name())
                .code(errorCode)
                .message(errorMessage)
                .build();
        webSocketSender.sendEventToSession(sessionId, response);
    }
}