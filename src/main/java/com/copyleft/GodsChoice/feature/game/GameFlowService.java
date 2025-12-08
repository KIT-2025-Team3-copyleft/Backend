package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.config.GameProperties;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.*;
import com.copyleft.GodsChoice.feature.lobby.LobbyResponseSender;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
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

    // 순환 참조 방지를 위해 Lazy 주입
    @Lazy
    private final GameJudgeService gameJudgeService;


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
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null || room.getCurrentPhase() == GamePhase.ORACLE) return;

            room.changePhase(GamePhase.ORACLE);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastRoundStart(room);
            gameResponseSender.broadcastOracle(room);
            room.getPlayers().forEach(p ->
                    gameResponseSender.sendRole(p, p.getRole() == PlayerRole.TRAITOR ? room.getGodPersonality() : null)
            );

            taskScheduler.schedule(() -> startRound(roomId), Instant.now().plusSeconds(gameProperties.oraclePhase()));
        });
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

            // 카드 전송 예약
            taskScheduler.schedule(() -> sendCardsDelayed(roomId), Instant.now().plusSeconds(gameProperties.cardSendDelay()));

            // 타임아웃 예약
            taskScheduler.schedule(() -> gameJudgeService.processCardTimeout(roomId),
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
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            room.changePhase(GamePhase.VOTE_PROPOSAL);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastVoteProposalStart(room);

            taskScheduler.schedule(() -> gameJudgeService.processVoteProposalEnd(roomId), Instant.now().plusSeconds(gameProperties.voteProposalTime()));
        });
    }

    public void startTrialInternal(Room room) {
        room.adjustHp(-gameProperties.trialStartPenalty());
        room.changePhase(GamePhase.TRIAL_VOTE);
        roomRepository.saveRoom(room);

        gameResponseSender.broadcastTrialStart(room);

        taskScheduler.schedule(() -> gameJudgeService.processTrialEnd(room.getRoomId()), Instant.now().plusSeconds(gameProperties.trialTime()));
    }

    public void startNextRound(String roomId) {
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            if (room.getCurrentRound() >= 4) {
                processGameOver(roomId);
            } else {
                room.changePhase(null);
                room.setCurrentRound(room.getCurrentRound() + 1);
                room.setOracle(Oracle.values()[new Random().nextInt(Oracle.values().length)]);

                roomRepository.saveRoom(room);
                gameResponseSender.broadcastNextRound(room);

                taskScheduler.schedule(() -> startOraclePhase(roomId), Instant.now().plusSeconds(gameProperties.oraclePhase()));
            }
        });
    }

    // 게임 종료 및 기타

    public void processGameOver(String roomId) {
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room == null) return;

            PlayerRole winnerRole = (room.getCurrentHp() <= 0 || !room.isVotingDisabled()) ? PlayerRole.TRAITOR : PlayerRole.CITIZEN;

            room.setStatus(RoomStatus.GAME_OVER);
            roomRepository.saveRoom(room);
            gameResponseSender.broadcastGameOver(room, winnerRole);
            gameLogService.saveGameLogAsync(room, winnerRole.name());

            taskScheduler.schedule(() -> cleanupGameOverRoom(roomId), Instant.now().plusSeconds(60));
        });
    }

    public void cleanupGameOverRoom(String roomId) {
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getStatus() == RoomStatus.GAME_OVER) {
                roomRepository.deleteRoom(roomId, room.getRoomCode());
                log.info("타임아웃된 방 강제 청소 완료: {}", roomId);
            }
        });
    }

    public void backToRoom(String sessionId) {
        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;
        lockFacade.execute(roomId, () -> {
            Room room = roomRepository.findRoomById(roomId).orElse(null);
            if (room != null && room.getStatus() == RoomStatus.GAME_OVER) {
                room.resetForNewGame();
                roomRepository.addWaitingRoom(roomId);
                roomRepository.saveRoom(room);
                lobbyResponseSender.broadcastLobbyUpdate(room);
            }
        });
    }
}