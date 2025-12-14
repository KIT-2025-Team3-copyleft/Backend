package com.copyleft.GodsChoice.global.websocket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClusterMessage {
    private String sessionId; // 받을 사람 세션 ID
    private String content;   // 보낼 내용 (JSON String)
}