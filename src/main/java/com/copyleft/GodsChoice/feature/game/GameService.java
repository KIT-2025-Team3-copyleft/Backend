package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import com.copyleft.GodsChoice.domain.type.SlotType;
import com.copyleft.GodsChoice.domain.type.WordData;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.infra.external.GroqApiClient;
import com.copyleft.GodsChoice.infra.persistence.RedisLockRepository;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private final RoomRepository roomRepository;
    private final RedisLockRepository redisLockRepository;
    private final GameResponseSender gameResponseSender;
    private final TaskScheduler taskScheduler;
    private final GroqApiClient groqApiClient;
    private final ObjectMapper objectMapper;

    private static final int GAME_START_DELAY_SECONDS = 3;
    private static final int CARD_SELECT_DURATION_SECONDS = 120;


    public void tryStartGame(String sessionId) {

        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) {
            log.warn("게임 시작 실패 (세션-방 매핑 없음): session={}", sessionId);
            gameResponseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
            return;
        }

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.warn("게임 시작 락 획득 실패 (tryStartGame): room={}, session={}", roomId, sessionId);
            gameResponseSender.sendError(sessionId, ErrorCode.GAME_START_FAILED);
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                gameResponseSender.sendError(sessionId, ErrorCode.ROOM_NOT_FOUND);
                return;
            }
            Room room = roomOpt.get();

            // 방장인가?
            if (!sessionId.equals(room.getHostSessionId())) {
                gameResponseSender.sendError(sessionId, ErrorCode.NOT_HOST);
                return;
            }

            // 4명인가?
            if (room.getPlayers().size() < Room.MAX_PLAYER_COUNT) {
                gameResponseSender.sendError(sessionId, ErrorCode.NOT_ENOUGH_PLAYERS);
                return;
            }

            // 대기 상태인가?
            if (room.getStatus() != RoomStatus.WAITING) {
                gameResponseSender.sendError(sessionId, ErrorCode.ROOM_ALREADY_PLAYING);
                return;
            }

            room.setStatus(RoomStatus.STARTING);
            roomRepository.saveRoom(room);

            gameResponseSender.broadcastGameStartTimer(room);

            taskScheduler.schedule(
                    () -> processGameStart(roomId),
                    Instant.now().plusSeconds(GAME_START_DELAY_SECONDS)
            );

            log.info("게임 시작 카운트다운 시작: room={}", roomId);

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void processGameStart(String roomId) {

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.error("게임 시작 처리 락 획득 실패. (방 상태가 STARTING으로 남을 수 있음): {}", roomId);
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                log.warn("게임 시작 처리 중 방을 찾을 수 없음: {}", roomId);
                return;
            }
            Room room = roomOpt.get();

            if (room.getStatus() != RoomStatus.STARTING) {
                log.info("게임 시작 취소됨 (상태 불일치): {}", room.getStatus());
                return;
            }

            if (room.getPlayers().size() < Room.MAX_PLAYER_COUNT) {
                log.warn("게임 시작 실패 (인원 부족): {}", roomId);
                room.setStatus(RoomStatus.WAITING);
                roomRepository.saveRoom(room);
                roomRepository.addWaitingRoom(roomId);
                gameResponseSender.broadcastGameStartCancelled(room);
                return;
            }


            try {
                room.setStatus(RoomStatus.PLAYING);
                room.assignRoles();

                roomRepository.saveRoom(room);
                roomRepository.removeWaitingRoom(roomId);
                gameResponseSender.broadcastLoadGameScene(room);

                log.info("게임 정식 시작 (Scene 이동): room={}", roomId);

                startRoundInternal(roomId);

            } catch (Exception e) {
                log.error("게임 시작 처리 중 저장/전송 오류 (롤백 시도): room={}", roomId, e);

                room.setStatus(RoomStatus.WAITING);
                roomRepository.saveRoom(room);
                roomRepository.addWaitingRoom(roomId);
                gameResponseSender.broadcastGameStartCancelled(room);
            }

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    public void startRound(String roomId) {
        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            startRoundInternal(roomId);
        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }
    }

    private void startRoundInternal(String roomId) {

        Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
        if (roomOpt.isEmpty()) return;
        Room room = roomOpt.get();

        List<SlotType> slots = new ArrayList<>(List.of(
                SlotType.SUBJECT, SlotType.TARGET, SlotType.HOW, SlotType.ACTION
        ));
        Collections.shuffle(slots);

        List<Player> players = room.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            SlotType assignedSlot = slots.get(i % slots.size());

            player.setSlot(assignedSlot);
            player.setSelectedCard(null);

            List<String> cards = WordData.getRandomCards(assignedSlot, 5);
            gameResponseSender.sendCards(player.getSessionId(), assignedSlot.name(), cards);
        }

        room.setCurrentPhase(GamePhase.CARD_SELECT);

        roomRepository.saveRoom(room);
        gameResponseSender.broadcastRoundStart(room);

        taskScheduler.schedule(
                () -> processCardTimeout(roomId),
                Instant.now().plusSeconds(CARD_SELECT_DURATION_SECONDS)
        );

        log.info("라운드 시작 완료: room={}", roomId);
    }

    public void processCardTimeout(String roomId) {
        boolean shouldJudge = false;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.error("타임아웃 처리 락 획득 실패: {}", roomId);
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() != GamePhase.CARD_SELECT) {
                return;
            }

            log.info("카드 선택 시간 초과! 미선택자 강제 선택 진행: room={}", roomId);

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
            shouldJudge = true;

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }

        if (shouldJudge) {
            judgeRound(roomId);
        }
    }


    public void selectCard(String sessionId, String cardContent) {

        String roomId = roomRepository.getRoomIdBySessionId(sessionId);
        if (roomId == null) return;

        boolean shouldJudge = false;

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) return;

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() != GamePhase.CARD_SELECT) {
                return;
            }

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

            log.info("카드 선택 완료: session={}, card={}", sessionId, cardContent);

            boolean allSelected = room.getPlayers().stream()
                    .allMatch(p -> p.getSelectedCard() != null);

            if (allSelected) {
                log.info("전원 카드 선택 완료! AI 심판을 시작합니다. room={}", roomId);
                gameResponseSender.broadcastAllCardsSelected(room);
                shouldJudge = true;
            }

        } finally {
            redisLockRepository.unlock(roomId, lockToken);
        }

        if (shouldJudge) {
            judgeRound(roomId);
        }
    }


    public void judgeRound(String roomId) {
        taskScheduler.schedule(() -> judgeRoundInternal(roomId), Instant.now());
    }

    private void judgeRoundInternal(String roomId) {

        String lockToken = redisLockRepository.lock(roomId);
        if (lockToken == null) {
            log.error("심판 처리 락 획득 실패 (재시도 필요): {}", roomId);
            return;
        }

        try {
            Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
            if (roomOpt.isEmpty()) return;
            Room room = roomOpt.get();

            if (room.getCurrentPhase() == GamePhase.JUDGING) return;
            room.setCurrentPhase(GamePhase.JUDGING);
            roomRepository.saveRoom(room);

            String sentence = constructSentence(room);
            String personality = room.getGodPersonality();
            if (personality == null) personality = "변덕스러운";

            log.info("AI 심판 시작 (비동기): room={}, 문장='{}'", roomId, sentence);

            String jsonResult = groqApiClient.judgeSentence(sentence, personality);

            int score = 0;
            String reason = "신이 침묵합니다.";
            try {
                JsonNode root = objectMapper.readTree(jsonResult);
                score = root.path("score").asInt();
                reason = root.path("reason").asText();
            } catch (Exception e) {
                log.error("AI 파싱 실패: {}", jsonResult);
            }

            int currentHp = room.getCurrentHp();
            room.setCurrentHp(currentHp + score);

            roomRepository.saveRoom(room);
            gameResponseSender.broadcastRoundResult(room, score, reason, sentence);

            log.info("심판 완료: score={}", score);


        } finally {
            redisLockRepository.unlock(roomId, lockToken);
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
}