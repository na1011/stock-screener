package com.stockscreener.stock;

import com.stockscreener.indicator.PriceBar;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 과거 봉에서 가격이 반복적으로 의미를 가졌던 구간을 찾는다.
 *
 * <p><b>순수 함수다.</b> DB도 시간도 난수도 모른다 — 같은 봉을 주면 언제나 같은 결과가 나오고,
 * 그래서 합성 봉으로 결정적으로 테스트할 수 있다.
 *
 * <p>알고리즘({@code SWING_EXTREMA_CLUSTERING}):
 * <ol>
 *   <li><b>수정 공간으로 옮긴다.</b> 고가·저가를 수정 배율로 환산한다.
 *   <li><b>스윙 극점</b>을 찾는다 — 좌우 {@value #SWING_WINDOW}봉을 모두 넘는 고점·저점.
 *   <li><b>밴드로 묶는다</b> — 변동성에 비례한 허용 폭 안의 극점들을 한 구간으로.
 *   <li><b>터치를 센다</b> — 구간과 겹친 봉을, 연속 구간은 한 번으로.
 *   <li><b>역할을 정하고 추린다</b> — 마지막 종가 기준 지지/저항/구간내.
 * </ol>
 */
@Component
public class SupportResistanceAnalyzer {

    /** 분석 구간. 약 6개월이면 최근 국면을 담으면서 옛 가격대에 묶이지 않는다. */
    public static final int WINDOW_BARS = 120;

    /** 이보다 적으면 값을 내지 않는다. 극점 몇 개로 만든 "구간"은 우연과 구별되지 않는다. */
    public static final int MIN_BARS = 60;

    /** 극점 판정의 좌우 폭. */
    private static final int SWING_WINDOW = 3;

    /** 구간이 되려면 최소 이만큼 터치되어야 한다. 한 번 스친 가격은 선이 아니다. */
    private static final int MIN_TOUCHES = 2;

    /** 방향별 최대 구간 수. 더 주면 화면이 선으로 가득 차 무엇이 중요한지 사라진다. */
    private static final int MAX_ZONES_PER_ROLE = 3;

    private static final BigDecimal MIN_TOLERANCE = new BigDecimal("0.015");
    private static final BigDecimal MAX_TOLERANCE = new BigDecimal("0.040");

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    /**
     * @param bars 날짜 오름차순. 최근 {@value #WINDOW_BARS}봉을 넘겨받는 것을 전제한다.
     */
    public PriceReference analyze(List<PriceBar> bars) {
        PriceReference.Window window = windowOf(bars);
        if (bars.size() < MIN_BARS) {
            return PriceReference.insufficient(window);
        }

        List<AdjustedBar> adjusted = toAdjustedSpace(bars);
        if (adjusted.size() < MIN_BARS) {
            return PriceReference.insufficient(window);
        }

        BigDecimal lastClose = adjusted.get(adjusted.size() - 1).close();
        BigDecimal tolerance = toleranceOf(adjusted, lastClose);

        List<BigDecimal> extrema = swingExtrema(adjusted);
        List<Cluster> clusters = cluster(extrema, tolerance);

        List<PriceReference.Zone> zones = new ArrayList<>();
        for (Cluster cluster : clusters) {
            Touches touches = countTouches(adjusted, cluster.lower(), cluster.upper());
            if (touches.count() < MIN_TOUCHES) {
                continue;
            }
            zones.add(new PriceReference.Zone(
                    roleOf(cluster, lastClose),
                    scale(cluster.lower()), scale(cluster.upper()),
                    touches.count(), touches.lastDate(), strengthOf(touches.count())));
        }

        return PriceReference.of(window, scale(lastClose), select(zones));
    }

    private static PriceReference.Window windowOf(List<PriceBar> bars) {
        if (bars.isEmpty()) {
            return new PriceReference.Window(null, null, 0);
        }
        return new PriceReference.Window(
                bars.get(0).tradeDate(), bars.get(bars.size() - 1).tradeDate(), bars.size());
    }

    /**
     * 고가·저가를 수정 공간으로 옮긴다.
     *
     * <p>IMPORTANT — 이 단계가 없으면 <b>분석 구간 안에 액면분할이 있는 종목에서 존재하지 않는
     * 지지선</b>이 분할 전 가격대에 생긴다. {@code PriceBar}에 수정 배율이 따로 없으므로
     * 봉마다 {@code adjClose / close}로 그날의 배율을 복원한다.
     *
     * <p>최신 구간의 배율은 1이므로({@code AdjustmentRecalculator}의 모델) 결과 가격은
     * 사용자가 보는 현재 가격 축과 일치한다.
     */
    private static List<AdjustedBar> toAdjustedSpace(List<PriceBar> bars) {
        List<AdjustedBar> adjusted = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            if (bar.close() == null || bar.adjClose() == null
                    || bar.high() == null || bar.low() == null
                    || bar.close().signum() == 0) {
                // 값이 없는 봉은 건너뛴다. 원주가로 대신 채우면 그날만 다른 축척이 섞여
                // 없는 구간이 생긴다.
                continue;
            }
            BigDecimal ratio = bar.adjClose().divide(bar.close(), MC);
            adjusted.add(new AdjustedBar(
                    bar.tradeDate(),
                    bar.high().multiply(ratio, MC),
                    bar.low().multiply(ratio, MC),
                    bar.adjClose()));
        }
        return adjusted;
    }

    /**
     * 변동성에 비례한 밴드 폭.
     *
     * <p>고정 폭(예: 2%)으로 두면 변동성이 큰 소형주에서는 모든 극점이 한 덩어리가 되고,
     * 잔잔한 대형주에서는 아무것도 묶이지 않는다. 상·하한을 두는 이유는 극단적인 종목에서
     * 밴드가 의미를 잃지 않게 하기 위해서다.
     *
     * <p>{@code ATR_14} 지표를 쓰지 않는다 — 그 지표가 없는 종목에서 지지·저항이 통째로
     * 사라지고, 참고정보가 지표 계산에 의존하기 시작한다.
     */
    private static BigDecimal toleranceOf(List<AdjustedBar> bars, BigDecimal lastClose) {
        if (lastClose.signum() == 0) {
            return MIN_TOLERANCE;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (AdjustedBar bar : bars) {
            sum = sum.add(bar.high().subtract(bar.low()));
        }
        BigDecimal meanRange = sum.divide(BigDecimal.valueOf(bars.size()), MC);
        BigDecimal ratio = meanRange.divide(lastClose, MC);

        if (ratio.compareTo(MIN_TOLERANCE) < 0) {
            return MIN_TOLERANCE;
        }
        return ratio.compareTo(MAX_TOLERANCE) > 0 ? MAX_TOLERANCE : ratio;
    }

    /**
     * 스윙 고점·저점.
     *
     * <p>양 끝 {@value #SWING_WINDOW}봉은 좌우를 다 볼 수 없어 제외한다 — 확정되지 않은 극점을
     * 넣으면 최근 봉이 항상 극점이 되어 구간이 오늘 가격 주변에 몰린다.
     */
    private static List<BigDecimal> swingExtrema(List<AdjustedBar> bars) {
        List<BigDecimal> extrema = new ArrayList<>();
        for (int i = SWING_WINDOW; i < bars.size() - SWING_WINDOW; i++) {
            if (isSwingHigh(bars, i)) {
                extrema.add(bars.get(i).high());
            }
            if (isSwingLow(bars, i)) {
                extrema.add(bars.get(i).low());
            }
        }
        return extrema;
    }

    private static boolean isSwingHigh(List<AdjustedBar> bars, int index) {
        BigDecimal value = bars.get(index).high();
        for (int j = index - SWING_WINDOW; j <= index + SWING_WINDOW; j++) {
            if (j == index) {
                continue;
            }
            if (bars.get(j).high().compareTo(value) > 0) {
                return false;
            }
            // 평탄 구간에서는 첫 봉만 극점으로 삼는다. 아니면 같은 고점이 여러 번 들어와
            // 하나의 밴드가 터치 수를 부풀린다.
            if (j < index && bars.get(j).high().compareTo(value) == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSwingLow(List<AdjustedBar> bars, int index) {
        BigDecimal value = bars.get(index).low();
        for (int j = index - SWING_WINDOW; j <= index + SWING_WINDOW; j++) {
            if (j == index) {
                continue;
            }
            if (bars.get(j).low().compareTo(value) < 0) {
                return false;
            }
            if (j < index && bars.get(j).low().compareTo(value) == 0) {
                return false;
            }
        }
        return true;
    }

    /** 가격순으로 정렬한 뒤 허용 폭 안의 극점들을 탐욕적으로 병합한다. */
    private static List<Cluster> cluster(List<BigDecimal> extrema, BigDecimal tolerance) {
        List<Cluster> clusters = new ArrayList<>();
        if (extrema.isEmpty()) {
            return clusters;
        }
        List<BigDecimal> sorted = new ArrayList<>(extrema);
        sorted.sort(Comparator.naturalOrder());

        BigDecimal lower = sorted.get(0);
        BigDecimal upper = sorted.get(0);
        BigDecimal sum = sorted.get(0);
        int count = 1;

        for (int i = 1; i < sorted.size(); i++) {
            BigDecimal value = sorted.get(i);
            BigDecimal mean = sum.divide(BigDecimal.valueOf(count), MC);
            BigDecimal allowed = mean.multiply(tolerance, MC);

            if (value.subtract(lower).compareTo(allowed) <= 0) {
                upper = value;
                sum = sum.add(value);
                count++;
            } else {
                clusters.add(widen(lower, upper, tolerance));
                lower = value;
                upper = value;
                sum = value;
                count = 1;
            }
        }
        clusters.add(widen(lower, upper, tolerance));
        return clusters;
    }

    /**
     * 극점이 하나뿐인 클러스터는 폭이 0이라 어떤 봉과도 겹치지 않는다. 최소 폭을 준다.
     */
    private static Cluster widen(BigDecimal lower, BigDecimal upper, BigDecimal tolerance) {
        BigDecimal mean = lower.add(upper).divide(BigDecimal.valueOf(2), MC);
        BigDecimal minHalfWidth = mean.multiply(tolerance, MC).divide(BigDecimal.valueOf(2), MC);
        if (upper.subtract(lower).compareTo(minHalfWidth.multiply(BigDecimal.valueOf(2))) < 0) {
            return new Cluster(mean.subtract(minHalfWidth), mean.add(minHalfWidth));
        }
        return new Cluster(lower, upper);
    }

    /**
     * 구간과 겹친 봉의 수. <b>연속된 봉은 한 번으로 센다.</b>
     *
     * <p>이것이 "그 구간이 몇 번 의미를 가졌는가"의 정의다. 겹친 봉을 그대로 세면 한 번
     * 눌렸다가 5일 머문 것이 5회가 되어, 오래 머문 구간이 여러 번 반등한 구간보다 강해 보인다.
     */
    private static Touches countTouches(List<AdjustedBar> bars, BigDecimal lower, BigDecimal upper) {
        int count = 0;
        boolean inTouch = false;
        LocalDate lastDate = null;

        for (AdjustedBar bar : bars) {
            boolean overlaps = bar.low().compareTo(upper) <= 0 && bar.high().compareTo(lower) >= 0;
            if (overlaps) {
                if (!inTouch) {
                    count++;
                    inTouch = true;
                }
                lastDate = bar.date();
            } else {
                inTouch = false;
            }
        }
        return new Touches(count, lastDate);
    }

    private static PriceReference.Role roleOf(Cluster cluster, BigDecimal lastClose) {
        if (cluster.upper().compareTo(lastClose) < 0) {
            return PriceReference.Role.SUPPORT;
        }
        if (cluster.lower().compareTo(lastClose) > 0) {
            return PriceReference.Role.RESISTANCE;
        }
        return PriceReference.Role.IN_RANGE;
    }

    private static PriceReference.Strength strengthOf(int touchCount) {
        if (touchCount >= 4) {
            return PriceReference.Strength.HIGH;
        }
        return touchCount == 3 ? PriceReference.Strength.MEDIUM : PriceReference.Strength.LOW;
    }

    /** 방향별로 강한 것부터 추린다. {@code IN_RANGE}는 개수가 적어 전부 남긴다. */
    private static List<PriceReference.Zone> select(List<PriceReference.Zone> zones) {
        Comparator<PriceReference.Zone> byImportance =
                Comparator.comparingInt(PriceReference.Zone::touchCount).reversed()
                        .thenComparing(PriceReference.Zone::lastTouchedOn,
                                Comparator.nullsLast(Comparator.reverseOrder()));

        List<PriceReference.Zone> selected = new ArrayList<>();
        for (PriceReference.Role role : PriceReference.Role.values()) {
            List<PriceReference.Zone> ofRole = zones.stream()
                    .filter(z -> z.role() == role)
                    .sorted(byImportance)
                    .toList();
            selected.addAll(role == PriceReference.Role.IN_RANGE
                    ? ofRole
                    : ofRole.subList(0, Math.min(MAX_ZONES_PER_ROLE, ofRole.size())));
        }
        // 화면에서 위에서 아래로 그리기 좋게 가격 내림차순으로 준다.
        selected.sort(Comparator.comparing(PriceReference.Zone::lower).reversed());
        return selected;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record AdjustedBar(LocalDate date, BigDecimal high, BigDecimal low, BigDecimal close) {
    }

    private record Cluster(BigDecimal lower, BigDecimal upper) {
    }

    private record Touches(int count, LocalDate lastDate) {
    }
}
