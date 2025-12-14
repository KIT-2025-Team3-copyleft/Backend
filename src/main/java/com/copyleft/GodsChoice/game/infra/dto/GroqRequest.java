package com.copyleft.GodsChoice.game.infra.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class GroqRequest {
    private String model;
    private List<Message> messages;

    @Builder.Default
    private Double temperature = 0.5;

    @Getter
    @Builder
    public static class Message {
        private String role;
        private String content;
    }
}