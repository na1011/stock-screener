package com.stockscreener.indicator.calculator.ichimoku;

import java.time.LocalDate;

/**
 * 주봉 하나. <b>전부 수정 축척</b>이다.
 *
 * <p>IMPORTANT — 가격은 {@code PriceScaling.adjusted}로, 거래량은 {@code adjustedVolume}으로
 * 옮긴 값이다. TradingView 차트는 기본이 분할 보정이라 원본 Pine의 {@code close}·{@code high}는
 * 이미 보정된 값이고, 우리 {@code price_daily}는 {@code adj_close}만 보정돼 있다. 원값을 섞으면
 * 5:1 분할이 −80% 폭락으로 보여 <b>3역호전과 피벗이 통째로 무너지는데 값은 그럴듯하다.</b>
 *
 * <p>{@code double}인 이유는 원본이 float64이고 {@code math.round}가 ties-round-up이기 때문이다
 * (ADR-0046). {@code BigDecimal}로 바꾸면 {@code score_d2}의 폴백 가지에서 1점이 갈린다.
 *
 * @param endDate 그 주에 실제로 거래가 있었던 <b>마지막 날</b>. 진행 중인 주에서는 그 시점까지다
 * @param days    그 주에 담긴 일봉 수. 진행 중인 주를 판별하는 데는 쓰지 않는다 —
 *                휴장이 섞이면 완결된 주도 5일이 아니기 때문이다
 */
record WeeklyBar(
        LocalDate endDate,
        double open,
        double high,
        double low,
        double close,
        double volume,
        int days
) {
}
