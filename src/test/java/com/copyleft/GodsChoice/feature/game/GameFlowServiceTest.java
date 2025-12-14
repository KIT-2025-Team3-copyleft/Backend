package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.global.config.GameProperties;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.game.service.*;
import com.copyleft.GodsChoice.lobby.service.LobbyResponseSender;
import com.copyleft.GodsChoice.game.repository.RoomRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameFlowServiceTest {

    @InjectMocks
    private GameFlowService gameFlowService;

    @Mock private RoomRepository roomRepository;
    @Mock private GameRoomLockFacade lockFacade; // Facade Mock
    @Mock private GameResponseSender gameResponseSender;
    @Mock private LobbyResponseSender lobbyResponseSender;
    @Mock private TaskScheduler taskScheduler;
    @Mock private GameLogService gameLogService;
    @Mock private GameProperties gameProperties;
    @Mock private GameJudgeService gameJudgeService; // Lazy 주입된 서비스 Mock

    @BeforeEach
    void setUp() {
        // [핵심] LockFacade가 락 없이 로직을 바로 실행하도록 설정
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(lockFacade).execute(anyString(), any(Runnable.class));

        // GameProperties 기본값 설정 (필요 시)
        lenient().when(gameProperties.startDelay()).thenReturn(3);
        lenient().when(gameProperties.cardSendDelay()).thenReturn(3);
        lenient().when(gameProperties.cardSelectTime()).thenReturn(120);
        lenient().when(gameProperties.maxRounds()).thenReturn(4);
    }

    @Test
    @DisplayName("게임 시작 요청 시 상태가 STARTING으로 변경되고 타이머가 예약된다")
    void tryStartGame_Success() {
        // given
        String sessionId = "host-123";
        String roomId = "room-uuid";
        Room room = Room.builder()
                .roomId(roomId)
                .hostSessionId(sessionId)
                .status(RoomStatus.WAITING)
                .build();
        // 플레이어 4명 채우기
        for(int i=0; i<4; i++) room.addPlayer(Player.builder().sessionId("p"+i).build());

        when(roomRepository.getRoomIdBySessionId(sessionId)).thenReturn(roomId);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameFlowService.tryStartGame(sessionId);

        // then
        assertEquals(RoomStatus.STARTING, room.getStatus());
        verify(roomRepository).saveRoom(room);
        verify(gameResponseSender).broadcastGameStartTimer(room);
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("라운드 시작 시 페이즈 변경 및 카드 전송 예약 확인")
    void startRound_Success() {
        // given
        String roomId = "room-1";
        Room room = Room.builder().roomId(roomId).build();
        for(int i=0; i<4; i++) room.addPlayer(Player.builder().sessionId("p"+i).build());

        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameFlowService.startRound(roomId);

        // then
        assertEquals(GamePhase.CARD_SELECT, room.getCurrentPhase());

        // 카드 전송 예약, 타임아웃 예약 2건이 스케줄링 되어야 함
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }
}