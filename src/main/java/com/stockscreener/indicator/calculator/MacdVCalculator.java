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
 * MACD-V — MACD를 ATR로 나눠 변동성으로 정규화한 값({@code MACD / ATR × 100}).
 *
 * <p>이 지표의 존재 이유는 <b>종목 간 비교 가능성</b>이다. MACD는 주가 수준에 비례해
 * 삼성전자의 MACD와 코스닥 소형주의 MACD를 나란히 놓을 수 없다. 변동성으로 나누면 축척이
 * 사라져 "이 종목의 모멘텀이 자기 변동성 대비 얼마나 강한가"가 되고, 그제서야 스크리닝의
 * 정렬 기준이 된다.
 *
 * <p>IMPORTANT — 이 클래스는 spec §3이 확장성의 예로 든 바로 그 지표이며, 동시에
 * <b>지표 간 의존 구조의 실증</b>이다. MACD와 ATR을 여기서 다시 구현하지 않고
 * {@link DataRequirement#dependsOn()}으로 선언해 이미 계산된 시계열을 받는다. 다시 구현하면
 * 부모 쪽 계산이 바뀔 때 이 지표만 옛 방식으로 남고, 그 어긋남은 값이 그럴듯해서 드러나지 않는다.
 *
 * <p>워밍업은 선언하지 않는다. 자기 몫의 추가 봉이 0이고 부모의 워밍업은 레지스트리가
 * 의존 그래프를 따라 합산한다.
 */
@Component
@IndicatorMeta(
        name = "MACD-V",
        description = "MACD를 같은 기간의 변동성(ATR)으로 나눈 값. "
                + "주가 수준과 변동성의 영향을 제거해 종목 간 비교가 가능하도록 만든 모멘텀 지표다.",
        interpretation = "0보다 크면 상승 모멘텀이 우세하다는 뜻이다. "
                + "일반적으로 +150 이상은 과열, -150 이하는 과매도 구간으로 본다. "
                + "MACD와 달리 종목 간 크기 비교가 가능하다.",
        category = IndicatorCategory.DERIVED,
        valueType = IndicatorValueType.COUNT,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 140
)
public class MacdVCalculator implements IndicatorCalculator {

    public static final String CODE = "MACD_V";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.derivedFrom(0, MacdCalculator.CODE, Atr14Calculator.CODE);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, BigDecimal> macd = context.dependency(MacdCalculator.CODE);
        NavigableMap<LocalDate, BigDecimal> atr = context.dependency(Atr14Calculator.CODE);
        if (macd.isEmpty() || atr.isEmpty()) {
            // 부모 중 하나라도 워밍업이 부족하면 계산하지 않는다.
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>();
        for (Map.Entry<LocalDate, BigDecimal> entry : macd.entrySet()) {
            BigDecimal volatility = atr.get(entry.getKey());
            // 두 부모의 워밍업 길이가 달라 날짜가 어긋나는 구간이 생긴다. 한쪽만 있는 날은 건너뛴다.
            if (volatility == null || volatility.signum() == 0) {
                continue;
            }
            BigDecimal value = entry.getValue()
                    .divide(volatility, MovingAverages.MC)
                    .multiply(HUNDRED)
                    .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(entry.getKey(), value));
        }
        return points;
    }
}
