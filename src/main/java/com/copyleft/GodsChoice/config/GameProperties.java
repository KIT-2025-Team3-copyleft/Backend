package com.copyleft.GodsChoice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "game.rule")
public record GameProperties(
        // 시간 설정 (초 단위)
        int startDelay,       // 게임 시작 대기
        int loadingTimeout,   // 게임씬 로딩 기다려주는 시간
        int oraclePhase,      // 신탁 확인 시간
        int cardSendDelay,    // 카드 전송 딜레이
        int cardSelectTime,   // 카드 선택 제한 시간
        int judgeDelay,       // 전원 카드 선택 완료 -> 결과 공개까지 시간
        int voteProposalTime, // 찬반 투표 시간
        int trialTime,        // 심판 투표 시간
        int roundResultDuration, // 라운드 결과 보여주는 시간
        int nextRoundDelay,      // 다음 라운드 진입 대기
        int voteFailDelay,       // 투표 부결 시 대기
        int gameOverCleanupTime, // 게임 종료 후 방 청소 대기

        // 게임 룰 설정
        int cardCount,        // 카드 선택 개수
        int maxRounds,        // 총 라운드 수 (기본 4)
        int minInitialHp,     // 초기 최소 hp
        int maxInitialHp,     // 초기 최대 hp
        int maxPlayerCount,      // 최대 인원 (기존 4명)

        // 닉네임 설정
        int nicknameMinLength,   // (기존 2자)
        int nicknameMaxLength,   // (기존 6자)

        // 점수 및 페이즈 관련
        int trialStartPenalty, // 이단 심문 시작 시 페널티
        int traitorCatchReward, // 배신자 검거 성공 보상
        int citizenFailPenalty  // 배신자 검거 실패 페널티
) {}