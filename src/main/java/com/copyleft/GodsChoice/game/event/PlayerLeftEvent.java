package com.copyleft.GodsChoice.game.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class PlayerLeftEvent {
    private final String roomId;
    private final String sessionId;
}