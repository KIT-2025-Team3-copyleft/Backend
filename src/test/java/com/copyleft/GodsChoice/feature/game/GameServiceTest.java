package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.domain.type.SlotType;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.external.GroqApiClient;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @InjectMocks
    private GameService gameService;

    @Mock private RoomRepository roomRepository;
    @Mock private RedisLockRepository redisLockRepository;
    @Mock private GameResponseSender gameResponseSender;
    @Mock private TaskScheduler taskScheduler; // 타이머 Mock
    @Mock private GroqApiClient groqApiClient;
    @Mock private ObjectMapper objectMapper;

    @Test
    @DisplayName("게임 시작 요청 시 상태가 STARTING으로 변경되고 타이머가 예약된다")
    void tryStartGame_Success() {
        // given
        String sessionId = "host-123";
        String roomId = "room-uuid";
        String lockToken = "lock-token";

        Room room = Room.builder()
                .roomId(roomId)
                .hostSessionId(sessionId)
                .status(RoomStatus.WAITING)
                .build();
        for(int i=0; i<4; i++) {
            room.addPlayer(com.copyleft.GodsChoice.domain.Player.builder()
                    .sessionId(i == 0 ? sessionId : "player-" + i)
                    .nickname("Player" + i)
                    .isHost(i == 0)
                    .build());
        }

        when(roomRepository.getRoomIdBySessionId(sessionId)).thenReturn(roomId);
        when(redisLockRepository.lock(roomId)).thenReturn(lockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameService.tryStartGame(sessionId);

        // then
        assertEquals(RoomStatus.STARTING, room.getStatus());
        verify(roomRepository).saveRoom(room);

        verify(gameResponseSender).broadcastGameStartTimer(room);

        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        verify(redisLockRepository).unlock(roomId, lockToken);
    }

    @Test
    @DisplayName("방장이 아니면 게임을 시작할 수 없다")
    void tryStartGame_NotHost() {
        // given
        String sessionId = "guest-123"; // 방장 아님
        String roomId = "room-uuid";
        String lockToken = "lock-token";

        Room room = Room.builder()
                .roomId(roomId)
                .hostSessionId("real-host") // 실제 방장
                .status(RoomStatus.WAITING)
                .build();

        when(roomRepository.getRoomIdBySessionId(sessionId)).thenReturn(roomId);
        when(redisLockRepository.lock(roomId)).thenReturn(lockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameService.tryStartGame(sessionId);

        // then
        verify(gameResponseSender).sendError(sessionId, ErrorCode.NOT_HOST);
        assertEquals(RoomStatus.WAITING, room.getStatus());
        verify(taskScheduler, never()).schedule(any(), any(Instant.class));
        verify(redisLockRepository).unlock(roomId, lockToken);
    }

    @Test
    @DisplayName("타이머 로직(processGameStart) 실행 시 PLAYING으로 변경된다")
    void processGameStart_Success() {
        // given
        String roomId = "room-uuid";
        String lockToken = "lock-token";

        Room room = Room.builder()
                .roomId(roomId)
                .status(RoomStatus.STARTING)
                .build();

        for (int i = 0; i < 4; i++) {
            room.addPlayer(com.copyleft.GodsChoice.domain.Player.builder()
                    .sessionId("player-" + i)
                    .nickname("Player" + i)
                    .build());
        }

        when(redisLockRepository.lock(roomId)).thenReturn(lockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameService.processGameStart(roomId);

        // then
        assertEquals(RoomStatus.PLAYING, room.getStatus()); // 상태 변경 확인
        verify(roomRepository).removeWaitingRoom(roomId); // 대기 목록 제거 확인
        verify(gameResponseSender).broadcastLoadGameScene(room); // 씬 이동 알림 확인
        verify(redisLockRepository).unlock(roomId, lockToken);
    }

    @Test
    @DisplayName("게임 시작 시 역할이 CITIZEN으로 초기화되는지 검증")
    void processGameStart_AssignRoles() {
        // given
        String roomId = "room-1";
        String lockToken = "token";
        Room room = Room.builder().roomId(roomId).status(RoomStatus.STARTING).build();
        for (int i = 0; i < 4; i++) {
            room.addPlayer(com.copyleft.GodsChoice.domain.Player.builder()
                    .sessionId("p" + i)
                    .build());
        }

        when(redisLockRepository.lock(roomId)).thenReturn(lockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameService.processGameStart(roomId);

        // then
        assertEquals("CITIZEN", room.getPlayers().get(0).getRole());
        verify(redisLockRepository).unlock(roomId, lockToken);
    }

    @Test
    @DisplayName("라운드 시작 시 카드 배분, 페이즈 변경, 타이머 예약이 수행된다")
    void startRound_Success() {
        // given
        String roomId = "room-1";
        String lockToken = "token";
        Room room = Room.builder().roomId(roomId).build();
        // 플레이어 4명
        for (int i = 0; i < 4; i++) {
            room.addPlayer(Player.builder().sessionId("p" + i).build());
        }

        when(redisLockRepository.lock(roomId)).thenReturn(lockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameService.startRound(roomId);

        // then
        // 1. 슬롯 할당 및 카드 전송 확인 (4명 모두에게)
        verify(gameResponseSender, times(4)).sendCards(anyString(), anyString(), anyList());

        // 2. 페이즈 변경 확인
        assertEquals(GamePhase.CARD_SELECT, room.getCurrentPhase());

        // 3. 라운드 시작 알림 확인
        verify(gameResponseSender).broadcastRoundStart(room);

        // 4. 120초(2분) 타이머 예약 확인
        // (ArgumentCaptor로 시간까지 정확히 검증하면 더 좋음)
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        verify(redisLockRepository).unlock(roomId, lockToken);
    }

    // ----------------------------------------------------------------
    // [PART 4] 카드 선택 및 AI 심판 테스트
    // ----------------------------------------------------------------

    @Test
    @DisplayName("전원 카드 선택 시 AI 심판(judgeRound)이 비동기로 예약된다")
    void selectCard_AllSelected_TriggersJudgment() throws Exception {
        // given
        String roomId = "room-1";
        String sessionId = "p1";
        String lockToken = "token";

        Room room = Room.builder()
                .roomId(roomId)
                .currentPhase(GamePhase.CARD_SELECT)
                .currentHp(1000)
                .build();

        // 플레이어 1명만 있다고 가정 (테스트 편의상) 하고 그 사람이 선택하면 '전원 선택'이 됨
        Player p1 = Player.builder().sessionId(sessionId).slot(SlotType.SUBJECT).build();
        room.addPlayer(p1);

        when(roomRepository.getRoomIdBySessionId(sessionId)).thenReturn(roomId);
        when(redisLockRepository.lock(roomId)).thenReturn(lockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // AI 응답 모킹
        when(groqApiClient.judgeSentence(anyString(), any())).thenReturn("{\"score\": -50, \"reason\": \"bad\"}");
        JsonNode mockJson = mock(JsonNode.class);
        when(objectMapper.readTree(anyString())).thenReturn(mockJson);
        when(mockJson.path("score")).thenReturn(mock(JsonNode.class)); // score.asInt() 호출 대비
        when(mockJson.path("score").asInt()).thenReturn(-50);
        when(mockJson.path("reason")).thenReturn(mock(JsonNode.class));
        when(mockJson.path("reason").asText()).thenReturn("bad");

        // when
        gameService.selectCard(sessionId, "선택한카드");

        // then
        // 1. 선택 저장 확인
        assertEquals("선택한카드", p1.getSelectedCard());
        verify(roomRepository, atLeastOnce()).saveRoom(room); // 저장 호출 확인 (최소 1번)

        // 2. [핵심] 심판 로직이 스케줄러에 예약되었는지 캡처
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        // selectCard 내부에서 judgeRound -> taskScheduler.schedule 호출함
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // 3. [핵심] 예약된 작업(Runnable)을 강제로 실행! (이게 judgeRoundInternal 로직임)
        Runnable judgeTask = runnableCaptor.getValue();
        judgeTask.run(); // <-- 여기서 실제 judgeRoundInternal 로직이 돌아감

        // 4. 심판 로직 검증 (Runnable 실행 결과)
        // - AI 호출했나?
        verify(groqApiClient).judgeSentence(anyString(), any());
        // - HP 깎였나?
        assertEquals(950, room.getCurrentHp());
        // - 결과 방송했나?
        verify(gameResponseSender).broadcastRoundResult(eq(room), eq(-50), eq("bad"), anyString());
    }

    @Test
    @DisplayName("타임아웃 시 미선택자 카드가 랜덤 선택되고 심판이 진행된다")
    void processCardTimeout_AutoSelects() {
        // given
        String roomId = "room-1";
        String lockToken = "token";

        Room room = Room.builder().roomId(roomId).currentPhase(GamePhase.CARD_SELECT).build();
        Player p1 = Player.builder().sessionId("p1").slot(SlotType.SUBJECT).build(); // 미선택
        room.addPlayer(p1);

        when(redisLockRepository.lock(roomId)).thenReturn(lockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameService.processCardTimeout(roomId);

        // then
        // 1. 카드가 null이 아니어야 함 (자동 선택됨)
        assertNotNull(p1.getSelectedCard());

        // 2. 심판 진행 확인 (broadcastAllCardsSelected 호출 여부)
        verify(gameResponseSender).broadcastAllCardsSelected(room);

        // 3. 심판 예약 확인
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }
}