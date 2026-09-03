package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
import com.stockscreener.indicator.IndicatorRegistry;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시점 재현 값을 <b>디스크에 남기는</b> 유일한 진입점. 이 클래스가 3.6시간을 진다.
 *
 * <p>MUST: 클래스를 지정해 돌린다. {@code backtestProbe}는 {@code @Tag("backtest")} 전부를
 * 돌리므로 지정 없이 치면 몇 시간짜리 탐침들이 함께 딸려 온다.
 *
 * <pre>
 * caffeinate -is ./gradlew backtestProbe --tests "*PitLoadTest*" -Dpit.days=255
 * </pre>
 *
 * <p><b>{@code caffeinate}가 권고가 아니다.</b> 실측으로 이 기계는 {@code pmset sleep = 1} —
 * <b>유휴 1분에 잠든다.</b> 무인으로 3.6시간을 돌리면 거의 첫 순간에 죽는다.
 *
 * <p><b>재개가 이 클래스의 절반이다.</b> 끝난 날짜는 다시 계산하지 않고, 종목 몇 개만 낡았으면
 * 저장본을 읽어 <b>그 종목만</b> 갈아 끼운다. 낡음 판정은 {@link PitFingerprint}의 내용 지문이고
 * 감사 타임스탬프를 안 본다 — 실측 24시간에 {@code updated_at}은 1,350종목 전부에서 움직였고
 * 값이 바뀐 종목은 1개였다.
 *
 * <p><b>구간</b>은 {@code -Dpit.days}(통화별 최근 거래일 수, 기본 255)나
 * {@code -Dpit.from}·{@code -Dpit.to}로 준다. 255가 기본인 이유는 실측이다 — 254거래일이면
 * 미국 최소 구간이 364일이라 1,506개 위치 중 13곳에서 1년에 못 미치고, 255면 0곳이다.
 *
 * <p><b>{@code -Dpit.dryRun=true}</b>면 아무것도 안 쓰고 착수 전 점검만 한다. 3.6시간을
 * 시작하기 전에 이것부터 돌린다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class PitLoadTest {

    /** 실측으로 1년을 보장하는 최소 거래일 수. 254는 미국에서 364일짜리 구간을 만든다. */
    private static final int DEFAULT_DAYS = 255;

    /**
     * 한 날짜에서 이만큼이 실패하면 <b>던진다.</b>
     *
     * <p>MUST NOT: 실패를 세면서 다음 날짜로 넘어가지 않는다. DB가 잠깐 끊기면
     * {@code computeAt}이 그 날짜의 남은 전 종목에 대해 던지고, 삼키면 <b>실패로 가득한
     * 저장본</b>을 몇 시간에 걸쳐 만든다. 한 종목의 데이터 결손과 인프라 장애는 다른 일이다.
     */
    private static final double MAX_FAILURE_RATIO = 0.02;

    @Autowired
    private IndicatorRunner runner;

    @Autowired
    private IndicatorRegistry registry;

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private AsOfCalendar calendar;

    @Autowired
    private ScreeningTargets targets;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("시점 재현 값을 통화별로 적재한다 — 끝난 날짜는 건너뛰고 낡은 종목만 갈아 끼운다")
    void loadPointInTimeValues() throws Exception {
        PitStore store = PitStore.fromEnvironment();
        PitFingerprint fingerprint = new PitFingerprint(jdbcTemplate);
        PitChangeDetector detector = new PitChangeDetector(jdbcTemplate);
        boolean dryRun = Boolean.parseBoolean(System.getProperty("pit.dryRun", "false"));

        System.out.printf("%n===== 시점 재현 적재%s =====%n", dryRun ? " (점검만)" : "");
        System.out.println("저장소 " + store.root());
        int swept = store.sweepTemporaries();
        System.out.printf("중단된 실행이 남긴 .tmp %d개를 지웠다%n", swept);

        Map<String, List<LocalDate>> plan = plan();
        String calculatorFp = PitFingerprint.forCalculators(registry);
        Set<String> declared = PointInTimeScreener.declaredCodesOf(registry);
        System.out.printf("계산기 판 %s · 선언 코드 %d개%n", calculatorFp, declared.size());

        long total = 0;
        for (Map.Entry<String, List<LocalDate>> e : plan.entrySet()) {
            List<Long> population = screener.populationFor(e.getKey());
            System.out.printf("%-4s %s ~ %s · %d거래일 · 모집단 %d%n", e.getKey(),
                    e.getValue().get(0), e.getValue().get(e.getValue().size() - 1),
                    e.getValue().size(), population.size());
            total += (long) e.getValue().size() * population.size();
        }
        System.out.printf("계산할 (종목, 날짜) 총 %,d칸%n", total);

        if (dryRun) {
            preflight(store, fingerprint, plan);
            return;
        }

        // MUST: 실행 하나에 기준 시각 하나. 날짜마다 찍으면 읽는 쪽이 그 수만큼 변경 훑기를
        // 돌아야 하고, 실측에서 그것이 스윕 시간을 통째로 지배했다.
        Instant startedAt = Instant.now();
        String inPlaceDigest = detector.inPlaceDigest(startedAt);

        int done = 0;
        int skipped = 0;
        int patched = 0;
        int plannedDates = plan.values().stream().mapToInt(List::size).sum();
        Instant runBegan = Instant.now();

        for (Map.Entry<String, List<LocalDate>> entry : plan.entrySet()) {
            String currency = entry.getKey();
            List<Long> population = screener.populationFor(currency);
            for (LocalDate asOf : entry.getValue()) {
                Map<Long, String> fps = fingerprint.forCurrency(currency, asOf);
                String sharedFp = fingerprint.forShared(asOf);
                PitStore.Freshness freshness =
                        store.freshnessOf(currency, asOf, fps, sharedFp);

                if (freshness.complete() && calculatorMatches(store, currency, asOf, calculatorFp)) {
                    skipped++;
                    continue;
                }
                // 이미 있고 종목 몇 개만 낡았으면 그것만 갈아 끼운다. gzip은 부분 수정이 안 되므로
                // 파일은 통째로 다시 쓰되, **계산은 낡은 종목만** 한다.
                boolean partial = freshness.usable()
                        && calculatorMatches(store, currency, asOf, calculatorFp)
                        && !freshness.staleStocks().isEmpty();
                Set<Long> toCompute = partial
                        ? new LinkedHashSet<>(freshness.staleStocks())
                        : new LinkedHashSet<>(population);

                Map<Long, Map<String, BigDecimal>> values = partial
                        ? store.readDate(currency, asOf)
                        : new LinkedHashMap<>();
                Set<Long> failed = computeInto(values, currency, asOf, toCompute, population);
                if (partial) {
                    patched += toCompute.size();
                    // 저장본이 지금 모집단보다 넓을 수 있다(편출). 남기면 업종 동료 집합이 라이브와
                    // 달라지므로 여기서 걷는다.
                    values.keySet().retainAll(new LinkedHashSet<>(population));
                }

                store.writeDate(currency, asOf, values, new PitStore.Stamp(
                        population.size(), declared, fps, sharedFp, inPlaceDigest,
                        calculatorFp, startedAt, failed));
                done++;
                report(done, skipped, plannedDates, runBegan, currency, asOf, failed.size());
            }
        }

        System.out.printf("%n적재 완료 — 새로 쓴 날짜 %d · 건너뛴 날짜 %d · 갈아 끼운 (종목,날짜) %d%n",
                done, skipped, patched);
        assertThat(done + skipped)
                .as("아무 날짜도 처리하지 않았다 — 이 실행은 아무것도 안 했다").isPositive();
    }

    /**
     * 값을 만들어 {@code values}에 넣는다. <b>실패를 삼키지 않는다.</b>
     *
     * @return 값을 못 만든 종목. 상한을 넘으면 던진다
     */
    private Set<Long> computeInto(Map<Long, Map<String, BigDecimal>> values,
                                  String currency, LocalDate asOf,
                                  Set<Long> toCompute, List<Long> population) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> indexes = runner.marketIndexesFor(asOf);
        Set<Long> failed = new LinkedHashSet<>();
        int ceiling = Math.max(1, (int) Math.ceil(population.size() * MAX_FAILURE_RATIO));
        for (Long stockId : toCompute) {
            try {
                values.put(stockId, new LinkedHashMap<>(
                        runner.computeAt(stockId, asOf, indexes, FinancialVisibility.KNOWN_ON)));
            } catch (TransientDataAccessException | RecoverableDataAccessException ex) {
                // 인프라 장애는 종목 실패가 아니다. 세고 넘어가면 몇 시간에 걸쳐 실패로 가득한
                // 저장본을 만든다.
                throw new IllegalStateException(
                        currency + " " + asOf + " stock=" + stockId + ": DB가 응답하지 않는다", ex);
            } catch (RuntimeException ex) {
                failed.add(stockId);
                System.out.printf("   적재 실패 %s %s stock=%d: %s: %s%n", currency, asOf, stockId,
                        ex.getClass().getSimpleName(), ex.getMessage());
                if (failed.size() > ceiling) {
                    throw new IllegalStateException(currency + " " + asOf + ": 한 날짜에서 "
                            + failed.size() + "종목이 실패했다(상한 " + ceiling
                            + ") — 종목별 결손이 아니라 무언가 근본이 어긋난 것이다", ex);
                }
            }
        }
        return failed;
    }

    /** 계산기 판이 다르면 저장본은 낡았다 — 값이 아니라 <b>만든 방법</b>이 달라졌다. */
    private boolean calculatorMatches(PitStore store, String currency, LocalDate asOf,
                                      String now) throws Exception {
        return now.equals(store.readManifest(currency, asOf).get("calculatorFp"));
    }

    /** 통화별 적재 구간. 저장소가 통화 단위라 시장이 아니라 통화로 묶는다. */
    private Map<String, List<LocalDate>> plan() {
        Map<String, List<LocalDate>> out = new LinkedHashMap<>();
        int days = Integer.parseInt(System.getProperty("pit.days", String.valueOf(DEFAULT_DAYS)));
        for (ScreeningTargets.Target target : targets.all()) {
            if (out.containsKey(target.currency())) {
                continue;
            }
            Set<String> markets = new LinkedHashSet<>();
            targets.all().stream().filter(t -> t.currency().equals(target.currency()))
                    .forEach(t -> markets.addAll(t.markets()));
            LocalDate latest = calendar.latestTradingDay(markets);
            LocalDate warmupFloor =
                    calendar.earliestComputableAsOf(registry.maxEffectiveLookback(), markets);
            if (latest == null || warmupFloor == null) {
                System.out.printf("%s: 시세가 워밍업(%d봉)을 못 채운다 — 건너뛴다%n",
                        target.currency(), registry.maxEffectiveLookback());
                continue;
            }
            LocalDate to = bound("pit.to", latest);
            List<LocalDate> all = calendar.everyTradingDay(warmupFloor, to, markets);
            String from = System.getProperty("pit.from");
            List<LocalDate> chosen = (from == null || from.isBlank())
                    ? all.subList(Math.max(0, all.size() - days), all.size())
                    : all.stream().filter(d -> !d.isBefore(LocalDate.parse(from.trim()))).toList();
            if (!chosen.isEmpty()) {
                out.put(target.currency(), new ArrayList<>(chosen));
            }
        }
        return out;
    }

    /** 착수 전 점검. 3.6시간을 시작하기 전에 답이 나와야 하는 것들이다. */
    private void preflight(PitStore store, PitFingerprint fingerprint,
                           Map<String, List<LocalDate>> plan) throws Exception {
        System.out.println("\n-- 착수 전 점검 --");
        for (Map.Entry<String, List<LocalDate>> e : plan.entrySet()) {
            String currency = e.getKey();
            LocalDate first = e.getValue().get(0);
            long t0 = System.nanoTime();
            Map<Long, String> fps = fingerprint.forCurrency(currency, first);
            long fpMs = (System.nanoTime() - t0) / 1_000_000;
            List<Long> population = screener.populationFor(currency);
            Set<Long> missing = new LinkedHashSet<>(population);
            missing.removeAll(fps.keySet());
            System.out.printf("   %-4s 지문 %,dms · 지문 %d개 · 모집단 %d · 지문 없는 종목 %d%n",
                    currency, fpMs, fps.size(), population.size(), missing.size());
            assertThat(missing)
                    .as("%s: 모집단에 있는데 지문이 없는 종목이 있다 — 그 종목은 영원히 낡음으로 "
                            + "읽혀 날짜가 완료되지 않는다", currency)
                    .isEmpty();
            System.out.printf("   %-4s 이미 저장된 날짜 %d개%n",
                    currency, store.storedDates(currency).size());
        }
        System.out.println("   점검 끝 — -Dpit.dryRun 없이 다시 돌리면 적재한다");
    }

    private static void report(int done, int skipped, int planned, Instant began,
                               String currency, LocalDate asOf, int failed) {
        if (done % 10 != 0) {
            return;
        }
        Duration spent = Duration.between(began, Instant.now());
        long remaining = (long) (planned - done - skipped);
        long etaSec = done == 0 ? 0 : spent.toSeconds() * remaining / done;
        System.out.printf("   [%d/%d] %s %s · 실패 %d · 경과 %s · 남은 예상 %d분%n",
                done + skipped, planned, currency, asOf, failed,
                spent.toMinutes() + "분", etaSec / 60);
    }

    private static LocalDate bound(String key, LocalDate fallback) {
        String raw = System.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : LocalDate.parse(raw.trim());
    }
}
