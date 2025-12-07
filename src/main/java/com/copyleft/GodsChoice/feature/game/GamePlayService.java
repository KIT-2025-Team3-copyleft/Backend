package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GamePlayService {

    private final RoomRepository roomRepository;
    private final GameRoomLockFacade lockFacade;
    private final GameResponseSender gameResponseSender;

    private final GameFlowService gameFlowService;
    private final GameJudgeService gameJudgeService;

    public void processGameReady(String sessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getStatus() != RoomStatus.PLAYING || room.getCurrentPhase() != null) return;

            room.getCurrentPhaseData().put(sessionId, "READY");
            roomRepository.saveRoom(room);

            if (room.getActionCount() >= room.getPlayers().size()) {
                room.clearPhaseData();
                roomRepository.saveRoom(room);
                gameFlowService.startOraclePhase(roomId);
            }
        });
    }

    public void selectCard(String sessionId, String cardContent) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() != GamePhase.CARD_SELECT) return;

            room.getPlayers().stream()
                    .filter(p -> p.getSessionId().equals(sessionId))
                    .findFirst()
                    .ifPresent(p -> p.setSelectedCard(cardContent));

            roomRepository.saveRoom(room);

            boolean allSelected = room.getPlayers().stream().allMatch(p -> p.getSelectedCard() != null);
            if (allSelected) {
                gameResponseSender.broadcastAllCardsSelected(room);
                gameJudgeService.judgeRound(roomId);
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

                if (room.getActionCount() >= room.getPlayers().size()) {
                    gameJudgeService.judgeTrialEndImmediately(roomId);
                }
            }
        });
    }
}