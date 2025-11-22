package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
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

        when(redisLockRepository.lock(roomId)).thenReturn(lockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gameService.processGameStart(roomId);

        // then
        assertEquals(RoomStatus.PLAYING, room.getStatus()); // 상태 변경 확인
        verify(roomRepository).removeWaitingRoom(roomId); // 대기 목록 제거 확인
        verify(gameResponseSender).broadcastLoadGameScene(room); // 씬 이동 알림 확인
    }
}