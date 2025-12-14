package com.copyleft.GodsChoice.user.service;

import com.copyleft.GodsChoice.global.config.GameProperties;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.type.ConnectionStatus;
import com.copyleft.GodsChoice.user.dto.NicknameResponse;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.global.constant.SocketEvent;
import com.copyleft.GodsChoice.user.repository.NicknameRepository;
import com.copyleft.GodsChoice.global.websocket.WebSocketSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class NicknameService {

    private final NicknameRepository nicknameRepository;
    private final WebSocketSender webSocketSender;
    private final GameProperties gameProperties;


    public void setNickname(String sessionId, String nickname) {

        if (!StringUtils.hasText(nickname) ||
                nickname.length() < gameProperties.nicknameMinLength() ||
                nickname.length() > gameProperties.nicknameMaxLength()) {
            sendError(sessionId);
            return;
        }

        String oldNickname = nicknameRepository.getNicknameBySessionId(sessionId);
        if (oldNickname != null && oldNickname.equals(nickname)) {
            sendSuccess(sessionId, nickname);
            return;
        }

        boolean isReserved = nicknameRepository.reserveNickname(nickname);

        if (!isReserved) {
            sendDuplicate(sessionId);
            return;
        }

        if (oldNickname != null && !oldNickname.equals(nickname)) {
            nicknameRepository.removeNickname(oldNickname);
            log.info("기존 닉네임 정리: session={}, old={}", sessionId, oldNickname);
        }

        nicknameRepository.saveSessionNicknameMapping(sessionId, nickname);

        sendSuccess(sessionId, nickname);
    }

    private void sendSuccess(String sessionId, String nickname) {
        Player newPlayer = Player.builder()
                .sessionId(sessionId)
                .nickname(nickname)
                .connectionStatus(ConnectionStatus.CONNECTED)
                .build();

        NicknameResponse response = NicknameResponse.builder()
                .event(SocketEvent.NICKNAME_SUCCESS.name())
                .player(newPlayer)
                .build();

        webSocketSender.sendEventToSession(sessionId, response);
        log.info("닉네임 설정 완료: session={}, user={}", sessionId, nickname);
    }

    private void sendError(String sessionId) {
        NicknameResponse response = NicknameResponse.builder()
                .event(SocketEvent.ERROR_MESSAGE.name())
                .code(ErrorCode.INVALID_NICKNAME.name())
                .message(ErrorCode.INVALID_NICKNAME.getMessage())
                .build();
        webSocketSender.sendEventToSession(sessionId, response);
    }

    private void sendDuplicate(String sessionId) {
        NicknameResponse response = NicknameResponse.builder()
                .event(SocketEvent.NICKNAME_DUPLICATE.name())
                .message(ErrorCode.NICKNAME_ALREADY_USE.getMessage())
                .build();
        webSocketSender.sendEventToSession(sessionId, response);
    }


    public void handleDisconnect(String sessionId) {
        String nickname = nicknameRepository.getNicknameBySessionId(sessionId);
        if (nickname != null) {
            nicknameRepository.removeNickname(nickname);
            nicknameRepository.deleteSessionMapping(sessionId);
            log.info("닉네임 반납 완료: {}", nickname);
        }
    }
}