package com.stockscreener.screening.preset;

import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.EvaluationMode;

/**
 * 코드에서 선언하는 사전 정의 전략. 기동 시 {@code screen} 테이블로 투영된다.
 *
 * <p>프리셋을 DB가 아니라 코드에 두는 이유는 spec §3이 "사용자가 별도 조건 설정 없이 바로
 * 실행"할 수 있어야 한다고 요구하기 때문이다 — 즉 프리셋은 <b>제품의 일부</b>이지 데이터가
 * 아니다. 코드에 두면 조건 변경이 diff에 남고 리뷰를 거치며 테스트가 검증한다.
 *
 * <p>그럼에도 DB로 투영하는 이유는 실행 경로를 하나로 두기 위해서다. 프리셋을 메모리에서만
 * 다루면 "프리셋 실행"과 "저장된 화면 실행"이 다른 코드가 되고, 지표를 추가할 때 고칠 곳이
 * 둘이 된다({@code backend.md}가 금지한 것이다).
 *
 * @param code        안정적 식별자. 재시딩의 키이고 API 요청이 이것으로 프리셋을 가리킨다.
 * @param universeCode null이면 전체. 실행 요청이 덮어쓸 수 있다.
 */
public record ScreenPreset(
        String code,
        String name,
        String description,
        EvaluationMode mode,
        String universeCode,
        ConditionGroup conditions
) {

    public ScreenPreset {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("프리셋 코드는 비어 있을 수 없다");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("프리셋 이름은 비어 있을 수 없다: " + code);
        }
        if (mode == null) {
            throw new IllegalArgumentException("판정 방식은 null일 수 없다: " + code);
        }
        if (conditions == null) {
            throw new IllegalArgumentException("조건이 없는 프리셋은 만들 수 없다: " + code);
        }
    }
}
