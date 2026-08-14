package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalDouble;

/**
 * 시장 지수 대비 초과 수익률. 원본 {@code DRV-55}이고 T-08(2년 주간 베타)도 같은 값을 쓴다.
 *
 * <p>공식이 여기 <b>한 곳</b>에 있는 이유는 소비자가 둘이기 때문이다 —
 * {@code EXCESS_RETURN_26W} 계산기와 일목 분석의 D5 점수. 각자 구현하면 어긋나기 시작하는데
 * <b>두 값 다 그럴듯해서 드러나지 않는다.</b>
 */
public final class ExcessReturn {

    /** 원본 주석대로 130 거래일 ≈ 26주다. */
    public static final int LOOKBACK = 130;

    /** 252 거래일 ≈ 52주. 검증된 모멘텀 문헌이 쓰는 기간이다. */
    public static final int LOOKBACK_52W = 252;

    private ExcessReturn() {
    }

    /**
     * {@code 종목 130거래일 수익률 − 지수 같은 구간 수익률}(%).
     *
     * <p>MUST: 대상 일자의 지수를 <b>정확 일치</b>로 묻는다. 직전 값을 집으면 그날 지수가 없어도
     * 값이 나오고, D5가 <b>상대 수익률에서 절대 수익률로 조용히 바뀐다</b>
     * (ADR-0046 §승인된 이탈 2). 130봉 전만 {@code floorEntry}를 쓰며, 그것은 종목의 그 날짜가
     * 지수의 휴장일과 어긋날 수 있기 때문이고 Pine의 forward-fill과 같은 모양이다.
     *
     * <p>MUST: 지수 시계열은 <b>이 종목이 속한 시장</b>의 것이다. 러너가 골라서 넘긴다
     * (ADR-0053 §이탈 4) — 원본은 통화만 보고 코스피 하나로 접지만 그것은 판단이 아니라
     * Pine의 한계다.
     *
     * @param index 값을 낼 수 없으면 비어 있음 — 워밍업 부족, 지수 결손, 0 종가
     */
    public static OptionalDouble at(List<PriceBar> bars,
                                    NavigableMap<LocalDate, BigDecimal> index, int i) {
        return at(bars, index, i, LOOKBACK);
    }

    /**
     * 기간을 지정하는 판. 위 설명이 그대로 적용되며 되돌아보는 거리만 다르다.
     *
     * <p>MUST NOT: 기간별로 이 공식을 다시 짜지 않는다. 정확 일치와 {@code floorEntry}를
     * 나눠 쓰는 규칙이 한쪽에만 반영되면 <b>두 기간의 값이 서로 다른 것을 재면서 둘 다
     * 그럴듯하다.</b>
     */
    public static OptionalDouble at(List<PriceBar> bars,
                                    NavigableMap<LocalDate, BigDecimal> index, int i,
                                    int lookback) {
        int back = i - lookback;
        if (back < 0 || index == null || index.isEmpty()) {
            return OptionalDouble.empty();
        }
        BigDecimal now = index.get(bars.get(i).tradeDate());
        Map.Entry<LocalDate, BigDecimal> past = index.floorEntry(bars.get(back).tradeDate());
        if (now == null || past == null) {
            return OptionalDouble.empty();
        }
        double indexNow = now.doubleValue();
        double indexPast = past.getValue().doubleValue();
        double priceNow = bars.get(i).adjClose().doubleValue();
        double pricePast = bars.get(back).adjClose().doubleValue();
        if (indexPast == 0 || pricePast == 0) {
            return OptionalDouble.empty();
        }
        // MUST: 수정종가끼리 견준다. 원주가를 쓰면 5:1 분할이 −80%가 되고, 그 값이
        // score_d5에 −ext_base로 붙는다 — 에러 없이.
        double stockReturn = (priceNow / pricePast - 1) * 100.0;
        double indexReturn = (indexNow / indexPast - 1) * 100.0;
        return OptionalDouble.of(stockReturn - indexReturn);
    }
}
