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
    @DisplayName("정상적인 닉네임 설정 요청 시 성공 이벤트를 보낸다")
    void setNickname_Success() {
        // given
        String sessionId = "abc-123";
        String nickname = "정상닉네임";

        // (가짜 행동 정의) 중복되지 않았다고 가정
        when(nicknameRepository.isNicknameExists(nickname)).thenReturn(false);

        // when
        nicknameService.setNickname(sessionId, nickname);

        // then
        // 1. Redis에 저장 메소드가 호출되었는지 검증
        verify(nicknameRepository).addNickname(nickname);
        verify(nicknameRepository).saveSessionNicknameMapping(sessionId, nickname);

        // 2. 성공 메시지를 보냈는지 검증 (event 값이 "NICKNAME_SUCCESS"인지 등은 ArgumentCaptor로 정밀 확인 가능하지만 여기선 호출 여부만)
        verify(webSocketSender).sendEventToSession(eq(sessionId), any(NicknameResponse.class));
    }

    @Test
    @DisplayName("중복된 닉네임이면 에러 이벤트를 보낸다")
    void setNickname_Duplicate() {
        // given
        String sessionId = "abc-123";
        String nickname = "중복된놈";

        // (가짜 행동 정의) 이미 존재한다고 가정
        when(nicknameRepository.isNicknameExists(nickname)).thenReturn(true);

        // when
        nicknameService.setNickname(sessionId, nickname);

        // then
        // Redis 저장 로직은 호출되면 안 됨
        verify(nicknameRepository, never()).addNickname(anyString());

        // 실패 메시지 전송 확인
        verify(webSocketSender).sendEventToSession(eq(sessionId), any(NicknameResponse.class));
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