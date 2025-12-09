package com.copyleft.GodsChoice.domain.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GodPersonality {

    ANGRY("분노한", "파괴와 엄격한 심판"),
    GENEROUS("너그러운", "포용과 생명의 성장"),
    WHIMSICAL("변덕스러운", "혼돈과 예측불가능한 유희"),
    CRUEL("잔혹한", "고통과 철저한 현실"),
    MISCHIEVOUS("장난꾸러기 같은", "유쾌한 장난과 풍자");

    private final String displayName;   // 배신자에게 보여줄 이름
    private final String keyword;       // AI에게 줄 가치관
}