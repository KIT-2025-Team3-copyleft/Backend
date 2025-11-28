package com.copyleft.GodsChoice.domain;

import com.copyleft.GodsChoice.domain.type.Oracle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class RoundHistory {
    private int round;
    private Oracle oracle;
    private String sentence;
    private int score;
    private String reason;
}