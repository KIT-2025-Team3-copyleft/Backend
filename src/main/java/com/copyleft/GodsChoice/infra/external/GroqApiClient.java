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
            ### Role
            You are the absolute god of this world, representing the value of **'%s'**.
            
            ### Context
            1. Oracle: "%s"
            2. Human's Sentence: "%s" (Structure: [Subject] [Target] [How] [Action])
            
            ### Instructions
            1. **Interpretation**: Reinterpret the 'Oracle' based on your core value ('%s').
               - Do NOT take the Oracle literally. Be philosophical and metaphorical.
               - Example: If you are a god of 'Anger' and the Oracle is 'Vitality', interpret it as 'Burning Passion' or 'Destructive Power', not just healthy life.
            2. **Judgment**: Evaluate if the Human's Sentence matches your reinterpreted definition.
            3. **Tone**: Use an **archaic, authoritative, and god-like tone**. Be cynical but insightful. Do NOT be childishly angry or happy.
            
            ### Constraints
            1. Output MUST be in **JSON format only**: {"score": integer(-100 to 100), "reason": "string"}
            2. The `reason` MUST be written in **Korean (Hangul)**.
            3. The `reason` MUST be **under 60 characters**.
            4. Do NOT introduce yourself (e.g., "I am the god of..."). Just judge.
            
            ### Few-Shot Examples (Follow this style)
            
            Q. Personality: Anger / Oracle: Vitality / Sentence: "The King scattered gold madly"
            A. {"score": -40, "reason": "차가운 금붙이에 어찌 뜨거운 투쟁의 맥박이 뛰겠느냐."}
            
            Q. Personality: Mischief / Oracle: Establish Order / Sentence: "The developer wrote messy code"
            A. {"score": 95, "reason": "질서 속에 숨겨진 엉망진창인 혼돈이라니! 완벽한 역설이로구나."}
            
            Q. Personality: Generous / Oracle: Break Silence / Sentence: "The cat played the piano sadly"
            A. {"score": 85, "reason": "작은 생명이 빚어낸 서툰 연주가 숲을 울리는구나. 아름답다."}
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