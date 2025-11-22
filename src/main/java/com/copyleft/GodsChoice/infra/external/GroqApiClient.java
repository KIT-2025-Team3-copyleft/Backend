package com.copyleft.GodsChoice.infra.external;

import com.copyleft.GodsChoice.infra.external.dto.GroqRequest;
import com.copyleft.GodsChoice.infra.external.dto.GroqResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class GroqApiClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    @Value("${app.groq.api-key}")
    private String apiKey;

    @Value("${app.groq.url}")
    private String apiUrl;

    @Value("${app.groq.model}")
    private String model;

    /**
     * AI에게 문장 심판을 요청합니다.
     * @param sentence 플레이어들이 조합한 문장
     * @param godPersonality 신의 성향 (예: "분노한", "너그러운")
     * @return AI의 답변 (JSON 문자열 -> 파싱 필요)
     */
    public String judgeSentence(String sentence, String godPersonality) {
        String systemPrompt = String.format(
                "당신은 '%s' 성향을 가진 신입니다. 인간들이 바친 문장: \"%s\" 을 평가하세요. " +
                        "응답은 오직 JSON 형식으로만 해야 합니다. " +
                        "형식: {\"score\": -100~100사이정수, \"reason\": \"한줄평\"}",
                godPersonality, sentence
        );

        GroqRequest request = GroqRequest.builder()
                .model(model)
                .messages(java.util.List.of(
                        GroqRequest.Message.builder().role("system").content("You are a creative game master. Answer in JSON format.").build(),
                        GroqRequest.Message.builder().role("user").content(systemPrompt).build()
                ))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            HttpEntity<GroqRequest> entity = new HttpEntity<>(request, headers);
            GroqResponse response = restTemplate.postForObject(apiUrl, entity, GroqResponse.class);

            if (response != null && response.getChoices() != null && !response.getChoices().isEmpty()) {
                String content = response.getChoices().get(0).getMessage().getContent();
                log.info("AI 응답 원본: {}", content);
                return extractJson(content);
            }
        } catch (Exception e) {
            log.error("Groq API 호출 실패: {}", e.getMessage());
            return "{\"score\": 0, \"reason\": \"신이 침묵합니다.\"}";
        }

        return "{\"score\": 0, \"reason\": \"신이 응답하지 않습니다.\"}";
    }

    private String extractJson(String content) {
        int start = content.indexOf("{");
        int end = content.lastIndexOf("}");
        if (start != -1 && end != -1) {
            return content.substring(start, end + 1);
        }
        return content;
    }
}