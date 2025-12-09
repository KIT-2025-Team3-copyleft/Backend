package com.copyleft.GodsChoice.domain.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Oracle {
    VITALITY("메마른 대지에 활력을 불어넣어라"),
    ORDER("무너진 질서를 다시 세워라"),
    LIGHT("가장 낮은 곳에서 빛을 밝혀라"),
    SILENCE("시끄러운 세상을 침묵시켜라"),
    REVOLUTION("기존의 것을 뒤엎고 혁명하라"),
    TRUTH("거짓된 가면을 벗기고 진실을 마주하라"),
    MADNESS("이성을 버리고 광기에 몸을 맡겨라"),
    PURITY("더러운 것을 씻어내고 순수를 되찾아라"),
    DESIRE("감춰진 욕망을 솔직하게 드러내라"),
    SACRIFICE("가장 소중한 것을 바쳐 증명하라");

    private final String message;
}