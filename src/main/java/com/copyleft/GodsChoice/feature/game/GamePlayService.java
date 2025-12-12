package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.config.GameProperties;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class GamePlayService {

    private final RoomRepository roomRepository;
    private final GameRoomLockFacade lockFacade;
    private final GameResponseSender gameResponseSender;
    private final GameJudgeService gameJudgeService;
    private final TaskScheduler taskScheduler;
    private final GameProperties gameProperties;

    public void selectCard(String sessionId, String cardContent) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() != GamePhase.CARD_SELECT) return;

            room.findPlayer(sessionId).ifPresent(p -> p.setSelectedCard(cardContent));
            roomRepository.saveRoom(room);

            if (room.isAllPlayersSelectedCard()) {
                gameResponseSender.broadcastAllCardsSelected(room);
                taskScheduler.schedule(
                        () -> gameJudgeService.judgeRound(roomId),
                        Instant.now().plusSeconds(gameProperties.judgeDelay())
                );
            }
        });
    }

    public void voteProposal(String sessionId, boolean agree) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getCurrentPhase() == GamePhase.VOTE_PROPOSAL) {
                room.getCurrentPhaseData().put(sessionId, String.valueOf(agree));
                roomRepository.saveRoom(room);

                gameResponseSender.broadcastVoteUpdate(room);

                if (room.getActionCount() >= room.getPlayers().size()) {
                    gameJudgeService.judgeVoteProposalEndImmediately(roomId);
                }
            }
        });
    }

    public void castVote(String sessionId, String targetSessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getCurrentPhase() == GamePhase.TRIAL_VOTE) {
                room.getCurrentPhaseData().put(sessionId, targetSessionId);
                roomRepository.saveRoom(room);

                gameResponseSender.broadcastTrialVoteUpdate(room);

                if (room.getActionCount() >= room.getPlayers().size()) {
                    gameJudgeService.judgeTrialEndImmediately(roomId);
                }
            }
        });
    }
}