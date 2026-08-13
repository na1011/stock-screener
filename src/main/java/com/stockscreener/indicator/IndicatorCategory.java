package com.stockscreener.indicator;

/**
 * 지표 분류. 조건 빌더와 종목 상세의 구획 기준이다.
 *
 * <p>{@code indicator.category} 컬럼의 CHECK 제약과 값이 일치해야 한다(V2 → V24 마이그레이션).
 *
 * <p>MUST NOT: 값을 빼지 않는다(ADR-0057). {@code IndicatorCatalog}의 RowMapper가
 * {@code valueOf}를 그대로 부르고 {@code describe()}는 {@code is_active}로 거르지도 않으므로,
 * DB에 그 문자열이 <b>비활성 행으로라도</b> 하나 남으면 카탈로그 조회가 통째로 터진다.
 * 더하는 것은 안전하고 빼는 것은 안전하지 않다.
 */
public enum IndicatorCategory {
    /** 시세에서 계산하는 추세·변동성 지표(이동평균, RSI, ATR). */
    TECHNICAL,
    /** 재무제표에서 계산하는 재무 지표와 그 백분위·업종 상대값. */
    FUNDAMENTAL,
    /** 가격 자체와 규모(종가, 시가총액, 거래대금). */
    PRICE,
    /**
     * 진동하는 값으로 과열·침체를 재는 지표(MACD, 스토캐스틱, OBV, 일목 구름의 정량 조건).
     *
     * <p>기준선을 두고 위아래를 오가는 것이 {@link #TECHNICAL}과 갈리는 지점이다.
     */
    OSCILLATOR,
    /**
     * 지표 <b>둘 이상의 관계</b>에서 나오는 교차·위치 신호(골든크로스, 전환선 대 기준선).
     *
     * <p>ADR-0057에서 뜻이 바뀌었다 — 예전에는 "다른 지표에서 파생된 것(백분위 등)"이었고
     * 백분위·업종 상대값이 전부 여기 있었다. 지금 그것들은 <b>원본 지표의 분류를 따라간다.</b>
     * 사용자에게 "PER 백분위"는 파생 계산이 아니라 재무 지표이기 때문이다.
     */
    DERIVED
}
