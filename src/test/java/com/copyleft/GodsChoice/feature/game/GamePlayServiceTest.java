package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Player;
import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.type.GamePhase;
import com.copyleft.GodsChoice.domain.type.SlotType;
import com.copyleft.GodsChoice.infra.persistence.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GamePlayServiceTest {

    @InjectMocks
    private GamePlayService gamePlayService;

    @Mock private RoomRepository roomRepository;
    @Mock private GameRoomLockFacade lockFacade;
    @Mock private GameResponseSender gameResponseSender;
    @Mock private GameJudgeService gameJudgeService;
    @Mock private GameFlowService gameFlowService;

    @BeforeEach
    void setUp() {
        // LockFacade Mocking
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(lockFacade).execute(anyString(), any(Runnable.class));
    }

    @Test
    @DisplayName("전원 카드 선택 시 심판(JudgeService)이 호출된다")
    void selectCard_AllSelected_TriggersJudgment() {
        // given
        String roomId = "room-1";
        String sessionId = "p1";
        Room room = Room.builder()
                .roomId(roomId)
                .currentPhase(GamePhase.CARD_SELECT)
                .build();

        // 테스트 편의상 플레이어 1명만 추가 (1명만 선택하면 전원 선택이 됨)
        Player p1 = Player.builder().sessionId(sessionId).slot(SlotType.SUBJECT).build();
        room.addPlayer(p1);

        when(roomRepository.getRoomIdBySessionId(sessionId)).thenReturn(roomId);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        // when
        gamePlayService.selectCard(sessionId, "선택한카드");

        // then
        assertEquals("선택한카드", p1.getSelectedCard());
        verify(roomRepository).saveRoom(room);
        verify(gameResponseSender).broadcastAllCardsSelected(room);

        // JudgeService 호출 확인
        verify(gameJudgeService).judgeRound(roomId);
    }
}