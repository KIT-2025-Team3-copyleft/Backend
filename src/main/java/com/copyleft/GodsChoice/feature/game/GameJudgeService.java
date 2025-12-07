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
    private final GameRoomLockFacade lockFacade;
    private final GameResponseSender gameResponseSender;
    private final GroqApiClient groqApiClient;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;

    @Lazy
    private final GameFlowService gameFlowService;

    private static final int ROUND_RESULT_DURATION_SECONDS = 35;
    private record AiPromptData(String sentence, String personality) {}


    // AI 심판

    public void judgeRound(String roomId) {
        taskScheduler.schedule(() -> judgeRoundInternal(roomId), Instant.now());
    }

    private void judgeRoundInternal(String roomId) {
        AiPromptData promptData = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() == GamePhase.JUDGING) return null;

            room.setCurrentPhase(GamePhase.JUDGING);
            roomRepository.saveRoom(room);

            String sentence = constructSentence(room);
            String personality = (room.getGodPersonality() != null)
                    ? room.getGodPersonality().getDescription()
                    : "변덕스러운";

            return new AiPromptData(sentence, personality);
        });

        if (promptData == null) {
            taskScheduler.schedule(() -> judgeRoundInternal(roomId), Instant.now().plusSeconds(1));
            return;
        }

        int score = 0;
        String reason = "신이 침묵합니다.";

        try {
            String jsonResult = groqApiClient.judgeSentence(promptData.sentence(), promptData.personality());
            JsonNode root = objectMapper.readTree(jsonResult);
            score = root.path("score").asInt();
            reason = root.path("reason").asText();
        } catch (Exception e) {
            log.error("AI API 오류: {}", e.getMessage());
        }

        applyJudgmentResult(roomId, score, reason, promptData.sentence());
    }

    private void applyJudgmentResult(String roomId, int score, String reason, String sentence) {
        Boolean success = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getStatus() == RoomStatus.GAME_OVER) return false;

            int currentHp = room.getCurrentHp();
            room.setCurrentHp(currentHp + score);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastRoundResult(room, score, reason, sentence);
            return true;
        });

        if (success == null) {
            taskScheduler.schedule(
                    () -> applyJudgmentResult(roomId, score, reason, sentence),
                    Instant.now().plusMillis(500)
            );
        } else if (success) {
            taskScheduler.schedule(() -> gameFlowService.startVoteProposal(roomId), Instant.now().plusSeconds(ROUND_RESULT_DURATION_SECONDS));
        }
    }

    // 타임아웃 및 결과 집계

    public void processCardTimeout(String roomId) {
        Boolean needsJudgment = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);

            if (room == null || room.getCurrentPhase() != GamePhase.CARD_SELECT) return false;

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
            return true;
        });

        if (needsJudgment == null) {
            taskScheduler.schedule(() -> processCardTimeout(roomId), Instant.now().plusSeconds(1));
        } else if (needsJudgment) {
            judgeRound(roomId);
        }
    }

    public void judgeVoteProposalEndImmediately(String roomId) {
        taskScheduler.schedule(() -> processVoteProposalEnd(roomId), Instant.now());
    }

    public void processVoteProposalEnd(String roomId) {
        Boolean result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() != GamePhase.VOTE_PROPOSAL) return false;

            long agreeCount = room.getCurrentPhaseData().values().stream()
                    .filter("true"::equalsIgnoreCase)
                    .count();

            log.info("찬반 투표 집계: room={}, agree={}", roomId, agreeCount);

            if (agreeCount >= 2) {
                gameFlowService.startTrialInternal(room);
            } else {
                gameResponseSender.broadcastVoteProposalFailed(room);
                taskScheduler.schedule(
                        () -> gameFlowService.startNextRound(roomId),
                        Instant.now().plusSeconds(3)
                );
            }
            return true;
        });

        if (result == null) {
            taskScheduler.schedule(() -> processVoteProposalEnd(roomId), Instant.now().plusMillis(500));
        }
    }

    public void judgeTrialEndImmediately(String roomId) {
        taskScheduler.schedule(() -> processTrialEnd(roomId), Instant.now());
    }

    public void processTrialEnd(String roomId) {
        Boolean result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() != GamePhase.TRIAL_VOTE) return false;

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
                    targetRole = target.getRole();

                    if (targetRole == PlayerRole.TRAITOR) {
                        success = true;
                        room.setCurrentHp(room.getCurrentHp() + 100);
                        room.setVotingDisabled(true);
                    } else {
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
                    () -> gameFlowService.startNextRound(roomId),
                    Instant.now().plusSeconds(5)
            );
            return true;
        });

        if (result == null) {
            taskScheduler.schedule(() -> processTrialEnd(roomId), Instant.now().plusMillis(500));
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