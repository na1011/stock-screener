package com.stockscreener.screening.store;

import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.EvaluationMode;

/**
 * 저장된 스크리닝 정의. 프리셋과 커스텀 화면이 <b>같은 타입</b>이다.
 *
 * <p>{@code conditions}가 별도의 저장용 모델이 아니라 도메인 {@link ConditionGroup} 그대로인
 * 것이 요점이다. 저장용 조건 타입을 따로 두면 지표를 하나 추가할 때 고칠 곳이 둘이 되고,
 * 두 모델이 어긋나기 시작한다({@code backend.md}가 금지한 것이다).
 *
 * @param id            저장 전이면 null
 * @param code          프리셋만 갖는 안정적 식별자. 커스텀은 null이다.
 * @param ownerUserId   커스텀만 갖는다. 프리셋은 null이다. ({@code ck_screen_ownership})
 * @param universeCode  null이면 전체 유니버스
 * @param conditions    루트 조건 그룹. v1은 깊이 1·AND만이며 그 제약은 {@link ScreenDefinitionValidator}에 있다.
 */
public record ScreenDefinition(
        Long id,
        String code,
        String name,
        String description,
        ScreenType type,
        EvaluationMode mode,
        Long ownerUserId,
        String universeCode,
        boolean active,
        ConditionGroup conditions
) {

    public ScreenDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("화면 이름은 비어 있을 수 없다");
        }
        if (type == null) {
            throw new IllegalArgumentException("화면 종류는 null일 수 없다");
        }
        if (mode == null) {
            throw new IllegalArgumentException("판정 방식은 null일 수 없다");
        }
        // DB의 ck_screen_ownership과 같은 불변식을 여기서도 막는다. 제약에만 맡기면
        // 위반이 500(DataIntegrityViolation)으로 나가고, 그것은 사용자 입력 문제처럼 보이지 않는다.
        if (type == ScreenType.PRESET && (ownerUserId != null || code == null)) {
            throw new IllegalArgumentException("프리셋은 소유자가 없고 code가 있어야 한다");
        }
        if (type == ScreenType.CUSTOM && ownerUserId == null) {
            throw new IllegalArgumentException("커스텀 화면에는 소유자가 필요하다");
        }
    }

    public boolean isPreset() {
        return type == ScreenType.PRESET;
    }

    /** 사용자가 만든 화면. 소유자만 읽고 쓸 수 있다. */
    public static ScreenDefinition custom(Long id, String name, String description, EvaluationMode mode,
                                          long ownerUserId, String universeCode, ConditionGroup conditions) {
        return new ScreenDefinition(id, null, name, description, ScreenType.CUSTOM, mode,
                ownerUserId, universeCode, true, conditions);
    }

    /** 코드에서 선언되는 프리셋. */
    public static ScreenDefinition preset(String code, String name, String description, EvaluationMode mode,
                                          String universeCode, ConditionGroup conditions) {
        return new ScreenDefinition(null, code, name, description, ScreenType.PRESET, mode,
                null, universeCode, true, conditions);
    }
}
