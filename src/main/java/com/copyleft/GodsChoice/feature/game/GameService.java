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
    private static final int LOADING_TIMEOUT_SECONDS = 15;
    private static final int ORACLE_PHASE_SECONDS = 8;
    private static final int CARD_SEND_DELAY_SECONDS = 3;
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


    public void processGameReady(String sessionId) {

        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getStatus() != RoomStatus.PLAYING || room.getCurrentPhase() != null) return;

            room.getCurrentPhaseData().put(sessionId, "READY");
            roomRepository.saveRoom(room);

            log.info("플레이어 로딩 완료: session={}, count={}/{}", sessionId, room.getActionCount(), room.getPlayers().size());

            if (room.getActionCount() >= room.getPlayers().size()) {
                log.info("전원 로딩 완료! 즉시 라운드 시작: room={}", roomId);
                room.clearPhaseData();
                roomRepository.saveRoom(room);

                startOraclePhase(roomId);
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    private void assignRolesAndScenario(Room room) {
        List<Player> players = room.getPlayers();
        Collections.shuffle(players);

        for (int i = 0; i < players.size(); i++) {
            players.get(i).setRole(i == 0 ? PlayerRole.TRAITOR : PlayerRole.CITIZEN);
        }

        Random random = new Random();
        Oracle[] oracles = Oracle.values();
        GodPersonality[] personalities = GodPersonality.values();

        room.setOracle(oracles[random.nextInt(oracles.length)]);
        room.setGodPersonality(personalities[random.nextInt(personalities.length)]);
    }

    public void startOraclePhase(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() != null) {
                log.warn("ORACLE 단계 진입 시점에 이미 다른 phase 진행 중 (중복 호출 무시): room={}, phase={}", roomId, room.getCurrentPhase());
                return;
            }

            room.setCurrentPhase(GamePhase.ORACLE);
            roomRepository.saveRoom(room);

            // 신탁 방송
            gameResponseSender.broadcastOracle(room);

            // 개인 역할 전송
            for (Player p : room.getPlayers()) {
                GodPersonality personality = null;
                if (p.getRole() == PlayerRole.TRAITOR) {
                    personality = room.getGodPersonality();
                }
                gameResponseSender.sendRole(p, personality);
            }

            log.info("오프닝(신탁) 시작: room={}, oracle={}", roomId, room.getOracle());

            taskScheduler.schedule(
                    () -> startRound(roomId),
                    Instant.now().plusSeconds(ORACLE_PHASE_SECONDS)
            );

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
        }

        room.setCurrentPhase(GamePhase.CARD_SELECT);

        roomRepository.saveRoom(room);
        gameResponseSender.broadcastRoundStart(room);

        taskScheduler.schedule(
                () -> sendCardsDelayed(roomId),
                Instant.now().plusSeconds(CARD_SEND_DELAY_SECONDS)
        );

        taskScheduler.schedule(
                () -> processCardTimeout(roomId),
                Instant.now().plusSeconds(CARD_SEND_DELAY_SECONDS + CARD_SELECT_DURATION_SECONDS)
        );

        log.info("라운드 시작 완료: room={}", roomId);
    }


    public void sendCardsDelayed(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() != GamePhase.CARD_SELECT) return;

            for (Player player : room.getPlayers()) {
                if (player.getSlot() != null) {
                    List<String> cards = WordData.getRandomCards(player.getSlot(), 5);
                    gameResponseSender.sendCards(player.getSessionId(), player.getSlot().name(), cards);
                }
            }
            log.info("카드 전송 완료: room={}", roomId);

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
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
        String sentence = null;
        String personality = null;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.warn("심판 처리 락 획득 실패 (1초 후 재시도): {}", roomId);
            taskScheduler.schedule(() -> judgeRoundInternal(roomId), Instant.now().plusSeconds(1));
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() == GamePhase.JUDGING) return;

            room.setCurrentPhase(GamePhase.JUDGING);
            roomRepository.saveRoom(room);

            sentence = constructSentence(room);
            GodPersonality godPersonality = room.getGodPersonality();
            personality = (godPersonality != null) ? godPersonality.getDescription() : "변덕스러운";

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }


        log.info("AI 심판 시작 (비동기, Lock 해제됨): room={}, 문장='{}'", roomId, sentence);

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


        applyJudgmentResult(roomId, score, reason, sentence);
    }

    private void applyJudgmentResult(String roomId, int score, String reason, String sentence) {

        String lockToken = redisLockRepository.lock(roomId);

        if (lockToken == null) {
            log.warn("심판 결과 반영 락 획득 실패 (0.5초 후 재시도): {}", roomId);
            final int finalScore = score;
            final String finalReason = reason;
            final String finalSentence = sentence;
            taskScheduler.schedule(
                    () -> applyJudgmentResult(roomId, finalScore, finalReason, finalSentence),
                    Instant.now().plusMillis(500)
            );
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                log.warn("심판 결과 반영 중 방이 사라짐: {}", roomId);
                return;
            }
            Room room = roomOpt.get();

            if (room.getStatus() == RoomStatus.GAME_OVER) {
                log.info("게임이 이미 종료되어 심판 결과 무시: {}", roomId);
                return;
            }

            int currentHp = room.getCurrentHp();
            room.setCurrentHp(currentHp + score);

            roomRepository.saveRoom(room);
            gameResponseSender.broadcastRoundResult(room, score, reason, sentence);

            log.info("심판 완료 및 반영: score={}, hp={}", score, room.getCurrentHp());

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
            room.clearPhaseData();

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

            room.getCurrentPhaseData().put(sessionId, String.valueOf(agree));
            roomRepository.saveRoom(room);

            log.info("투표 접수: session={}, agree={}", sessionId, agree);

            if (room.getActionCount() >= room.getPlayers().size()) {
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

            long agreeCount = room.getCurrentPhaseData().values().stream()
                    .filter("true"::equalsIgnoreCase)
                    .count();

            log.info("찬반 투표 집계: room={}, agree={}", roomId, agreeCount);

            if (agreeCount >= 2) {
                log.info("투표 가결! 이단 심문으로 넘어갑니다.");
                startTrialInternal(room);
            } else {
                gameResponseSender.broadcastVoteProposalFailed(room);
                taskScheduler.schedule(
                        () -> startNextRound(roomId),
                        Instant.now().plusSeconds(3)
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
        room.clearPhaseData();

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

            room.getCurrentPhaseData().put(sessionId, targetSessionId);
            roomRepository.saveRoom(room);

            log.info("지목 투표: {} -> {}", sessionId, targetSessionId);

            if (room.getActionCount() >= room.getPlayers().size()) {
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

            if (room.getCurrentPhase() != GamePhase.TRIAL_VOTE) {
                log.warn("이미 처리된 심문 결과입니다. room={}", roomId);
                return;
            }

            String targetSessionId = calculateMostVoted(room);
            boolean success = false;
            String targetNickname = "없음";
            PlayerRole targetRole = PlayerRole.CITIZEN;

            if (targetSessionId != null) {
                Player target = room.getPlayers().stream()
                        .filter(p -> p.getSessionId().equals(targetSessionId))
                        .findFirst()
                        .orElse(null);

                if (target != null) {
                    targetNickname = target.getNickname();
                    targetRole = target.getRole(); // CITIZEN or TRAITOR

                    if (targetRole == PlayerRole.TRAITOR) {
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

            room.setCurrentPhase(GamePhase.TRIAL_RESULT);
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
        for (String target : room.getCurrentPhaseData().values()) {
            voteCounts.put(target, voteCounts.getOrDefault(target, 0) + 1);
        }

        return voteCounts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }

    public void startNextRound(String roomId) {

        boolean isGameOver = false;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentRound() >= 4) {
                isGameOver = true;
                log.info("4라운드 종료! 게임 오버 처리 예정: {}", roomId);
            } else {
                room.setCurrentRound(room.getCurrentRound() + 1);
                room.setOracle(Oracle.values()[new Random().nextInt(Oracle.values().length)]);

                roomRepository.saveRoom(room);

                gameResponseSender.broadcastNextRound(room);
                log.info("다음 라운드 진입: {}라운드", room.getCurrentRound());

                taskScheduler.schedule(
                        () -> startOraclePhase(roomId),
                        Instant.now().plusSeconds(ORACLE_PHASE_SECONDS)
                );
            }

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }

        if (isGameOver) {
            processGameOver(roomId);
        }
    }

    public void processGameOver(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            PlayerRole winnerRole;

            if (room.isVotingDisabled()) {
                winnerRole = PlayerRole.CITIZEN;
            }
            else if (room.getCurrentHp() <= 0) {
                winnerRole = PlayerRole.TRAITOR;
            }
            else {
                winnerRole = PlayerRole.TRAITOR;
            }

            room.setStatus(RoomStatus.GAME_OVER);
            roomRepository.saveRoom(room);

            log.info("게임 종료 판정: room={}, winner={}", roomId, winnerRole);

            gameResponseSender.broadcastGameOver(room, winnerRole);

            gameLogService.saveGameLogAsync(room, winnerRole.name());

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