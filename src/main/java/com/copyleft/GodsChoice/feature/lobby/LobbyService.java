package com.copyleft.GodsChoice.feature.lobby;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.ConnectionStatus;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.global.util.RandomUtil;
import com.copyleft.GodsChoice.infra.persistence.NicknameRepository;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyService {

    private final RoomRepository roomRepository;
    private final NicknameRepository nicknameRepository;
    private final RedisLockRepository redisLockRepository;

    private final LobbyResponseSender responseSender;


    public void createRoom(String sessionId) {
        String nickname = nicknameRepository.getNicknameBySessionId(sessionId);
        if (nickname == null) {
            responseSender.sendError(sessionId, ErrorCode.INVALID_NICKNAME);
            return;
        }

        String roomId = RandomUtil.generateRoomId();
        String roomCode = RandomUtil.generateRoomCode();
        String roomTitle = nickname + "님의 방";

        Player host = Player.createHost(sessionId, nickname);

        Room room = Room.create(roomId, roomCode, roomTitle, sessionId, host);

        roomRepository.saveRoom(room);
        roomRepository.saveSessionRoomMapping(sessionId, roomId);
        roomRepository.saveRoomCodeMapping(roomCode, roomId);
        roomRepository.addWaitingRoom(roomId);

        responseSender.sendCreateSuccess(sessionId, room);

        log.info("방 생성 완료: id={}, code={}", roomId, roomCode);
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

        String roomId = roomRepository.getRandomWaitingRoomId();

        if (roomId == null) {
            log.info("빠른 입장: 빈 방 없음 -> 새 방 생성");
            createRoom(sessionId);
            return;
        }

        joinRoomInternal(sessionId, roomId);
    }

    private void joinRoomInternal(String sessionId, String roomId) {

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            responseSender.sendError(sessionId, ErrorCode.ROOM_JOIN_FAILED);
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                responseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
                roomRepository.removeWaitingRoom(roomId);
                return;
            }
            Room room = roomOpt.get();

            if (room.getPlayers().size() >= Room.MAX_PLAYER_COUNT) {
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
            Player newPlayer = Player.builder()
                    .sessionId(sessionId)
                    .nickname(nickname)
                    .isHost(false)
                    .connectionStatus(ConnectionStatus.CONNECTED)
                    .build();

            room.addPlayer(newPlayer);
            roomRepository.saveRoom(room);
            roomRepository.saveSessionRoomMapping(sessionId, roomId);

            if (room.getPlayers().size() >= Room.MAX_PLAYER_COUNT) {
                roomRepository.removeWaitingRoom(roomId);
            }

            responseSender.sendJoinSuccess(sessionId, room);
            responseSender.broadcastLobbyUpdate(room);

            log.info("방 입장 완료: room={}, player={}", roomId, nickname);

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void leaveRoom(String sessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) {
            log.warn("방 나가기 실패: 해당 세션({})은 방에 속해있지 않습니다.", sessionId);
            return;
        }

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            responseSender.sendError(sessionId, ErrorCode.ROOM_JOIN_FAILED);
            return;
        }

        try {
            roomRepository.deleteSessionRoomMapping(sessionId);

            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                responseSender.sendLeaveSuccess(sessionId);
                return;
            }
            Room room = roomOpt.get();

            boolean wasHost = room.getPlayers().stream()
                    .filter(p -> p.getSessionId().equals(sessionId))
                    .findFirst()
                    .map(Player::isHost)
                    .orElse(false);

            room.removePlayer(sessionId);
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

            roomRepository.saveRoom(room);
            responseSender.broadcastLobbyUpdate(room);

            log.info("방 퇴장 처리 완료: session={}, room={}", sessionId, roomId);

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }
}