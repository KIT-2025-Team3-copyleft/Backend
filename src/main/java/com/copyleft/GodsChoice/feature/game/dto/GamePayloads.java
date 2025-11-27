package com.copyleft.GodsChoice.feature.game.dto;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GodPersonality;
import com.copyleft.GodsChoice.domain.type.Oracle;
import com.copyleft.GodsChoice.domain.type.PlayerRole;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

public class GamePayloads {

    // 신탁 공개용 데이터
    @Getter
    @Builder
    public static class OracleInfo {
        private Room room;
        private Oracle oracle; // Enum
    }

    // 역할 공개용 데이터 (개인)
    @Getter
    @Builder
    public static class RoleInfo {
        private PlayerRole role;
        private GodPersonality godPersonality; // 배신자용
    }

    // 카드 수신용 데이터
    @Getter
    @Builder
    public static class CardInfo {
        private String slotType;
        private List<String> cards;
    }

    // 라운드 결과 데이터
    @Getter
    @Builder
    public static class RoundResult {
        private Room room;
        private int score;
        private String reason;
        private String sentence;
    }

    // 심판 결과 데이터
    @Getter
    @Builder
    public static class TrialResult {
        private Room room;
        private boolean success;
        private String targetNickname;
        private PlayerRole targetRole;
    }

    // 게임 오버 데이터
    @Getter
    @Builder
    public static class GameOverInfo {
        private Room room;
        private PlayerRole winnerRole;
    }
}