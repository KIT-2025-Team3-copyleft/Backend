package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.type.ConnectionStatus;
import com.copyleft.GodsChoice.feature.game.dto.GameResponse; // [변경] LobbyResponse -> GameResponse
import com.copyleft.GodsChoice.global.constant.ErrorCode;
import com.copyleft.GodsChoice.global.constant.GameCode;
import com.copyleft.GodsChoice.global.constant.SocketEvent;
import com.copyleft.GodsChoice.infra.websocket.WebSocketSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GameResponseSender {

    private final WebSocketSender webSocketSender;

    public void broadcastGameStartTimer(Room room) {
        GameResponse response = createResponse(
                SocketEvent.GAME_START_TIMER,
                null,
                GameCode.GAME_COUNTDOWN_START.getMessage(),
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastLoadGameScene(Room room) {
        GameResponse response = createResponse(
                SocketEvent.LOAD_GAME_SCENE,
                room,
                null,
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastGameStartCancelled(Room room) {
        GameResponse response = createResponse(
                SocketEvent.TIMER_CANCELLED,
                null,
                GameCode.GAME_TIMER_CANCELLED.getMessage(),
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastRoundStart(Room room) {
        GameResponse response = createResponse(
                SocketEvent.ROUND_START,
                room,
                "라운드가 시작되었습니다.",
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastAllCardsSelected(Room room) {
        GameResponse response = createResponse(
                SocketEvent.ALL_CARDS_SELECTED,
                room,
                GameCode.ALL_USERS_SELECTED.getMessage(),
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastRoundResult(Room room, int score, String reason, String sentence) {
        GameResponse response = GameResponse.builder()
                .event(SocketEvent.ROUND_RESULT.name())
                .room(room)
                .score(score)
                .reason(reason)
                .sentence(sentence)
                .message(reason)
                .build();

        broadcastToRoom(room, response);
    }

    public void sendCards(String sessionId, String slotType, java.util.List<String> cards) {
        GameResponse response = GameResponse.builder()
                .event(SocketEvent.RECEIVE_CARDS.name())
                .slotType(slotType)
                .cards(cards)
                .message("카드를 선택해주세요.")
                .build();

        webSocketSender.sendEventToSession(sessionId, response);
    }

    public void broadcastVoteProposalStart(Room room) {
        GameResponse response = createResponse(
                SocketEvent.VOTE_PROPOSAL_START,
                room,
                "15",
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastTrialStart(Room room) {
        GameResponse response = createResponse(
                SocketEvent.TRIAL_START,
                room,
                "60",
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastTrialResult(Room room, boolean success, String targetNickname, String targetRole) {

        String resultMsg = String.format("심판 결과: %s님은 %s였습니다!", targetNickname, targetRole);

        GameResponse response = createResponse(
                SocketEvent.TRIAL_RESULT,
                room,
                resultMsg,
                success ? "SUCCESS" : "FAIL"
        );
        broadcastToRoom(room, response);
    }

    public void broadcastVoteProposalFailed(Room room) {
        GameResponse response = createResponse(
                SocketEvent.VOTE_PROPOSAL_FAILED,
                room,
                "투표가 부결되었습니다.",
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastNextRound(Room room) {
        GameResponse response = createResponse(
                SocketEvent.NEXT_ROUND_START,
                room,
                room.getCurrentRound() + "라운드를 시작합니다.",
                null
        );
        broadcastToRoom(room, response);
    }

    public void broadcastGameOver(Room room, String winnerRole) {
        String msg = winnerRole.equals("CITIZEN")
                ? "시민 승리! 배신자를 색출했습니다."
                : "배신자 승리! 시민들은 혼란에 빠졌습니다.";

        GameResponse response = createResponse(
                SocketEvent.GAME_OVER,
                room,
                msg,
                winnerRole
        );
        broadcastToRoom(room, response);
    }

    // [추가] 14. 로비 복귀 명령 (선택사항 - 클라이언트가 GAME_OVER 받고 알아서 가면 필요 없음)
    public void broadcastReturnToLobby(Room room) {
        // ... (필요시 구현, 보통 GAME_OVER 하나로 충분)
    }

    public void sendError(String sessionId, ErrorCode errorCode) {
        GameResponse response = createResponse(
                SocketEvent.ERROR_MESSAGE,
                null,
                errorCode.getMessage(),
                errorCode.name()
        );
        webSocketSender.sendEventToSession(sessionId, response);
    }

    private GameResponse createResponse(SocketEvent event, Room room, String message, String code) {
        return GameResponse.builder()
                .event(event.name())
                .room(room)
                .message(message)
                .code(code)
                .build();
    }

    private void broadcastToRoom(Room room, GameResponse response) {
        if (room != null && room.getPlayers() != null) {
            for (Player player : room.getPlayers()) {
                if (player.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    webSocketSender.sendEventToSession(player.getSessionId(), response);
                }
            }
        }
    }
}