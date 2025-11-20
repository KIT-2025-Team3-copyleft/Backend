package com.copyleft.GodsChoice.feature.nickname;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.feature.nickname.dto.NicknameResponse;
import com.copyleft.GodsChoice.infra.persistence.NicknameRepository;
import com.copyleft.GodsChoice.infra.websocket.WebSocketSender;
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


    public void setNickname(String sessionId, String nickname) {
        if (!StringUtils.hasText(nickname) || nickname.length() < 2 || nickname.length() > 6) {
            sendError(sessionId, "INVALID_NICKNAME", "닉네임은 2~6자 사이여야 합니다.");
            return;
        }

        if (nicknameRepository.isNicknameExists(nickname)) {
            sendDuplicate(sessionId);
            return;
        }

        nicknameRepository.addNickname(nickname);
        nicknameRepository.saveSessionNicknameMapping(sessionId, nickname);

        Player newPlayer = Player.builder()
                .sessionId(sessionId)
                .nickname(nickname)
                .connectionStatus("CONNECTED")
                .build();

        NicknameResponse response = NicknameResponse.builder()
                .event("NICKNAME_SUCCESS")
                .player(newPlayer)
                .build();

        webSocketSender.sendEventToSession(sessionId, response);
        log.info("닉네임 설정 완료: session={}, nickname={}", sessionId, nickname);
    }

    private void sendError(String sessionId, String code, String message) {
        NicknameResponse response = NicknameResponse.builder()
                .event("ERROR_MESSAGE")
                .code(code)
                .message(message)
                .build();
        webSocketSender.sendEventToSession(sessionId, response);
    }

    private void sendDuplicate(String sessionId) {
        NicknameResponse response = NicknameResponse.builder()
                .event("NICKNAME_DUPLICATE")
                .message("이미 사용 중인 닉네임입니다.")
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