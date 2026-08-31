package com.stockscreener.probe.backtest;

import com.stockscreener.screening.ConditionOutcome;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조건 단위 수익률 — 이 phase가 「그렇게 고르면 버는가」에 실제로 답하는 자리다
 * (ADR-0071, phase-criteria §P17).
 *
 * <p><b>묻는 단위가 프리셋 포트폴리오가 아니라 조건이다.</b> 통과 종목이 한 자릿수라
 * 포트폴리오 수익률의 귀무 SD가 10~13.5%p이고, 어떤 적재를 해도 답이 안 나온다. 반면
 * 유니버스를 조건 하나로 가른 두 집단의 차는 귀무 SD가 4.3~5.6%p라 <b>답이 나오는 물음</b>이다.
 *
 * <p><b>MUST NOT: 이 표의 값으로 임계값을 조정하지 않는다. 어느 방향으로도 하지 않는다.</b>
 * 결과의 용도는 <b>다음에 프리셋을 문헌에서 다시 짤 때의 사전정보</b>이지 손잡이를 푸는
 * 근거가 아니다(ADR-0044·0061의 금지를 수익률로 확장한 것이다).
 *
 * <p><b>MUST: 진단 축과 같은 표에 두지 않는다.</b> {@link GateDiagnosticsTest}가 내는 것은
 * 프리셋을 고칠 근거가 되고 이 표는 되지 않는다 — 같은 표에 두면 그 구분이 읽는 사람에게 사라진다.
 *
 * <p><b>읽을 때 반드시 함께 보는 것 둘.</b>
 * <ul>
 *   <li><b>생존 편향이 차를 0 쪽으로 누른다</b> — 폐지 종목이 DB에 아예 없고 그것들은 거의 전부
 *       미충족 집단이었다. 실측 하한이 6개월당 0.57~2.47%p이고 검출 임계가 3.5%p라,
 *       <b>작은 양(+)의 효과는 원리적으로 가려진다.</b>
 *   <li><b>수익성 게이트가 좋게 나오면 그것부터 의심한다</b> — ADR-0061의 국내 수익성
 *       재현율 5.0%가 사전 확률이고, 이 프리셋의 게이트 여섯 중 셋이 수익성이다.
 * </ul>
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class ConditionReturnTest {

    /**
     * <b>비중첩</b> 6개월 시점 여덟. 겹치게 잘라 표본을 늘리지 않는다 — 겹치는 창은 관측 수를
     * 늘리되 정보를 늘리지 않고, 그 4년에 실제로 일어난 일은 하나다.
     */
    private static final List<LocalDate> ANCHORS = List.of(
            LocalDate.of(2022, 4, 1), LocalDate.of(2022, 10, 1),
            LocalDate.of(2023, 4, 1), LocalDate.of(2023, 10, 1),
            LocalDate.of(2024, 4, 1), LocalDate.of(2024, 10, 1),
            LocalDate.of(2025, 4, 1), LocalDate.of(2025, 10, 1));

    /**
     * 아웃오브샘플 경계. <b>as-of에 건다</b> — 그 이전 판정은 FY2023 이하 재무로 이뤄져,
     * 임계 보정이 본 단면(FY2024·FY2025)과 겹치지 않는다.
     */
    private static final LocalDate OOS_BOUNDARY = LocalDate.of(2025, 4, 1);

    /**
     * 수익률 축에서 빼는 조건. 가격 1,261봉이 필요해 값이 2025-02-18부터 나오고,
     * OOS 경계가 2025-04-01이라 <b>아웃오브샘플 시점이 0</b>이다. 시세 바닥이 2020-01-02라
     * <b>적재로 안 풀린다</b> — 「못 잰다」로 적는 것이 옳다(진단 축에서는 관측한다).
     */
    private static final String NOT_MEASURABLE = "RETAINED_TO_MARKET_4Y";

    /** 사전등록된 검출 가능 효과(2σ). 결과를 보기 전에 정해져 있었다. */
    private static final double DETECTABLE_EFFECT_PP = 3.5;

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private AsOfCalendar calendar;

    @Autowired
    private ForwardReturns forwardReturns;

    @Autowired
    private List<ScreenPreset> presets;

    /** 분위 수. 5분위면 시점당 한 칸에 60~70종목이라 평균이 흔들리지 않는다. */
    private static final int QUANTILES = 5;

    /** 한 조건 × 한 시점의 관측. */
    private record Observation(LocalDate asOf, double differencePp,
                               int satisfied, int notSatisfied, int unevaluable) {
    }

    @Test
    @DisplayName("조건별 사후 6개월 수익률 차를 인샘플·아웃오브샘플로 나눠 낸다")
    void measureConditionReturns() {
        ScreenPreset preset = presets.stream()
                .filter(p -> p.code().equals(PresetCatalog.BUFFETT_QUALITY))
                .findFirst().orElseThrow();

        List<IndicatorCondition> conditions = preset.conditions().conditions().stream()
                .filter(c -> !c.indicatorCode().equals(NOT_MEASURABLE))
                .toList();

        Map<String, List<Observation>> byCondition = new LinkedHashMap<>();
        // 지표 코드 → 분위별 수익률(OOS 시점만 모은다). 단조성은 임계와 무관한 물음이라
        // 조건이 아니라 **지표** 단위다 — DEBT_RATIO가 임계 둘로 나와도 분포는 하나다.
        Map<String, List<List<Double>>> quantileReturns = new LinkedHashMap<>();
        int totalExcluded = 0;

        System.out.printf("%n===== P17 조건 단위 수익률 (%s) =====%n", preset.code());
        System.out.println("MUST NOT: 이 표로 임계값을 조정하지 않는다. 용도는 다음 설계의 사전정보다.");
        System.out.printf("사전등록 검출 가능 효과 %.1f%%p/6개월(2σ) · OOS 경계 %s%n%n",
                DETECTABLE_EFFECT_PP, OOS_BOUNDARY);

        for (LocalDate anchor : ANCHORS) {
            LocalDate asOf = calendar.onOrBefore(anchor);
            PointInTimeScreener.Outcome outcome = screener.screen(
                    asOf, preset.conditions(), EvaluationMode.SCORING, preset.universeCode());
            ForwardReturns.Window window = forwardReturns.measure(asOf, 6);
            totalExcluded += window.excluded().size();

            System.out.printf("%s → %s  값이 나온 종목 %d · 수익률 있는 종목 %d · 기업행위 제외 %d%n",
                    asOf, window.end(),
                    (int) outcome.valuesByStock().values().stream().filter(v -> !v.isEmpty()).count(),
                    window.returns().size(), window.excluded().size());

            if (asOf.isBefore(OOS_BOUNDARY)) {
                collectQuantiles(conditions, outcome, window, quantileReturns);
            }

            for (IndicatorCondition condition : conditions) {
                List<Double> satisfied = new ArrayList<>();
                List<Double> notSatisfied = new ArrayList<>();
                int unevaluable = 0;

                for (Map.Entry<Long, Map<String, BigDecimal>> entry
                        : outcome.valuesByStock().entrySet()) {
                    Long stockId = entry.getKey();
                    Double ret = window.returns().get(stockId);
                    if (ret == null || window.excluded().contains(stockId)) {
                        continue;
                    }
                    ConditionOutcome co = condition.evaluate(entry.getValue());
                    // MUST: 판정불가는 표본에서 뺀다. 미충족으로 접으면 데이터 결손이 곧
                    // 미충족이 되어 부호가 정해진 편향이 들어간다.
                    if (co.status() == ConditionOutcome.Status.NOT_EVALUABLE) {
                        unevaluable++;
                        continue;
                    }
                    (co.passesFilter() ? satisfied : notSatisfied).add(ret);
                }

                // 한쪽이 비면 차가 정의되지 않는다. 0으로 두면 「효과 없음」으로 읽힌다.
                if (satisfied.isEmpty() || notSatisfied.isEmpty()) {
                    continue;
                }
                double difference = 100.0 * (mean(satisfied) - mean(notSatisfied));
                byCondition.computeIfAbsent(key(condition), k -> new ArrayList<>())
                        .add(new Observation(asOf, difference,
                                satisfied.size(), notSatisfied.size(), unevaluable));
            }
        }

        assertThat(byCondition)
                .as("조건을 하나도 재지 못했으면 이 측정은 '효과 없음'이 아니라 '못 쟀다'이다")
                .isNotEmpty();

        report("필수 게이트", conditions.stream().filter(IndicatorCondition::required).toList(),
                byCondition);
        report("배점", conditions.stream().filter(c -> !c.required()).toList(), byCondition);
        reportQuantiles(quantileReturns);

        System.out.printf("%n----- 결과에 반드시 붙이는 것 -----%n");
        System.out.printf("미보정 기업행위로 제외한 (종목,구간) %d건%n", totalExcluded);
        System.out.printf("%s는 이 표본으로 못 잰다 — 값이 2025-02-18부터라 OOS 시점이 0이다.%n",
                NOT_MEASURABLE);
        System.out.println("생존 편향이 차를 0 쪽으로 0.57~2.47%p/6개월 누른다(하한)."
                + " 작은 양의 효과는 원리적으로 가려진다.");
        System.out.println("MUST: 수익성 게이트(ROE_MIN_3Y·ROIC_MIN_3Y·PROFIT_STREAK_Y)가 좋게"
                + " 나왔다면 ADR-0061의 재현율 5.0%를 사전 확률로 두고 그것부터 의심한다.");
    }

    /**
     * 지표 값 오름차순 5분위의 사후 수익률을 모은다.
     *
     * <p><b>임계값을 지나지 않는 물음이다.</b> 충족/미충족 차는 그 임계 하나에서만 답하지만,
     * 분위는 <b>지표 자체에 방향이 있는가</b>를 묻는다 — 차가 유의해도 단조가 아니면 그 임계가
     * 우연히 좋은 자리에 있었던 것이고, 차가 안 나와도 단조면 신호가 임계 밖에 있는 것이다.
     */
    private static void collectQuantiles(
            List<IndicatorCondition> conditions, PointInTimeScreener.Outcome outcome,
            ForwardReturns.Window window, Map<String, List<List<Double>>> into) {
        for (String code : conditions.stream().map(IndicatorCondition::indicatorCode)
                .distinct().toList()) {
            List<double[]> pairs = new ArrayList<>();
            for (Map.Entry<Long, Map<String, BigDecimal>> entry
                    : outcome.valuesByStock().entrySet()) {
                BigDecimal value = entry.getValue().get(code);
                Double ret = window.returns().get(entry.getKey());
                if (value == null || ret == null || window.excluded().contains(entry.getKey())) {
                    continue;
                }
                pairs.add(new double[] {value.doubleValue(), ret});
            }
            if (pairs.size() < QUANTILES * 5) {
                continue;
            }
            pairs.sort((a, b) -> Double.compare(a[0], b[0]));
            List<List<Double>> buckets = into.computeIfAbsent(code, k -> {
                List<List<Double>> fresh = new ArrayList<>();
                for (int i = 0; i < QUANTILES; i++) {
                    fresh.add(new ArrayList<>());
                }
                return fresh;
            });
            for (int q = 0; q < QUANTILES; q++) {
                int from = q * pairs.size() / QUANTILES;
                int to = (q + 1) * pairs.size() / QUANTILES;
                double sum = 0;
                for (int i = from; i < to; i++) {
                    sum += pairs.get(i)[1];
                }
                // 시점마다 한 칸의 평균을 하나씩 쌓는다 — 시점 간 가중이 종목 수로 쏠리지 않는다.
                buckets.get(q).add(100.0 * sum / (to - from));
            }
        }
    }

    private static void reportQuantiles(Map<String, List<List<Double>>> quantileReturns) {
        System.out.printf("%n----- 분위별 단조성 (지표 오름차순 %d분위, OOS 시점 평균 %%p) -----%n",
                QUANTILES);
        System.out.println("MUST: 차가 유의해도 단조가 아니면 그 임계가 우연히 좋은 자리였던 것이다.");
        quantileReturns.forEach((code, buckets) -> {
            StringBuilder line = new StringBuilder("%-24s".formatted(code));
            for (List<Double> bucket : buckets) {
                line.append("%+8.1f".formatted(mean(bucket)));
            }
            double low = mean(buckets.get(0));
            double high = mean(buckets.get(QUANTILES - 1));
            line.append("   Q5−Q1 %+.1f%%p".formatted(high - low));
            System.out.println(line);
        });
    }

    /**
     * 가족 하나를 표로 낸다. <b>Holm은 가족 안에서만 건다</b> — 게이트와 배점은 물음의 성격이
     * 다르고, 하나로 합치면 검정 수가 늘어 둘 다 무뎌진다.
     *
     * <p>인샘플은 <b>p-값을 내지 않는다.</b> 시점이 둘뿐이라 자유도가 1이고, 거기서 나온
     * p는 숫자일 뿐이다 — 그것을 표에 두면 결론처럼 읽힌다.
     */
    private static void report(String family, List<IndicatorCondition> conditions,
                               Map<String, List<Observation>> byCondition) {
        List<String> keys = conditions.stream().map(ConditionReturnTest::key)
                .filter(byCondition::containsKey).toList();
        if (keys.isEmpty()) {
            return;
        }

        // OOS만으로 검정한다. 인샘플은 임계 보정이 본 단면 위라 지위가 다르다.
        List<double[]> oosStats = new ArrayList<>();
        for (String conditionKey : keys) {
            List<Double> oos = byCondition.get(conditionKey).stream()
                    .filter(o -> o.asOf().isBefore(OOS_BOUNDARY))
                    .map(Observation::differencePp).toList();
            oosStats.add(statistics(oos));
        }
        double[] pValues = oosStats.stream().mapToDouble(s -> s[3]).toArray();
        double[] holm = StudentT.holm(pValues);

        System.out.printf("%n----- %s (Holm 가족 크기 %d) -----%n", family, keys.size());
        System.out.printf("%-30s %3s %8s %7s %6s %8s %8s | %s%n",
                "조건", "n", "OOS평균", "SE", "t", "p", "Holm p", "인샘플(검정 없음)");
        for (int i = 0; i < keys.size(); i++) {
            List<Observation> all = byCondition.get(keys.get(i));
            double[] s = oosStats.get(i);
            String inSample = all.stream()
                    .filter(o -> !o.asOf().isBefore(OOS_BOUNDARY))
                    .map(o -> "%+.1f".formatted(o.differencePp()))
                    .reduce((a, b) -> a + ", " + b).orElse("없음");
            long n = all.stream().filter(o -> o.asOf().isBefore(OOS_BOUNDARY)).count();
            System.out.printf("%-30s %3d %+7.2f%%p %6.2f %6.2f %8.3f %8.3f | %s%n",
                    keys.get(i), n, s[0], s[1], s[2], s[3], holm[i], inSample);
        }
        System.out.println("MUST: Holm p를 본다. 개별 p로 결론을 내면 조건 16개에서"
                + " 기대 오탐이 0.80개라 아무 관계가 없어도 하나는 좋아 보인다."
                + " 실제로 재는 것은 15개다 — RETAINED_TO_MARKET_4Y가 OOS 0시점이라 빠진다.");
        System.out.println("MUST: n을 본다. 사전등록의 3.5%p는 시점 8개 기준이고,"
                + " OOS만 쓰면 n이 그보다 작아 검출 한계가 그만큼 커진다.");
    }

    /** 평균·SE·t·양측 p. 시점이 2 미만이면 검정하지 않는다. */
    private static double[] statistics(List<Double> values) {
        int n = values.size();
        if (n < 2) {
            return new double[] {n == 1 ? values.get(0) : Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN};
        }
        double mean = mean(values);
        double variance = values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean)).sum() / (n - 1);
        double standardError = Math.sqrt(variance / n);
        double t = standardError == 0 ? 0 : mean / standardError;
        return new double[] {mean, standardError, t, StudentT.twoSidedPValue(t, n - 1)};
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /**
     * 같은 지표가 임계만 달리 두 번 나오므로({@code DEBT_RATIO}) 임계까지 키에 넣는다.
     *
     * <p>{@code operand().toString()}을 그대로 쓰지 않는다 — {@code Literal[value=10,
     * upperBound=null]}로 나와 표가 읽히지 않는다. 표가 이 측정의 산출물이다.
     */
    private static String key(IndicatorCondition condition) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("value=([^,\\]]+)")
                .matcher(String.valueOf(condition.operand()));
        String threshold = m.find() ? m.group(1) : String.valueOf(condition.operand());
        return "%s %s %s".formatted(condition.indicatorCode(),
                switch (condition.operator()) {
                    case GTE -> ">=";
                    case LTE -> "<=";
                    case GT -> ">";
                    case LT -> "<";
                    case EQ -> "=";
                    case NEQ -> "!=";
                    case BETWEEN -> "in";
                }, threshold);
    }
}
