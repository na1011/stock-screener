package com.stockscreener.probe.backtest;

import com.stockscreener.screening.ConditionEvaluator;
import com.stockscreener.screening.ConditionOutcome;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.preset.PresetCatalog;
import com.stockscreener.screening.preset.ScreenPreset;
import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진단 축 — <b>표본과 무관하게 답이 나오는 유일한 자리</b>다(ADR-0071, phase-criteria §P17 ⑤).
 *
 * <p>수익률과 <b>같은 표에 두지 않는다.</b> 지위가 다르다 — 진단은 프리셋을 고칠 근거가 되고
 * 수익률은 되지 않는다. 여기서 내는 것은 셋이다.
 * <ol>
 *   <li><b>게이트별 탈락 수의 시계열</b> — 어느 시점에도 아무도 못 자르는 게이트는 안전장치가
 *       아니라 <b>상수</b>다. 게이트 개수가 안전장치 개수로 읽히므로, 그 상태는 화면·테스트·
 *       배치 로그 어디에도 드러나지 않는다.
 *   <li><b>판정불가 수의 시계열</b> — 탈락과 <b>반드시 갈라 센다.</b> 데이터 결손이 만든
 *       탈락을 국면 변화로 읽는 것을 막는 것이 이 열의 목적이다.
 *   <li><b>통과 집합의 회전율</b>(자카드 거리) — 매달 뒤집히면 「장기 보유」라는 원저의 전제가
 *       깨진 것이다.
 * </ol>
 *
 * <p><b>이 테스트는 판정을 하지 않는다.</b> 0건이 나와도 실패로 만들지 않는다 — 0건의 원인을
 * ① 조건 ② 데이터 ③ 국면으로 가르는 것이 사람의 몫이고, 여기서 실패로 만들면 다음 세션이
 * <b>초록을 만들려고 임계값을 만지게 된다.</b> 유일한 단언은 「잴 것이 있었는가」다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class GateDiagnosticsTest {

    /**
     * 창의 시작. 재무가 FY2015부터 열려 {@code PROFIT_STREAK_Y >= 7}이 여기서 성립하기
     * 시작한다. <b>이보다 앞을 넣지 않는 이유</b>는 그때 머리 회계연도가 FY2020이 되어
     * 전체재무제표를 쓰는 조건 넷이 전 종목 판정불가가 되기 때문이다 — 그 0은 게이트가
     * 아니라 적재 범위 탓이다.
     */
    private static final LocalDate WINDOW_START = LocalDate.of(2022, 4, 1);

    /** 진단은 월 단위로 본다. <b>해상도이지 검정력이 아니다</b> — 그 4년에 일어난 일은 하나다. */
    private static final int MONTH_STEP = 3;

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private AsOfCalendar calendar;

    @Autowired
    private List<ScreenPreset> presets;

    /** 탈락 종목이 <b>어느 게이트에서</b> 걸렸는지는 다시 판정해야 나온다. */
    @Autowired
    private ConditionEvaluator conditionEvaluator;

    @Test
    @DisplayName("게이트별 탈락·판정불가·회전율을 시점별로 낸다")
    void reportGateDiagnostics() {
        ScreenPreset preset = presets.stream()
                .filter(p -> p.code().equals(PresetCatalog.BUFFETT_QUALITY_KR))
                .findFirst().orElseThrow();

        List<LocalDate> grid = calendar.every(
                WINDOW_START, calendar.latestTradingDay(), MONTH_STEP);
        assertThat(grid).as("거래일 격자가 비면 잰 것이 없다").isNotEmpty();

        System.out.printf("%n===== P17 진단 축 (%s, %d시점) =====%n",
                preset.code(), grid.size());
        System.out.println("MUST: 아래는 진단이다. 수익률과 같은 표에 두지 않는다.");
        System.out.println("MUST: 탈락과 판정불가를 갈라 읽는다 — 후자는 데이터 결손이다.\n");

        // 게이트 코드 → 시점별 탈락 수. 어느 시점에도 0이면 그 게이트는 상수다.
        Map<String, List<Integer>> failuresByGate = new LinkedHashMap<>();
        Map<String, List<Integer>> unevaluableByGate = new LinkedHashMap<>();
        List<Set<Long>> passSets = new ArrayList<>();
        // 게이트 → 시점별 「임계값이 그날 분포의 몇 분위에 앉았는가」.
        Map<String, List<Double>> thresholdPercentile = new LinkedHashMap<>();

        for (LocalDate asOf : grid) {
            PointInTimeScreener.Outcome outcome = screener.screen(
                    asOf, preset.conditions(), preset.mode(), preset.universeCode());

            Map<String, Integer> failed = new TreeMap<>();
            Map<String, Integer> unevaluable = new TreeMap<>();
            int withAnyValue = 0;
            for (Long stockId : outcome.valuesByStock().keySet()) {
                Map<String, java.math.BigDecimal> values = outcome.valuesByStock().get(stockId);
                if (values.isEmpty()) {
                    continue;
                }
                withAnyValue++;
                ScreeningResult result = evaluateForDiagnostics(
                        stockId, preset, values);
                for (ConditionOutcome co : result.outcomes()) {
                    if (!co.condition().required()) {
                        continue;
                    }
                    String code = co.condition().indicatorCode();
                    switch (co.status()) {
                        case NOT_MATCHED -> failed.merge(code, 1, Integer::sum);
                        case NOT_EVALUABLE -> unevaluable.merge(code, 1, Integer::sum);
                        default -> { }
                    }
                }
            }

            Set<Long> passed = new TreeSet<>();
            outcome.included().forEach(r -> passed.add(r.stockId()));
            passSets.add(passed);

            System.out.printf("%s  대상 %3d · 값이 나온 종목 %3d · 통과 %2d%n",
                    asOf, outcome.evaluated(), withAnyValue, passed.size());
            for (com.stockscreener.screening.IndicatorCondition gate : gateConditions(preset)) {
                thresholdPercentile
                        .computeIfAbsent(gate.indicatorCode(), k -> new ArrayList<>())
                        .add(percentileOfThreshold(gate, outcome));
            }

            for (String gate : sortedGates(preset)) {
                int f = failed.getOrDefault(gate, 0);
                int u = unevaluable.getOrDefault(gate, 0);
                System.out.printf("      %-24s 탈락 %3d · 판정불가 %3d%n", gate, f, u);
                failuresByGate.computeIfAbsent(gate, k -> new ArrayList<>()).add(f);
                unevaluableByGate.computeIfAbsent(gate, k -> new ArrayList<>()).add(u);
            }
        }

        System.out.println("\n----- 아무도 못 자르는 게이트 -----");
        List<String> constants = failuresByGate.entrySet().stream()
                .filter(e -> e.getValue().stream().allMatch(count -> count == 0))
                .map(Map.Entry::getKey)
                .toList();
        if (constants.isEmpty()) {
            System.out.println("없다 — 게이트 여섯이 전부 어느 시점에선가 종목을 잘랐다.");
        } else {
            System.out.println("!! " + constants + " — 어느 시점에도 탈락이 0이다."
                    + " 안전장치가 아니라 상수이며, 화면·테스트·배치 로그 어디에도 안 드러난다.");
        }

        System.out.println("\n----- 임계값이 앉은 분위의 이동 -----");
        System.out.println("2026년 단면으로 정한 절대 임계가 과거에는 다른 자리에 앉는다."
                + " 폭이 크면 그 게이트는 시점마다 다른 엄격도로 걸린 것이다.");
        thresholdPercentile.forEach((code, series) -> {
            double min = series.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            double max = series.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            System.out.printf("%-24s 처음 %.2f → 끝 %.2f · 폭 %.2f~%.2f (이동 %.2f)%n",
                    code, series.getFirst(), series.getLast(), min, max, max - min);
        });

        System.out.println("\n----- 통과 집합의 회전율 (자카드 거리) -----");
        for (int i = 1; i < passSets.size(); i++) {
            System.out.printf("%s → %s: %.2f  (유지 %d · 신규 %d · 이탈 %d)%n",
                    grid.get(i - 1), grid.get(i), jaccardDistance(passSets.get(i - 1), passSets.get(i)),
                    intersectionSize(passSets.get(i - 1), passSets.get(i)),
                    difference(passSets.get(i), passSets.get(i - 1)).size(),
                    difference(passSets.get(i - 1), passSets.get(i)).size());
        }

        System.out.println("\nMUST NOT: 위 수를 근거로 임계값을 조정하지 않는다."
                + " 진단은 조건의 구조를 고칠 근거이지 손잡이를 풀 근거가 아니다.");
    }

    private ScreeningResult evaluateForDiagnostics(
            long stockId, ScreenPreset preset, Map<String, java.math.BigDecimal> values) {
        return conditionEvaluator.evaluate(
                stockId, preset.conditions(), EvaluationMode.SCORING, values);
    }

    private static List<com.stockscreener.screening.IndicatorCondition> gateConditions(
            ScreenPreset preset) {
        return preset.conditions().conditions().stream()
                .filter(com.stockscreener.screening.IndicatorCondition::required).toList();
    }

    /**
     * 그날 값이 나온 종목 분포에서 임계값이 앉은 분위. 0.30이면 「아래에서 30%를 자른다」다.
     *
     * <p>MUST: 방향을 보지 않는다 — 값 오름차순 분위 하나로 통일한다. 방향까지 넣으면
     * {@code LTE} 게이트에서 수가 뒤집혀 <b>이동의 크기를 비교할 수 없게 된다.</b>
     */
    private static double percentileOfThreshold(
            com.stockscreener.screening.IndicatorCondition gate,
            PointInTimeScreener.Outcome outcome) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("value=([^,\\]]+)")
                .matcher(String.valueOf(gate.operand()));
        if (!m.find()) {
            return Double.NaN;
        }
        double threshold = Double.parseDouble(m.group(1));
        long below = 0;
        long total = 0;
        for (Map<String, java.math.BigDecimal> values : outcome.valuesByStock().values()) {
            java.math.BigDecimal value = values.get(gate.indicatorCode());
            if (value == null) {
                continue;
            }
            total++;
            if (value.doubleValue() < threshold) {
                below++;
            }
        }
        return total == 0 ? Double.NaN : (double) below / total;
    }

    private static List<String> sortedGates(ScreenPreset preset) {
        return preset.conditions().conditions().stream()
                .filter(c -> c.required())
                .map(c -> c.indicatorCode())
                .distinct()
                .toList();
    }

    /** 0이면 완전히 같고 1이면 겹치는 종목이 없다. */
    private static double jaccardDistance(Set<Long> a, Set<Long> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<Long> union = new TreeSet<>(a);
        union.addAll(b);
        return 1.0 - (double) intersectionSize(a, b) / union.size();
    }

    private static int intersectionSize(Set<Long> a, Set<Long> b) {
        Set<Long> both = new TreeSet<>(a);
        both.retainAll(b);
        return both.size();
    }

    private static Set<Long> difference(Set<Long> a, Set<Long> b) {
        Set<Long> only = new TreeSet<>(a);
        only.removeAll(b);
        return only;
    }
}
