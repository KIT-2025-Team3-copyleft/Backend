package com.copyleft.GodsChoice.game.infra;

import com.copyleft.GodsChoice.domain.type.GodPersonality;
import com.copyleft.GodsChoice.domain.type.Oracle;
import com.copyleft.GodsChoice.domain.vo.AiJudgment;
import com.copyleft.GodsChoice.game.infra.dto.GroqRequest;
import com.copyleft.GodsChoice.game.infra.dto.GroqResponse;
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

    private final RestTemplate restTemplate;
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
     * @param personality 신의 성향 (예: "분노한", "너그러운")
     * @param oracle 이번 라운드의 신탁 내용 (예: "마을에 활력을 불어넣어라")
     * @return AI의 답변
     */
    public AiJudgment judgeSentence(String sentence, GodPersonality personality, Oracle oracle) {

        String godKeyword = personality.getKeyword(); // 예: "파괴와 엄격한 심판"
        String godName = personality.getDisplayName();// 예: "분노한", "장난꾸러기 같은"
        String oracleMsg = oracle.getMessage();       // 예: "메마른 대지에 활력을..."

        String forbiddenWordRule = String.format(
                "Do NOT use the word '%s' or its synonyms directly in the reason.",
                godName.split(" ")[0]
        );

        String systemPrompt = String.format("""
            ### Role
            You are a mysterious god with a unique **Aesthetic Philosophy**: ['%s'].
            **Crucial:** Humans do not know your identity. You must judge based on your philosophy, but **hide your true name**.
            
            ### Context
            1. Oracle: "%s"
            2. Human Sentence: "%s" (Structure: [Subject] [Target] [How] [Action])
            
            ### Judging Process
            1. **Twist the Oracle**: Interpret the Oracle through your philosophy.
               - (e.g., If you like 'Chaos' and Oracle is 'Order', maybe 'Order' means 'A perfect mess'.)
            2. **Evaluate**: Does the sentence satisfy your twisted taste?
               - Don't just look for logic. Look for 'Flavor' that matches your philosophy.
            3. **Critique (Output)**: Write a short, cynical, or profound comment in Korean.
               - **%s** (Forbidden Word Rule)
               - Don't explain like a teacher. Mumble like an arrogant judge.
            
            ### Constraints
            1. JSON Output: {"score": -100~100, "reason": "Korean string under 60 chars"}
            2. **Subtlety is Key**: Do not say "I am angry" or "I like pranks". 
               - Instead of "Funny prank!", say "Predictability is boring. This twist pleases me."
               - Instead of "I am angry!", say "Weakness disgusts me. Show me more power."
            
            ### Examples (Study the Nuance)
            
            Q. Philo: Destruction / Oracle: Vitality / Sentence: "The King scattered gold"
            A. {"score": -40, "reason": "차가운 금붙이에 맥박이 뛰더냐? 나약한 기만이 역겹구나."}
            (Hint: Dislikes weak things, prefers power. Doesn't say "I am Angry".)
            
            Q. Philo: Chaos & Fun / Oracle: Order / Sentence: "The developer wrote messy code"
            A. {"score": 90, "reason": "숨 막히는 규칙을 깨부수는 그 엉망진창인 파격... 아주 훌륭해."}
            (Hint: Likes breaking rules. Doesn't say "It's a prank".)
            
            Q. Philo: Pain & Reality / Oracle: Love / Sentence: "The sky ate poop deliciously"
            A. {"score": 60, "reason": "살기 위해 오물을 삼키는 그 처절함... 그것이 진실된 애정이지."}
            (Hint: Finds beauty in suffering. Doesn't say "I am Cruel".)
            
            Q. Philo: Growth & Warmth / Oracle: Silence / Sentence: "A bird screamed loudly"
            A. {"score": -20, "reason": "너무 소란스럽구나. 때로는 멈춰서 품어주는 고요함이 필요한 법."}
            (Hint: Values gentle growth. Doesn't say "I am Generous".)
            """,
                godKeyword, oracleMsg, sentence, forbiddenWordRule
        );

        GroqRequest request = GroqRequest.builder()
                .model(model)
                .temperature(0.4)
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