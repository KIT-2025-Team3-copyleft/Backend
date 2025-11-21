package com.copyleft.GodsChoice.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Room {

    private String roomTitle;     // 방 제목
    private String roomId;        // 내부 관리용 UUID
    private String roomCode;      // 유저 공유용 코드
    private String hostSessionId; // 방장의 sessionId

    @Builder.Default
    private List<Player> players = new ArrayList<>();

    @Builder.Default
    private int currentHp = 500;

    @Builder.Default
    private int currentRound = 1;

    private String godPersonality; // 이번 판의 신 성향

    @Builder.Default
    private boolean isVotingDisabled = false; // 배신자 색출 후 투표 잠금 여부

    private String status;        // "WAITING", "STARTING", "PLAYING"
    private String currentPhase;  // "CARD_SELECT", "JUDGING", "VOTE_PROPOSAL" 등

    @Builder.Default
    private long createdAt = System.currentTimeMillis(); // 생성 시각 (Timestamp)

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

    public static Room create(String roomTitle, String hostSessionId, Player hostPlayer) {
        // 코드생성 수정필요
        String uuid = UUID.randomUUID().toString();
        String code = uuid.substring(0, 4).toUpperCase();

        Room room = Room.builder()
                .roomId(uuid)
                .roomCode(code)
                .roomTitle(roomTitle)
                .hostSessionId(hostSessionId)
                .status("WAITING")
                .currentHp(1000)
                .currentRound(1)
                .isVotingDisabled(false)
                .createdAt(System.currentTimeMillis())
                .build();

        room.addPlayer(hostPlayer);
        return room;
    }
}