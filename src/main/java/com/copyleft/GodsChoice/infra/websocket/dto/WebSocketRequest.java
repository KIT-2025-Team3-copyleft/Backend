package com.copyleft.GodsChoice.infra.websocket.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

@Getter
public class WebSocketRequest {

    private String action;

    private JsonNode payload;
}