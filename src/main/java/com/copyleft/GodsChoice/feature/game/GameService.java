package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private final RoomRepository roomRepository;
    private final RedisLockRepository redisLockRepository;
    private final GameResponseSender gameResponseSender;
    private final TaskScheduler taskScheduler;


    public void tryStartGame(String sessionId) {

        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) {
            log.warn("게임 시작 실패 (세션-방 매핑 없음): session={}", sessionId);
            gameResponseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
            return;
        }

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            gameResponseSender.sendError(sessionId, ErrorCode.UNKNOWN_ERROR);
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                gameResponseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
                return;
            }
            Room room = roomOpt.get();

            // 방장인가?
            if (!sessionId.equals(room.getHostSessionId())) {
                gameResponseSender.sendError(sessionId, ErrorCode.NOT_HOST);
                return;
            }

            // 4명인가?
            if (room.getPlayers().size() < Room.MAX_PLAYER_COUNT) {
                gameResponseSender.sendError(sessionId, ErrorCode.NOT_ENOUGH_PLAYERS);
                return;
            }

            // 대기 상태인가?
            if (room.getStatus() != RoomStatus.WAITING) {
                gameResponseSender.sendError(sessionId, ErrorCode.ROOM_ALREADY_PLAYING);
                return;
            }

            room.setStatus(RoomStatus.STARTING);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastGameStartTimer(room);

            taskScheduler.schedule(() -> processGameStart(roomId), Instant.now().plusSeconds(3));

            log.info("게임 시작 카운트다운 시작: room={}", roomId);

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void processGameStart(String roomId) {

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.error("게임 시작 처리 락 획득 실패 (재시도 필요): {}", roomId);
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                log.warn("게임 시작 처리 중 방을 찾을 수 없음: {}", roomId);
                return;
            }
            Room room = roomOpt.get();

            if (room.getStatus() != RoomStatus.STARTING) {
                log.info("게임 시작 취소됨 (상태 불일치): {}", room.getStatus());
                return;
            }

            if (room.getPlayers().size() < Room.MAX_PLAYER_COUNT) {
                log.warn("게임 시작 실패 (인원 부족): {}", roomId);
                room.setStatus(RoomStatus.WAITING);
                roomRepository.saveRoom(room);
                gameResponseSender.broadcastGameStartCancelled(room);
                return;
            }


            try {
                room.setStatus(RoomStatus.PLAYING);
                room.assignRoles();

                roomRepository.saveRoom(room);
                roomRepository.removeWaitingRoom(roomId);
                gameResponseSender.broadcastLoadGameScene(room);

                log.info("게임 정식 시작 (Scene 이동): room={}", roomId);
            } catch (Exception e) {
                log.error("게임 시작 처리 중 저장/전송 오류: room={}", roomId, e);
            }

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }
}