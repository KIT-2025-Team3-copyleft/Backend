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

    private final GameFlowService gameFlowService; // 로딩 완료 시 게임 시작 호출
    private final GameJudgeService gameJudgeService; // 전원 카드 선택 시 심판 호출

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
                gameFlowService.startOraclePhase(roomId); // FlowService 호출
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

            // 모든 플레이어가 선택했는지 도메인 로직으로 확인하면 좋음 (리팩토링 포인트 4번)
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
                    // 비동기로 하면 좋지만, 즉시 실행을 위해 스케줄러 0초 딜레이 사용
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