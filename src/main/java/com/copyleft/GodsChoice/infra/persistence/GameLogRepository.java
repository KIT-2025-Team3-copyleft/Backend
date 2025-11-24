package com.copyleft.GodsChoice.infra.persistence;

import com.copyleft.GodsChoice.domain.log.GameLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameLogRepository extends JpaRepository<GameLog, Long> {

}