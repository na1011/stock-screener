package com.stockscreener.indicator;

/**
 * 지표 분류. 조건 빌더 UI의 그룹핑 기준이다.
 *
 * <p>{@code indicator.category} 컬럼의 CHECK 제약과 값이 일치해야 한다(V2 마이그레이션).
 */
public enum IndicatorCategory {
    /** 시세에서 계산하는 기술적 지표. */
    TECHNICAL,
    /** 재무제표에서 계산하는 재무 지표. */
    FUNDAMENTAL,
    /** 가격 자체(종가, 시가총액 등). */
    PRICE,
    /** 다른 지표에서 파생된 것(백분위 등). */
    DERIVED
}
