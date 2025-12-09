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
     * @param oracle 이번 라운드의 신탁 내용 (예: "마을에 활력을 불어넣어라")
     * @return AI의 답변
     */
    public AiJudgment judgeSentence(String sentence, String godPersonality, String oracle) {
        String systemPrompt = String.format("""
                당신은 '%s' 성향을 가진 신입니다.
                
                [상황]
                1. 당신이 내린 신탁(Oracle): "%s"
                2. 인간들이 바친 문장(Sentence): "%s"
                
                [지시사항]
                1. 당신의 성향('%s')에 입각하여 신탁을 재해석하십시오.
                   (예: '잔혹한' 신에게 '활력'이란 '파괴의 불꽃'일 수 있고, '장난꾸러기' 신에게는 '우스꽝스러운 소동'일 수 있습니다.)
                2. 인간의 문장이 당신의 해석에 부합하거나, 당신의 기분을 만족시켰는지 판단하여 점수(-100 ~ 100)를 매기세요.
                3. 'reason' 필드에는 당신의 성향이 드러나는 '말투'로 한 줄 평가를 남기세요.
                
                [제약사항]
                - 응답은 오직 JSON 형식이어야 합니다: {"score": 정수, "reason": "문자열"}
                - 'reason'은 반드시 한글로 작성하세요(한자 사용 금지).
                - 'reason'은 공백 포함 50자 이내로 짧고 강렬하게 작성하세요.
                """,
                godPersonality, oracle, sentence, godPersonality
        );

        GroqRequest request = GroqRequest.builder()
                .model(model)
                .messages(java.util.List.of(
                        GroqRequest.Message.builder()
                                .role("system")
                                .content("You are a god in a game. Act according to your personality. Response strictly in JSON format.")
                                .build(),
                        GroqRequest.Message.builder()
                                .role("user")
                                .content(systemPrompt)
                                .build()
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