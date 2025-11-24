package com.copyleft.GodsChoice.domain.log;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "game_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GameLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String roomId;

    private String roomTitle;

    @Column(nullable = false)
    private String winnerRole; // "CITIZEN" or "TRAITOR"

    private int finalHp;
    private int totalRounds;

    private LocalDateTime playedAt;
}