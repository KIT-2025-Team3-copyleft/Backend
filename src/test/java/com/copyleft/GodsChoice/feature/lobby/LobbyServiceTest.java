package com.copyleft.GodsChoice.feature.lobby;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.feature.game.GameRoomLockFacade; // 추가
import com.copyleft.GodsChoice.feature.game.LockResult;       // 추가
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.persistence.NicknameRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LobbyServiceTest {

    @InjectMocks
    private LobbyService lobbyService;

    @Mock private RoomRepository roomRepository;
    @Mock private NicknameRepository nicknameRepository;
    // @Mock private RedisLockRepository redisLockRepository; // [삭제] 더 이상 안 씀
    @Mock private GameRoomLockFacade lockFacade; // [추가] 이걸로 교체
    @Mock private LobbyResponseSender responseSender;

    @Test
    @DisplayName("방 생성 시 리포지토리에 저장하고 성공 메시지를 보낸다")
    void createRoom_Success() {
        // given
        String sessionId = "session-123";
        String nickname = "방장님";

        when(nicknameRepository.getNicknameBySessionId(sessionId)).thenReturn(nickname);

        // when
        lobbyService.createRoom(sessionId);

        // then
        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository).saveRoom(roomCaptor.capture());

        Room savedRoom = roomCaptor.getValue();
        assertEquals(nickname + "님의 방", savedRoom.getRoomTitle());
        assertEquals(RoomStatus.WAITING, savedRoom.getStatus());
        assertEquals(sessionId, savedRoom.getHostSessionId());
        assertEquals(1, savedRoom.getPlayers().size()); // 방장 1명

        verify(responseSender).sendCreateSuccess(eq(sessionId), any(Room.class));
    }

    @Test
    @DisplayName("빠른 입장 시 빈 방이 없으면 새 방을 생성한다")
    void quickJoin_NoEmptyRoom_CreatesNew() {
        // given
        String sessionId = "session-123";
        when(roomRepository.findAllWaitingRooms()).thenReturn(java.util.Collections.emptyList()); // 빈 방 없음
        when(nicknameRepository.getNicknameBySessionId(sessionId)).thenReturn("유저A");

        // when
        lobbyService.quickJoin(sessionId);

        // then
        verify(roomRepository).saveRoom(any(Room.class));
        verify(responseSender).sendCreateSuccess(eq(sessionId), any(Room.class));
    }

    @Test
    @DisplayName("방 입장 시 분산 락 획득에 실패하면 에러를 보낸다")
    void joinRoom_LockFailed() {
        // given
        String sessionId = "session-123";
        String roomId = "room-uuid";
        Room bestRoom = Room.builder().roomId(roomId).status(RoomStatus.WAITING).build();

        // 빠른 입장 로직상 방을 먼저 찾음
        when(roomRepository.findAllWaitingRooms()).thenReturn(java.util.List.of(bestRoom));

        // [핵심] 락 획득 실패 시뮬레이션
        when(lockFacade.execute(eq(roomId), any(Runnable.class)))
                .thenReturn(LockResult.lockFailed());

        // when
        lobbyService.quickJoin(sessionId);

        // then
        // 락 실패 에러를 보내는지 확인
        verify(responseSender).sendError(sessionId, ErrorCode.ROOM_JOIN_FAILED);

        // 락 내부 로직(방 조회, 저장 등)은 실행되면 안 됨
        verify(roomRepository, never()).findRoomById(anyString());
        verify(roomRepository, never()).saveRoom(any(Room.class));
    }

    @Test
    @DisplayName("방 입장 성공 시 플레이어가 추가되고 브로드캐스트된다")
    void joinRoom_Success() {
        // given
        String sessionId = "guest-session";
        String roomId = "room-uuid";
        String nickname = "게스트";

        Room existingRoom = Room.builder()
                .roomId(roomId)
                .status(RoomStatus.WAITING)
                .build();

        // 1. 빠른 입장으로 방 찾기 모킹
        when(roomRepository.findAllWaitingRooms()).thenReturn(java.util.List.of(existingRoom));

        // 2. [핵심] 락 획득 성공 및 내부 로직 실행 모킹
        // execute 메서드가 호출되면, 파라미터로 넘어온 Runnable(실제 비즈니스 로직)을 실행시켜줘야 함!
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1); // 두 번째 인자인 Runnable 가져오기
            action.run(); // 실제 로직 실행 (방 조회, 플레이어 추가 등)
            return LockResult.success(null);
        }).when(lockFacade).execute(eq(roomId), any(Runnable.class));

        // 3. 락 내부에서 호출될 리포지토리 모킹
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(existingRoom));
        when(nicknameRepository.getNicknameBySessionId(sessionId)).thenReturn(nickname);

        // when
        lobbyService.quickJoin(sessionId);

        // then
        // 저장이 일어났는지 확인 (플레이어 추가됨)
        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository).saveRoom(roomCaptor.capture());

        Room updatedRoom = roomCaptor.getValue();
        assertEquals(1, updatedRoom.getPlayers().size());

        // 성공 메시지 전송 확인
        verify(responseSender).sendJoinSuccess(sessionId, existingRoom);
        verify(responseSender).broadcastLobbyUpdate(existingRoom);
    }
}