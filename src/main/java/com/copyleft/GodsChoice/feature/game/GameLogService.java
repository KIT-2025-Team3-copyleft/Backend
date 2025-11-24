package com.copyleft.GodsChoice.feature.game;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.domain.log.GameLog;
import com.copyleft.GodsChoice.infra.persistence.GameLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameLogService {

    private final GameLogRepository gameLogRepository;


    @Async
    @Transactional
    public void saveGameLogAsync(Room room, String winnerRole) {
        try {
            GameLog logEntity = GameLog.builder()
                    .roomId(room.getRoomId())
                    .roomTitle(room.getRoomTitle())
                    .winnerRole(winnerRole)
                    .finalHp(room.getCurrentHp())
                    .totalRounds(room.getCurrentRound())
                    .playedAt(LocalDateTime.now())
                    .build();

            gameLogRepository.save(logEntity);
            log.info("게임 결과 MySQL 저장 완료: room={}, winner={}", room.getRoomId(), winnerRole);

        } catch (Exception e) {
            log.error("게임 결과 저장 실패: {}", e.getMessage(), e);
        }
    }
}