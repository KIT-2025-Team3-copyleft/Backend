package com.copyleft.GodsChoice.feature.game.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class VoteRequest {
    // 찬반 투표용 (PROPOSE_VOTE)
    private Boolean agree;

    // 지목 투표용 (CAST_VOTE)
    private String targetSessionId;
}