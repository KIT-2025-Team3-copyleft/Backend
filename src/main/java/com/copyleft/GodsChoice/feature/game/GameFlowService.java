package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.config.GameProperties;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.*;
import com.copyleft.GodsChoice.feature.game.event.GameDecisionEvent;
import com.copyleft.GodsChoice.feature.game.event.GameUserTimeoutEvent;
import com.copyleft.GodsChoice.feature.lobby.LobbyResponseSender;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameFlowService {

    private final RoomRepository roomRepository;
    private final GameRoomLockFacade lockFacade;
    private final GameResponseSender gameResponseSender;
    private final LobbyResponseSender lobbyResponseSender;
    private final TaskScheduler taskScheduler;
    private final GameLogService gameLogService;
    private final GameProperties gameProperties;
    private final GameJudgeService gameJudgeService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void handleGameDecision(GameDecisionEvent event) {
        String roomId = event.getRoomId();
        log.info("게임 흐름 이벤트 수신: room={}, type={}", roomId, event.getType());

        switch (event.getType()) {
            case ROUND_JUDGED:
                startVoteProposal(roomId);
                break;

            case VOTE_PROPOSAL_PASSED:
                startTrialInternal(roomId);
                break;

            case VOTE_PROPOSAL_FAILED, TRIAL_FINISHED:
                startNextRound(roomId);
                break;
        }
    }


    // 게임 시작 관련

    public void tryStartGame(String sessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) {
            gameResponseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
            return;
        }

        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (!validateGameStart(room, sessionId)) return;

            room.setStatus(RoomStatus.STARTING);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastGameStartTimer(room);

            taskScheduler.schedule(
                    () -> processGameStart(roomId),
                    Instant.now().plusSeconds(gameProperties.startDelay())
            );
        });
    }

    private boolean validateGameStart(Room room, String sessionId) {
        if (room == null) {
            gameResponseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
            return false;
        }
        if (!sessionId.equals(room.getHostSessionId())) {
            gameResponseSender.sendError(sessionId, ErrorCode.NOT_HOST);
            return false;
        }
        if (room.getPlayers().size() < Room.MAX_PLAYER_COUNT) {
            gameResponseSender.sendError(sessionId, ErrorCode.NOT_ENOUGH_PLAYERS);
            return false;
        }
        if (room.getStatus() != RoomStatus.WAITING) {
            gameResponseSender.sendError(sessionId, ErrorCode.ROOM_ALREADY_PLAYING);
            return false;
        }
        for (Player p : room.getPlayers()) {
            if (!room.getCurrentPhaseData().containsKey(p.getSessionId())) {
                gameResponseSender.sendError(sessionId, ErrorCode.NOT_ENOUGH_PLAYERS);
                return false;
            }
        }
        return true;
    }

    public void processGameStart(String roomId) {
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getStatus() != RoomStatus.STARTING) return;

            if (room.getPlayers().size() < Room.MAX_PLAYER_COUNT) {
                log.warn("게임 시작 실패 (인원 부족): {}", roomId);
                cancelGameStart(room);
                return;
            }

            room.setStatus(RoomStatus.PLAYING);
            room.clearPhaseData();
            assignRolesAndScenario(room);

            roomRepository.saveRoom(room);
            roomRepository.removeWaitingRoom(roomId);

            gameResponseSender.broadcastLoadGameScene(room);
            log.info("게임 정식 시작 (Scene 이동): room={}", roomId);

            taskScheduler.schedule(
                    () -> startOraclePhase(roomId),
                    Instant.now().plusSeconds(gameProperties.loadingTimeout())
            );
        });
    }

    private void cancelGameStart(Room room) {
        room.setStatus(RoomStatus.WAITING);
        roomRepository.saveRoom(room);
        roomRepository.addWaitingRoom(room.getRoomId());
        gameResponseSender.broadcastGameStartCancelled(room);
    }

    private void assignRolesAndScenario(Room room) {
        List<Player> players = room.getPlayers();
        Collections.shuffle(players);
        for (int i = 0; i < players.size(); i++) {
            players.get(i).setRole(i == 0 ? PlayerRole.TRAITOR : PlayerRole.CITIZEN);
        }

        Random random = new Random();
        room.setOracle(Oracle.values()[random.nextInt(Oracle.values().length)]);
        room.setGodPersonality(GodPersonality.values()[random.nextInt(GodPersonality.values().length)]);
    }

    // 라운드 진행 흐름

    public void startOraclePhase(String roomId) {
        LockResult<Void> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() == GamePhase.ORACLE) return;

            room.changePhase(GamePhase.ORACLE);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastRoundStart(room);
            gameResponseSender.broadcastOracle(room);
            if (room.getCurrentRound() == 1) {
                room.getPlayers().forEach(p ->
                        gameResponseSender.sendRole(p, p.getRole() == PlayerRole.TRAITOR ? room.getGodPersonality() : null)
                );
            }

            taskScheduler.schedule(() -> startRound(roomId), Instant.now().plusSeconds(gameProperties.oraclePhase()));
        });

        if (result.isLockFailed()) {
            taskScheduler.schedule(() -> startOraclePhase(roomId), Instant.now().plusMillis(200));
        }
    }

    public void startRound(String roomId) {
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            List<SlotType> slots = new ArrayList<>(List.of(SlotType.SUBJECT, SlotType.TARGET, SlotType.HOW, SlotType.ACTION));
            Collections.shuffle(slots);
            for (int i = 0; i < room.getPlayers().size(); i++) {
                Player p = room.getPlayers().get(i);
                p.setSlot(slots.get(i % slots.size()));
                p.setSelectedCard(null);
            }

            room.changePhase(GamePhase.CARD_SELECT);
            roomRepository.saveRoom(room);
            final int currentRound = room.getCurrentRound();

            // 카드 전송 예약
            taskScheduler.schedule(() -> sendCardsDelayed(roomId), Instant.now().plusSeconds(gameProperties.cardSendDelay()));

            // 타임아웃 예약
            taskScheduler.schedule(() -> gameJudgeService.processCardTimeout(roomId, currentRound),
                    Instant.now().plusSeconds(gameProperties.cardSendDelay() + gameProperties.cardSelectTime()));

            log.info("라운드 시작: room={}", roomId);
        });
    }

    public void sendCardsDelayed(String roomId) {
        lockFacade.execute(roomId, () -> {
            roomRepository.findRoomById(roomId)
                    .filter(room -> room.getCurrentPhase() == GamePhase.CARD_SELECT)
                    .ifPresent(this::processCardDistribution);
        });
    }

    private void processCardDistribution(Room room) {
        Map<SlotType, PlayerColor> slotOwners = collectSlotOwners(room.getPlayers());
        distributeCardsToPlayers(room.getPlayers(), slotOwners);
        log.info("카드 및 슬롯 정보 전송 완료: room={}", room.getRoomId());
    }

    private Map<SlotType, PlayerColor> collectSlotOwners(List<Player> players) {
        return players.stream()
                .filter(p -> p.getSlot() != null && p.getColor() != null)
                .collect(Collectors.toMap(Player::getSlot, Player::getColor));
    }

    private void distributeCardsToPlayers(List<Player> players, Map<SlotType, PlayerColor> slotOwners) {
        players.stream()
                .filter(p -> p.getSlot() != null)
                .forEach(player -> {
                    List<String> cards = WordData.getRandomCards(player.getSlot(), gameProperties.cardCount());
                    gameResponseSender.sendCards(player.getSessionId(), player.getSlot(), cards, slotOwners);
                });
    }

    public void startVoteProposal(String roomId) {
        LockResult<Void> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            if (room.isVotingDisabled()) {
                log.info("배신자가 색출되었으므로 투표 단계를 건너뜁니다: room={}", roomId);
                taskScheduler.schedule(() -> startNextRound(roomId), Instant.now());
                return;
            }

            room.changePhase(GamePhase.VOTE_PROPOSAL);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastVoteProposalStart(room);

            int currentRound = room.getCurrentRound();
            taskScheduler.schedule(() -> gameJudgeService.processVoteProposalEnd(roomId, currentRound), Instant.now().plusSeconds(gameProperties.voteProposalTime()));
        });

        if (result.isLockFailed()) {
            taskScheduler.schedule(() -> startVoteProposal(roomId), Instant.now().plusMillis(200));
        }
    }

    public void startTrialInternal(String roomId) {
        LockResult<Void> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            room.adjustHp(-gameProperties.trialStartPenalty());
            room.changePhase(GamePhase.TRIAL_VOTE);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastTrialStart(room);

            int currentRound = room.getCurrentRound();
            taskScheduler.schedule(() -> gameJudgeService.processTrialEnd(roomId, currentRound), Instant.now().plusSeconds(gameProperties.trialTime()));
        });
        if (result.isLockFailed()) {
            taskScheduler.schedule(() -> startTrialInternal(roomId), Instant.now().plusMillis(200));
        }
    }

    public void startNextRound(String roomId) {
        LockResult<Void> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            if (room.getCurrentRound() >= 4) {
                taskScheduler.schedule(() -> processGameOver(roomId), Instant.now());
            } else {
                room.changePhase(null);
                room.setCurrentRound(room.getCurrentRound() + 1);
                room.setOracle(Oracle.values()[new Random().nextInt(Oracle.values().length)]);

                roomRepository.saveRoom(room);
                gameResponseSender.broadcastNextRound(room);

                taskScheduler.schedule(() -> startOraclePhase(roomId), Instant.now().plusSeconds(gameProperties.oraclePhase()));
            }
        });

        if (result.isLockFailed()) {
            log.info("락 획득 실패 (NextRound): {}, 재시도합니다.", roomId);
            taskScheduler.schedule(() -> startNextRound(roomId), Instant.now().plusMillis(200));
        }
    }

    // 게임 종료 및 기타

    public void processGameOver(String roomId) {
        LockResult<Void> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            processGameOverInternal(room);
        });
        if (result.isLockFailed()) {
            taskScheduler.schedule(() -> processGameOver(roomId), Instant.now().plusMillis(200));
        }
    }

    private void processGameOverInternal(Room room) {
        if (room.getStatus() == RoomStatus.GAME_OVER) {
            log.warn("이미 종료 처리된 게임입니다. 중복 요청을 무시합니다. room={}", room.getRoomId());
            return;
        }

        PlayerRole winnerRole = (room.isVotingDisabled() && room.getCurrentHp() > 0)
                ? PlayerRole.CITIZEN : PlayerRole.TRAITOR;

        room.setStatus(RoomStatus.GAME_OVER);
        room.clearPhaseData();
        roomRepository.saveRoom(room);

        gameResponseSender.broadcastGameOver(room, winnerRole);
        gameLogService.saveGameLogAsync(room, winnerRole.name());

        taskScheduler.schedule(() -> cleanupGameOverRoom(room.getRoomId()), Instant.now().plusSeconds(gameProperties.gameOverCleanupTime()));

        log.info("게임 종료 처리 완료: room={}, winner={}", room.getRoomId(), winnerRole);
    }

    public void cleanupGameOverRoom(String roomId) {
        LockResult<List<String>> result = lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return null;

            if (room.getStatus() == RoomStatus.GAME_OVER) {
                roomRepository.deleteRoom(roomId, room.getRoomCode());
                log.info("아무도 복귀하지 않아 방 삭제: {}", roomId);
                return null;
            }

            List<String> toKick = new ArrayList<>();
            if (room.getStatus() == RoomStatus.WAITING) {
                for (Player p : room.getPlayers()) {
                    if (!room.getCurrentPhaseData().containsKey(p.getSessionId())) {
                        toKick.add(p.getSessionId());
                    }
                }
            }
            return toKick;
        });

        if (result.isSuccess() && result.getData() != null) {
            for (String sessionId : result.getData()) {
                eventPublisher.publishEvent(new GameUserTimeoutEvent(sessionId));
                log.info("미복귀 유저 강퇴 이벤트 발행: {}", sessionId);
            }
        }
    }

    public void backToRoom(String sessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && (room.getStatus() == RoomStatus.GAME_OVER || room.getStatus() == RoomStatus.WAITING)) {
                room.getCurrentPhaseData().put(sessionId, "RETURNED");
                if (room.getStatus() == RoomStatus.GAME_OVER) {
                    room.resetForNewGame();
                    roomRepository.addWaitingRoom(roomId);
                }
                roomRepository.saveRoom(room);
                lobbyResponseSender.broadcastLobbyUpdate(room);
            }
        });
    }
}