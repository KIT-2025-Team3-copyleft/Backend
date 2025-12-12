package com.copyleft.GodsChoice.feature.chat;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.feature.chat.dto.ChatResponse;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.global.constant.SocketEvent;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import com.copyleft.GodsChoice.infra.websocket.dto.WebSocketResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final RoomRepository roomRepository;
    private final ChatResponseSender chatResponseSender;

    private static final String CHAT_FORMAT = "%s[%s] : %s";

    public void processChat(String sessionId, String message) {

        if (!StringUtils.hasText(message)) {
            chatResponseSender.sendError(sessionId, ErrorCode.CHAT_EMPTY.name(), ErrorCode.CHAT_EMPTY.getMessage());
            return;
        }

        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) {
            chatResponseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND.name(), ErrorCode.ROOM_NOT_FOUND.getMessage());
            return;
        }

        Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
        if (roomOpt.isEmpty()) {
            chatResponseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND.name(), ErrorCode.ROOM_NOT_FOUND.getMessage());
            return;
        }
        Room room = roomOpt.get();

        Player sender = room.getPlayers().stream()
                .filter(p -> p.getSessionId().equals(sessionId))
                .findFirst()
                .orElse(null);

        if (sender == null) {
            return;
        }

        String colorCode = sender.getColor() != null ? sender.getColor().name() : "UNKNOWN";
        String formattedMessage = String.format(CHAT_FORMAT, sender.getNickname(), colorCode, message);

        ChatResponse chatData = ChatResponse.builder()
                .sender(sender.getNickname())
                .color(colorCode)
                .content(message)
                .formattedMessage(formattedMessage)
                .build();

        chatResponseSender.broadcastChat(room, chatData);

        log.info("채팅 전송: room={}, sender={}, msg={}", roomId, sender.getNickname(), message);
    }
}