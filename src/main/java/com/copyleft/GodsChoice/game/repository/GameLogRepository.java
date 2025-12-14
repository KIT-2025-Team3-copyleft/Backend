package com.copyleft.GodsChoice.game.repository;

import com.copyleft.GodsChoice.domain.log.GameLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameLogRepository extends JpaRepository<GameLog, Long> {

}