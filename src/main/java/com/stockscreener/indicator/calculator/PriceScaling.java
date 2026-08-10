package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 원주가 항목을 수정 축척으로 옮긴다.
 *
 * <p>IMPORTANT — {@code price_daily}는 <b>{@code adj_close}만</b> 수정해 저장한다(ADR-0002).
 * 고가·저가·시가는 원주가다. 둘을 그대로 섞으면 액면분할 구간에서 "고가 − 종가"나
 * "52주 최고가 대비"가 분할비율만큼 튀는데, 그 값은 그럴듯해서 분할이 일어나기 전까지
 * 아무도 눈치채지 못한다.
 *
 * <p>같은 봉의 {@code adjClose / close} 비율을 곱해 축척을 맞춘다. 이 계산이
 * {@code Atr14Calculator}·52주 고저에 모두 필요해 한 곳에 둔다 — 각자 구현하면 반올림이
 * 어긋나기 시작하는데 그 어긋남도 값이 그럴듯해 드러나지 않는다.
 */
final class PriceScaling {

    private PriceScaling() {
    }

    /**
     * 그 봉의 원주가 항목({@code high}·{@code low}·{@code open})을 수정 축척으로.
     *
     * <p>{@code close}가 0이면 나눌 수 없다. 실제로는 발생하지 않지만
     * ({@code price_daily}에 0 종가가 들어오면 그것 자체가 수집 결함이다) 배치를 죽이지 않도록
     * 원값을 그대로 쓴다.
     */
    static BigDecimal adjusted(PriceBar bar, BigDecimal rawValue) {
        if (bar.close().signum() == 0) {
            return rawValue;
        }
        return rawValue.multiply(bar.adjClose())
                .divide(bar.close(), MovingAverages.SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal adjustedHigh(PriceBar bar) {
        return adjusted(bar, bar.high());
    }

    static BigDecimal adjustedLow(PriceBar bar) {
        return adjusted(bar, bar.low());
    }
}
