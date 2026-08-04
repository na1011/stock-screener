package com.stockscreener.indicator;

/**
 * 지표 값의 성격. 화면 포맷팅과 시장 간 비교 가능 여부를 결정한다.
 *
 * <p>IMPORTANT — {@link #CURRENCY}와 {@link #PRICE}는 통화가 달라 한국·미국 종목 간
 * 직접 비교가 불가능하다(시가총액 5조원과 500억달러). 그런 지표를 시장 간 비교에 쓰려면
 * 백분위({@link IndicatorCategory#DERIVED})로 변환해야 한다. 반면 {@link #RATIO}와
 * {@link #PERCENT}는 무차원이라 그대로 비교할 수 있다.
 *
 * <p>{@code indicator.value_type} 컬럼의 CHECK 제약과 값이 일치해야 한다(V2 마이그레이션).
 */
public enum IndicatorValueType {
    /** 배수·비율. PER, PBR 등. 무차원이라 시장 간 비교 가능. */
    RATIO,
    /** 백분율. ROE, 성장률 등. 무차원이라 시장 간 비교 가능. */
    PERCENT,
    /** 통화 금액. 시가총액, 매출 등. 시장 간 직접 비교 불가. */
    CURRENCY,
    /** 개수. 거래량 등. */
    COUNT,
    /** 주가. 시장 간 직접 비교 불가. */
    PRICE
}
