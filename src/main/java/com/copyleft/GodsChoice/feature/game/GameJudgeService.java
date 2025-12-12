package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.config.GameProperties;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.*;
import com.copyleft.GodsChoice.domain.vo.AiJudgment;
import com.copyleft.GodsChoice.feature.game.dto.GamePayloads;
import com.copyleft.GodsChoice.feature.game.event.GameDecisionEvent;
import com.copyleft.GodsChoice.feature.game.event.PlayerLeftEvent;
import com.copyleft.GodsChoice.infra.external.GroqApiClient;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
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

    private record AiPromptData(
            String fullSentence,
            List<GamePayloads.SentencePart> parts,
            GodPersonality personality,
            Oracle oracle,
            int round
    ) {}

    @EventListener
    public void handlePlayerLeft(PlayerLeftEvent event) {
        String roomId = event.getRoomId();

        lockFacade.execute(roomId, () -> {
            roomRepository.findRoomById(roomId).ifPresent(this::checkPhaseFinishCondition);
        });
    }

    private void checkPhaseFinishCondition(Room room) {
        if (room.getStatus() != RoomStatus.PLAYING) return;
        if (room.getPlayers().isEmpty()) return;

        GamePhase phase = room.getCurrentPhase();
        if (phase == null) return;

        int currentPlayers = room.getPlayers().size();
        int actionCount = room.getActionCount();

        switch (phase) {
            case CARD_SELECT:
                if (room.isAllPlayersSelectedCard()) {
                    log.info("퇴장으로 인한 카드 선택 완료");
                    judgeRound(room.getRoomId());
                }
                break;
            case VOTE_PROPOSAL:
                if (actionCount >= currentPlayers) {
                    log.info("퇴장으로 인한 찬반 투표 조기 종료");
                    judgeVoteProposalEndImmediately(room.getRoomId());
                }
                break;
            case TRIAL_VOTE:
                if (actionCount >= currentPlayers) {
                    log.info("퇴장으로 인한 이단 심문 투표 조기 종료");
                    judgeTrialEndImmediately(room.getRoomId());
                }
                break;
            default:
                break;
        }
    }


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
            GodPersonality personality = (room.getGodPersonality() != null)
                    ? room.getGodPersonality()
                    : GodPersonality.WHIMSICAL;
            Oracle oracle = (room.getOracle() != null)
                    ? room.getOracle()
                    : Oracle.VITALITY;

            return new AiPromptData(fullSentence, parts, personality, oracle, room.getCurrentRound());
        });

        if (result.isSkipped() || !result.isSuccess()) return;

        AiPromptData promptData = result.getData();
        AiJudgment judgment = groqApiClient.judgeSentence(promptData.fullSentence(), promptData.personality(), promptData.oracle());

        applyJudgmentResult(roomId, judgment.score(), judgment.reason(), promptData.parts(), promptData.fullSentence(), promptData.round());
    }

    private void applyJudgmentResult(String roomId, int score, String reason, List<GamePayloads.SentencePart> parts, String fullSentence, int targetRound) {
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getStatus() == RoomStatus.GAME_OVER) return;

            if (room.getCurrentRound() != targetRound || room.getCurrentPhase() != GamePhase.JUDGING) {
                return;
            }

            room.adjustHp(score);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastRoundResult(room, score, reason, parts, fullSentence);

            taskScheduler.schedule(
                    () -> eventPublisher.publishEvent(new GameDecisionEvent(roomId, GameDecisionEvent.Type.ROUND_JUDGED)),
                    Instant.now().plusSeconds(gameProperties.roundResultDuration())
            );
        });
    }

    // 타임아웃 및 결과 집계

    public void processCardTimeout(String roomId, int targetRound) {
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);

            if (room == null || room.getCurrentPhase() != GamePhase.CARD_SELECT || room.getCurrentRound() != targetRound) return;

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

            judgeRound(roomId);
        });
    }

    public void judgeVoteProposalEndImmediately(String roomId) {
        roomRepository.findRoomById(roomId).ifPresent(room -> taskScheduler.schedule(() -> processVoteProposalEnd(roomId, room.getCurrentRound()), Instant.now()));
    }

    public void processVoteProposalEnd(String roomId, int targetRound) {
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() != GamePhase.VOTE_PROPOSAL || room.getCurrentRound() != targetRound) return;

            boolean isPassed = room.isVotePassed();
            log.info("찬반 투표 결과: room={}, passed={}", roomId, isPassed);

            if (isPassed) {
                taskScheduler.schedule(
                        () -> eventPublisher.publishEvent(new GameDecisionEvent(roomId, GameDecisionEvent.Type.VOTE_PROPOSAL_PASSED)),
                        Instant.now()
                );
            } else {
                gameResponseSender.broadcastVoteProposalFailed(room);
                room.changePhase(null);
                roomRepository.saveRoom(room);
                taskScheduler.schedule(
                        () -> eventPublisher.publishEvent(new GameDecisionEvent(roomId, GameDecisionEvent.Type.VOTE_PROPOSAL_FAILED)),
                        Instant.now().plusSeconds(gameProperties.voteFailDelay())
                );
            }
        });
    }

    public void judgeTrialEndImmediately(String roomId) {
        roomRepository.findRoomById(roomId).ifPresent(room -> taskScheduler.schedule(() -> processTrialEnd(roomId, room.getCurrentRound()), Instant.now()));
    }

    public void processTrialEnd(String roomId, int targetRound) {
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() != GamePhase.TRIAL_VOTE || room.getCurrentRound() != targetRound) return;

            String targetId = room.getMostVotedTargetSessionId();
            boolean success = false;
            String targetNickname = "없음";
            PlayerRole targetRole = PlayerRole.CITIZEN;

            if (targetId == null) {
                log.info("투표 무효 (동점 또는 득표 없음): room={}", roomId);
                room.changePhase(GamePhase.TRIAL_RESULT);
                roomRepository.saveRoom(room);
                gameResponseSender.broadcastTrialResult(room, false, "무효(동점)", PlayerRole.CITIZEN);

                taskScheduler.schedule(
                        () -> eventPublisher.publishEvent(new GameDecisionEvent(roomId, GameDecisionEvent.Type.TRIAL_FINISHED)),
                        Instant.now().plusSeconds(gameProperties.nextRoundDelay())
                );
                return;
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
        });
    }

    private List<GamePayloads.SentencePart> constructSentenceParts(Room room) {
        List<GamePayloads.SentencePart> parts = new ArrayList<>();
        List<SlotType> order = List.of(SlotType.SUBJECT, SlotType.TARGET, SlotType.HOW, SlotType.ACTION);

        for (SlotType type : order) {
            room.getPlayers().stream()
                    .filter(p -> p.getSlot() == type)
                    .findFirst()
                    .ifPresent(p -> parts.add(GamePayloads.SentencePart.builder()
                            .playerColor(p.getColor())
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