package com.copyleft.GodsChoice.infra.websocket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSocketResponse<T> {
    private String event;   // 이벤트 타입 (SocketEvent.name())
    private String message; // 사용자에게 보여줄 메시지
    private String code;    // 에러 코드 or 상태 코드

    private T data;         // 실제 페이로드 (제네릭)
}