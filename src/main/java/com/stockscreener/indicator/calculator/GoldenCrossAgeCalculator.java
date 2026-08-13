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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;

/**
 * 골든크로스 이후 경과 거래일 — 60일선이 200일선을 <b>위로</b> 지난 뒤 며칠 지났는가.
 *
 * <p>60일선을 쓴다. 통상적인 골든크로스는 50일선이지만 이 저장소는 50일 이동평균을 만들지
 * 않으며, 그 판단은 {@code PresetCatalog}가 스윙 프리셋에서 이미 내렸다 — "원본은 50일선을
 * 쓰지만 60일선으로 대신한다. 정배열의 뜻이 달라지지 않는다"(ADR-0039).
 *
 * <p>MUST NOT: 교차가 없을 때 센티널(−1)을 내지 않는다. 「해당 없음」이 <b>유리한 쪽 극단</b>에
 * 앉게 되어 부등호를 걸면 정반대 집합이 걸린다 — 일목의 {@code ICHI_FRESHNESS}가 정확히 그
 * 문제를 안고 있고(ADR-0046 §결과) 그것을 되풀이하지 않는다. 값을 내지 않는다.
 *
 * <p><b>그 대신 치르는 대가가 있다.</b> "창 안에 교차가 없다"와 "이력이 모자라 계산할 수 없다"가
 * 같은 상태(값 없음)가 된다. 그래서 {@code >= N}류 조건 — "교차한 지 오래된 종목" — 에서는
 * 오래전에 교차한 종목이 아니라 <b>아무것도</b> 걸리지 않는다. {@code interpretation}에 그
 * 사실을 적어 화면에서도 보이게 한다.
 */
@Component
@IndicatorMeta(
        name = "골든크로스 경과일",
        description = "60일 이동평균선이 200일선을 위로 통과한 뒤 지난 거래일 수. "
                + "0이면 오늘 통과했다는 뜻이다. 최근 " + GoldenCrossAgeCalculator.SEARCH_DAYS
                + "거래일 안에 통과한 적이 없으면 값이 없다.",
        interpretation = "작을수록 추세 전환이 최근이라는 뜻이다. 통과 직후에는 되돌림이 잦아 "
                + "20~60일쯤 지나 자리를 잡았는지 함께 본다. "
                + "값이 없는 것은 「오래 전에 통과했다」가 아니라 「최근 구간에 통과가 없거나 "
                + "이력이 모자란다」는 뜻이므로, 이 지표로 오래된 통과를 찾을 수는 없다.",
        category = IndicatorCategory.DERIVED,
        valueType = IndicatorValueType.COUNT,
        unit = "일",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        sortOrder = 300
)
public class GoldenCrossAgeCalculator implements IndicatorCalculator {

    public static final String CODE = "GOLDEN_CROSS_AGE";

    /**
     * 얼마나 거슬러 교차를 찾는가.
     *
     * <p>MUST: 이 값을 {@link #dataRequirement()}에 그대로 선언한다. {@code IndicatorRunner}가
     * {@code UnadjustedActionGuard}에 넘기는 억제 폭이 <b>선언에서 유도되므로</b>, 실제로 훑는
     * 범위가 선언보다 넓으면 미보정 기업행위 구간의 억제가 새고 그때 증상은 "값이 나온다"다.
     */
    static final int SEARCH_DAYS = 120;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.derivedFrom(SEARCH_DAYS, Sma60Calculator.CODE, Sma200Calculator.CODE);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, BigDecimal> shortMa = context.dependency(Sma60Calculator.CODE);
        NavigableMap<LocalDate, BigDecimal> longMa = context.dependency(Sma200Calculator.CODE);

        // 200일선이 있는 날짜가 곧 둘 다 있는 날짜다. 그 위에서만 판정한다.
        List<LocalDate> dates = new ArrayList<>(longMa.size());
        List<Boolean> above = new ArrayList<>(longMa.size());
        for (var entry : longMa.entrySet()) {
            BigDecimal shortValue = shortMa.get(entry.getKey());
            if (shortValue == null) {
                continue;
            }
            dates.add(entry.getKey());
            above.add(shortValue.compareTo(entry.getValue()) > 0);
        }

        List<IndicatorPoint> points = new ArrayList<>(dates.size());
        int lastCross = -1;
        for (int i = 1; i < dates.size(); i++) {
            // 아래(또는 같음)에 있다가 위로 올라선 그 하루가 교차다.
            if (!above.get(i - 1) && above.get(i)) {
                lastCross = i;
            }
            int age = lastCross < 0 ? -1 : i - lastCross;
            if (age < 0 || age > SEARCH_DAYS) {
                // 교차를 못 찾았거나 창 밖이다. 행을 쓰지 않는다 — 위 javadoc의 대가가 여기다.
                continue;
            }
            points.add(new IndicatorPoint(dates.get(i), BigDecimal.valueOf(age)));
        }
        return points;
    }
}
