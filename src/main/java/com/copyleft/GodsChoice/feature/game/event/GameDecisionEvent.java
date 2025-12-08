package com.copyleft.GodsChoice.feature.game.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class GameDecisionEvent {
    private final String roomId;
    private final Type type;

    public enum Type {
        ROUND_JUDGED,       // 라운드 심판 완료 (점수 공개 끝) -> 찬반 투표로 이동
        VOTE_PROPOSAL_PASSED, // 찬반 투표 가결 -> 이단 심문으로 이동
        VOTE_PROPOSAL_FAILED, // 찬반 투표 부결 -> 다음 라운드로 이동
        TRIAL_FINISHED      // 이단 심문 완료 -> 다음 라운드로 이동
    }
}