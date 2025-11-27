package com.copyleft.GodsChoice.domain.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GodPersonality {
    ANGRY("분노한"),
    GENEROUS("너그러운"),
    WHIMSICAL("변덕스러운"),
    CRUEL("잔혹한"),
    MISCHIEVOUS("장난꾸러기 같은");

    private final String description;
}