package com.copyleft.GodsChoice.domain;

import com.copyleft.GodsChoice.domain.type.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomTest {

    @Test
    @DisplayName("라운드 히스토리가 정상적으로 추가된다")
    void addRoundHistory_Success() {
        // given
        Room room = Room.builder()
                .roomId("test-room")
                .build();

        // when
        room.addRoundHistory(1, Oracle.ORDER, "개발자가 코드를 짰다", 100, "완벽해");
        room.addRoundHistory(2, Oracle.ORDER, "버그가 터졌다", -50, "슬퍼");

        // then
        assertNotNull(room.getRoundHistories());
        assertEquals(2, room.getRoundHistories().size());

        RoundHistory firstLog = room.getRoundHistories().get(0);
        assertEquals(1, firstLog.getRound());
        assertEquals(Oracle.ORDER, firstLog.getOracle());
        assertEquals("개발자가 코드를 짰다", firstLog.getSentence());
        assertEquals(100, firstLog.getScore());
    }

    @Test
    @DisplayName("새 게임을 시작하면 히스토리가 초기화된다")
    void resetForNewGame_ClearsHistory() {
        // given
        Room room = Room.builder().build();
        room.addRoundHistory(1, Oracle.ORDER, "기록", 10, "msg");

        // when
        room.resetForNewGame();

        // then
        assertTrue(room.getRoundHistories().isEmpty(), "히스토리가 비워져야 합니다.");
    }
}