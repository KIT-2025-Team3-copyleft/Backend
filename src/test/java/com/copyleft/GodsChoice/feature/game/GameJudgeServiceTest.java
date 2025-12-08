package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.config.GameProperties;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.domain.type.SlotType;
import com.copyleft.GodsChoice.infra.external.GroqApiClient;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameJudgeServiceTest {

    @InjectMocks
    private GameJudgeService gameJudgeService;

    @Mock private RoomRepository roomRepository;
    @Mock private GameRoomLockFacade lockFacade;
    @Mock private GameResponseSender gameResponseSender;
    @Mock private GroqApiClient groqApiClient;
    @Mock private ObjectMapper objectMapper;
    @Mock private TaskScheduler taskScheduler;
    @Mock private GameFlowService gameFlowService;
    @Mock private GameProperties gameProperties;

    @BeforeEach
    void setUp() {
        // [수정 1] lenient() 추가: 사용되지 않는 스터빙 에러 방지
        // Runnable 버전 Mocking
        lenient().doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return true;
        }).when(lockFacade).execute(anyString(), any(Runnable.class));

        // Supplier 버전 Mocking (주로 사용됨)
        lenient().doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        }).when(lockFacade).execute(anyString(), any(Supplier.class));

        // GameProperties 설정값 Mocking (필요시)
        lenient().when(gameProperties.roundResultDuration()).thenReturn(35);
    }

    @Test
    @DisplayName("타임아웃 시 미선택자 카드가 자동 선택되고 심판이 진행된다")
    void processCardTimeout_AutoSelects() {
        // given
        String roomId = "room-1";
        Room room = Room.builder().roomId(roomId).currentPhase(GamePhase.CARD_SELECT).build();
        Player p1 = Player.builder()
                .sessionId("p1")
                .nickname("미선택자") // [수정] 닉네임 추가
                .slot(SlotType.SUBJECT)
                .build(); // 카드 미선택
        room.addPlayer(p1);

        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameJudgeService.processCardTimeout(roomId);

        // then
        assertNotNull(p1.getSelectedCard()); // 랜덤 선택 확인
        verify(gameResponseSender).broadcastAllCardsSelected(room);

        // 심판 로직(judgeRound) 예약 확인
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("AI 심판 로직이 정상적으로 점수를 반영한다")
    void judgeRound_Success() throws Exception {
        // given
        String roomId = "room-1";
        Room room = Room.builder()
                .roomId(roomId)
                .currentPhase(GamePhase.CARD_SELECT) // [수정 2] JUDGING -> CARD_SELECT (중요!)
                .currentHp(500)
                .build();

        Player p1 = Player.builder()
                .sessionId("p1")
                .nickname("유저1")
                .slot(SlotType.SUBJECT)
                .selectedCard("왕이")
                .build();
        room.addPlayer(p1);

        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // AI Mock
        when(groqApiClient.judgeSentence(any(), any())).thenReturn("{\"score\": -50, \"reason\": \"bad\"}");

        // ObjectMapper Mock
        JsonNode mockJson = mock(JsonNode.class);
        when(objectMapper.readTree(anyString())).thenReturn(mockJson);
        when(mockJson.path("score")).thenReturn(mock(JsonNode.class));
        when(mockJson.path("score").asInt()).thenReturn(-50);
        when(mockJson.path("reason")).thenReturn(mock(JsonNode.class));
        when(mockJson.path("reason").asText()).thenReturn("bad");

        // when
        gameJudgeService.judgeRound(roomId);

        // 스케줄러에 등록된 Runnable(judgeRoundInternal)을 캡처해서 강제 실행
        org.mockito.ArgumentCaptor<Runnable> captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(Instant.class));
        captor.getValue().run(); // judgeRoundInternal 실행

        // then
        // AI 호출 확인
        verify(groqApiClient).judgeSentence(anyString(), anyString());

        // 결과 반영 확인 (HP 감소)
        assertEquals(450, room.getCurrentHp()); // 500 - 50 = 450
        verify(gameResponseSender).broadcastRoundResult(eq(room), eq(-50), eq("bad"), anyString());
    }
}