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
 *   <li><b>그 회전의 게이트별 귀속</b> — 회전율만으로는 <b>무엇을 고칠지 정할 수 없다</b>.
 *       금액 게이트가 뒤집는다면 임계가 시장을 안 따라가서이고(ADR-0072가 그 수리를 막았다),
 *       재무 게이트가 뒤집는다면 원저의 「장기 보유」와 어긋나는 것은 조건이 아니라
 *       <b>분기 재공시 주기</b>이며 설계로 풀 것이 아니다.
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
                .filter(p -> p.code().equals(PresetCatalog.BUFFETT_QUALITY))
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
        // 시점별 「종목 → 그 종목을 막은 필수 게이트 집합」. 비면 통과다.
        // 값이 하나도 없는 종목은 아예 넣지 않는다 — 회전의 원인이 게이트가 아니라 데이터라서,
        // 그것을 게이트에 귀속하면 고칠 수 없는 것을 고칠 것으로 읽게 된다.
        List<Map<Long, Set<String>>> blockersByAsOf = new ArrayList<>();
        // 게이트 → 시점별 「임계값이 그날 분포의 몇 분위에 앉았는가」.
        Map<String, List<Double>> thresholdPercentile = new LinkedHashMap<>();

        for (LocalDate asOf : grid) {
            PointInTimeScreener.Outcome outcome = screener.screen(
                    asOf, preset.conditions(), preset.mode(), preset.universeCode());

            Map<String, Integer> failed = new TreeMap<>();
            Map<String, Integer> unevaluable = new TreeMap<>();
            Map<Long, Set<String>> blockers = new LinkedHashMap<>();
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
                        case NOT_MATCHED -> {
                            failed.merge(code, 1, Integer::sum);
                            blockers.computeIfAbsent(stockId, k -> new TreeSet<>()).add(code);
                        }
                        case NOT_EVALUABLE -> {
                            unevaluable.merge(code, 1, Integer::sum);
                            blockers.computeIfAbsent(stockId, k -> new TreeSet<>()).add(code);
                        }
                        default -> { }
                    }
                }
                blockers.computeIfAbsent(stockId, k -> new TreeSet<>());
            }
            blockersByAsOf.add(blockers);

            Set<Long> passed = new TreeSet<>();
            outcome.included().forEach(r -> passed.add(r.stockId()));
            passSets.add(passed);

            // 귀속은 blockers가 통과 집합과 같은 것을 가리킬 때만 뜻이 있다. 스크리너의
            // 포함 판정과 여기서 다시 돌린 평가가 갈리면 아래 표가 통째로 헛것이 된다.
            Set<Long> derivedPass = new TreeSet<>(blockers.entrySet().stream()
                    .filter(e -> e.getValue().isEmpty())
                    .map(Map.Entry::getKey)
                    .toList());
            assertThat(derivedPass)
                    .as("%s: 재평가로 얻은 통과 집합이 스크리너의 것과 달라 귀속이 성립하지 않는다", asOf)
                    .isEqualTo(passed);

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

        reportTurnoverAttribution(grid, passSets, blockersByAsOf);

        System.out.println("\nMUST NOT: 위 수를 근거로 임계값을 조정하지 않는다."
                + " 진단은 조건의 구조를 고칠 근거이지 손잡이를 풀 근거가 아니다.");
    }

    /**
     * 회전을 <b>게이트에 귀속</b>한다 — 「분기마다 3분의 1이 바뀐다」의 원인을 가른다.
     *
     * <p>들어온 종목은 <b>직전 시점에 그를 막고 있던</b> 게이트에, 나간 종목은 <b>이번 시점에
     * 그를 막은</b> 게이트에 귀속한다.
     *
     * <p>IMPORTANT — <b>한 종목이 여러 게이트에 동시에 걸리면 전부에 센다.</b> 그래서 귀속 합이
     * 전환 수를 넘는다. 하나만 고르면 「대표 원인」이 생기는데 그런 것은 없다 — 두 게이트가
     * 같이 뒤집힌 종목에서 어느 쪽이 원인인지는 이 자료로 정해지지 않는다.
     *
     * <p><b>값이 없다가 생긴(또는 사라진) 종목은 따로 센다.</b> 그 회전의 원인은 게이트가 아니라
     * 적재·상장 이력이고, 게이트에 얹으면 고칠 수 없는 것을 고칠 것으로 읽게 된다.
     */
    private static void reportTurnoverAttribution(
            List<LocalDate> grid,
            List<Set<Long>> passSets,
            List<Map<Long, Set<String>>> blockersByAsOf) {

        Map<String, Integer> entryCause = new TreeMap<>();
        Map<String, Integer> exitCause = new TreeMap<>();
        int transitions = 0;
        int dataCaused = 0;

        for (int i = 1; i < passSets.size(); i++) {
            Map<Long, Set<String>> prev = blockersByAsOf.get(i - 1);
            Map<Long, Set<String>> cur = blockersByAsOf.get(i);

            for (Long stockId : difference(passSets.get(i), passSets.get(i - 1))) {
                transitions++;
                Set<String> released = prev.get(stockId);
                if (released == null) {
                    dataCaused++;      // 직전 시점에는 값이 하나도 없던 종목이다.
                    continue;
                }
                released.forEach(gate -> entryCause.merge(gate, 1, Integer::sum));
            }
            for (Long stockId : difference(passSets.get(i - 1), passSets.get(i))) {
                transitions++;
                Set<String> blocked = cur.get(stockId);
                if (blocked == null) {
                    dataCaused++;      // 이번 시점에 값이 통째로 사라진 종목이다.
                    continue;
                }
                blocked.forEach(gate -> exitCause.merge(gate, 1, Integer::sum));
            }
        }

        System.out.println("\n----- 그 회전을 무엇이 만들었나 (게이트별 귀속) -----");
        System.out.printf("전환 %d건 (%s ~ %s, 연속한 시점 쌍 %d개)%n",
                transitions, grid.getFirst(), grid.getLast(), passSets.size() - 1);
        System.out.println("MUST: 한 종목이 여러 게이트에 걸리면 전부에 센다 —"
                + " 아래 합은 전환 수를 넘는다. 「대표 원인」은 이 자료로 정해지지 않는다.");
        if (transitions == 0) {
            System.out.println("잴 것이 없었다 — 통과 집합이 한 번도 바뀌지 않았다.");
            return;
        }

        Set<String> gates = new TreeSet<>(entryCause.keySet());
        gates.addAll(exitCause.keySet());
        System.out.printf("%-24s %8s %8s %8s%n", "게이트", "진입", "이탈", "합");
        gates.stream()
                .sorted((a, b) -> Integer.compare(
                        exitCause.getOrDefault(b, 0) + entryCause.getOrDefault(b, 0),
                        exitCause.getOrDefault(a, 0) + entryCause.getOrDefault(a, 0)))
                .forEach(gate -> System.out.printf("%-24s %8d %8d %8d%n",
                        gate,
                        entryCause.getOrDefault(gate, 0),
                        exitCause.getOrDefault(gate, 0),
                        entryCause.getOrDefault(gate, 0) + exitCause.getOrDefault(gate, 0)));
        System.out.printf("%-24s %8s %8s %8d  (게이트가 아니라 적재·상장 이력이다)%n",
                "값 없음", "", "", dataCaused);

        System.out.println("읽는 법: 금액 게이트(MARKET_CAP·TURNOVER_60)가 위에 오면 회전의 원인이"
                + " 임계가 시장을 안 따라가는 것이고, 그 수리는 ADR-0072가 막았다."
                + " 재무 게이트가 위에 오면 원저의 「장기 보유」와 어긋나는 것은 조건이 아니라"
                + " 분기 재공시 주기이며 설계로 풀 것이 아니다.");
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
