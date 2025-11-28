package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.domain.type.PlayerRole;
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
import java.util.List;
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
            room.addPlayer(Player.builder()
                    .sessionId("p" + i)
                    .build());
        }

        when(redisLockRepository.lock(roomId)).thenReturn(lockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameService.processGameStart(roomId);

        // then
        assertEquals(PlayerRole.TRAITOR, room.getPlayers().get(0).getRole());
        assertEquals(PlayerRole.CITIZEN, room.getPlayers().get(1).getRole());
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
        assertEquals(GamePhase.CARD_SELECT, room.getCurrentPhase());
        verify(gameResponseSender).broadcastRoundStart(room);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        verify(taskScheduler, times(2)).schedule(runnableCaptor.capture(), any(Instant.class));

        List<Runnable> capturedTasks = runnableCaptor.getAllValues();

        capturedTasks.get(0).run();

        verify(gameResponseSender, times(4)).sendCards(anyString(), anyString(), anyList());

        verify(redisLockRepository, times(2)).unlock(roomId, lockToken);
    }

    // ----------------------------------------------------------------
    // [PART 4] 카드 선택 및 AI 심판 테스트
    // ----------------------------------------------------------------

    @Test
    @DisplayName("전원 카드 선택 시 AI 심판이 수행되고 히스토리가 저장된다") // 이름 약간 수정
    void selectCard_AllSelected_TriggersJudgment() throws Exception {
        // given
        String roomId = "room-1";
        String sessionId = "p1";
        String lockToken = "token";

        // Oracle 정보가 있어야 하므로 설정 추가
        Room room = Room.builder()
                .roomId(roomId)
                .currentPhase(GamePhase.CARD_SELECT)
                .currentHp(1000)
                .currentRound(1)
                .oracle(com.copyleft.GodsChoice.domain.type.Oracle.ORDER) // 테스트용 신탁
                .build();

        Player p1 = Player.builder().sessionId(sessionId).slot(SlotType.SUBJECT).build();
        room.addPlayer(p1);

        when(roomRepository.getRoomIdBySessionId(sessionId)).thenReturn(roomId);
        when(redisLockRepository.lock(roomId)).thenReturn(lockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // AI Mocking
        when(groqApiClient.judgeSentence(anyString(), any())).thenReturn("{\"score\": -50, \"reason\": \"bad\"}");
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode realJsonNode = realMapper.readTree("{\"score\": -50, \"reason\": \"bad\"}");

        when(objectMapper.readTree(anyString())).thenReturn(realJsonNode);

        // when
        gameService.selectCard(sessionId, "선택한카드");

        // then
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));
        runnableCaptor.getValue().run();

        // judgeRoundInternal 내부에서 applyJudgmentResult를 호출할 때 락을 획득하면 즉시 실행됨.
        // 테스트 환경에서는 redisLockRepository.lock()이 성공(token반환)하면 동기적으로 처리됨을 가정하거나,
        // GameService 구현상 재귀 스케줄링이 없으면 바로 반영됨.

        assertEquals(1, room.getRoundHistories().size());
        assertEquals(com.copyleft.GodsChoice.domain.type.Oracle.ORDER, room.getRoundHistories().get(0).getOracle());
        assertEquals("bad", room.getRoundHistories().get(0).getReason());

        verify(roomRepository, atLeastOnce()).saveRoom(room);
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