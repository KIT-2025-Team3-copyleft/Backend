package com.copyleft.GodsChoice.domain.type;

import lombok.Getter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Getter
public enum WordData {
    // 1. 주체 (Subject)
    SUBJECT(Arrays.asList(
            "하늘이", "왕이", "개발자가", "고양이가", "이단자가", "마을 사람이",
            "거지가", "악마가", "천사가", "각설이가", "탐관오리가", "AI가",
            "외계인이", "유령이", "바다가", "민초단이", "노예가", "괴물이"
    )),

    // 2. 대상 (Target)
    TARGET(Arrays.asList(
            "똥을", "황금을", "코드를", "사랑을", "폭탄을", "치킨을",
            "흑역사를", "쓰레기를", "비명을", "거짓말을", "꿈을", "미래를",
            "폭력을", "평화를", "해골을", "팬티를", "영혼을", "약속을", "뱃살을"
    )),

    // 3. 어떻게 (How)
    HOW(Arrays.asList(
            "맛있게", "슬프게", "섹시하게", "더럽게", "우아하게", "미친듯이",
            "잔인하게", "멍청하게", "상큼하게", "음흉하게", "비겁하게", "성스럽게",
            "느끼하게", "무자비하게", "은밀하게", "처절하게", "건방지게", "수줍게", "요염하게"
    )),

    // 4. 어쩐다 (Action)
    ACTION(Arrays.asList(
            "먹었다", "던졌다", "키스했다", "훔쳤다", "부셨다", "좋아했다",
            "숭배했다", "살해했다", "창조했다", "저주했다", "비웃었다", "찬양했다",
            "요리했다", "판매했다", "전시했다", "폭파했다", "핥았다", "숨겼다"
    ));

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