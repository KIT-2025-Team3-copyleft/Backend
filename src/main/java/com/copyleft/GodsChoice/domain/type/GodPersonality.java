package com.copyleft.GodsChoice.domain.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GodPersonality {

    ANGRY("분노한", "파괴와 엄격한 심판"),
    GENEROUS("너그러운", "포용과 생명의 성장"),
    WHIMSICAL("변덕스러운", "혼돈과 예측불가능한 유희"),
    CRUEL("잔혹한", "고통과 철저한 현실"),
    MISCHIEVOUS("장난꾸러기 같은", "유쾌한 장난과 풍자"),
    EDGY("흑염룡이 날뛰는", "심연의 어둠, 금지된 힘, 고독, 그리고 허세"),
    BOOMER("라떼는 말이야", "전통, 예절, 엄격한 위계질서, 그리고 훈계"),
    SOFTIE("상처를 잘 받는", "유리멘탈, 극도의 소심함, 평화주의, 작은 것에도 놀람, 쭈글거림");

    private final String displayName;   // 배신자에게 보여줄 이름
    private final String keyword;       // AI에게 줄 가치관
}