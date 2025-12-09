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
    REVOLUTION("기존의 것을 뒤엎고 혁명하라");

    private final String message;
}