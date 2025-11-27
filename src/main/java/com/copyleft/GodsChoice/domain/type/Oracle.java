package com.copyleft.GodsChoice.domain.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Oracle {
    VITALITY("메마른 땅에 활력을 불어넣어라"),
    ORDER("혼돈 속에서 질서를 찾아라"),
    LIGHT("가장 낮은 곳에서 빛을 밝혀라"),
    VOICE("침묵하는 자들의 목소리가 되어라");

    private final String message;
}