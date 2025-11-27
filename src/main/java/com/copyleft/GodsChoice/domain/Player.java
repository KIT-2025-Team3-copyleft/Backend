package com.copyleft.GodsChoice.domain;

import com.copyleft.GodsChoice.domain.type.ConnectionStatus;
import com.copyleft.GodsChoice.domain.type.PlayerColor;
import com.copyleft.GodsChoice.domain.type.PlayerRole;
import com.copyleft.GodsChoice.domain.type.SlotType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;

@Getter
@Setter
@Builder
@Jacksonized
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Player {

    private String sessionId; // 고유 세션 ID
    private String nickname;  // 표시용 닉네임
    private boolean isHost;   // 방장 여부

    private PlayerColor color; // "RED", "BLUE" 등
    private PlayerRole role;  // "CITIZEN", "TRAITOR"
    private SlotType slot;  // "주체", "대상", "어떻게", "어쩐다"

    // "CONNECTED", "DISCONNECTED"
    private ConnectionStatus connectionStatus;

    private String selectedCard; // 현재 라운드에서 선택한 카드 텍스트
    private String voteTarget;   // 현재 투표에서 지목한 대상의 sessionId

    public static Player createHost(String sessionId, String nickname) {
        return Player.builder()
                .sessionId(sessionId)
                .nickname(nickname)
                .isHost(true)
                .connectionStatus(ConnectionStatus.CONNECTED)
                .build();
    }
}