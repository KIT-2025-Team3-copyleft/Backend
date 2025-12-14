package com.copyleft.GodsChoice.game.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class GameUserTimeoutEvent {
    private final String sessionId;
}