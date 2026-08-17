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
 * PEG — PER을 이익 성장률로 나눈 값({@code PER / EPS_GROWTH_TTM_YOY}).
 *
 * <p>린치의 판별 도구다. "PER이 이익성장률과 같거나 낮으면 매수 가능 영역"이라는 명제를
 * 하나의 수로 만든 것이며, 1.0이 그 경계다. GARP(합리적 가격의 성장)라는 개념 자체가
 * 여기서 나왔다.
 *
 * <p>IMPORTANT — <b>성장률이 0 이하면 값을 내지 않는다.</b> 이것이 이 계산기에서 가장 중요한
 * 한 줄이다. 역성장 종목은 분모가 음수라 PEG가 음수가 되는데, <b>음수는 「PEG가 낮은 종목」을
 * 찾는 조건에서 가장 매력적인 값으로 통과한다.</b> 원본 명세도 같은 함정을 적어 뒀다 —
 * "하한 0 필수. 없으면 이익 역성장 종목이 음수 PEG로 통과함"(피터린치-방법론 3.3).
 * 조건 계층에는 하한을 걸 수단이 없으므로({@code ComparisonOperator}는 순수 수치 비교다)
 * 여기서 막는 것이 유일한 방어다.
 *
 * <p>분모의 단위가 <b>퍼센트 포인트</b>다. {@code EPS_GROWTH_TTM_YOY}가 15% 성장을 {@code 15}로
 * 저장하므로(그 계산기가 100을 곱한다) PER 15를 15로 나눠 1.0이 나온다 — 표준 PEG 정의와 같다.
 * <b>MUST NOT: 분모를 0.15로 환산하지 않는다.</b> 값이 100배가 되는데 그럴듯한 수로 보인다.
 *
 * <p>PER이 적자 종목에 값을 내지 않으므로({@link PerCalculator}) 이 지표도 흑자 종목에만 생긴다.
 * 즉 <b>PEG를 필수 조건에 걸면 흑자가 이미 강제된다</b> — 별도의 「순이익 &gt; 0」 게이트를 더하면
 * 통과율 100%짜리 조건이 하나 생긴다.
 *
 * <p>워밍업은 선언하지 않는다. 자기 몫의 추가 봉이 0이고 부모의 워밍업은 레지스트리가 합산한다.
 */
@Component
@IndicatorMeta(
        name = "PEG",
        description = "PER을 주당순이익 성장률로 나눈 값. "
                + "이익이 빠르게 느는 회사는 PER이 높아도 비싸지 않을 수 있다는 관점을 하나의 수로 만든 것이다.",
        interpretation = "1보다 작으면 성장 속도에 견줘 주가가 싸다는 뜻이고, 2를 넘으면 비싸다고 본다. "
                + "이익이 줄고 있는 종목은 값이 아예 없다 — 음수가 「가장 싸다」로 읽히는 것을 막기 위해서다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        unit = "배",
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 11,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 2),
                @IndicatorMeta.Band(label = "높음", from = 1.5, to = 2),
                @IndicatorMeta.Band(label = "보통", from = 1, to = 1.5),
                @IndicatorMeta.Band(label = "낮음", from = 0.5, to = 1),
                @IndicatorMeta.Band(label = "매우 낮음", to = 0.5)
        }
)
public class PegCalculator implements IndicatorCalculator {

    public static final String CODE = "PEG";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.derivedFrom(
                0, PerCalculator.CODE, EpsGrowthTtmYoyCalculator.CODE);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, BigDecimal> per = context.dependency(PerCalculator.CODE);
        NavigableMap<LocalDate, BigDecimal> growth =
                context.dependency(EpsGrowthTtmYoyCalculator.CODE);
        if (per.isEmpty() || growth.isEmpty()) {
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>();
        for (Map.Entry<LocalDate, BigDecimal> entry : per.entrySet()) {
            BigDecimal growthPct = growth.get(entry.getKey());
            // 두 부모의 워밍업 길이가 달라 날짜가 어긋나는 구간이 생긴다. 한쪽만 있는 날은 건너뛴다.
            if (growthPct == null) {
                continue;
            }
            // 역성장·정체는 값을 내지 않는다. 위 javadoc의 IMPORTANT가 이 분기를 설명한다.
            if (growthPct.signum() <= 0) {
                continue;
            }
            BigDecimal value = entry.getValue()
                    .divide(growthPct, FinancialMath.SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(entry.getKey(), value));
        }
        return points;
    }
}
