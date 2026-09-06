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
 * caffeinate -is ./gradlew backtestLoad -Dpit.days=255
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
 *
 * <p><b>{@code -Dpit.recheck=calculator}</b>는 지표 계산기의 판만 다시 확인한다. 클래스 내용을
 * 해시하는 판정이 <b>과검출</b>이기 때문이다 — 화면에 뜨는 설명 문구 한 낱말만 고쳐도 해시가
 * 움직이는 것을 실측했고(최근 {@code fix(indicator)} 커밋 둘이 실제로 그런 변경이었다), 그때
 * 값이 하나도 안 바뀌는데 3시간 41분을 다시 치른다. 표본을 <b>다시 계산해 저장본과 대조하고</b>
 * 전부 같을 때만 판을 다시 찍는다.
 *
 * <p>실측 <b>5분 30초</b>(42,123칸 대조 · 선언 코드 125개를 전부 건드렸다). 3시간 41분과 다르다.
 *
 * <p>MUST NOT: 사람이 「이건 문구만 고친 것」이라고 선언해 통과시키지 않는다. 그 판단이 틀리면
 * 조용히 옛 공식의 표가 나오고, 그것을 막으려고 이 기제를 만들었다. <b>재서 같으면 넘어가고
 * 다르면 던진다.</b>
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

    /** 계산기 재확인 표본의 통화별 종목 수. 날짜 셋과 곱해진다. */
    private static final int SAMPLE_STOCKS = 60;

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
        boolean dryRun = flag("pit.dryRun");
        String recheck = choice("pit.recheck", "calculator", "index", "fingerprint");

        System.out.printf("%n===== 시점 재현 적재%s =====%n", dryRun ? " (점검만)" : "");
        System.out.println("저장소 " + store.root());
        int swept = store.sweepTemporaries();
        System.out.printf("중단된 실행이 남긴 .tmp %d개를 지웠다%n", swept);

        Map<String, List<LocalDate>> plan = plan(store);
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
        // MUST: 이것이 **계획의 상한**이지 실제 계산량이 아니라는 것을 문장으로 적는다.
        // 안 적으면 「매번 전량 재적재가 돈다」로 읽힌다 — 실측으로 이 수가 345,600일 때
        // 실제 계산은 0칸이었다(2026-09-06).
        System.out.printf("계획 상한 (종목, 날짜) %,d칸 — 안 바뀐 날짜는 건너뛰므로 "
                + "실제 계산은 이보다 훨씬 적고 0일 수도 있다%n", total);

        if ("calculator".equals(recheck)) {
            recheckCalculators(store, plan, calculatorFp, declared);
            return;
        }
        if ("index".equals(recheck)) {
            restampSharedFingerprints(store, fingerprint, plan);
            return;
        }
        if ("fingerprint".equals(recheck)) {
            restampStockFingerprints(store, fingerprint, plan);
            return;
        }
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
            // 통화마다 왜 비싼 길로 가는지 한 번 찍는다. 안 찍으면 값을 하나도 안 만드는
            // 실행이 몇 분간 완전 무출력이 되고, 그것을 「멈췄다」로 읽어 끊게 된다.
            // 끊으면 저장본은 안 낫는다 — 낫는 것은 스윕의 restamp뿐이다.
            String costNote = null;
            for (LocalDate asOf : entry.getValue()) {
                // MUST: 건너뛸지를 **싼 훑기로 먼저** 묻는다. 지문부터 재면 아무것도 안 바뀐
                // 날에도 날짜당 0.56~1.43초가 들어 512날짜 확인만 9분이 된다 — 실측이다.
                // 훑기가 「이 날짜에 의심 종목이 없다」고 하면 지문은 반드시 같으므로 안 잰다.
                Map<String, String> mf = store.readManifest(currency, asOf);
                String cannotSkip = whyCannotSkipCheaply(detector, fingerprint, currency, asOf, mf,
                        calculatorFp, population);
                if (cannotSkip == null) {
                    skipped++;
                    report(done, skipped, plannedDates, runBegan, currency, asOf, 0, "확인");
                    continue;
                }
                if (costNote == null) {
                    costNote = cannotSkip;
                    System.out.printf("   %s %d날짜를 지문으로 확인한다 — %s%n"
                                    + "   (값이 안 바뀌었으면 다시 만들지 않는다. 확인만 %s당 "
                                    + "0.6~1.5초라 몇 분 걸릴 수 있고 그동안 조용하다)%n",
                            currency, entry.getValue().size(), cannotSkip, "날짜");
                }

                Map<Long, String> fps = fingerprint.forCurrency(currency, asOf);
                String sharedFp = fingerprint.forShared(currency, asOf);
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
                report(done, skipped, plannedDates, runBegan, currency, asOf, failed.size(),
                        partial ? "갈아 끼움" : "다시 만듦");
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

    /**
     * <b>지문을 재지 않고</b> 건너뛸 수 있는가. 건너뛸 수 있으면 {@code null}, 아니면
     * <b>왜 비싼 길로 가는지</b>를 돌려준다 — <b>과검출은 하되 미검출은 안 한다.</b>
     *
     * <p>싼 훑기가 그 날짜에 의심 종목을 하나도 안 올리고, 매니페스트가 완료이며, 계산기 판과
     * 지수 지문이 같으면 <b>종목 지문은 반드시 같다.</b> 그 확인이 실측 날짜당 0.56~1.43초인데
     * 여기는 통화당 한 번의 훑기 + 날짜당 지수 지문 하나다.
     *
     * <p><b>사유를 문자열로 돌려주는 이유가 진단이다.</b> 갈래가 여섯인데 밖에서는 하나로
     * 보여서, 값을 한 칸도 안 만드는 실행이 왜 몇 분씩 도는지 알 수 없었다. 실측 2026-09-06:
     * 「새로 쓴 날짜 0 · 건너뛴 날짜 512」인 실행이 <b>12분 58초</b>, 그 직후 재실행이
     * <b>21초</b>였고 차이는 전부 {@code inPlaceDigest} 하나였다.
     *
     * <p>MUST NOT: 이 사유를 {@link PitStore.Freshness}에 <b>더하지 않는다.</b> 저쪽의
     * {@code unusableReason}은 「이 저장본을 못 쓴다」는 뜻이라 {@code complete()}를 거짓으로
     * 만들고, 그러면 아래 건너뛰기와 {@code partial}이 <b>둘 다 무너져 전 종목을 다시 만든다.</b>
     * digest 불일치는 <b>「못 쓴다」가 아니라 「확인이 필요하다」</b>다 — 실측으로 그때
     * 종목 지문 불일치는 0이었다.
     */
    private String whyCannotSkipCheaply(PitChangeDetector detector,
                                        PitFingerprint fingerprint, String currency, LocalDate asOf,
                                        Map<String, String> mf, String calculatorFp,
                                        List<Long> population) {
        if (mf.isEmpty()) {
            return "매니페스트 없음";
        }
        if (!"true".equals(mf.get("complete"))) {
            return "미완료(실패 " + mf.getOrDefault("failed", "?") + ")";
        }
        if (!calculatorFp.equals(mf.get("calculatorFp"))) {
            return "계산기 판 불일치";
        }
        if (mf.get("writtenAt") == null) {
            return "writtenAt 없음";
        }
        if (!fingerprint.forShared(currency, asOf).equals(mf.get("indexFp"))) {
            return "지수 지문 불일치";
        }
        PitChangeDetector.Changes changes =
                changesCache.computeIfAbsent(Instant.parse(mf.get("writtenAt")), detector::since);
        String digestAtLoad = mf.get("inPlaceDigest");
        if (digestAtLoad == null || !digestAtLoad.equals(changes.inPlaceDigest())) {
            return "모집단 전역 해시(inPlaceDigest) 불일치 — 유니버스 편입이나 재무 제자리 수정이 "
                    + "있었다. 값이 바뀐 종목은 지문이 가린다";
        }
        java.util.Set<Long> suspects = changes.suspectsOn(asOf, population, digestAtLoad);
        return suspects.isEmpty() ? null : "원자료가 움직인 종목 " + suspects.size() + "개";
    }

    /** 같은 시각을 여러 날짜가 묻는다. 훑기가 2초대라 캐시하지 않으면 그것이 지배한다. */
    private final Map<Instant, PitChangeDetector.Changes> changesCache = new LinkedHashMap<>();

    /** 계산기 판이 다르면 저장본은 낡았다 — 값이 아니라 <b>만든 방법</b>이 달라졌다. */
    private boolean calculatorMatches(PitStore store, String currency, LocalDate asOf,
                                      String now) throws Exception {
        return now.equals(store.readManifest(currency, asOf).get("calculatorFp"));
    }

    /**
     * 통화별 적재 구간. 저장소가 통화 단위라 시장이 아니라 통화로 묶는다.
     *
     * <p><b>MUST: 이미 저장된 날짜를 계획에 합친다.</b> {@code -Dpit.days}는 <b>오늘 기준
     * 상대값</b>이라 거래일이 하루 늘 때마다 창이 한 칸 밀리고, 그러면 <b>옛 시작일이 계획에서
     * 빠져 고아가 된다</b> — 그 날짜만 옛 모집단·옛 지문인 채 저장소에 남는다. 스윕은 저장된
     * 날짜를 전부 읽으므로 그 한 날짜가 전체를 멈춘다.
     *
     * <p>실제로 그렇게 됐다: 유니버스에서 32종목이 갈린 뒤 보수를 돌렸는데 그 사이 새 거래일이
     * 생겨 창이 밀렸고, 255날짜를 다 고쳤는데도 <b>2025-08-28 하나가 옛 모집단으로 남아</b>
     * 스윕이 같은 자리에서 다시 멈췄다. <b>저장된 것은 유지 대상이다</b> — 계획 밖이라고
     * 버려두면 저장소가 「관리되는 절반」과 「버려진 절반」으로 조용히 갈린다.
     */
    private Map<String, List<LocalDate>> plan(PitStore store) throws Exception {
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
            java.util.SortedSet<LocalDate> merged = new java.util.TreeSet<>(chosen);
            merged.addAll(store.storedDates(target.currency()));
            if (!merged.isEmpty()) {
                out.put(target.currency(), new ArrayList<>(merged));
            }
        }
        return out;
    }

    /**
     * 계산기 판만 다시 확인한다. <b>표본을 다시 계산해 저장본과 대조</b>하고 전부 같을 때만
     * 매니페스트의 판을 갱신한다 — 값은 한 바이트도 안 건드린다.
     *
     * <p>표본은 <b>날짜 셋 × 종목 여럿</b>이다. 날짜를 하나만 보면 그 날짜에 값이 없는 지표가
     * 통째로 안 걸리고, 종목을 적게 보면 일부 종목에만 있는 지표가 빠진다. <b>표본이 건드리지
     * 못한 코드를 세어 함께 찍는다</b> — 안 찍으면 「전부 같다」가 「전부 봤다」로 읽힌다.
     */
    private void recheckCalculators(PitStore store, Map<String, List<LocalDate>> plan,
                                    String calculatorFp, Set<String> declared) throws Exception {
        System.out.println("\n-- 계산기 판 재확인 (값은 안 건드린다) --");
        Set<String> touched = new java.util.TreeSet<>();
        List<String> diffs = new ArrayList<>();
        int comparedCells = 0;

        for (Map.Entry<String, List<LocalDate>> e : plan.entrySet()) {
            String currency = e.getKey();
            List<LocalDate> stored = store.storedDates(currency);
            if (stored.isEmpty()) {
                System.out.printf("   %-4s 저장된 날짜가 없다 — 볼 것이 없다%n", currency);
                continue;
            }
            List<LocalDate> sampleDates = List.of(stored.get(0),
                    stored.get(stored.size() / 2), stored.get(stored.size() - 1));
            List<Long> population = screener.populationFor(currency);
            for (LocalDate asOf : sampleDates) {
                Map<Long, Map<String, BigDecimal>> saved = store.readDate(currency, asOf);
                Map<String, NavigableMap<LocalDate, BigDecimal>> indexes =
                        runner.marketIndexesFor(asOf);
                // 모집단을 고르게 훑는다. 앞에서 N개만 뽑으면 한쪽 업종·한쪽 시총에 몰린다.
                int step = Math.max(1, population.size() / SAMPLE_STOCKS);
                for (int i = 0; i < population.size(); i += step) {
                    Long stockId = population.get(i);
                    Map<String, BigDecimal> now =
                            runner.computeAt(stockId, asOf, indexes, FinancialVisibility.KNOWN_ON);
                    Map<String, BigDecimal> then = saved.getOrDefault(stockId, Map.of());
                    touched.addAll(now.keySet());
                    Set<String> codes = new LinkedHashSet<>(now.keySet());
                    codes.addAll(then.keySet());
                    for (String code : codes) {
                        comparedCells++;
                        BigDecimal a = now.get(code);
                        BigDecimal b = then.get(code);
                        if (a == null || b == null ? a != b : a.compareTo(b) != 0) {
                            diffs.add(currency + " " + asOf + " stock=" + stockId + " " + code
                                    + " 저장본=" + b + " 지금=" + a);
                        }
                    }
                }
            }
        }

        Set<String> untouched = new java.util.TreeSet<>(declared);
        untouched.removeAll(touched);
        System.out.printf("   대조한 칸 %,d · 표본이 건드린 코드 %d/%d%n",
                comparedCells, touched.size(), declared.size());
        if (!untouched.isEmpty()) {
            System.out.printf("   표본이 못 건드린 코드 %d개: %s%n", untouched.size(),
                    untouched.stream().limit(12).toList());
        }
        assertThat(comparedCells).as("대조한 칸이 0이면 이 재확인은 아무것도 안 봤다").isPositive();
        if (!diffs.isEmpty()) {
            diffs.stream().limit(10).forEach(d -> System.out.println("   차이: " + d));
            throw new IllegalStateException("계산기가 실제로 다른 값을 낸다(" + diffs.size()
                    + "칸) — 문구 수정이 아니라 공식 변경이다. 그 구간을 다시 적재한다");
        }

        int restamped = 0;
        for (Map.Entry<String, List<LocalDate>> e : plan.entrySet()) {
            for (LocalDate asOf : store.storedDates(e.getKey())) {
                Map<String, String> mf = store.readManifest(e.getKey(), asOf);
                if (calculatorFp.equals(mf.get("calculatorFp"))) {
                    continue;
                }
                store.rewriteManifest(e.getKey(), asOf,
                        Integer.parseInt(mf.getOrDefault("stocks", "0")),
                        stampFrom(mf, calculatorFp));
                restamped++;
            }
        }
        System.out.printf("   차이 없음 — 매니페스트 %d개의 계산기 판만 갱신했다%n", restamped);
    }

    /** 매니페스트를 그대로 되쓰되 계산기 판만 바꾼다. 다른 값을 손대면 그것이 근거를 잃는다. */
    private static PitStore.Stamp stampFrom(Map<String, String> mf, String calculatorFp) {
        return stampFrom(mf, calculatorFp, mf.get("indexFp"));
    }

    /** 매니페스트를 그대로 되쓰되 계산기 판과 지수 지문만 바꾼다. */
    private static PitStore.Stamp stampFrom(Map<String, String> mf, String calculatorFp,
                                            String indexFp) {
        Map<Long, String> fps = new LinkedHashMap<>();
        Set<String> codes = new java.util.TreeSet<>();
        for (Map.Entry<String, String> e : mf.entrySet()) {
            if (e.getKey().startsWith("fp:")) {
                fps.put(Long.parseLong(e.getKey().substring(3)), e.getValue());
            }
        }
        String declared = mf.get("codes");
        if (declared != null && !declared.isBlank()) {
            codes.addAll(List.of(declared.split(" ")));
        }
        // MUST: `failed`를 이어받는다. `Set.of()`를 넘기면 `complete = failed.isEmpty()`가
        // **미완료 날짜를 조용히 완료로 승격**시킨다 — `PitSweep.restamp`에서 방금 없앤 결함을
        // 형제 메서드에 되살리는 자리다. 「지금 미완료가 0건이라 안 닿는다」는 근거가 아니다.
        return new PitStore.Stamp(Integer.parseInt(mf.getOrDefault("attempted", "0")), codes, fps,
                indexFp, mf.get("inPlaceDigest"), calculatorFp,
                Instant.parse(mf.get("writtenAt")), failedOf(mf));
    }

    /**
     * <b>지수 지문 식이 바뀌었을 때</b> 값을 다시 만들지 않고 매니페스트만 다시 찍는다.
     *
     * <p>판정은 <b>내용</b>이다 — 시각이 아니다. 좁힌 입력은 옛 입력의 <b>진부분집합</b>이므로
     * <b>옛 식이 저장된 값과 같으면 좁은 입력도 반드시 안 움직였다.</b> 그때만 새 식을 찍는다.
     *
     * <p>MUST NOT: {@code updated_at}으로 무죄를 선고하지 않는다. {@code market}·{@code universe}에는
     * 시각 컬럼이 <b>아예 없고</b> 삭제도 시각에 안 남아, 그러면 ADR-0123 §규칙의
     * 「싼 훑기는 의심을 고르고 <b>내용이 확정한다</b>」가 뒤집힌다 — 그 뒤집힘이 바로
     * 이 저장소에서 찾은 조용한 오답의 모양이다.
     *
     * <p>옛 식이 다른 날짜는 <b>안 찍고 신고한다.</b> 그 날짜는 지수가 실제로 움직인 것이라
     * 값을 다시 만들어야 하고, 그것은 적재의 일이다.
     */
    private void restampSharedFingerprints(PitStore store, PitFingerprint fingerprint,
                                           Map<String, List<LocalDate>> plan) throws Exception {
        System.out.println("\n-- 지수 지문 재찍기 (값은 안 건드린다) --");
        String calculatorFp = PitFingerprint.forCalculators(registry);
        int restamped = 0;
        List<String> moved = new ArrayList<>();
        for (Map.Entry<String, List<LocalDate>> e : plan.entrySet()) {
            String currency = e.getKey();
            for (LocalDate asOf : store.storedDates(currency)) {
                Map<String, String> mf = store.readManifest(currency, asOf);
                String stored = mf.get("indexFp");
                if (!fingerprint.forSharedBeforeNarrowing(asOf).equals(stored)) {
                    moved.add(currency + " " + asOf);
                    continue;
                }
                String narrowed = fingerprint.forShared(currency, asOf);
                if (narrowed.equals(stored)) {
                    continue;
                }
                store.rewriteManifest(currency, asOf,
                        Integer.parseInt(mf.getOrDefault("stocks", "0")),
                        stampFrom(mf, calculatorFp, narrowed));
                restamped++;
            }
        }
        System.out.printf("   다시 찍은 매니페스트 %d · 지수가 실제로 움직인 날짜 %d%n",
                restamped, moved.size());
        moved.stream().limit(10).forEach(m -> System.out.println("   움직임: " + m));
        assertThat(restamped + moved.size())
                .as("저장된 날짜가 없다 — 재찍을 것이 없다").isPositive();
        if (!moved.isEmpty()) {
            System.out.println("   → 그 날짜들은 값을 다시 만들어야 한다. 적재를 돌린다");
        }
    }

    /**
     * <b>종목 지문 식이 바뀌었을 때</b> 값을 다시 만들지 않고 매니페스트만 다시 찍는다.
     *
     * <p>{@link #restampSharedFingerprints}와 같은 <b>부분집합 논증</b>이다 — 좁힌 입력이
     * 옛 입력의 진부분집합이므로, <b>옛 식이 저장된 값과 같으면 좁은 입력도 반드시 안 움직였다.</b>
     *
     * <p>이 마이그레이션이 없으면 좁히기가 곧 <b>전량 재적재 3시간 40분</b>이다 —
     * {@code freshnessOf}가 전 종목을 낡음으로 읽기 때문이다.
     */
    private void restampStockFingerprints(PitStore store, PitFingerprint fingerprint,
                                          Map<String, List<LocalDate>> plan) throws Exception {
        System.out.println("\n-- 종목 지문 재찍기 (값은 안 건드린다) --");
        String calculatorFp = PitFingerprint.forCalculators(registry);
        int restamped = 0;
        List<String> moved = new ArrayList<>();
        for (Map.Entry<String, List<LocalDate>> e : plan.entrySet()) {
            String currency = e.getKey();
            for (LocalDate asOf : store.storedDates(currency)) {
                Map<String, String> mf = store.readManifest(currency, asOf);
                Map<Long, String> wide =
                        fingerprint.forCurrencyBeforeNarrowing(currency, asOf);
                boolean same = wide.entrySet().stream()
                        .allMatch(x -> x.getValue().equals(mf.get("fp:" + x.getKey())));
                if (!same) {
                    moved.add(currency + " " + asOf);
                    continue;
                }
                PitStore.Stamp base = stampFrom(mf, calculatorFp, mf.get("indexFp"));
                store.rewriteManifest(currency, asOf,
                        Integer.parseInt(mf.getOrDefault("stocks", "0")),
                        new PitStore.Stamp(base.attempted(), base.codes(),
                                fingerprint.forCurrency(currency, asOf), base.indexFingerprint(),
                                base.inPlaceDigest(), base.calculatorFp(), base.writtenAt(),
                                base.failed()));
                restamped++;
            }
        }
        System.out.printf("   다시 찍은 매니페스트 %d · 원자료가 실제로 움직인 날짜 %d%n",
                restamped, moved.size());
        moved.stream().limit(10).forEach(m -> System.out.println("   움직임: " + m));
        assertThat(restamped + moved.size()).as("저장된 날짜가 없다").isPositive();
    }

    /** 매니페스트에 적힌 실패 종목. 없으면 빈 집합이다. */
    private static Set<Long> failedOf(Map<String, String> mf) {
        Set<Long> out = new java.util.TreeSet<>();
        String raw = mf.get("failed");
        if (raw != null && !raw.isBlank()) {
            for (String id : raw.trim().split("\\s+")) {
                out.add(Long.parseLong(id));
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

    /**
     * 진척 한 줄. <b>건너뛰는 날짜에도 찍는다</b> — 안 찍으면 값을 하나도 안 만드는 실행이
     * 몇 분간 완전 무출력이 되고, 사람이 그것을 「멈췄다」로 읽어 끊는다. 실측으로 그런
     * 실행이 두 번 취소됐고(2026-09-05·09-06), <b>끊으면 저장본이 안 낫는다</b> —
     * 낡음을 실제로 고치는 것은 {@link PitSweep}의 {@code restamp}뿐이다.
     *
     * <p>MUST: 스로틀과 ETA를 <b>{@code done + skipped}</b>로 센다. {@code done}으로 세면
     * 건너뛰기만 도는 구간에서 {@code 0 % 10 == 0}이라 <b>매 날짜가 출력되고</b>, ETA는
     * {@code spent}에 건너뛴 시간이 다 들어간 채 {@code done}으로만 나뉘어 부풀려진다 —
     * 실측으로 첫 표본이 <b>64분</b>을 찍었는데 실제 소요는 10분 28초였다.
     *
     * @param note 이 줄이 무엇인지. {@code "확인"}이면 값을 안 만들고 지나간 것이다
     */
    private static void report(int done, int skipped, int planned, Instant began,
                               String currency, LocalDate asOf, int failed, String note) {
        int seen = done + skipped;
        if (seen % 10 != 0) {
            return;
        }
        Duration spent = Duration.between(began, Instant.now());
        long remaining = (long) (planned - seen);
        long etaSec = seen == 0 ? 0 : spent.toSeconds() * remaining / seen;
        System.out.printf("   [%d/%d] %s %s · %s · 다시 만듦 %d · 실패 %d · 경과 %d분 · 남은 예상 %d분%n",
                seen, planned, currency, asOf, note, done, failed,
                spent.toMinutes(), etaSec / 60);
    }

    /**
     * 스위치 <b>값</b>을 판정한다. 이름 펜스는 `build.gradle`이 지고, 여기는 값을 진다.
     *
     * <p>MUST: 모르는 값에 던진다. {@code -Dpit.recheck=calc}가 조용히 지나가면 3초짜리
     * 재확인 대신 <b>3시간 41분짜리 적재</b>가 돈다 — 이름 오타와 같은 증상이고 원인만 다르다.
     */
    private static String choice(String key, String... allowed) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException("-D" + key + "=" + value
                + "는 모르는 값이다. 아는 것: " + List.of(allowed));
    }

    /** {@code Boolean.parseBoolean}은 {@code yes}를 조용히 false로 읽는다. */
    private static boolean flag(String key) {
        String value = choice(key, "true", "false");
        return "true".equals(value);
    }

    private static LocalDate bound(String key, LocalDate fallback) {
        String raw = System.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : LocalDate.parse(raw.trim());
    }
}
