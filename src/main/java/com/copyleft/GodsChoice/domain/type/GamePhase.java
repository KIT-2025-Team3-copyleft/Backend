package com.copyleft.GodsChoice.domain.type;

public enum GamePhase {
    CARD_SELECT,   // 카드 선택 (타이머 2분)
    JUDGING,       // AI 심판 및 결과 확인
    VOTE_PROPOSAL, // 이단 심문 제안 (찬반 투표)
    TRIAL_VOTE     // 이단 심문 (지목 투표)
}