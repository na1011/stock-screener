package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 봉의 원값을 수정 축척으로 옮긴다 — 가격은 그대로, <b>거래량은 반대 방향으로</b>.
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
public final class PriceScaling {

    private PriceScaling() {
    }

    /**
     * 그 봉의 원주가 항목({@code high}·{@code low}·{@code open})을 수정 축척으로.
     *
     * <p>{@code close}가 0이면 나눌 수 없다. 실제로는 발생하지 않지만
     * ({@code price_daily}에 0 종가가 들어오면 그것 자체가 수집 결함이다) 배치를 죽이지 않도록
     * 원값을 그대로 쓴다.
     */
    public static BigDecimal adjusted(PriceBar bar, BigDecimal rawValue) {
        if (bar.close().signum() == 0) {
            return rawValue;
        }
        return rawValue.multiply(bar.adjClose())
                .divide(bar.close(), MovingAverages.SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal adjustedHigh(PriceBar bar) {
        return adjusted(bar, bar.high());
    }

    public static BigDecimal adjustedLow(PriceBar bar) {
        return adjusted(bar, bar.low());
    }

    /**
     * 그 봉의 거래량을 수정 축척으로 — {@code volume × close / adjClose}.
     *
     * <p>MUST: 가격과 <b>반대 방향</b>이다. {@link #adjusted}는 {@code adjClose/close}를 곱하고
     * 여기는 그 역수를 곱한다 — 5:1 분할이면 분할 <b>전</b> 거래량이 5배가 되어야 분할 후와
     * 같은 주식 수를 뜻한다. 항등식으로 검산하면 {@code volume ÷ adj_factor}다.
     * 두 함수가 나란히 있어 부호를 베껴 쓰기 쉽고, <b>뒤집혀도 값은 그럴듯하다.</b>
     *
     * <p><b>IMPORTANT — 지금 이 값을 읽는 계산기가 하나도 없다.</b> 거래량을 쓰는 지표가 아직
     * 없어서이며 경고가 아니라 사실이다({@code TURNOVER_60}조차 소스가 준 {@code tradeValue}를
     * 쓴다). <b>첫 소비자는 P12의 주봉 OBV({@code obv_w})다</b> — 분할 후 거래량이 배수만큼
     * 커지고 그것이 OBV <b>누적</b>에 영구히 남아 {@code drv_26 → drv_41 → score_d2}(±3)가
     * 틀린다(ADR-0046 §결과). 그때 이것을 다시 만들지 않도록 P9에서 자리를 만들어 둔다.
     *
     * <p>{@code adj_volume} 컬럼도 {@link PriceBar}의 컴포넌트도 만들지 않는다 —
     * {@code close}·{@code adjClose}로 유도되므로 저장하면 계수와 lockstep으로 지켜야 할
     * 불변식이 하나 늘고, {@code PriceWriter}가 이미 정확히 그 형태의 버그를 냈다(ADR-0049).
     */
    public static BigDecimal adjustedVolume(PriceBar bar) {
        BigDecimal volume = BigDecimal.valueOf(bar.volume());
        if (bar.adjClose() == null || bar.adjClose().signum() == 0) {
            // adjClose = close × adj_factor이고 계수는 양수이므로 이 분기는 종가가 0일 때다.
            // 그 자체가 수집 결함이며(adjusted와 같은 판단) 배치를 죽이지 않도록 원값을 쓴다.
            return volume;
        }
        return volume.multiply(bar.close())
                .divide(bar.adjClose(), MovingAverages.SCALE, RoundingMode.HALF_UP);
    }
}
