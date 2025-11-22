package com.copyleft.GodsChoice.domain.type;

import lombok.Getter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Getter
public enum WordData {
    // 1. 주체 (Subject)
    SUBJECT(Arrays.asList("하늘이", "왕이", "개발자가", "고양이가", "배신자가", "마을 사람이")),

    // 2. 대상 (Target)
    TARGET(Arrays.asList("똥을", "황금을", "코드를", "사랑을", "폭탄을", "치킨을")),

    // 3. 어떻게 (How)
    HOW(Arrays.asList("맛있게", "슬프게", "섹시하게", "더럽게", "우아하게", "미친듯이")),

    // 4. 어쩐다 (Action)
    ACTION(Arrays.asList("먹었다", "던졌다", "키스했다", "훔쳤다", "부셨다", "좋아했다"));

    private final List<String> words;

    WordData(List<String> words) {
        this.words = words;
    }

    // 랜덤으로 5장 뽑기
    public static List<String> getRandomCards(SlotType slotType, int count) {
        if (slotType == null) return Collections.emptyList();

        try {
            WordData data = WordData.valueOf(slotType.name());
            List<String> allWords = new java.util.ArrayList<>(data.getWords());
            Collections.shuffle(allWords);
            return allWords.subList(0, Math.min(count, allWords.size()));
        } catch (Exception e) {
            return Arrays.asList("오류", "카드", "없음");
        }
    }
}