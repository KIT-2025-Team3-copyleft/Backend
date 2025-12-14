package com.copyleft.GodsChoice.lobby.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

public class LobbyPayloads {

    @Getter
    @Builder
    public static class RoomInfo {
        private String roomId;
        private String roomTitle;
        private int currentCount;
        private int maxCount;
        private boolean isPlaying;
    }

    @Getter
    @Builder
    public static class RoomList {
        private List<RoomInfo> rooms;
    }
}