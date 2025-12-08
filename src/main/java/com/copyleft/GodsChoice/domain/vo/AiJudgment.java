package com.copyleft.GodsChoice.domain.vo;

public record AiJudgment(int score, String reason) {
    // 실패 시 사용할 기본값 (Null Object Pattern)
    public static AiJudgment fallback() {
        return new AiJudgment(0, "신이 침묵합니다.");
    }
}