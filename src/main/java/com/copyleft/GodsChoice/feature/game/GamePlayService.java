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
    private final RedisLockRepository redisLockRepository;
    private final GameResponseSender gameResponseSender;

    private final GameFlowService gameFlowService;
    private final GameJudgeService gameJudgeService;

    public void processGameReady(String sessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getStatus() != RoomStatus.PLAYING || room.getCurrentPhase() != null) return;

            room.getCurrentPhaseData().put(sessionId, "READY");
            roomRepository.saveRoom(room);

            if (room.getActionCount() >= room.getPlayers().size()) {
                room.clearPhaseData();
                roomRepository.saveRoom(room);
                gameFlowService.startOraclePhase(roomId);
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void selectCard(String sessionId, String cardContent) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        boolean shouldJudge = false;
        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() != GamePhase.CARD_SELECT) return;

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

            boolean allSelected = room.getPlayers().stream().allMatch(p -> p.getSelectedCard() != null);
            if (allSelected) {
                gameResponseSender.broadcastAllCardsSelected(room);
                shouldJudge = true;
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }

        if (shouldJudge) {
            gameJudgeService.judgeRound(roomId); // JudgeService 호출
        }
    }

    public void voteProposal(String sessionId, boolean agree) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getCurrentPhase() == GamePhase.VOTE_PROPOSAL) {
                room.getCurrentPhaseData().put(sessionId, String.valueOf(agree));
                roomRepository.saveRoom(room);

                if (room.getActionCount() >= room.getPlayers().size()) {
                    gameJudgeService.judgeVoteProposalEndImmediately(roomId);
                }
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void castVote(String sessionId, String targetSessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getCurrentPhase() == GamePhase.TRIAL_VOTE) {
                room.getCurrentPhaseData().put(sessionId, targetSessionId);
                roomRepository.saveRoom(room);

                if (room.getActionCount() >= room.getPlayers().size()) {
                    gameJudgeService.judgeTrialEndImmediately(roomId);
                }
            }
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }
}