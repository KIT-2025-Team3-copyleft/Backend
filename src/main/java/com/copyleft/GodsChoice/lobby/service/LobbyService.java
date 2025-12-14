package com.copyleft.GodsChoice.lobby.service;

import com.copyleft.GodsChoice.global.config.GameProperties;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.ConnectionStatus;
import com.copyleft.GodsChoice.domain.type.PlayerColor;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.game.service.GameRoomLockFacade;
import com.copyleft.GodsChoice.game.service.LockResult;
import com.copyleft.GodsChoice.game.event.GameUserTimeoutEvent;
import com.copyleft.GodsChoice.game.event.PlayerLeftEvent;
import com.copyleft.GodsChoice.lobby.dto.LobbyPayloads;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.global.util.RandomUtil;
import com.copyleft.GodsChoice.user.repository.NicknameRepository;
import com.copyleft.GodsChoice.game.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyService {

    private final RoomRepository roomRepository;
    private final NicknameRepository nicknameRepository;
    private final GameRoomLockFacade lockFacade;
    private final GameProperties gameProperties;
    private final LobbyResponseSender responseSender;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void handleGameUserTimeout(GameUserTimeoutEvent event) {
        leaveRoom(event.getSessionId());
    }

    public void createRoom(String sessionId) {
        String nickname = nicknameRepository.getNicknameBySessionId(sessionId);
        if (nickname == null) {
            responseSender.sendError(sessionId, ErrorCode.INVALID_NICKNAME);
            return;
        }

        String roomId = RandomUtil.generateRoomId();
        String roomCode;
        try {
            roomCode = generateUniqueRoomCode(roomId);
        } catch (RuntimeException e) {
            log.error("방 생성 실패 (코드 중복): {}", e.getMessage());
            responseSender.sendError(sessionId, ErrorCode.UNKNOWN_ERROR);
            return;
        }
        String roomTitle = nickname + "님의 방";

        Player host = Player.createHost(sessionId, nickname);
        host.setColor(PlayerColor.RED);
        int randomHp = RandomUtil.generateRandomHp(gameProperties.minInitialHp(), gameProperties.maxInitialHp());

        Room room = Room.create(roomId, roomCode, roomTitle, sessionId, host, randomHp);

        room.getCurrentPhaseData().put(sessionId, "HOST");
        roomRepository.saveRoom(room);
        roomRepository.saveSessionRoomMapping(sessionId, roomId);
        roomRepository.addWaitingRoom(roomId);

        responseSender.sendCreateSuccess(sessionId, room);
        responseSender.broadcastLobbyUpdate(room);

        log.info("방 생성 완료: id={}, code={}", roomId, roomCode);
    }

    private String generateUniqueRoomCode(String roomId) {
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            String code = RandomUtil.generateRoomCode();
            if (roomRepository.saveRoomCodeMappingIfAbsent(code, roomId)) {
                return code;
            }
            log.warn("방 코드 충돌 발생, 재생성 시도 ({}/{}): {}", i + 1, maxRetries, code);
        }
        throw new RuntimeException("방 코드 생성 실패: 최대 재시도 횟수 초과");
    }

    public void joinRoomByCode(String sessionId, String roomCode) {

        String roomId = roomRepository.findRoomIdByCode(roomCode);
        if (roomId == null) {
            responseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
            return;
        }

        joinRoomInternal(sessionId, roomId);
    }

    public void quickJoin(String sessionId) {

        List<Room> waitingRooms = roomRepository.findAllWaitingRooms();

        List<Room> availableRooms = waitingRooms.stream()
                .filter(r -> r.getStatus() == RoomStatus.WAITING)
                .filter(r -> r.getPlayers().size() < gameProperties.maxPlayerCount())
                .toList();

        if (availableRooms.isEmpty()) {
            createRoom(sessionId);
            return;
        }

        Map<Integer, List<Room>> roomsByCount = availableRooms.stream()
                .collect(Collectors.groupingBy(r -> r.getPlayers().size()));

        for (int i = gameProperties.maxPlayerCount() - 1; i >= 0; i--) {
            List<Room> candidates = roomsByCount.get(i);

            if (candidates != null && !candidates.isEmpty()) {
                Collections.shuffle(candidates);

                joinRoomInternal(sessionId, candidates.getFirst().getRoomId());
                return;
            }
        }

        createRoom(sessionId);
    }

    public void getRoomList(String sessionId) {
        List<Room> waitingRooms = roomRepository.findAllWaitingRooms();

        List<LobbyPayloads.RoomInfo> roomInfos = waitingRooms.stream()
                .filter(r -> r.getStatus() == RoomStatus.WAITING)
                .map(r -> LobbyPayloads.RoomInfo.builder()
                        .roomId(r.getRoomId())
                        .roomTitle(r.getRoomTitle())
                        .currentCount(r.getPlayers().size())
                        .maxCount(gameProperties.maxPlayerCount())
                        .isPlaying(false)
                        .build())
                .collect(Collectors.toList());

        responseSender.sendRoomList(sessionId, roomInfos);
    }

    private void joinRoomInternal(String sessionId, String roomId) {

        LockResult<Void> result = lockFacade.execute(roomId, () -> {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                responseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
                roomRepository.removeWaitingRoom(roomId);
                return;
            }
            Room room = roomOpt.get();

            if (room.getPlayers().size() >= gameProperties.maxPlayerCount()) {
                responseSender.sendError(sessionId, ErrorCode.ROOM_FULL);
                roomRepository.removeWaitingRoom(roomId);
                return;
            }
            if (room.getStatus() != RoomStatus.WAITING) {
                responseSender.sendError(sessionId, ErrorCode.ROOM_ALREADY_PLAYING);
                roomRepository.removeWaitingRoom(roomId);
                return;
            }

            String nickname = nicknameRepository.getNicknameBySessionId(sessionId);
            PlayerColor assignedColor = room.getNextAvailableColor();
            Player newPlayer = Player.builder()
                    .sessionId(sessionId)
                    .nickname(nickname)
                    .host(false)
                    .connectionStatus(ConnectionStatus.CONNECTED)
                    .color(assignedColor)
                    .build();

            room.addPlayer(newPlayer);
            room.getCurrentPhaseData().put(sessionId, "NEW");
            roomRepository.saveRoom(room);
            roomRepository.saveSessionRoomMapping(sessionId, roomId);

            if (room.getPlayers().size() >= gameProperties.maxPlayerCount()) {
                roomRepository.removeWaitingRoom(roomId);
            }

            responseSender.sendJoinSuccess(sessionId, room);
            responseSender.broadcastLobbyUpdate(room);

            log.info("방 입장 완료: room={}, player={}", roomId, nickname);
        });

        if (result.isLockFailed()) {
            responseSender.sendError(sessionId, ErrorCode.ROOM_JOIN_FAILED);
        }
    }
    
    public void leaveRoom(String sessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) {
            log.info("이미 방에 없는 유저의 퇴장 요청: {}", sessionId);
            responseSender.sendLeaveSuccess(sessionId);
            return;
        }

        LockResult<Void> result = lockFacade.execute(roomId, () -> {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                roomRepository.deleteSessionRoomMapping(sessionId);
                responseSender.sendLeaveSuccess(sessionId);
                return;
            }
            Room room = roomOpt.get();

            boolean wasHost = sessionId.equals(room.getHostSessionId());

            room.getCurrentPhaseData().remove(sessionId);
            room.removePlayer(sessionId);
            roomRepository.deleteSessionRoomMapping(sessionId);
            responseSender.sendLeaveSuccess(sessionId);

            if (room.isEmpty()) {
                roomRepository.deleteRoom(roomId, room.getRoomCode());
                log.info("방 삭제 완료: {}", roomId);
                return;
            }

            if (wasHost) {
                String newHostSessionId = room.delegateHost();
                log.info("방장 위임: 구 방장={} -> 새 방장={}", sessionId, newHostSessionId);
            }

            if (room.getStatus() == RoomStatus.STARTING) {
                room.setStatus(RoomStatus.WAITING);

                responseSender.broadcastTimerCancelled(room);
                log.info("게임 시작 카운트다운 중단: {}", roomId);
            }

            if (room.getStatus() == RoomStatus.WAITING) {
                roomRepository.addWaitingRoom(roomId);
            }

            roomRepository.saveRoom(room);
            responseSender.broadcastLobbyUpdate(room);

            eventPublisher.publishEvent(new PlayerLeftEvent(roomId, sessionId));
            log.info("방 퇴장 처리 완료: session={}, room={}", sessionId, roomId);

        });

        if (result.isLockFailed()) {
            responseSender.sendError(sessionId, ErrorCode.ROOM_LEAVE_FAILED);
        }
    }
}