package com.copyleft.GodsChoice.feature.lobby;

import com.copyleft.GodsChoice.global.config.GameProperties;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.game.service.GameRoomLockFacade; // 추가
import com.copyleft.GodsChoice.game.service.LockResult;       // 추가
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.user.repository.NicknameRepository;
import com.copyleft.GodsChoice.game.repository.RoomRepository;
import com.copyleft.GodsChoice.lobby.service.LobbyResponseSender;
import com.copyleft.GodsChoice.lobby.service.LobbyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LobbyServiceTest {

    @InjectMocks
    private LobbyService lobbyService;

    @Mock private RoomRepository roomRepository;
    @Mock private NicknameRepository nicknameRepository;
    @Mock private GameProperties gameProperties;
    @Mock private ApplicationEventPublisher eventPublisher;
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
        // Given
        String sessionId = "session-123";
        String roomCode = "CODE12";
        String roomId = "room-uuid";

        // [핵심] 방 코드로 ID를 찾을 수 있어야 락 로직으로 진입합니다.
        when(roomRepository.findRoomIdByCode(roomCode)).thenReturn(roomId);

        // 락 획득 실패 설정
        // (이 줄이 "불필요하다"고 에러가 났던 건데, 위 설정을 추가하면 이 줄이 실행되므로 에러가 사라집니다.)
        when(lockFacade.execute(eq(roomId), any(Runnable.class)))
                .thenReturn(LockResult.lockFailed());

        // When
        lobbyService.joinRoomByCode(sessionId, roomCode);

        // Then
        verify(responseSender).sendError(sessionId, ErrorCode.ROOM_JOIN_FAILED);
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

        when(gameProperties.maxPlayerCount()).thenReturn(4);
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

    @Test
    void joinRoomByCode_LockFailed() {
        // Given
        String sessionId = "session-123";
        String roomCode = "CODE12";
        String roomId = "room-uuid";

        // 1. 방 코드 조회 성공 설정
        when(roomRepository.findRoomIdByCode(roomCode)).thenReturn(roomId);

        // 2. 락 실패 설정
        when(lockFacade.execute(eq(roomId), any(Runnable.class)))
                .thenReturn(LockResult.lockFailed());

        // When
        lobbyService.joinRoomByCode(sessionId, roomCode);

        // Then
        verify(responseSender).sendError(sessionId, ErrorCode.ROOM_JOIN_FAILED);
    }

    @Test
    void quickJoin_LockFailed() {
        // Given
        String sessionId = "session-123";
        String roomId = "room-uuid";

        // [핵심 1] 최대 인원수를 설정해야 반복문이 돕니다.
        when(gameProperties.maxPlayerCount()).thenReturn(4);

        // [핵심 2] 입장 가능한 방이 있어야 방 생성으로 빠지지 않고 락 로직을 탑니다.
        Room room = Room.builder()
                .roomId(roomId)
                .status(RoomStatus.WAITING)
                .players(new ArrayList<>()) // 인원 0명
                .build();
        when(roomRepository.findAllWaitingRooms()).thenReturn(List.of(room));

        // 락 획득 실패 설정
        when(lockFacade.execute(eq(roomId), any(Runnable.class)))
                .thenReturn(LockResult.lockFailed());

        // When
        lobbyService.quickJoin(sessionId);

        // Then
        verify(responseSender).sendError(sessionId, ErrorCode.ROOM_JOIN_FAILED);
    }
}