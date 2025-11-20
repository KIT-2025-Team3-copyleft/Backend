package com.copyleft.GodsChoice.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Player {

    private String sessionId; // 고유 세션 ID
    private String nickname;  // 표시용 닉네임
    private boolean isHost;   // 방장 여부

    private String color; // "RED", "BLUE" 등
    private String role;  // "CITIZEN", "TRAITOR"
    private String slot;  // "주체", "대상", "어떻게", "어쩐다"

    // "CONNECTED", "DISCONNECTED"
    private String connectionStatus;

    private String selectedCard; // 현재 라운드에서 선택한 카드 텍스트
    private String voteTarget;   // 현재 투표에서 지목한 대상의 sessionId

    public static Player createHost(String sessionId, String nickname) {
        return Player.builder()
                .sessionId(sessionId)
                .nickname(nickname)
                .isHost(true)
                .connectionStatus("CONNECTED")
                .build();
    }
}