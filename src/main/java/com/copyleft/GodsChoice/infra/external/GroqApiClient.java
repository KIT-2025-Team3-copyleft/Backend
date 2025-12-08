package com.copyleft.GodsChoice.infra.external;

import com.copyleft.GodsChoice.domain.vo.AiJudgment;
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
    public AiJudgment judgeSentence(String sentence, String godPersonality) {
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
                GroqResponse.Choice firstChoice = response.getChoices().get(0);
                if (firstChoice != null && firstChoice.getMessage() != null && firstChoice.getMessage().getContent() != null) {
                    String content = firstChoice.getMessage().getContent();
                    log.info("AI 응답 원본: {}", content);
                    return parseContent(content);
                }
                log.warn("Groq API 응답 구조 이상 (content 없음): {}", response);
            }
        } catch (Exception e) {
            log.error("Groq API 호출 실패", e);
        }

        return AiJudgment.fallback();
    }

    private AiJudgment parseContent(String content) {
        try {
            int start = content.indexOf("{");
            int end = content.lastIndexOf("}");
            if (start != -1 && end != -1) {
                String jsonStr = content.substring(start, end + 1);
                JsonNode root = objectMapper.readTree(jsonStr);
                return new AiJudgment(
                        root.path("score").asInt(),
                        root.path("reason").asText()
                );
            }
        } catch (Exception e) {
            log.error("AI 응답 파싱 실패: content={}", content, e);
        }
        return AiJudgment.fallback();
    }
}