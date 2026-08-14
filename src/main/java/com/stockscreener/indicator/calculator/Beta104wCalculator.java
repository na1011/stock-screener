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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 2년(104주) 주간 베타 — 스윙 원본 명세의 T-08이다.
 *
 * <p>종목의 주간 수익률을 같은 구간 시장 지수의 주간 수익률에 회귀한 기울기다. 1이면 지수와
 * 같은 폭으로 움직였다는 뜻이고, 낮을수록 시장 변동에 덜 흔들렸다는 뜻이다.
 *
 * <p><b>IMPORTANT — 국내 유니버스의 중앙값은 1이 아니라 0.7대다.</b> 지수가 시총가중이라
 * 소수의 대형주가 지수를 끌고 나머지 대부분은 그보다 덜 움직인다. 미국 문헌의 「베타 1이
 * 중앙」이라는 직관으로 임계값을 잡으면 <b>게이트가 아니라 상수가 된다</b> — 실측에서
 * 1.3 이하가 82%, 0.9 이하가 60%였다. 임계값은 반드시 분포를 재고 정한다.
 *
 * <p><b>진행 중인 주를 쓰지 않는다.</b> {@link WeeklyResampler}는 「진행 중인 주 포함」으로
 * 접지만(ADR-0046 §승인된 이탈 3) 그것은 <b>지금 이 주의 상태</b>를 보는 일목의 요구다.
 * 베타는 분포를 재는 통계량이라 1~5일짜리 부분 주가 섞이면 그 한 주만 다른 기간을 재게 된다.
 * 그래서 각 거래일에 <b>그 날짜가 속한 주의 직전 주</b>까지로 회귀한다 — 결과로 값이 주 단위로만
 * 바뀐다. 그것이 「주간」 베타의 뜻이다.
 *
 * <p>MUST NOT: 지수를 종목과 다른 주 경계로 접지 않는다. {@link WeeklyResampler#weekKey}를
 * 양쪽에 같이 쓴다 — 어긋난 짝으로도 회귀는 값을 낸다.
 *
 * <p>MUST NOT: {@code SeriesMath.slope}를 쓰지 않는다. 그것은 <b>시간축</b> 추세 기울기이지
 * 이변량 회귀가 아니다. 이름이 비슷해 오용을 부른다.
 */
@Component
@IndicatorMeta(
        name = "2년 주간 베타",
        description = "최근 104주 동안 이 종목의 주간 등락이 시장 지수의 주간 등락에 "
                + "얼마나 따라 움직였는지. 코스피 종목은 코스피 지수와, 코스닥 종목은 "
                + "코스닥 지수와 견준다.",
        interpretation = "1이면 지수와 같은 폭으로 움직였다는 뜻이고 1보다 크면 더 크게, "
                + "작으면 덜 흔들렸다는 뜻이다. 국내 편입 종목의 중앙값은 1이 아니라 0.7 근처다 "
                + "— 지수를 소수의 대형주가 끌기 때문이며, 1 미만이라고 특별히 안정적인 것은 아니다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.RATIO,
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 3,
        sortOrder = 137
)
public class Beta104wCalculator implements IndicatorCalculator {

    public static final String CODE = "BETA_104W";

    /** 회귀에 쓰는 주간 수익률 개수. 종가는 하나 더 필요하다. */
    private static final int WEEKS = 104;

    /** 104주를 채우려면 휴장을 감안해도 이만큼의 일봉이면 넉넉하다. */
    private static final int PRICE_BARS = (WEEKS + 2) * 5;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceBars(PRICE_BARS);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        NavigableMap<LocalDate, BigDecimal> index = context.marketIndex();
        if (bars.isEmpty() || index.isEmpty()) {
            return List.of();
        }

        List<WeeklyBar> weeks = WeeklyResampler.resample(bars);
        Map<Long, Double> indexByWeek = foldIndexByWeek(index);

        // 종목 주봉과 지수가 같은 주에 둘 다 있는 주만 남긴다.
        List<Long> keys = new ArrayList<>(weeks.size());
        List<Double> stockCloses = new ArrayList<>(weeks.size());
        List<Double> indexCloses = new ArrayList<>(weeks.size());
        for (WeeklyBar week : weeks) {
            long key = WeeklyResampler.weekKey(week.endDate());
            Double indexClose = indexByWeek.get(key);
            if (indexClose == null || indexClose == 0 || week.close() == 0) {
                continue;
            }
            keys.add(key);
            stockCloses.add(week.close());
            indexCloses.add(indexClose);
        }
        if (keys.size() <= WEEKS) {
            return List.of();
        }

        // 주 인덱스 w까지(포함)의 최근 104개 수익률로 구한 베타.
        Map<Long, Double> betaByWeek = new HashMap<>();
        for (int w = WEEKS; w < keys.size(); w++) {
            Double beta = betaAt(stockCloses, indexCloses, w);
            if (beta != null) {
                betaByWeek.put(keys.get(w), beta);
            }
        }
        if (betaByWeek.isEmpty()) {
            return List.of();
        }

        // 거래일마다 "그 날짜가 속한 주의 직전 주"의 베타를 붙인다.
        Map<Long, Long> previousKey = new HashMap<>();
        for (int i = 1; i < keys.size(); i++) {
            previousKey.put(keys.get(i), keys.get(i - 1));
        }

        List<IndicatorPoint> points = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            Long prior = previousKey.get(WeeklyResampler.weekKey(bar.tradeDate()));
            if (prior == null) {
                continue;
            }
            Double beta = betaByWeek.get(prior);
            if (beta == null) {
                continue;
            }
            points.add(new IndicatorPoint(bar.tradeDate(),
                    BigDecimal.valueOf(beta).setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }

    /** 주 키 → 그 주의 마지막 지수 종가. 종목과 같은 경계를 쓴다. */
    private static Map<Long, Double> foldIndexByWeek(NavigableMap<LocalDate, BigDecimal> index) {
        Map<Long, Double> byWeek = new HashMap<>(index.size());
        // 오름차순 순회라 같은 주에서는 뒤가 이긴다 — 그 주의 마지막 값이 남는다.
        for (Map.Entry<LocalDate, BigDecimal> entry : index.entrySet()) {
            byWeek.put(WeeklyResampler.weekKey(entry.getKey()), entry.getValue().doubleValue());
        }
        return byWeek;
    }

    /**
     * {@code w}번째 주까지의 최근 {@link #WEEKS}개 수익률로 구한 회귀 기울기.
     *
     * @return 지수 수익률의 분산이 0이면 null — 기울기가 정의되지 않는다
     */
    private static Double betaAt(List<Double> stock, List<Double> market, int w) {
        double sumX = 0;
        double sumY = 0;
        for (int k = w - WEEKS + 1; k <= w; k++) {
            sumX += market.get(k) / market.get(k - 1) - 1;
            sumY += stock.get(k) / stock.get(k - 1) - 1;
        }
        double meanX = sumX / WEEKS;
        double meanY = sumY / WEEKS;

        double covariance = 0;
        double variance = 0;
        for (int k = w - WEEKS + 1; k <= w; k++) {
            double x = market.get(k) / market.get(k - 1) - 1 - meanX;
            double y = stock.get(k) / stock.get(k - 1) - 1 - meanY;
            covariance += x * y;
            variance += x * x;
        }
        if (variance == 0) {
            return null;
        }
        return covariance / variance;
    }
}
