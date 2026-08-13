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
 * 이동평균선 정배열 단계 — 20 &gt; 60 &gt; 120 &gt; 200 중 성립한 이웃 쌍의 수(0~3).
 *
 * <p>완전 정배열(3)은 단기·중기·장기가 전부 순서대로 서 있다는 뜻이고, 역배열(0)은 하나도
 * 서지 않았다는 뜻이다. 이진값(정배열이다/아니다)으로 접지 않는 이유는 <b>2가 실제로 흔하고
 * 뜻이 있기 때문</b>이다 — 단기가 먼저 돌아섰지만 장기가 아직 안 따라온 상태가 그것이다.
 * 이진이 필요하면 조건에서 {@code >= 3}으로 쓴다.
 *
 * <p>네 선을 <b>이웃끼리만</b> 견준다. 20 &gt; 60, 60 &gt; 120, 120 &gt; 200 셋이며, 20 &gt; 200처럼
 * 건너뛴 쌍은 세지 않는다 — 이웃 셋이 서면 나머지는 부등호의 추이律로 자동이라 함께 세면
 * 같은 사실을 두 번 세는 것이 된다.
 */
@Component
@IndicatorMeta(
        name = "이평선 정배열",
        description = "20·60·120·200일 이동평균선이 짧은 것부터 순서대로 서 있는 정도. "
                + "이웃한 세 쌍(20>60, 60>120, 120>200) 중 몇 개가 성립하는지를 0~3으로 센다.",
        interpretation = "3이면 완전 정배열로 상승 추세가 모든 기간에서 일관된다는 뜻이다. "
                + "0이면 역배열이다. 1~2는 추세가 바뀌는 중일 수 있으며, 단기부터 서기 시작하면 "
                + "상승 전환을, 단기부터 무너지면 하락 전환을 의심한다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.COUNT,
        unit = "단계",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 105,
        bands = {
                @IndicatorMeta.Band(label = "완전 정배열", from = 3),
                @IndicatorMeta.Band(label = "부분 정배열", from = 2, to = 3),
                @IndicatorMeta.Band(label = "혼조", from = 1, to = 2),
                @IndicatorMeta.Band(label = "역배열", to = 1)
        }
)
public class MaAlignScoreCalculator implements IndicatorCalculator {

    public static final String CODE = "MA_ALIGN_SCORE";

    /** 짧은 것부터. 이웃끼리 견주므로 순서가 곧 정의다. */
    private static final String[] LADDER = {
            Sma20Calculator.CODE, Sma60Calculator.CODE,
            Sma120Calculator.CODE, Sma200Calculator.CODE
    };

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 자기 몫의 봉은 필요 없다. 네 이동평균이 있는 날짜에만 값이 선다.
        return DataRequirement.derivedFrom(0, LADDER);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<NavigableMap<LocalDate, BigDecimal>> ladder = new ArrayList<>(LADDER.length);
        for (String code : LADDER) {
            ladder.add(context.dependency(code));
        }

        // 가장 긴 선이 나오는 날짜가 곧 넷이 다 있는 날짜다. 그 위에서만 센다 —
        // 없는 선을 0으로 두면 200일선이 없는 종목이 늘 완전 정배열로 읽힌다.
        NavigableMap<LocalDate, BigDecimal> longest = ladder.get(ladder.size() - 1);
        List<IndicatorPoint> points = new ArrayList<>(longest.size());

        for (LocalDate date : longest.keySet()) {
            int score = 0;
            boolean complete = true;
            for (int i = 0; i < ladder.size() - 1; i++) {
                BigDecimal shorter = ladder.get(i).get(date);
                BigDecimal longerMa = ladder.get(i + 1).get(date);
                if (shorter == null || longerMa == null) {
                    complete = false;
                    break;
                }
                if (shorter.compareTo(longerMa) > 0) {
                    score++;
                }
            }
            if (complete) {
                points.add(new IndicatorPoint(date, BigDecimal.valueOf(score)));
            }
        }
        return points;
    }
}
