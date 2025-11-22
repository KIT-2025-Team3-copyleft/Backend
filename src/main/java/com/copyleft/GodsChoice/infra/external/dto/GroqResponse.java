package com.copyleft.GodsChoice.infra.external.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@ToString
public class GroqResponse {
    private List<Choice> choices;

    @Getter
    @NoArgsConstructor
    @ToString
    public static class Choice {
        private Message message;
    }

    @Getter
    @NoArgsConstructor
    @ToString
    public static class Message {
        private String role;
        private String content;
    }
}