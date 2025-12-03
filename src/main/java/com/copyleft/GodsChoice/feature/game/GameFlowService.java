package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.*;
import com.copyleft.GodsChoice.feature.lobby.LobbyResponseSender;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameFlowService {

    private final RoomRepository roomRepository;
    private final RedisLockRepository redisLockRepository;
    private final GameResponseSender gameResponseSender;
    private final LobbyResponseSender lobbyResponseSender;
    private final TaskScheduler taskScheduler;
    private final GameLogService gameLogService;

    // 순환 참조 방지를 위해 Lazy 주입
    @Lazy
    private final GameJudgeService gameJudgeService;

    private static final int GAME_START_DELAY_SECONDS = 3;
    private static final int LOADING_TIMEOUT_SECONDS = 5;
    private static final int ORACLE_PHASE_SECONDS = 8;
    private static final int CARD_COUNT = 4;
    private static final int CARD_SEND_DELAY_SECONDS = 3;
    private static final int CARD_SELECT_DURATION_SECONDS = 120;
    private static final int VOTE_PROPOSAL_SECONDS = 15;
    private static final int TRIAL_DURATION_SECONDS = 60;
    private static final int TRIAL_START_PENALTY = 50;

    // 게임 시작 관련

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
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (!validateGameStart(room, sessionId)) return;

            room.setStatus(RoomStatus.STARTING);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastGameStartTimer(room);

            taskScheduler.schedule(
                    () -> processGameStart(roomId),
                    Instant.now().plusSeconds(GAME_START_DELAY_SECONDS)
            );
            log.info("게임 시작 카운트다운: room={}", roomId);
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    private boolean validateGameStart(Room room, String sessionId) {
        if (room == null) {
            gameResponseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
            return false;
        }
        if (!sessionId.equals(room.getHostSessionId())) {
            gameResponseSender.sendError(sessionId, ErrorCode.NOT_HOST);
            return false;
        }
        if (room.getPlayers().size() < Room.MAX_PLAYER_COUNT) {
            gameResponseSender.sendError(sessionId, ErrorCode.NOT_ENOUGH_PLAYERS);
            return false;
        }
        if (room.getStatus() != RoomStatus.WAITING) {
            gameResponseSender.sendError(sessionId, ErrorCode.ROOM_ALREADY_PLAYING);
            return false;
        }
        return true;
    }

    public void processGameStart(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.error("게임 시작 처리 락 획득 실패. (방 상태가 STARTING으로 남을 수 있음): {}", roomId);
            return;
        }

        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getStatus() != RoomStatus.STARTING) return;

            if (room.getPlayers().size() < Room.MAX_PLAYER_COUNT) {
                log.warn("게임 시작 실패 (인원 부족): {}", roomId);
                cancelGameStart(room);
                return;
            }

            room.setStatus(RoomStatus.PLAYING);
            room.clearPhaseData();
            assignRolesAndScenario(room);

            roomRepository.saveRoom(room);
            roomRepository.removeWaitingRoom(roomId);
            gameResponseSender.broadcastLoadGameScene(room);
            log.info("게임 정식 시작 (Scene 이동): room={}", roomId);

            taskScheduler.schedule(
                    () -> startOraclePhase(roomId),
                    Instant.now().plusSeconds(LOADING_TIMEOUT_SECONDS)
            );
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    private void cancelGameStart(Room room) {
        room.setStatus(RoomStatus.WAITING);
        roomRepository.saveRoom(room);
        roomRepository.addWaitingRoom(room.getRoomId());
        gameResponseSender.broadcastGameStartCancelled(room);
    }

    private void assignRolesAndScenario(Room room) {
        List<Player> players = room.getPlayers();
        Collections.shuffle(players);
        for (int i = 0; i < players.size(); i++) {
            players.get(i).setRole(i == 0 ? PlayerRole.TRAITOR : PlayerRole.CITIZEN);
        }

        room.setOracle(Oracle.values()[new Random().nextInt(Oracle.values().length)]);
        room.setGodPersonality(GodPersonality.values()[new Random().nextInt(GodPersonality.values().length)]);
    }

    // 라운드 진행 흐름

    public void startOraclePhase(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() == GamePhase.ORACLE) return;

            room.setCurrentPhase(GamePhase.ORACLE);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastRoundStart(room);
            gameResponseSender.broadcastOracle(room);
            room.getPlayers().forEach(p ->
                    gameResponseSender.sendRole(p, p.getRole() == PlayerRole.TRAITOR ? room.getGodPersonality() : null)
            );

            taskScheduler.schedule(() -> startRound(roomId), Instant.now().plusSeconds(ORACLE_PHASE_SECONDS));
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void startRound(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            List<SlotType> slots = new ArrayList<>(List.of(SlotType.SUBJECT, SlotType.TARGET, SlotType.HOW, SlotType.ACTION));
            Collections.shuffle(slots);
            for (int i = 0; i < room.getPlayers().size(); i++) {
                Player p = room.getPlayers().get(i);
                p.setSlot(slots.get(i % slots.size()));
                p.setSelectedCard(null);
            }

            room.setCurrentPhase(GamePhase.CARD_SELECT);
            roomRepository.saveRoom(room);

            // 카드 전송 예약
            taskScheduler.schedule(() -> sendCardsDelayed(roomId), Instant.now().plusSeconds(CARD_SEND_DELAY_SECONDS));

            // 타임아웃 예약
            taskScheduler.schedule(() -> gameJudgeService.processCardTimeout(roomId),
                    Instant.now().plusSeconds(CARD_SEND_DELAY_SECONDS + CARD_SELECT_DURATION_SECONDS));

            log.info("라운드 시작: room={}", roomId);
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void sendCardsDelayed(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;
        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getCurrentPhase() == GamePhase.CARD_SELECT) {
                room.getPlayers().forEach(p -> {
                    if (p.getSlot() != null) {
                        gameResponseSender.sendCards(p.getSessionId(), p.getSlot().name(), WordData.getRandomCards(p.getSlot(), CARD_COUNT));
                    }
                });
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void startVoteProposal(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            room.setCurrentPhase(GamePhase.VOTE_PROPOSAL);
            room.clearPhaseData();
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastVoteProposalStart(room);

            taskScheduler.schedule(() -> gameJudgeService.processVoteProposalEnd(roomId), Instant.now().plusSeconds(VOTE_PROPOSAL_SECONDS));
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void startTrialInternal(Room room) {
        room.setCurrentHp(room.getCurrentHp() - TRIAL_START_PENALTY);
        room.setCurrentPhase(GamePhase.TRIAL_VOTE);
        room.clearPhaseData();
        roomRepository.saveRoom(room);

        gameResponseSender.broadcastTrialStart(room);

        taskScheduler.schedule(() -> gameJudgeService.processTrialEnd(room.getRoomId()), Instant.now().plusSeconds(TRIAL_DURATION_SECONDS));
    }

    public void startNextRound(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            if (room.getCurrentRound() >= 4) {
                processGameOver(roomId);
            } else {
                room.setCurrentPhase(null);
                room.clearPhaseData();
                room.setCurrentRound(room.getCurrentRound() + 1);
                room.setOracle(Oracle.values()[new Random().nextInt(Oracle.values().length)]);

                roomRepository.saveRoom(room);
                gameResponseSender.broadcastNextRound(room);

                taskScheduler.schedule(() -> startOraclePhase(roomId), Instant.now().plusSeconds(ORACLE_PHASE_SECONDS));
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    // 게임 종료 및 기타

    public void processGameOver(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;
        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            PlayerRole winnerRole = (room.getCurrentHp() <= 0 || !room.isVotingDisabled()) ? PlayerRole.TRAITOR : PlayerRole.CITIZEN;

            room.setStatus(RoomStatus.GAME_OVER);
            roomRepository.saveRoom(room);
            gameResponseSender.broadcastGameOver(room, winnerRole);
            gameLogService.saveGameLogAsync(room, winnerRole.name());

            taskScheduler.schedule(() -> cleanupGameOverRoom(roomId), Instant.now().plusSeconds(60));
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void cleanupGameOverRoom(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;
        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getStatus() == RoomStatus.GAME_OVER) {
                roomRepository.deleteRoom(roomId, room.getRoomCode());
                log.info("타임아웃된 방 강제 청소 완료: {}", roomId);
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void backToRoom(String sessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;
        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getStatus() == RoomStatus.GAME_OVER) {
                room.resetForNewGame();
                roomRepository.addWaitingRoom(roomId);
                roomRepository.saveRoom(room);
                lobbyResponseSender.broadcastLobbyUpdate(room);
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }
}