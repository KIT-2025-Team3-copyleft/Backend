package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.config.GameProperties;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.*;
import com.copyleft.GodsChoice.domain.vo.AiJudgment;
import com.copyleft.GodsChoice.feature.game.dto.GamePayloads;
import com.copyleft.GodsChoice.feature.game.event.GameDecisionEvent;
import com.copyleft.GodsChoice.infra.external.GroqApiClient;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameJudgeService {

    private final RoomRepository roomRepository;
    private final GameRoomLockFacade lockFacade;
    private final GameResponseSender gameResponseSender;
    private final GroqApiClient groqApiClient;
    private final TaskScheduler taskScheduler;
    private final GameProperties gameProperties;

    private final ApplicationEventPublisher eventPublisher;

    private record AiPromptData(String fullSentence, List<GamePayloads.SentencePart> parts, String personality) {}


    // AI 심판

    public void judgeRound(String roomId) {
        taskScheduler.schedule(() -> judgeRoundInternal(roomId), Instant.now());
    }

    private void judgeRoundInternal(String roomId) {
        LockResult<AiPromptData> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() == GamePhase.JUDGING) return null;

            room.setCurrentPhase(GamePhase.JUDGING);
            roomRepository.saveRoom(room);

            List<GamePayloads.SentencePart> parts = constructSentenceParts(room);
            String fullSentence = constructSentenceString(parts);
            String personality = (room.getGodPersonality() != null)
                    ? room.getGodPersonality().getDescription()
                    : "변덕스러운";

            return new AiPromptData(fullSentence, parts, personality);
        });

        if (result.isLockFailed()) {
            taskScheduler.schedule(() -> judgeRoundInternal(roomId), Instant.now().plusSeconds(1));
            return;
        }
        if (result.isSkipped()) {
            log.info("심판 로직 스킵됨 (조건 불일치): {}", roomId);
            return;
        }

        AiPromptData promptData = result.getData();
        AiJudgment judgment = groqApiClient.judgeSentence(promptData.fullSentence(), promptData.personality());

        applyJudgmentResult(roomId, judgment.score(), judgment.reason(), promptData.parts(), promptData.fullSentence());
    }

    private void applyJudgmentResult(String roomId, int score, String reason, List<GamePayloads.SentencePart> parts, String fullSentence) {
        LockResult<Boolean> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getStatus() == RoomStatus.GAME_OVER) return null;

            room.adjustHp(score);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastRoundResult(room, score, reason, parts, fullSentence);
            return true;
        });

        if (result.isLockFailed()) {
            taskScheduler.schedule(
                    () -> applyJudgmentResult(roomId, score, reason, parts, fullSentence),
                    Instant.now().plusMillis(500)
            );
        } else if (result.isSuccess()) {
            taskScheduler.schedule(
                    () -> eventPublisher.publishEvent(new GameDecisionEvent(roomId, GameDecisionEvent.Type.ROUND_JUDGED)),
                    Instant.now().plusSeconds(gameProperties.roundResultDuration())
            );
        }
    }

    // 타임아웃 및 결과 집계

    public void processCardTimeout(String roomId) {
        LockResult<Boolean> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);

            if (room == null || room.getCurrentPhase() != GamePhase.CARD_SELECT) return null;

            boolean changed = false;
            for (Player p : room.getPlayers()) {
                if (p.getSelectedCard() == null) {
                    List<String> randomCards = WordData.getRandomCards(p.getSlot(), 1);
                    if (!randomCards.isEmpty()) {
                        p.setSelectedCard(randomCards.getFirst());
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

        if (result.isLockFailed()) {
            taskScheduler.schedule(() -> processCardTimeout(roomId), Instant.now().plusSeconds(1));
        } else if (result.isSuccess()) {
            judgeRound(roomId);
        }
    }

    public void judgeVoteProposalEndImmediately(String roomId) {
        taskScheduler.schedule(() -> processVoteProposalEnd(roomId), Instant.now());
    }

    public void processVoteProposalEnd(String roomId) {
        LockResult<Boolean> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() != GamePhase.VOTE_PROPOSAL) return null;

            boolean isPassed = room.isVotePassed();
            log.info("찬반 투표 결과: room={}, passed={}", roomId, isPassed);

            if (isPassed) {
                eventPublisher.publishEvent(new GameDecisionEvent(roomId, GameDecisionEvent.Type.VOTE_PROPOSAL_PASSED));
            } else {
                gameResponseSender.broadcastVoteProposalFailed(room);
                taskScheduler.schedule(
                        () -> eventPublisher.publishEvent(new GameDecisionEvent(roomId, GameDecisionEvent.Type.VOTE_PROPOSAL_FAILED)),
                        Instant.now().plusSeconds(gameProperties.voteFailDelay())
                );
            }
            return true;
        });

        if (result.isLockFailed()) {
            taskScheduler.schedule(() -> processVoteProposalEnd(roomId), Instant.now().plusMillis(500));
        }
    }

    public void judgeTrialEndImmediately(String roomId) {
        taskScheduler.schedule(() -> processTrialEnd(roomId), Instant.now());
    }

    public void processTrialEnd(String roomId) {
        LockResult<Boolean> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() != GamePhase.TRIAL_VOTE) return null;

            String targetId = room.getMostVotedTargetSessionId();
            boolean success = false;
            String targetNickname = "없음";
            PlayerRole targetRole = PlayerRole.CITIZEN;

            if (targetId == null) {
                log.info("투표 무효 (동점 또는 득표 없음): room={}", roomId);
                gameResponseSender.broadcastTrialResult(room, false, "무효(동점)", PlayerRole.CITIZEN);

                taskScheduler.schedule(
                        () -> eventPublisher.publishEvent(new GameDecisionEvent(roomId, GameDecisionEvent.Type.TRIAL_FINISHED)),
                        Instant.now().plusSeconds(gameProperties.nextRoundDelay())
                );
                return true;
            }

            Player target = room.findPlayer(targetId).orElse(null);
            if (target != null) {
                targetNickname = target.getNickname();
                targetRole = target.getRole();
                success = (targetRole == PlayerRole.TRAITOR);

                if (success) {
                    room.adjustHp(gameProperties.traitorCatchReward());
                    room.setVotingDisabled(true);
                } else {
                    room.adjustHp(-gameProperties.citizenFailPenalty());
                }
            }

            room.changePhase(GamePhase.TRIAL_RESULT);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastTrialResult(room, success, targetNickname, targetRole);
            log.info("심문 결과: target={}, success={}, hp={}", targetNickname, success, room.getCurrentHp());

            taskScheduler.schedule(
                    () -> eventPublisher.publishEvent(new GameDecisionEvent(roomId, GameDecisionEvent.Type.TRIAL_FINISHED)),
                    Instant.now().plusSeconds(gameProperties.nextRoundDelay())
            );
            return true;
        });

        if (result.isLockFailed()) {
            taskScheduler.schedule(() -> processTrialEnd(roomId), Instant.now().plusMillis(500));
        }
    }

    private List<GamePayloads.SentencePart> constructSentenceParts(Room room) {
        List<GamePayloads.SentencePart> parts = new ArrayList<>();
        List<SlotType> order = List.of(SlotType.SUBJECT, SlotType.TARGET, SlotType.HOW, SlotType.ACTION);

        for (SlotType type : order) {
            room.getPlayers().stream()
                    .filter(p -> p.getSlot() == type)
                    .findFirst()
                    .ifPresent(p -> parts.add(GamePayloads.SentencePart.builder()
                            .nickname(p.getNickname())
                            .word(p.getSelectedCard())
                            .slotType(type)
                            .build()));
        }
        return parts;
    }

    private String constructSentenceString(List<GamePayloads.SentencePart> parts) {
        StringBuilder sb = new StringBuilder();
        for (GamePayloads.SentencePart part : parts) {
            sb.append(part.getWord()).append(" ");
        }
        return sb.toString().trim();
    }
}