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

            if (room.getPlayers().size() >= Room.MAX_PLAYER_COUNT) {
                roomRepository.removeWaitingRoom(roomId);
            }

            responseSender.sendJoinSuccess(sessionId, room);
            responseSender.broadcastLobbyUpdate(roomId, room);

            log.info("방 입장 완료: room={}, player={}", roomId, nickname);

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }
}