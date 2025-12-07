package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.*;
import com.copyleft.GodsChoice.infra.external.GroqApiClient;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameJudgeService {

    private final RoomRepository roomRepository;
    private final RedisLockRepository redisLockRepository;
    private final GameResponseSender gameResponseSender;
    private final GroqApiClient groqApiClient;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;

    @Lazy // 순환 참조 해결 (Judge -> Flow)
    private final GameFlowService gameFlowService;

    private static final int ROUND_RESULT_DURATION_SECONDS = 35;


    // AI 심판

    public void judgeRound(String roomId) {
        taskScheduler.schedule(() -> judgeRoundInternal(roomId), Instant.now());
    }

    private void judgeRoundInternal(String roomId) {
        String sentence = null;
        String personality = null;
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            taskScheduler.schedule(() -> judgeRoundInternal(roomId), Instant.now().plusSeconds(1));
            return;
        }

        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() == GamePhase.JUDGING) return;

            room.setCurrentPhase(GamePhase.JUDGING);
            roomRepository.saveRoom(room);

            sentence = constructSentence(room);
            personality = room.getGodPersonality() != null ? room.getGodPersonality().getDescription() : "변덕스러운";
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }

        int score = 0;
        String reason = "신이 침묵합니다.";
        try {
            String jsonResult = groqApiClient.judgeSentence(sentence, personality);
            JsonNode root = objectMapper.readTree(jsonResult);
            score = root.path("score").asInt();
            reason = root.path("reason").asText();
        } catch (Exception e) {
            log.error("AI 심판 오류: {}", e.getMessage());
        }

        applyJudgmentResult(roomId, score, reason, sentence);
    }

    private void applyJudgmentResult(String roomId, int score, String reason, String sentence) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            final int s = score; final String r = reason; final String sen = sentence;
            taskScheduler.schedule(() -> applyJudgmentResult(roomId, s, r, sen), Instant.now().plusMillis(500));
            return;
        }
        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getStatus() == RoomStatus.GAME_OVER) return;

            room.setCurrentHp(room.getCurrentHp() + score);
            roomRepository.saveRoom(room);
            gameResponseSender.broadcastRoundResult(room, score, reason, sentence);

            taskScheduler.schedule(() -> gameFlowService.startVoteProposal(roomId), Instant.now().plusSeconds(ROUND_RESULT_DURATION_SECONDS));
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    // 타임아웃 및 결과 집계

    public void processCardTimeout(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            taskScheduler.schedule(() -> processCardTimeout(roomId), Instant.now().plusSeconds(1));
            return;
        }

        boolean shouldJudge = false;
        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getCurrentPhase() == GamePhase.CARD_SELECT) {
                boolean changed = false;
                for (Player p : room.getPlayers()) {
                    if (p.getSelectedCard() == null) {
                        List<String> cards = WordData.getRandomCards(p.getSlot(), 1);
                        if (!cards.isEmpty()) {
                            p.setSelectedCard(cards.get(0));
                            changed = true;
                        }
                    }
                }
                if (changed) roomRepository.saveRoom(room);
                gameResponseSender.broadcastAllCardsSelected(room);
                shouldJudge = true;
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
        if (shouldJudge) judgeRound(roomId);
    }

    public void judgeVoteProposalEndImmediately(String roomId) {
        taskScheduler.schedule(() -> processVoteProposalEnd(roomId), Instant.now());
    }

    public void processVoteProposalEnd(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;
        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getCurrentPhase() == GamePhase.VOTE_PROPOSAL) {
                long agreeCount = room.getCurrentPhaseData().values().stream().filter("true"::equalsIgnoreCase).count();
                if (agreeCount >= 2) {
                    gameFlowService.startTrialInternal(room);
                } else {
                    gameResponseSender.broadcastVoteProposalFailed(room);
                    taskScheduler.schedule(() -> gameFlowService.startNextRound(roomId), Instant.now().plusSeconds(3));
                }
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void judgeTrialEndImmediately(String roomId) {
        taskScheduler.schedule(() -> processTrialEnd(roomId), Instant.now());
    }

    public void processTrialEnd(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;
        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getCurrentPhase() == GamePhase.TRIAL_VOTE) {
                String targetSessionId = calculateMostVoted(room);
                boolean success = false;
                String targetNickname = "기권";
                PlayerRole targetRole = PlayerRole.CITIZEN;

                if (targetSessionId != null) {
                    Player target = room.getPlayers().stream()
                            .filter(p -> p.getSessionId().equals(targetSessionId))
                            .findFirst()
                            .orElse(null);

                    if (target != null) {
                        targetNickname = target.getNickname();
                        targetRole = target.getRole();

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

                taskScheduler.schedule(() -> gameFlowService.startNextRound(roomId), Instant.now().plusSeconds(5));
            }
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
}