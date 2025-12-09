package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.config.GameProperties;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.type.*;
import com.copyleft.GodsChoice.feature.game.dto.GamePayloads;
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.global.constant.GameCode;
import com.copyleft.GodsChoice.global.constant.SocketEvent;
import com.copyleft.GodsChoice.infra.websocket.WebSocketSender;
import com.copyleft.GodsChoice.infra.websocket.dto.WebSocketResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class GameResponseSender {

    private final WebSocketSender webSocketSender;
    private final GameProperties gameProperties;

    // Room 정보만 보내는 경우
    public void broadcastRoomEvent(Room room, SocketEvent event, String message) {
        WebSocketResponse<Room> response = WebSocketResponse.<Room>builder()
                .event(event.name())
                .message(message)
                .data(room)
                .build();
        broadcastToRoom(room, response);
    }

    // 신탁 공개
    public void broadcastOracle(Room room) {
        GamePayloads.OracleInfo data = GamePayloads.OracleInfo.builder()
                .room(room)
                .oracle(room.getOracle().getMessage())
                .build();

        WebSocketResponse<GamePayloads.OracleInfo> response = WebSocketResponse.<GamePayloads.OracleInfo>builder()
                .event(SocketEvent.SHOW_ORACLE.name())
                .message("신탁이 내려왔습니다.")
                .data(data)
                .build();

        broadcastToRoom(room, response);
    }

    // 역할 전달 (개인)
    public void sendRole(Player player, GodPersonality godPersonality) {
        String msg = "당신은 " + (player.getRole() == PlayerRole.TRAITOR ? "배신자" : "시민") + "입니다.";

        String personalityText = (godPersonality != null)
                ? godPersonality.getDisplayName()
                : null;

        GamePayloads.RoleInfo data = GamePayloads.RoleInfo.builder()
                .role(player.getRole())
                .godPersonality(personalityText)
                .build();

        WebSocketResponse<GamePayloads.RoleInfo> response = WebSocketResponse.<GamePayloads.RoleInfo>builder()
                .event(SocketEvent.SHOW_ROLE.name())
                .message(msg)
                .data(data)
                .build();

        webSocketSender.sendEventToSession(player.getSessionId(), response);
    }

    public void sendCards(String sessionId, SlotType slotType, List<String> cards, Map<SlotType, PlayerColor> slotOwnersMap) {
        List<GamePayloads.SlotOwnerEntry> slotOwnerList = slotOwnersMap.entrySet().stream()
                .map(entry -> GamePayloads.SlotOwnerEntry.builder()
                        .slotType(entry.getKey())
                        .playerColor(entry.getValue())
                        .build())
                .toList();

        GamePayloads.CardInfo data = GamePayloads.CardInfo.builder()
                .slotType(slotType)
                .cards(cards)
                .slotOwners(slotOwnerList)
                .build();

        WebSocketResponse<GamePayloads.CardInfo> response = WebSocketResponse.<GamePayloads.CardInfo>builder()
                .event(SocketEvent.RECEIVE_CARDS.name())
                .message(GameCode.REQUEST_CARD_SELECTION.getMessage())
                .data(data)
                .build();

        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void broadcastRoundResult(Room room, int score, String reason, List<GamePayloads.SentencePart> parts, String fullSentence) {
        GamePayloads.RoundResult data = GamePayloads.RoundResult.builder()
                .room(room)
                .score(score)
                .reason(reason)
                .sentenceParts(parts)
                .fullSentence(fullSentence)
                .build();

        WebSocketResponse<GamePayloads.RoundResult> response = WebSocketResponse.<GamePayloads.RoundResult>builder()
                .event(SocketEvent.ROUND_RESULT.name())
                .message(reason)
                .data(data)
                .build();

        broadcastToRoom(room, response);
    }

    public void broadcastTrialResult(Room room, boolean success, String targetNickname, PlayerRole targetRole) {
        String msg = String.format("심판 결과: %s님은 %s였습니다!", targetNickname, targetRole);

        GamePayloads.TrialResult data = GamePayloads.TrialResult.builder()
                .room(room)
                .success(success)
                .targetNickname(targetNickname)
                .targetRole(targetRole)
                .build();

        WebSocketResponse<GamePayloads.TrialResult> response = WebSocketResponse.<GamePayloads.TrialResult>builder()
                .event(SocketEvent.TRIAL_RESULT.name())
                .message(msg)
                .code(success ? "SUCCESS" : "FAIL")
                .data(data)
                .build();

        broadcastToRoom(room, response);
    }

    public void broadcastGameOver(Room room, PlayerRole winnerRole) {
        String msg = winnerRole == PlayerRole.CITIZEN ? "시민 승리!" : "배신자 승리!";

        GamePayloads.GameOverInfo data = GamePayloads.GameOverInfo.builder()
                .room(room)
                .winnerRole(winnerRole)
                .build();

        WebSocketResponse<GamePayloads.GameOverInfo> response = WebSocketResponse.<GamePayloads.GameOverInfo>builder()
                .event(SocketEvent.GAME_OVER.name())
                .message(msg)
                .data(data)
                .build();

        broadcastToRoom(room, response);
    }

    public void sendError(String sessionId, ErrorCode errorCode) {
        WebSocketResponse<Void> response = WebSocketResponse.<Void>builder()
                .event(SocketEvent.ERROR_MESSAGE.name())
                .message(errorCode.getMessage())
                .code(errorCode.name())
                .build();
        webSocketSender.sendEventToSession(sessionId, response);
    }

    private void broadcastToRoom(Room room, Object response) {
        if (room != null && room.getPlayers() != null) {
            for (Player player : room.getPlayers()) {
                if (player.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    webSocketSender.sendEventToSession(player.getSessionId(), response);
                }
            }
        }
    }

    public void broadcastGameStartTimer(Room room) { broadcastRoomEvent(room, SocketEvent.GAME_START_TIMER, GameCode.GAME_COUNTDOWN_START.getMessage()); }
    public void broadcastLoadGameScene(Room room) { broadcastRoomEvent(room, SocketEvent.LOAD_GAME_SCENE, null); }
    public void broadcastGameStartCancelled(Room room) { broadcastRoomEvent(room, SocketEvent.TIMER_CANCELLED, GameCode.GAME_TIMER_CANCELLED.getMessage()); }
    public void broadcastRoundStart(Room room) { broadcastRoomEvent(room, SocketEvent.ROUND_START, "라운드가 시작되었습니다."); }
    public void broadcastAllCardsSelected(Room room) { broadcastRoomEvent(room, SocketEvent.ALL_CARDS_SELECTED, GameCode.ALL_USERS_SELECTED.getMessage()); }
    public void broadcastVoteProposalStart(Room room) { broadcastRoomEvent(room, SocketEvent.VOTE_PROPOSAL_START, String.valueOf(gameProperties.voteProposalTime())); }
    public void broadcastVoteProposalFailed(Room room) { broadcastRoomEvent(room, SocketEvent.VOTE_PROPOSAL_FAILED, "투표가 부결되었습니다."); }
    public void broadcastTrialStart(Room room) { broadcastRoomEvent(room, SocketEvent.TRIAL_START, String.valueOf(gameProperties.trialTime())); }
    public void broadcastNextRound(Room room) { broadcastRoomEvent(room, SocketEvent.NEXT_ROUND_START, room.getCurrentRound() + "라운드를 준비합니다."); }
}