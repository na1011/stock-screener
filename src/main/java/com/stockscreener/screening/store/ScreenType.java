package com.stockscreener.screening.store;

/**
 * 화면을 누가 소유하는가.
 *
 * <p>{@code EvaluationMode}(어떻게 판정하는가)와 <b>직교</b>한다. 오늘은 프리셋이 점수,
 * 커스텀이 필터라는 상관관계가 있지만 그것은 spec이 서술한 경향이지 제약이 아니다
 * (V3의 주석 참조). 사용자가 자기 조건에 가중치를 주고 싶어하는 순간이 오고,
 * 그때 두 축이 합쳐져 있으면 컬럼을 추가하고 백필해야 한다.
 */
public enum ScreenType {

    /** 코드에서 선언되어 DB로 투영된다. 소유자가 없고 모두에게 읽기 전용이다. */
    PRESET,

    /** 사용자가 만든다. 소유자가 반드시 있다. */
    CUSTOM
}
