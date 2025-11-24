package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.*;
import com.copyleft.GodsChoice.feature.lobby.LobbyResponseSender;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.external.GroqApiClient;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private final RoomRepository roomRepository;
    private final RedisLockRepository redisLockRepository;
    private final GameResponseSender gameResponseSender;
    private final LobbyResponseSender lobbyResponseSender;
    private final TaskScheduler taskScheduler;
    private final GroqApiClient groqApiClient;
    private final ObjectMapper objectMapper;
    private final GameLogService gameLogService;


    private static final int GAME_START_DELAY_SECONDS = 3;
    private static final int CARD_SELECT_DURATION_SECONDS = 120;
    private static final int VOTE_PROPOSAL_SECONDS = 15;
    private static final int TRIAL_DURATION_SECONDS = 60;


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

                startRoundInternal(roomId);

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
            startRoundInternal(roomId);
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    private void startRoundInternal(String roomId) {

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
            gameResponseSender.sendCards(player.getSessionId(), assignedSlot.name(), cards);
        }

        room.setCurrentPhase(GamePhase.CARD_SELECT);

        roomRepository.saveRoom(room);
        gameResponseSender.broadcastRoundStart(room);

        taskScheduler.schedule(
                () -> processCardTimeout(roomId),
                Instant.now().plusSeconds(CARD_SELECT_DURATION_SECONDS)
        );

        log.info("라운드 시작 완료: room={}", roomId);
    }

    public void processCardTimeout(String roomId) {
        boolean shouldJudge = false;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.warn("타임아웃 처리 락 획득 실패 (1초 후 재시도): {}", roomId);
            taskScheduler.schedule(
                    () -> processCardTimeout(roomId),
                    Instant.now().plusSeconds(1)
            );
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() != GamePhase.CARD_SELECT) {
                return;
            }

            log.info("카드 선택 시간 초과! 미선택자 강제 선택 진행: room={}", roomId);

            boolean changed = false;
            for (Player p : room.getPlayers()) {
                if (p.getSelectedCard() == null) {
                    List<String> randomCards = WordData.getRandomCards(p.getSlot(), 1);
                    if (!randomCards.isEmpty()) {
                        p.setSelectedCard(randomCards.get(0));
                        changed = true;
                        log.info("강제 선택: player={}, card={}", p.getNickname(), p.getSelectedCard());
                    }
                }
            }

            if (changed) {
                roomRepository.saveRoom(room);
            }

            gameResponseSender.broadcastAllCardsSelected(room);
            shouldJudge = true;

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }

        if (shouldJudge) {
            judgeRound(roomId);
        }
    }


    public void selectCard(String sessionId, String cardContent) {

        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        boolean shouldJudge = false;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() != GamePhase.CARD_SELECT) {
                return;
            }

            boolean isUpdated = false;
            for (Player p : room.getPlayers()) {
                if (p.getSessionId().equals(sessionId)) {
                    p.setSelectedCard(cardContent);
                    isUpdated = true;
                    break;
                }
            }
            if (!isUpdated) return;

            roomRepository.saveRoom(room);

            log.info("카드 선택 완료: session={}, card={}", sessionId, cardContent);

            boolean allSelected = room.getPlayers().stream()
                    .allMatch(p -> p.getSelectedCard() != null);

            if (allSelected) {
                log.info("전원 카드 선택 완료! AI 심판을 시작합니다. room={}", roomId);
                gameResponseSender.broadcastAllCardsSelected(room);
                shouldJudge = true;
            }

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }

        if (shouldJudge) {
            judgeRound(roomId);
        }
    }


    public void judgeRound(String roomId) {
        taskScheduler.schedule(() -> judgeRoundInternal(roomId), Instant.now());
    }

    private void judgeRoundInternal(String roomId) {

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.warn("심판 처리 락 획득 실패 (1초 후 재시도): {}", roomId);
            taskScheduler.schedule(
                    () -> judgeRoundInternal(roomId),
                    Instant.now().plusSeconds(1)
            );
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() == GamePhase.JUDGING) return;
            room.setCurrentPhase(GamePhase.JUDGING);
            roomRepository.saveRoom(room);

            String sentence = constructSentence(room);
            String personality = room.getGodPersonality();
            if (personality == null) personality = "변덕스러운";

            log.info("AI 심판 시작 (비동기): room={}, 문장='{}'", roomId, sentence);

            int score = 0;
            String reason = "신이 침묵합니다.";
            String jsonResult = null;
            try {
                jsonResult = groqApiClient.judgeSentence(sentence, personality);
            } catch (Exception e) {
                log.error("AI API 호출 중 예외 발생 (기본값 적용): room={}", roomId, e);
            }

            if (jsonResult != null) {
                try {
                    JsonNode root = objectMapper.readTree(jsonResult);
                    score = root.path("score").asInt();
                    reason = root.path("reason").asText();
                } catch (Exception e) {
                    log.error("AI 응답 파싱 실패 (기본값 적용): json={}", jsonResult, e);
                }
            }

            int currentHp = room.getCurrentHp();
            room.setCurrentHp(currentHp + score);

            roomRepository.saveRoom(room);
            gameResponseSender.broadcastRoundResult(room, score, reason, sentence);

            log.info("심판 완료: score={}", score);

            taskScheduler.schedule(
                    () -> startVoteProposal(roomId),
                    Instant.now().plusSeconds(5)
            );

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    private String constructSentence(Room room) {
        StringBuilder sb = new StringBuilder();
        List<SlotType> order = java.util.List.of(SlotType.SUBJECT, SlotType.TARGET, SlotType.HOW, SlotType.ACTION);

        for (SlotType type : order) {
            room.getPlayers().stream()
                    .filter(p -> p.getSlot() == type)
                    .findFirst()
                    .ifPresent(p -> sb.append(p.getSelectedCard()).append(" "));
        }
        return sb.toString().trim();
    }

    public void startVoteProposal(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            room.setCurrentPhase(GamePhase.VOTE_PROPOSAL);
            room.clearVotes();

            roomRepository.saveRoom(room);

            gameResponseSender.broadcastVoteProposalStart(room);

            taskScheduler.schedule(
                    () -> processVoteProposalEnd(roomId),
                    Instant.now().plusSeconds(VOTE_PROPOSAL_SECONDS)
            );

            log.info("찬반 투표 시작: room={}", roomId);

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }


    public void voteProposal(String sessionId, boolean agree) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() != GamePhase.VOTE_PROPOSAL) return;

            room.getProposalVotes().put(sessionId, agree);
            roomRepository.saveRoom(room);

            log.info("투표 접수: session={}, agree={}", sessionId, agree);

            if (room.getProposalVotes().size() >= room.getPlayers().size()) {
                log.info("전원 투표 완료! 즉시 집계 진행: room={}", roomId);
                taskScheduler.schedule(
                        () -> processVoteProposalEnd(roomId),
                        Instant.now()
                );
            }

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }


    public void processVoteProposalEnd(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() != GamePhase.VOTE_PROPOSAL) return;

            long agreeCount = room.getProposalVotes().values().stream()
                    .filter(Boolean::booleanValue)
                    .count();

            log.info("찬반 투표 집계: room={}, agree={}", roomId, agreeCount);

            if (agreeCount >= 2) {
                log.info("투표 가결! 이단 심문으로 넘어갑니다.");
                startTrialInternal(room);
            } else {
                gameResponseSender.broadcastVoteProposalFailed(room);
                taskScheduler.schedule(
                        () -> startNextRound(roomId),
                        Instant.now().plusSeconds(3) // 3초 뒤 이동
                );
                log.info("투표 부결! 다음 라운드로 넘어갑니다.");
            }

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }


    private void startTrialInternal(Room room) {

        int penalty = 50;
        room.setCurrentHp(room.getCurrentHp() - penalty);

        room.setCurrentPhase(GamePhase.TRIAL_VOTE);
        room.clearVotes();

        roomRepository.saveRoom(room);

        gameResponseSender.broadcastTrialStart(room);

        taskScheduler.schedule(
                () -> processTrialEnd(room.getRoomId()),
                Instant.now().plusSeconds(TRIAL_DURATION_SECONDS)
        );

        log.info("이단 심문 시작 (HP -{}): room={}", penalty, room.getRoomId());
    }

    public void castVote(String sessionId, String targetSessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() != GamePhase.TRIAL_VOTE) return;

            room.getTrialVotes().put(sessionId, targetSessionId);
            roomRepository.saveRoom(room);

            log.info("지목 투표: {} -> {}", sessionId, targetSessionId);

            if (room.getTrialVotes().size() >= room.getPlayers().size()) {
                log.info("전원 지목 완료! 즉시 집계 진행: room={}", roomId);
                taskScheduler.schedule(
                        () -> processTrialEnd(roomId),
                        Instant.now()
                );
            }

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }


    public void processTrialEnd(String roomId) {

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() != GamePhase.TRIAL_VOTE) return;

            String targetSessionId = calculateMostVoted(room);

            boolean success = false;
            String targetNickname = "없음";
            String targetRole = "UNKNOWN";

            if (targetSessionId != null) {
                Player target = room.getPlayers().stream()
                        .filter(p -> p.getSessionId().equals(targetSessionId))
                        .findFirst()
                        .orElse(null);

                if (target != null) {
                    targetNickname = target.getNickname();
                    targetRole = target.getRole(); // CITIZEN or TRAITOR

                    if (com.copyleft.GodsChoice.domain.type.PlayerRole.TRAITOR.name().equals(targetRole)) {
                        success = true;
                        room.setCurrentHp(room.getCurrentHp() + 100);
                        room.setVotingDisabled(true);
                    }
                    else {
                        success = false;
                        room.setCurrentHp(room.getCurrentHp() - 100);
                    }
                }
            } else {
                targetNickname = "기권";
            }

            roomRepository.saveRoom(room);
            gameResponseSender.broadcastTrialResult(room, success, targetNickname, targetRole);

            log.info("심문 결과: target={}, success={}, hp={}", targetNickname, success, room.getCurrentHp());

            taskScheduler.schedule(
                    () -> startNextRound(roomId),
                    Instant.now().plusSeconds(5)
            );

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    private String calculateMostVoted(Room room) {
        Map<String, Integer> voteCounts = new java.util.HashMap<>();
        for (String target : room.getTrialVotes().values()) {
            voteCounts.put(target, voteCounts.getOrDefault(target, 0) + 1);
        }

        return voteCounts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }

    public void startNextRound(String roomId) {

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentRound() >= 4) {
                processGameOver(roomId);
                log.info("4라운드 종료! 게임 오버 처리 예정: {}", roomId);
                return;
            }

            room.setCurrentRound(room.getCurrentRound() + 1);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastNextRound(room);

            log.info("다음 라운드 진입: {}라운드", room.getCurrentRound());

            taskScheduler.schedule(
                    () -> startRound(roomId),
                    Instant.now().plusSeconds(3)
            );

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void processGameOver(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            String winnerRole;

            if (room.isVotingDisabled()) {
                winnerRole = PlayerRole.CITIZEN.name();
            }
            else if (room.getCurrentHp() <= 0) {
                winnerRole = PlayerRole.TRAITOR.name();
            }
            else {
                winnerRole = PlayerRole.TRAITOR.name();
            }

            room.setStatus(RoomStatus.GAME_OVER);
            roomRepository.saveRoom(room);

            log.info("게임 종료 판정: room={}, winner={}", roomId, winnerRole);

            gameResponseSender.broadcastGameOver(room, winnerRole);

            gameLogService.saveGameLogAsync(room, winnerRole);

            taskScheduler.schedule(() -> {
                cleanupGameOverRoom(roomId);
            }, Instant.now().plusSeconds(60));

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
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getStatus() != RoomStatus.GAME_OVER && room.getStatus() != RoomStatus.WAITING) {
                return;
            }

            if (room.getStatus() == RoomStatus.GAME_OVER) {
                room.resetForNewGame();
                roomRepository.addWaitingRoom(roomId);
                roomRepository.saveRoom(room);

                log.info("방 초기화 및 대기 상태 전환: room={}", roomId);
            }

            lobbyResponseSender.broadcastLobbyUpdate(room);

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void cleanupGameOverRoom(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getStatus() == RoomStatus.GAME_OVER) {
                roomRepository.deleteRoom(roomId, room.getRoomCode());
                log.info("타임아웃된 방 강제 청소 완료: {}", roomId);
            } else {
                log.info("청소 취소 (이미 재시작됨): status={}", room.getStatus());
            }

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }
}