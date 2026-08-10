package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 200일선의 60일 회귀 기울기 — 최소제곱 직선이 60거래일 동안 몇 퍼센트 오르는가.
 *
 * <p>IMPORTANT — <b>원시 기울기를 그대로 내지 않는다.</b> 최소제곱 기울기는 "하루당 원(₩)"이라
 * 주가 수준에 비례하고, 그 값에 구간을 붙이면 종목마다 뜻이 달라져 거짓이 된다(ADR-0023).
 * 창의 평균으로 나눠 무차원으로 만든 뒤 창 길이를 곱해 <b>"이 추세가 60일에 몇 % 오르는가"</b>로
 * 옮긴다. 부호는 그대로 보존되므로 게이트 조건({@code > 0})의 뜻이 바뀌지 않는다.
 *
 * <p>{@link Sma200Momentum21Calculator}와 짝이다. 회귀는 전 구간을 평활해서 보므로 횡보의
 * 노이즈에 흔들리지 않는 대신 최근 며칠의 꺾임을 놓친다. 1개월 변화율이 그 반대라서, 둘을
 * AND로 걸면 양쪽 오류가 상쇄된다.
 *
 * <p>x축을 날짜가 아니라 <b>순번</b>으로 둔다. 휴장일 간격을 실제 일수로 반영하면 연휴가 낀
 * 구간만 기울기가 완만해지는데, 그것은 추세에 대한 사실이 아니라 달력에 대한 사실이다.
 */
@Component
@IndicatorMeta(
        name = "200일선 60일 기울기",
        description = "200일 이동평균의 최근 60거래일 추세선이 그 구간 동안 몇 퍼센트 오르는지. "
                + "최소제곱 직선의 기울기를 이동평균 수준으로 나눠 종목 간 비교가 되게 한 값이다.",
        interpretation = "0보다 크면 장기 추세선이 우상향이라는 뜻이다. "
                + "1개월 변화율과 달리 최근 며칠의 등락에 흔들리지 않지만, "
                + "그만큼 갓 시작된 꺾임을 늦게 반영한다. 두 지표를 함께 본다.",
        category = IndicatorCategory.DERIVED,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 103,
        bands = {
                @IndicatorMeta.Band(label = "강한 상승 추세", from = 8),
                @IndicatorMeta.Band(label = "상승 추세", from = 1, to = 8),
                @IndicatorMeta.Band(label = "횡보", from = -1, to = 1),
                @IndicatorMeta.Band(label = "하락 추세", to = -1)
        }
)
public class Sma200Slope60Calculator implements IndicatorCalculator {

    public static final String CODE = "SMA_200_SLOPE_60";

    /** 약 3개월. 추세 전환을 판정할 만큼 길고, 반년 전 국면에 끌리지 않을 만큼 짧다. */
    static final int WINDOW = 60;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.derivedFrom(WINDOW, Sma200Calculator.CODE);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, BigDecimal> sma = context.dependency(Sma200Calculator.CODE);
        if (sma.size() < WINDOW) {
            return List.of();
        }

        List<LocalDate> dates = new ArrayList<>(sma.size());
        List<BigDecimal> values = new ArrayList<>(sma.size());
        for (Map.Entry<LocalDate, BigDecimal> entry : sma.entrySet()) {
            dates.add(entry.getKey());
            values.add(entry.getValue());
        }

        List<IndicatorPoint> points = new ArrayList<>(values.size() - WINDOW + 1);
        for (int end = WINDOW - 1; end < values.size(); end++) {
            BigDecimal normalized = relativeSlope(values, end - WINDOW + 1);
            if (normalized != null) {
                points.add(new IndicatorPoint(dates.get(end), normalized));
            }
        }
        return points;
    }

    /**
     * {@code [start, start+WINDOW)} 구간의 정규화 기울기.
     *
     * <p>x는 0..WINDOW-1이므로 {@code Σ(x−x̄)²}는 상수다. 그래서 분모를 매번 다시 구하지 않는다.
     *
     * @return 구간 평균이 0이면 null. 나눌 수 없고, 이동평균이 0인 구간은 데이터 결함이다.
     */
    private static BigDecimal relativeSlope(List<BigDecimal> values, int start) {
        double meanX = (WINDOW - 1) / 2.0;
        // Σ(x−x̄)² = n(n²−1)/12
        BigDecimal denominator = BigDecimal.valueOf(WINDOW * ((long) WINDOW * WINDOW - 1) / 12.0);

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < WINDOW; i++) {
            sum = sum.add(values.get(start + i));
        }
        BigDecimal meanY = sum.divide(BigDecimal.valueOf(WINDOW), MovingAverages.MC);
        if (meanY.signum() == 0) {
            return null;
        }

        BigDecimal covariance = BigDecimal.ZERO;
        for (int i = 0; i < WINDOW; i++) {
            BigDecimal dx = BigDecimal.valueOf(i - meanX);
            covariance = covariance.add(dx.multiply(values.get(start + i).subtract(meanY)));
        }

        // 하루당 기울기 → 창 전체의 상승분 → 이동평균 수준 대비 비율(%)
        return covariance.divide(denominator, MovingAverages.MC)
                .multiply(BigDecimal.valueOf(WINDOW))
                .divide(meanY, MovingAverages.MC)
                .multiply(HUNDRED)
                .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
    }
}
