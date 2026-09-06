package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.preset.ScreenPreset;
import com.stockscreener.screening.store.ScreenDefinition;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 적재 → 저장 → 스윕의 <b>실제 경로</b>를 짧은 구간으로 한 바퀴 돌린다.
 *
 * <p><b>왜 다시 썼는가.</b> 앞선 판은 이 파일 안에 자기 gzip 입출력을 따로 갖고 있었다. 값은
 * 맞았지만 <b>적재가 실제로 쓸 형식이 아닌 것을 검증하고 있었다</b> — {@link PitStore}의
 * 매니페스트·지문·표지 행은 한 번도 안 태웠다는 뜻이다. 이 세션에서 계속 쫓던 거짓 초록과
 * 같은 모양이라 진짜 경로로 갈아끼웠다.
 *
 * <p>확인하는 것 넷.
 * <ol>
 *   <li>저장본으로 낸 <b>최초 통과일</b>이 직접 계산과 같다</li>
 *   <li>{@code _PCTL}을 쓰는 전략이 <b>거절되지 않고 돈다</b> — 저장소는 통화 단위인데 그
 *       백분위는 유니버스별이라, 그 축이 읽기 시점 재구성으로만 살아난다</li>
 *   <li>저장본에 <b>없는 지표</b>를 참조하면 그 전략만 사유와 함께 거절된다(통과 0이 아니다)</li>
 *   <li>거래일이 <b>비면 던진다</b> — 최초 통과일이 조용히 틀려지는 자리다</li>
 * </ol>
 *
 * <p>구간을 짧게 잡는 이유는 값 계산이 날짜당 KRW 14.5초 · USD 37.0초이기 때문이다. 형식과
 * 배선을 보는 데는 며칠이면 충분하고, 1년치는 적재가 따로 진다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class StoreRoundTripProbeTest {

    /** 며칠을 적재할지. 연속이어야 한다 — {@link PitSweep}이 빠진 거래일을 거절한다. */
    private static final int DAYS = 3;

    private static final BigDecimal MIN_SCORE_RATIO = new BigDecimal("0.80");

    @Autowired
    private IndicatorRunner runner;

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private AsOfCalendar calendar;

    @Autowired
    private ScreeningTargets targets;

    @Autowired
    private com.stockscreener.indicator.IndicatorRegistry registry;

    @Autowired
    private ScreenStore screenStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private List<ScreenPreset> presets;

    @Test
    @DisplayName("적재한 저장본으로 스윕한 최초 통과일이 직접 계산과 같고, 못 낼 전략은 거절된다")
    void storeSweepMatchesDirectComputation() throws Exception {
        PitFingerprint fingerprint = new PitFingerprint(jdbcTemplate);
        PitChangeDetector detector = new PitChangeDetector(jdbcTemplate);
        Path dir = Files.createTempDirectory("pit-sweep");
        PitStore store = new PitStore(dir);
        PitSweep sweep = new PitSweep(store, detector, fingerprint, screener, runner, calendar, registry);

        List<PointInTimeScreener.Request> requests = requests();
        System.out.println("대조 전략: " + requests.stream()
                .map(PointInTimeScreener.Request::label).toList());

        // --- 적재: 통화 단위로, 실제 경로 그대로 ---
        Map<String, List<LocalDate>> datesByCurrency = new LinkedHashMap<>();
        for (ScreeningTargets.Target t : targets.all()) {
            datesByCurrency.computeIfAbsent(t.currency(), k -> lastTradingDays(t));
        }
        long loadMs = 0;
        for (Map.Entry<String, List<LocalDate>> e : datesByCurrency.entrySet()) {
            long t0 = System.nanoTime();
            loadCurrency(store, fingerprint, detector, e.getKey(), e.getValue());
            loadMs += (System.nanoTime() - t0) / 1_000_000;
        }

        // --- 스윕: 저장본만 읽는다 ---
        long t1 = System.nanoTime();
        PitSweep.Result result = sweep.sweep(requests, targets.all(), MIN_SCORE_RATIO);
        long sweepMs = (System.nanoTime() - t1) / 1_000_000;

        // --- 대조: 같은 날짜를 직접 계산해 최초 통과일을 다시 만든다 ---
        Map<String, Map<Long, LocalDate>> direct = new LinkedHashMap<>();
        long t2 = System.nanoTime();
        for (ScreeningTargets.Target t : targets.all()) {
            for (LocalDate asOf : datesByCurrency.get(t.currency())) {
                Map<String, PointInTimeScreener.Outcome> outcomes =
                        screener.screenAll(asOf, requests, t);
                for (PointInTimeScreener.Request r : requests) {
                    PointInTimeScreener.Outcome outcome = outcomes.get(r.label());
                    if (outcome.rejected()) {
                        continue;
                    }
                    Map<Long, LocalDate> acc = direct.computeIfAbsent(
                            r.label() + " · " + t.code(), k -> new LinkedHashMap<>());
                    for (ScreeningResult row : outcome.included()) {
                        if (row.scoreRatio().compareTo(MIN_SCORE_RATIO) >= 0) {
                            acc.putIfAbsent(row.stockId(), asOf);
                        }
                    }
                }
            }
        }
        long directMs = (System.nanoTime() - t2) / 1_000_000;

        report(result, direct, loadMs, sweepMs, directMs);
        assertSameFirstPasses(result, direct);
        assertUniversePercentileRan(result);
        assertRejectionCarriesReason(sweep, store, requests, datesByCurrency);
        assertGapIsRefused(sweep, store, dir, datesByCurrency);

        try (var s = Files.list(dir)) {
            for (Path f : s.toList()) {
                Files.deleteIfExists(f);
            }
        }
        Files.deleteIfExists(dir);
    }

    /** v12 하나로는 {@code _PCTL} 축이 안 걸린다 — 그 축을 쓰는 프리셋을 반드시 함께 넣는다. */
    private List<PointInTimeScreener.Request> requests() {
        List<PointInTimeScreener.Request> out = new ArrayList<>();
        ScreenDefinition v12 = loadByName("v12");
        out.add(new PointInTimeScreener.Request("v12", v12.conditions(), v12.mode()));
        for (ScreenPreset p : presets) {
            boolean usesUniversePctl = p.conditions().referencedIndicatorCodes().stream()
                    .anyMatch(c -> c.endsWith("_PCTL") && !c.endsWith("_SECTOR_PCTL"));
            if (usesUniversePctl) {
                out.add(new PointInTimeScreener.Request(p.code(), p.conditions(), p.mode()));
            }
        }
        assertThat(out).as("_PCTL을 쓰는 프리셋이 없어 그 축이 검증되지 않는다").hasSizeGreaterThan(1);
        return out;
    }

    private List<LocalDate> lastTradingDays(ScreeningTargets.Target target) {
        LocalDate latest = calendar.latestTradingDay(target.markets());
        List<LocalDate> all =
                calendar.everyTradingDay(latest.minusMonths(2), latest, target.markets());
        return all.subList(Math.max(0, all.size() - DAYS), all.size());
    }

    private void loadCurrency(PitStore store, PitFingerprint fingerprint,
                              PitChangeDetector detector, String currency, List<LocalDate> dates)
            throws Exception {
        List<Long> population = screener.populationFor(currency);
        // MUST: 적재 실행 하나에 기준 시각 하나. 날짜마다 새로 찍으면 읽는 쪽이 그 수만큼
        // 변경 훑기를 돌아야 하고(회당 2초대), 실측에서 그것이 스윕 시간을 통째로 지배했다.
        Instant startedAt = Instant.now();
        Map<Long, String> inPlaceDigests = detector.inPlaceDigests(startedAt);
        for (LocalDate asOf : dates) {
            Map<String, NavigableMap<LocalDate, BigDecimal>> indexes =
                    runner.marketIndexesFor(asOf);
            Map<Long, Map<String, BigDecimal>> values = new LinkedHashMap<>();
            Set<String> codes = new TreeSet<>();
            Set<Long> failed = new LinkedHashSet<>();
            for (Long stockId : population) {
                try {
                    Map<String, BigDecimal> v =
                            runner.computeAt(stockId, asOf, indexes, FinancialVisibility.KNOWN_ON);
                    values.put(stockId, new LinkedHashMap<>(v));
                    codes.addAll(v.keySet());
                } catch (RuntimeException ex) {
                    // MUST NOT: 삼키지 않는다. 실패한 종목이 있으면 그 날짜는 완료가 아니다.
                    failed.add(stockId);
                    System.out.printf("   적재 실패 %s %s stock=%d: %s%n",
                            currency, asOf, stockId, ex.getMessage());
                }
            }
            store.writeDate(currency, asOf, values, new PitStore.Stamp(
                    population.size(), PointInTimeScreener.declaredCodesOf(registry),
                    fingerprint.forCurrency(currency, asOf), fingerprint.forShared(currency, asOf),
                    inPlaceDigests, PitFingerprint.forCalculators(registry), startedAt, failed));
        }
    }

    private void report(PitSweep.Result result, Map<String, Map<Long, LocalDate>> direct,
                        long loadMs, long sweepMs, long directMs) {
        System.out.printf("%n===== 저장본 스윕 대 직접 계산 (통화별 %d거래일) =====%n", DAYS);
        System.out.printf("적재 %,dms · 저장본 스윕 %,dms · 직접 계산 %,dms%n",
                loadMs, sweepMs, directMs);
        System.out.printf("구간 %s · 그 자리에서 다시 만든 칸 %d%n",
                result.rangeText(), result.repairedCells());
        System.out.println("-- 거절 --");
        if (result.rejections().isEmpty()) {
            System.out.println("   없음");
        }
        result.rejections().forEach((k, v) -> System.out.printf("   %-40s %s%n", k, v));
        System.out.println("-- 최초 통과 (저장본 / 직접) --");
        Set<String> keys = new TreeSet<>(result.firstPasses().keySet());
        keys.addAll(direct.keySet());
        for (String k : keys) {
            System.out.printf("   %-40s %d / %d%n", k,
                    result.firstPasses().getOrDefault(k, Map.of()).size(),
                    direct.getOrDefault(k, Map.of()).size());
        }
    }

    /** MUST: 칸 수가 아니라 <b>종목과 날짜</b>를 맞춘다. 개수만 보면 서로 다른 종목이 통과한다. */
    private void assertSameFirstPasses(PitSweep.Result result,
                                       Map<String, Map<Long, LocalDate>> direct) {
        Set<String> diffs = new TreeSet<>();
        int compared = 0;
        Set<String> keys = new TreeSet<>(result.firstPasses().keySet());
        keys.addAll(direct.keySet());
        for (String key : keys) {
            Map<Long, PitSweep.FirstPass> mine = result.firstPasses().getOrDefault(key, Map.of());
            Map<Long, LocalDate> theirs = direct.getOrDefault(key, Map.of());
            for (Map.Entry<Long, PitSweep.FirstPass> e : mine.entrySet()) {
                compared++;
                LocalDate other = theirs.get(e.getKey());
                if (other == null) {
                    diffs.add(key + " 저장본만 " + e.getKey());
                } else if (!other.equals(e.getValue().asOf())) {
                    diffs.add(key + " 통과일 " + e.getKey()
                            + " 저장본=" + e.getValue().asOf() + " 직접=" + other);
                }
            }
            for (Long id : theirs.keySet()) {
                if (!mine.containsKey(id)) {
                    diffs.add(key + " 직접만 " + id);
                }
            }
        }
        diffs.stream().limit(15).forEach(d -> System.out.println("   차이: " + d));
        assertThat(compared).as("대조한 칸이 0이면 이 탐침은 아무것도 재지 않았다").isPositive();
        assertThat(diffs).as("저장본 스윕이 직접 계산과 다른 최초 통과일을 냈다").isEmpty();
    }

    /** 통과 0이어도 <b>거절은 아니어야</b> 한다 — 거절이면 그 축이 닫힌 것이다. */
    private void assertUniversePercentileRan(PitSweep.Result result) {
        List<String> pctlRejections = result.rejections().entrySet().stream()
                .filter(e -> e.getValue().contains("_PCTL"))
                .map(e -> e.getKey() + " — " + e.getValue())
                .toList();
        assertThat(pctlRejections)
                .as("유니버스 백분위 전략이 거절됐다 — 저장소를 붙인 이유가 그 축을 닫는 것이 아니다")
                .isEmpty();
    }

    /** 저장본에 없는 지표는 <b>사유와 함께</b> 거절된다. 조용한 통과 0이면 표가 거짓말을 한다. */
    private void assertRejectionCarriesReason(PitSweep sweep, PitStore store,
                                              List<PointInTimeScreener.Request> requests,
                                              Map<String, List<LocalDate>> datesByCurrency)
            throws Exception {
        ConditionGroup group = requests.get(0).group();
        Set<String> stored = new TreeSet<>(List.of("PER", "ROE"));
        Set<String> missing = PointInTimeScreener.missingCodesOf(
                group.resolveFor("KRW"), stored);
        assertThat(missing)
                .as("저장본이 PER·ROE만 담았는데 v12가 그것만 쓴다고 판정됐다")
                .isNotEmpty();

        // 재구성되는 축은 원천만 있으면 모자란 것이 아니다.
        assertThat(PointInTimeScreener.missingCodesOf(
                referencing("PER_SECTOR_PCTL"), Set.of("PER")))
                .as("원천이 있는데 업종 백분위를 「없는 지표」로 잡았다 — 그 전략들이 통째로 닫힌다")
                .isEmpty();
        assertThat(PointInTimeScreener.missingCodesOf(
                referencing("PER_PCTL"), Set.of("PER")))
                .as("원천이 있는데 유니버스 백분위를 「없는 지표」로 잡았다").isEmpty();
        assertThat(PointInTimeScreener.missingCodesOf(
                referencing("PER_PCTL"), Set.of("ROE")))
                .as("원천이 없는데 통과시켰다 — 판정이 아무것도 막지 않는다")
                .containsExactly("PER_PCTL");
    }

    /** 하루가 비면 최초 통과일이 밀린다. 조용히 지나가면 표 전체가 미세하게 틀린다. */
    private void assertGapIsRefused(PitSweep sweep, PitStore store, Path dir,
                                    Map<String, List<LocalDate>> datesByCurrency) {
        String currency = datesByCurrency.keySet().iterator().next();
        List<LocalDate> dates = datesByCurrency.get(currency);
        LocalDate middle = dates.get(dates.size() / 2);
        ScreeningTargets.Target target = targets.all().stream()
                .filter(t -> t.currency().equals(currency)).findFirst().orElseThrow();
        try {
            Files.delete(dir.resolve(currency + "-" + middle + ".manifest"));
        } catch (Exception e) {
            throw new IllegalStateException("가운데 날짜를 지우지 못했다", e);
        }
        assertThatThrownBy(() -> sweep.coverageOf(currency, target.markets()))
                .as("거래일이 비었는데 그대로 스윕했다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비었다");
    }

    /** 코드 하나만 참조하는 최소 조건군. 코드 집합 판정만 보는 자리라 임계값은 뜻이 없다. */
    private static ConditionGroup referencing(String code) {
        return ConditionGroup.and(IndicatorCondition.of(
                code, ComparisonOperator.GT, ConditionOperand.Literal.of("0")));
    }

    private ScreenDefinition loadByName(String namePart) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM screen WHERE screen_type = 'CUSTOM' AND is_active"
                        + " AND lower(name) LIKE lower(?) ORDER BY id",
                Long.class, "%" + namePart + "%");
        if (ids.size() != 1) {
            throw new IllegalStateException("「" + namePart + "」가 " + ids.size() + "개 걸린다");
        }
        return screenStore.findById(ids.get(0)).orElseThrow();
    }
}
