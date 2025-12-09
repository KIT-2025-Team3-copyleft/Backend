package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.config.GameProperties;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.domain.type.GodPersonality;
import com.copyleft.GodsChoice.domain.type.Oracle;
import com.copyleft.GodsChoice.domain.type.SlotType;
import com.copyleft.GodsChoice.domain.vo.AiJudgment;
import com.copyleft.GodsChoice.feature.game.event.GameDecisionEvent; // 이벤트 클래스 import
import com.copyleft.GodsChoice.infra.external.GroqApiClient;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
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
    @Mock private GameProperties gameProperties;

    @Mock private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        // [수정] Runnable 버전 Mocking -> LockResult<Void> 반환
        lenient().doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return LockResult.success(null); // ✅ LockResult로 감싸야 함
        }).when(lockFacade).execute(anyString(), any(Runnable.class));

        // [수정] Supplier 버전 Mocking -> LockResult<T> 반환
        lenient().doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            Object result = action.get();
            return LockResult.success(result); // ✅ LockResult로 감싸야 함
        }).when(lockFacade).execute(anyString(), any(Supplier.class));

        // GameProperties 설정값 Mocking (기존 유지)
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
                .nickname("미선택자")
                .slot(SlotType.SUBJECT)
                .build();
        room.addPlayer(p1);

        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameJudgeService.processCardTimeout(roomId, room.getCurrentRound());

        // then
        assertNotNull(p1.getSelectedCard()); // 랜덤 선택 확인
        verify(gameResponseSender).broadcastAllCardsSelected(room);

        // 심판 로직(judgeRound) 예약 확인 (기존과 동일)
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("AI 심판 로직이 정상적으로 점수를 반영하고, '판정 완료' 이벤트를 발행한다")
    void judgeRound_Success() throws Exception {
        // given
        String roomId = "room-1";
        Room room = Room.builder()
                .roomId(roomId)
                .currentPhase(GamePhase.CARD_SELECT)
                .currentHp(500)
                .oracle(Oracle.VITALITY) // [추가] 테스트용 신탁 설정 (예: 활력)
                .godPersonality(GodPersonality.ANGRY) // [추가] 테스트용 성향 설정 (예: 분노)
                .build();

        Player p1 = Player.builder()
                .sessionId("p1")
                .nickname("유저1")
                .slot(SlotType.SUBJECT)
                .selectedCard("왕이")
                .build();
        room.addPlayer(p1);

        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // AI & JSON Mocking
        when(groqApiClient.judgeSentence(anyString(), eq(GodPersonality.ANGRY), eq(Oracle.VITALITY)))
                .thenReturn(new AiJudgment(-50, "감히 내 신탁을 망치다니!"));
        // (참고: AiJudgment 레코드를 쓰므로 ObjectMapper Mocking 부분은 실제 구현에 따라 필요 없을 수도 있습니다.
        // 만약 서비스 코드에서 JsonParsing을 직접 안하고 groqApiClient가 객체를 리턴한다면 이 부분 간소화 가능)

        // when
        gameJudgeService.judgeRound(roomId);

        // 스케줄러에 등록된 Runnable(judgeRoundInternal) 캡처 및 실행
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(Instant.class));
        captor.getValue().run(); // judgeRoundInternal -> AI 호출 -> applyJudgmentResult 호출

        // applyJudgmentResult 내부에서 또 스케줄링(이벤트 발행)을 하므로 한 번 더 캡처해야 함
        // (judgeRoundInternal 안에서 applyJudgmentResult가 lockFacade 안에서 불리는데,
        // 테스트에서는 lockFacade가 즉시 실행되므로 바로 스케줄러가 호출됨)

        // Mockito의 verify는 호출 횟수를 누적하므로, 두 번째 호출을 잡아야 함 (atLeast(2) 또는 times(2))
        verify(taskScheduler, atLeast(2)).schedule(captor.capture(), any(Instant.class));

        // 가장 마지막에 등록된 작업(이벤트 발행) 실행
        captor.getValue().run();

        // then
        // 1. 점수 반영 확인
        assertEquals(450, room.getCurrentHp());
        verify(gameResponseSender).broadcastRoundResult(
                eq(room),
                eq(-50),
                eq("감히 내 신탁을 망치다니!"),
                anyList(),
                anyString()
        );

        // 2. ✅ 핵심 변경: GameFlowService 호출 대신 '이벤트 발행' 검증
        ArgumentCaptor<GameDecisionEvent> eventCaptor = ArgumentCaptor.forClass(GameDecisionEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        GameDecisionEvent event = eventCaptor.getValue();
        assertEquals(roomId, event.getRoomId());
        assertEquals(GameDecisionEvent.Type.ROUND_JUDGED, event.getType());
    }
}