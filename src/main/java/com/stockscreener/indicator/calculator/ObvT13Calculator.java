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
 * 일봉 OBV의 13일 추세 강도 — OBV 시계열에 회귀선을 그어 얻은 t-통계량.
 *
 * <p>OBV(On-Balance Volume)는 종가가 오른 날의 거래량을 더하고 내린 날의 거래량을 빼 누적한
 * 값이다. 가격보다 거래량이 먼저 움직인다는 관찰에서 나왔다.
 *
 * <p>MUST NOT: <b>누적값 자체를 저장하지 않는다.</b> 누적은 읽기 창의 시작점에서 출발하는데
 * 그 창이 매일 하루씩 밀리므로, 원값을 저장하면 (a) 시작점이 달라 종목 간 비교가 불가능하고
 * (b) 실행마다 <b>과거 전 행의 값이 바뀌어</b> {@code IndicatorValueWriter}의
 * {@code WHERE value IS DISTINCT FROM}이 무력화된다. 그 필터가 무력화되면 어떻게 되는지는
 * 그쪽 javadoc에 실측으로 남아 있다(1.39M행 테이블에 갱신 2.49억 건, 인덱스 1,987MB).
 *
 * <p>t-통계량은 <b>상수 오프셋에 불변</b>이라 창이 밀려도 값이 그대로다. 무차원이라 종목 간
 * 비교도 된다. 주봉판({@code ICHI_OBV_T})이 같은 형태이며 공식의 정본은 {@link SeriesMath}다.
 *
 * <p>MUST: 거래량을 {@link PriceScaling#adjustedVolume}로 보정한다. 보정 방향이 <b>가격과
 * 반대</b>라(분할하면 주가는 내려가고 거래량은 올라간다) 원값을 쓰면 분할일에 OBV가 실제로
 * 없던 대량 매집·분산으로 읽힌다.
 */
@Component
@IndicatorMeta(
        name = "OBV 추세 강도",
        description = "거래량을 방향대로 누적한 OBV가 최근 13거래일 동안 얼마나 뚜렷하게 "
                + "한 방향으로 움직였는지를 통계량으로 나타낸 값. 가격이 아니라 거래량의 흐름을 본다.",
        interpretation = "+1.96을 넘으면 매집이, −1.96 아래면 분산이 통계적으로 뚜렷하다는 뜻이다. "
                + "그 사이는 방향을 말하지 않는다. 가격은 오르는데 이 값이 음수면 상승에 "
                + "거래량이 따라붙지 않는다는 신호로 읽는다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.RATIO,
        decimalScale = 2,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 215,
        bands = {
                @IndicatorMeta.Band(label = "매집 뚜렷", from = 1.96),
                @IndicatorMeta.Band(label = "방향 없음", from = -1.96, to = 1.96),
                @IndicatorMeta.Band(label = "분산 뚜렷", to = -1.96)
        }
)
public class ObvT13Calculator implements IndicatorCalculator {

    public static final String CODE = "OBV_T_13";
    /** 회귀에 쓰는 점의 수. 주봉판({@code ICHI_OBV_T})과 같은 13이다. */
    static final int POINTS = 13;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 13점을 만들려면 차분이 13번 필요하고, 차분 하나에 봉 둘이 든다.
        return DataRequirement.priceBars(POINTS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() <= POINTS) {
            return List.of();
        }

        // 창 시작점을 0으로 두고 누적한다. 시작점이 어디든 t-통계량은 같다.
        double[] obv = new double[bars.size()];
        for (int i = 1; i < bars.size(); i++) {
            double change = bars.get(i).adjClose().subtract(bars.get(i - 1).adjClose()).doubleValue();
            double volume = PriceScaling.adjustedVolume(bars.get(i)).doubleValue();
            obv[i] = obv[i - 1] + SeriesMath.sign(change) * volume;
        }

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - POINTS);
        double[] window = new double[POINTS];
        for (int end = POINTS; end < bars.size(); end++) {
            // 과거 → 현재 순서. SeriesMath가 인덱스를 시간으로 읽는다.
            System.arraycopy(obv, end - POINTS + 1, window, 0, POINTS);
            double t = SeriesMath.tStatistic(window);
            points.add(new IndicatorPoint(bars.get(end).tradeDate(),
                    BigDecimal.valueOf(t).setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
