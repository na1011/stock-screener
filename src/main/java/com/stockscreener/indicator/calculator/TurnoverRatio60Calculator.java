package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;
import com.stockscreener.indicator.PriceBar;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 60일 평균 회전율 — <b>두 시장이 같은 식으로 내는 유동성 축</b>이다.
 *
 * <p><b>왜 거래대금이 아닌가.</b> {@code TURNOVER_60}은 원화 금액이고 그 임계가 국내 분포에서
 * 나왔다. 실측으로 <b>같은 100억원이 국내에서는 통과율 67.7%(진짜 게이트)인데 미국에서는
 * 98.9%(게이트가 아니다)</b>이고, 50억원도 85.1% 대 99.3%다. 게다가 미국은 소스가
 * {@code trade_value}를 안 줘서 그 지표가 <b>원리적으로 0행</b>이다 — 프리셋 둘이 미국에서
 * 영구히 0건이었다. {@code close × volume}으로 메우는 것은 V1 스키마의 MUST NOT이고,
 * 재보니 계통 <b>+13.6%</b> 과대라(5거래일 · 9종목 · 45 종목일 전부 양수) 평균으로도 안 사라진다.
 *
 * <p><b>회전율은 두 소스가 다 주는 것으로 낸다</b> — 거래량은 어디서나 오고, 주식수는 국내가
 * 봉에서·미국이 재무에서 온다({@code FinancialMath.sharesOf}). <b>합성이 없다.</b>
 *
 * <p><b>임계의 출처는 문헌이다.</b> 버핏·린치 방법론 문서가 둘 다
 * <i>「유동성 | Market data → Average Volume (30D) | <b>하한 설정</b> | 거래 불가 종목 유입 차단」</i>
 * 으로 적는다 — <b>조건과 목적은 문헌이 주고 수는 명시적으로 구현에 맡긴다.</b>
 * 그래서 이 지표의 임계는 「거래 불가 종목을 자른다」에서 나오지 <b>분위에서 나오지 않는다</b>
 * (ADR-0072가 백분위를 하한 게이트로 쓰는 것을 기각했다).
 *
 * <p><b>MUST NOT: {@link PriceScaling#adjustedVolume}을 쓰지 않는다.</b> 분자를 분할 후 단위로
 * 올리면 분모({@code sharesOf})와 축척이 어긋나 <b>분할 이전 구간의 회전율이 사건 배수만큼
 * 커진다.</b> 비율은 애초에 축척에 불변이다 — {@code TurnoverRatioCalculator}와 같은 이유다.
 *
 * <p><b>주식수가 없는 봉은 창에서 뺀다</b>(합계·개수 양쪽에서). 0으로 두면 그날의 회전율이
 * 무한이 되고, 평균에 넣으면 그 하루가 창 전체를 지배한다.
 */
@Component
@IndicatorMeta(
        name = "60일 평균 회전율",
        description = "최근 60거래일 동안 하루에 손바뀜한 주식이 발행주식 총수의 몇 %였는지의 평균. "
                + "거래대금과 달리 통화가 없어 시장이 달라도 같은 뜻으로 읽힌다.",
        interpretation = "낮을수록 거래가 얇다는 뜻이고, 너무 얇으면 원하는 때에 사고팔지 못한다. "
                + "회사 규모로 나눈 값이라 대형주와 소형주를 나란히 견줄 수 있다.",
        category = IndicatorCategory.PRICE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        // 활발한 것이 좋다고 말할 수 없다. 유동성은 조건의 재료이지 우열이 아니다.
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 3,
        sortOrder = 7,
        bands = {
                @IndicatorMeta.Band(label = "매우 활발", from = 2),
                @IndicatorMeta.Band(label = "활발", from = 1, to = 2),
                @IndicatorMeta.Band(label = "보통", from = 0.3, to = 1),
                @IndicatorMeta.Band(label = "얇음", to = 0.3)
        })
public class TurnoverRatio60Calculator implements IndicatorCalculator {

    public static final String CODE = "TURNOVER_RATIO_60";

    /** {@code TURNOVER_60}과 같은 창을 쓴다 — 갈아끼우는 것이지 다른 것을 재는 것이 아니다. */
    static final int WINDOW = Turnover60Calculator.WINDOW;

    private static final int SCALE = 6;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // MUST NOT: 재무를 선언하지 않는다 — 사유는 MarketCapCalculator와 같다(ADR-0097).
        // 국내는 재무 없이 값을 내고, 선언하면 재무 0행 종목에서 옛 행이 영영 안 걷힌다.
        return DataRequirement.priceBars(WINDOW);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() < WINDOW) {
            return List.of();
        }
        // 봉마다 그날의 회전율을 먼저 낸다. 주식수가 없는 날은 null로 두고 창에서 뺀다.
        BigDecimal[] daily = new BigDecimal[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            PriceBar bar = bars.get(i);
            BigDecimal shares = FinancialMath.sharesOf(context, bar, context.quarterlyFigures());
            if (shares == null || shares.signum() <= 0) {
                continue;
            }
            daily[i] = BigDecimal.valueOf(bar.volume())
                    .multiply(HUNDRED)
                    .divide(shares, SCALE, RoundingMode.HALF_UP);
        }

        List<IndicatorPoint> points = new ArrayList<>();
        for (int end = WINDOW - 1; end < bars.size(); end++) {
            BigDecimal sum = BigDecimal.ZERO;
            int counted = 0;
            for (int i = end - WINDOW + 1; i <= end; i++) {
                if (daily[i] != null) {
                    sum = sum.add(daily[i]);
                    counted++;
                }
            }
            if (counted == 0) {
                continue;
            }
            points.add(new IndicatorPoint(bars.get(end).tradeDate(),
                    sum.divide(BigDecimal.valueOf(counted), SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
