package com.copyleft.GodsChoice.game.dto;

import com.copyleft.GodsChoice.domain.Room;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameResponse {
    private String event;
    private Room room;      // 방 정보 (게임 상태 포함)
    private String message; // 안내 메시지
    private String code;    // 에러 코드 or 성공 코드

    private java.util.List<String> cards; // 받을 단어 카드 목록
    private String slotType;              // 내가 맡은 역할 (SUBJECT, TARGET 등)

    private Integer score;
    private String reason;
    private String sentence;

    private String oracle;         // 신탁 (공통)
    private String godPersonality; // 신의 성향 (배신자 전용)
    private String role;           // 역할 (개인)
}