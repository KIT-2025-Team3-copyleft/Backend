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

    // --- [AI 심판] ---

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

            // 문장 조합
            sentence = constructSentence(room);
            personality = room.getGodPersonality() != null ? room.getGodPersonality().getDescription() : "변덕스러운";
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }

        // AI 호출 (락 없이 수행)
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
            // 재시도 로직
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

            // FlowService 호출하여 다음 단계(투표)로 이동
            taskScheduler.schedule(() -> gameFlowService.startVoteProposal(roomId), Instant.now().plusSeconds(5));
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    // --- [타임아웃 및 결과 집계] ---

    public void processCardTimeout(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) { /* 재시도 로직 생략 */ return; }

        boolean shouldJudge = false;
        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getCurrentPhase() == GamePhase.CARD_SELECT) {
                // 미선택자 랜덤 선택 로직
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
                    gameFlowService.startTrialInternal(room); // 가결 -> 이단 심문
                } else {
                    gameResponseSender.broadcastVoteProposalFailed(room);
                    taskScheduler.schedule(() -> gameFlowService.startNextRound(roomId), Instant.now().plusSeconds(3)); // 부결 -> 다음 라운드
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
                // 최다 득표자 계산
                String targetSessionId = calculateMostVoted(room);
                // 결과 처리 (HP 증감 등)
                // ... (기존 GameService.processTrialEnd 로직 복사) ...

                // 간략화:
                boolean success = false;
                String targetNickname = "기권";
                PlayerRole targetRole = PlayerRole.CITIZEN;

                if (targetSessionId != null) {
                    // 타겟 찾아서 로직 수행
                    // ...
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
        // ... (문장 조합 로직) ...
        return "";
    }

    private String calculateMostVoted(Room room) {
        // ... (최다 득표 계산 로직) ...
        return null;
    }
}