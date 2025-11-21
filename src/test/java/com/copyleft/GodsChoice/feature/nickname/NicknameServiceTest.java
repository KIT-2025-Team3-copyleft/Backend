package com.copyleft.GodsChoice.feature.nickname;

import com.copyleft.GodsChoice.feature.nickname.dto.NicknameResponse;
import com.copyleft.GodsChoice.infra.persistence.NicknameRepository;
import com.copyleft.GodsChoice.infra.websocket.WebSocketSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class) // Mockito 확장 기능 사용
class NicknameServiceTest {

    @InjectMocks
    private NicknameService nicknameService; // 테스트 대상

    @Mock
    private NicknameRepository nicknameRepository; // 가짜 Repository

    @Mock
    private WebSocketSender webSocketSender; // 가짜 Sender

    @Test
    void setNickname_Success() {
        // given
        String sessionId = "session123";
        String nickname = "테스트닉";

        when(nicknameRepository.reserveNickname(nickname)).thenReturn(true);

        // when
        nicknameService.setNickname(sessionId, nickname);

        // then
        verify(nicknameRepository).reserveNickname(nickname);
        verify(nicknameRepository).saveSessionNicknameMapping(sessionId, nickname);
        verify(webSocketSender).sendEventToSession(eq(sessionId), any(NicknameResponse.class));
    }

    @Test
    void setNickname_Duplicate() {
        // given
        String sessionId = "session123";
        String nickname = "중복닉";

        when(nicknameRepository.reserveNickname(nickname)).thenReturn(false);

        // when
        nicknameService.setNickname(sessionId, nickname);

        // then
        verify(nicknameRepository).reserveNickname(nickname);
        // 중복이면 저장은 호출되면 안 됨
        verify(nicknameRepository, never()).saveSessionNicknameMapping(anyString(), anyString());
    }

    @Test
    @DisplayName("닉네임 길이가 유효하지 않으면 에러를 보낸다")
    void setNickname_InvalidLength() {
        // given
        String sessionId = "abc-123";
        String shortName = "김"; // 2자 미만

        // when
        nicknameService.setNickname(sessionId, shortName);

        // then
        verify(nicknameRepository, never()).isNicknameExists(anyString()); // 중복 검사도 안 해야 함
        verify(webSocketSender).sendEventToSession(eq(sessionId), any(NicknameResponse.class));
    }
}