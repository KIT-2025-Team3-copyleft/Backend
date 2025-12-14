package com.copyleft.GodsChoice.infra.external;

import com.copyleft.GodsChoice.domain.type.GodPersonality;
import com.copyleft.GodsChoice.domain.type.Oracle;
import com.copyleft.GodsChoice.domain.vo.AiJudgment;
import com.copyleft.GodsChoice.game.infra.GroqApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = {
        GroqApiClient.class,
        JacksonAutoConfiguration.class,
        PropertyPlaceholderAutoConfiguration.class
})
@TestPropertySource(properties = {
        "app.groq.api-key=${GROQ_API_KEY}",
        "app.groq.url=https://api.groq.com/openai/v1/chat/completions",
        "app.groq.model=openai/gpt-oss-120b"
})
class GroqApiClientTest {

    @Autowired
    private GroqApiClient groqApiClient;

    @Test
    @DisplayName("DB 없이 Groq API만 호출해서 신탁 재해석 확인하기")
    void testOnlyApi() {
        // Given
        String sentence = "하늘이 나무를 우아하게 심었다";
        GodPersonality personality = GodPersonality.MISCHIEVOUS;
        Oracle oracle = Oracle.LIGHT;

        System.out.println(">>> 요청: " + personality + " 신이 '" + oracle + "' 신탁을 내림. 문장: " + sentence);

        // When
        AiJudgment result = groqApiClient.judgeSentence(sentence, personality, oracle);

        // Then
        System.out.println("<<< 결과 점수: " + result.score());
        System.out.println("<<< 결과 평가: " + result.reason());
    }
}