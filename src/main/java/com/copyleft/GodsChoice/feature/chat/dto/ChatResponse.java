package com.copyleft.GodsChoice.feature.chat.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatResponse {
    private String sender;
    private String color;
    private String content;
    private String formattedMessage; // "닉네임[RED] : 내용" 형태
}