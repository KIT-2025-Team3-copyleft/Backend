package com.copyleft.GodsChoice.domain;

import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.domain.type.PlayerRole;
import com.copyleft.GodsChoice.domain.type.RoomStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;

import java.util.*;

@Getter
@Setter
@Builder
@Jacksonized
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Room {

    private String roomTitle;     // 방 제목
    private String roomId;        // 내부 관리용 UUID
    private String roomCode;      // 유저 공유용 코드
    private String hostSessionId; // 방장의 sessionId

    public static final int MAX_PLAYER_COUNT = 4;

    @Builder.Default
    private List<Player> players = new ArrayList<>();

    @Builder.Default
    private int currentHp = 500;

    @Builder.Default
    private int currentRound = 1;

    private String godPersonality; // 이번 판의 신 성향

    @Builder.Default
    private boolean isVotingDisabled = false; // 배신자 색출 후 투표 잠금 여부

    private RoomStatus status;        // "WAITING", "STARTING", "PLAYING"
    private GamePhase currentPhase;  // "CARD_SELECT", "JUDGING", "VOTE_PROPOSAL" 등

    @Builder.Default
    private long createdAt = System.currentTimeMillis(); // 생성 시각 (Timestamp)

    @Builder.Default
    private Map<String, Boolean> proposalVotes = new HashMap<>();

    @Builder.Default
    private Map<String, String> trialVotes = new HashMap<>();

    public void addPlayer(Player player) {
        if (this.players == null) {
            this.players = new ArrayList<>();
        }
        this.players.add(player);
    }

    public void removePlayer(String sessionId) {
        if (this.players != null) {
            this.players.removeIf(p -> Objects.equals(p.getSessionId(), sessionId));
        }
    }

    public static Room create(String roomId, String roomCode, String roomTitle, String hostSessionId, Player hostPlayer) {

        Room room = Room.builder()
                .roomId(roomId)
                .roomCode(roomCode)
                .roomTitle(roomTitle)
                .hostSessionId(hostSessionId)
                .status(RoomStatus.WAITING)
                .currentHp(1000)
                .currentRound(1)
                .isVotingDisabled(false)
                .createdAt(System.currentTimeMillis())
                .build();

        room.addPlayer(hostPlayer);
        return room;
    }

    public String delegateHost() {
        if (this.players == null || this.players.isEmpty()) {
            return null;
        }

        for (Player p : this.players) {
            p.setHost(false);
        }

        Player newHost = this.players.get(0);
        newHost.setHost(true);

        this.hostSessionId = newHost.getSessionId();
        this.roomTitle = newHost.getNickname() + "님의 방";

        return newHost.getSessionId();
    }

    public boolean isEmpty() {
        return this.players == null || this.players.isEmpty();
    }

    public void assignRoles() {
        if (this.players == null || this.players.isEmpty()) return;

        for (Player player : this.players) {
            player.setRole(PlayerRole.CITIZEN.name());
        }
    }

    public void clearVotes() {
        this.proposalVotes.clear();
        this.trialVotes.clear();
    }
}