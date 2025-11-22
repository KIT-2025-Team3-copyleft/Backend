package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.domain.type.SlotType;
import com.copyleft.GodsChoice.domain.type.WordData;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private final RoomRepository roomRepository;
    private final RedisLockRepository redisLockRepository;
    private final GameResponseSender gameResponseSender;
    private final TaskScheduler taskScheduler;

    private static final int GAME_START_DELAY_SECONDS = 3;


    public void tryStartGame(String sessionId) {

        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) {
            log.warn("게임 시작 실패 (세션-방 매핑 없음): session={}", sessionId);
            gameResponseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
            return;
        }

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.warn("게임 시작 락 획득 실패 (tryStartGame): room={}, session={}", roomId, sessionId);
            gameResponseSender.sendError(sessionId, ErrorCode.GAME_START_FAILED);
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

            taskScheduler.schedule(
                    () -> processGameStart(roomId),
                    Instant.now().plusSeconds(GAME_START_DELAY_SECONDS)
            );

            log.info("게임 시작 카운트다운 시작: room={}", roomId);

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void processGameStart(String roomId) {

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.error("게임 시작 처리 락 획득 실패. (방 상태가 STARTING으로 남을 수 있음): {}", roomId);
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
                roomRepository.addWaitingRoom(roomId);
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

                startRound(roomId);

            } catch (Exception e) {
                log.error("게임 시작 처리 중 저장/전송 오류 (롤백 시도): room={}", roomId, e);

                room.setStatus(RoomStatus.WAITING);
                roomRepository.saveRoom(room);
                roomRepository.addWaitingRoom(roomId);
                gameResponseSender.broadcastGameStartCancelled(room);
            }

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void startRound(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            List<SlotType> slots = new ArrayList<>(List.of(
                    SlotType.SUBJECT, SlotType.TARGET, SlotType.HOW, SlotType.ACTION
            ));
            Collections.shuffle(slots);

            List<Player> players = room.getPlayers();
            for (int i = 0; i < players.size(); i++) {
                Player player = players.get(i);
                SlotType assignedSlot = slots.get(i % slots.size());

                player.setSlot(assignedSlot);
                player.setSelectedCard(null);

                List<String> cards = WordData.getRandomCards(assignedSlot, 5);

                gameResponseSender.sendCards(player.getSessionId(), assignedSlot.name(), cards);;
            }

            room.setCurrentPhase(GamePhase.CARD_SELECT);

            roomRepository.saveRoom(room);
            gameResponseSender.broadcastRoundStart(room);

            log.info("라운드 시작 및 카드 배분 완료: room={}", roomId);

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }
}