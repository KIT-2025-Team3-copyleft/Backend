package com.copyleft.GodsChoice.feature.game.dto;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.*;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

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
        private SlotType slotType;
        private List<String> cards;
        private List<SlotOwnerEntry> slotOwners;
    }

    @Getter
    @Builder
    public static class SlotOwnerEntry {
        private SlotType slotType;
        private PlayerColor playerColor;
    }

    // 라운드 결과 데이터
    @Getter
    @Builder
    public static class RoundResult {
        private Room room;
        private int score;
        private String reason;
        private List<SentencePart> sentenceParts;
        private String fullSentence;
    }

    @Getter
    @Builder
    public static class SentencePart {
        private PlayerColor playerColor;
        private String word;
        private SlotType slotType;
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