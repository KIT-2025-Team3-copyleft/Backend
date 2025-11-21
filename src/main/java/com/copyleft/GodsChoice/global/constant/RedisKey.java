package com.copyleft.GodsChoice.global.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RedisKey {

    ACTIVE_NICKNAMES("active_nicknames"), // Set
    SESSION("session:"),                  // String (session:abc-123)

    ROOM("room:"),                        // Hash (room:uuid)
    ROOM_CODE("room_code:"),              // String (room_code:B3FK)
    WAITING_ROOMS("waiting_rooms"),       // Set

    ROOM_LOG("room_log:");                // List (room_log:uuid)

    private final String prefix;

    public String makeKey(String identifier) {
        return this.prefix + identifier;
    }

    public String getKey() {
        return this.prefix;
    }
}