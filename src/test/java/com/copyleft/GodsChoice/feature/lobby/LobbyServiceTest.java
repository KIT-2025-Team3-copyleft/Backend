package com.copyleft.GodsChoice.feature.lobby;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.persistence.NicknameRepository;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
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
    @Mock private RedisLockRepository redisLockRepository;
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
        when(roomRepository.getRandomWaitingRoomId()).thenReturn(null); // 빈 방 없음
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

        when(roomRepository.getRandomWaitingRoomId()).thenReturn(roomId);
        when(redisLockRepository.lock(roomId)).thenReturn(null);

        // when
        lobbyService.quickJoin(sessionId);

        // then
        verify(responseSender).sendError(sessionId, ErrorCode.ROOM_JOIN_FAILED);
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
        String mockToken = "lock-token-1234";

        Room existingRoom = Room.builder()
                .roomId(roomId)
                .status(RoomStatus.WAITING)
                .build();

        when(roomRepository.getRandomWaitingRoomId()).thenReturn(roomId);
        when(redisLockRepository.lock(roomId)).thenReturn(mockToken);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(existingRoom));
        when(nicknameRepository.getNicknameBySessionId(sessionId)).thenReturn(nickname);

        // when
        lobbyService.quickJoin(sessionId);

        // then
        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository).saveRoom(roomCaptor.capture());

        Room updatedRoom = roomCaptor.getValue();
        assertEquals(1, updatedRoom.getPlayers().size());
        //  Room.builder()는 players를 빈 리스트로 초기화하므로, addPlayer 후 size는 1이 됩니다.)

        verify(responseSender).sendJoinSuccess(sessionId, existingRoom);
        verify(responseSender).broadcastLobbyUpdate(roomId, existingRoom);

        verify(redisLockRepository).unlock(eq(roomId), anyString());
    }
}