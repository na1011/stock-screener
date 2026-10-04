package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorRetentionPolicy;
import com.stockscreener.indicator.IndicatorTradeDateIndex;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.indicator.LayerChange;
import com.stockscreener.indicator.PercentileCalculator;
import com.stockscreener.indicator.SectorRelativeCalculator;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 이미 적재된 원자료로 지표를 소급 계산한다.
 *
 * <p>MUST NOT: 이 분기를 {@link DailyUpdateJob}에 섞지 않는다({@code data-pipeline.md}).
 * 일일 갱신은 하루치를, 이 잡은 기간 전체를 다룬다. 섞으면 매일 "오늘이 백필해야 하는 날인가"를
 * 판단하게 되고, 그 판단이 틀리면 850종목 × 500일을 매일 다시 계산한다.
 *
 * <p>쓰는 때가 셋이다 — ① 초기 적재 직후 과거 구간을 한 번 채울 때, ② 지표를 새로 추가해
 * 과거 값이 비어 있을 때, ③ 액면분할·정정공시로 무효화된 구간을 다시 채울 때.
 *
 * <p>외부 API를 호출하지 않는다. 원자료는 이미 DB에 있고 계산만 다시 한다 — 그래서 쿼터를
 * 신경 쓸 필요가 없고, 실패해도 다시 돌리는 비용이 낮다.
 *
 * <h2>{@code tickers}가 순위의 모집단을 좁히지 않는 이유</h2>
 * <p>phase 기준이 <i>"재계산이 순위의 모집단을 좁히지 않는다"</i>를 명시한다(§P9 · ADR-0036과
 * 같은 자리다) — 일부 종목만 다시 계산해도 백분위·업종 상대값은 전 종목에서 나와야 한다.
 * 그것이 <b>구조로</b> 성립한다: {@link PercentileCalculator#calculate}와
 * {@link SectorRelativeCalculator#calculate}는 <b>거래일만 받고</b> {@code indicator_value}를
 * 활성 유니버스 전량과 조인하므로, 아래 종목 루프를 좁혀도 두 계산은 좁아지지 않는다.
 *
 * <p>MUST NOT: 두 호출을 {@code targets}로 좁히지 않는다. 좁히면 대상 밖 종목의 순위가
 * 조용히 낡는데 <b>값이 그럴듯해 화면에서 드러나지 않는다.</b>
 * {@code IndicatorBackfillTickerScopeTest}가 그것을 못박는다.
 *
 * <p>이 필터가 있어야 하는 이유는 <b>무효화가 낀 재백필의 복구 비용</b>이다.
 * {@code AdjustmentRecalculator.rebuild}가 값을 바꾸면 그 종목의 {@code indicator_value}가
 * <b>보존 창 전체</b>에서 지워진다({@code StoredIndicatorInvalidationPort}의 {@code >=}).
 * 필터가 없으면 한 종목의 판정을 고칠 때마다 보존 창 전체 × 전 종목을 다시 돌아야 하고
 * (실측 2.6~8시간 · 350종목 × 180일자였을 때), 그 비용이 <b>"판정을 한 번에 다 끝내야 한다"</b>는 제약을 만든다.
 *
 * <h2>{@code --universe} 갈래 — 순환을 끊는 자리이고, 반만 끊으면 조용히 빈다</h2>
 *
 * <p>이 잡은 활성 유니버스만 보므로 <b>「켜야 지표가 생기고, 지표가 없으면 켜면 안 되는」
 * 순환</b>이 있다(미국이 그 자리다 — 켜는 순간 값 하나 없는 8,504종목이 프리셋 결과에 실린다).
 * {@code corporate-action}·{@code adjustment-rebuild}·{@code initial-load}·
 * {@code company-profile}이 이미 {@code --universe}로 그 순환을 끊었고 <b>이 잡도 같은 갈래를
 * 갖는다</b>({@code BootstrapRunner.UNIVERSE_JOBS}에 함께 올라 있다 — 빠지면
 * {@code rejectMisplacedLoadOptions}가 첫 줄에서 거절한다).
 *
 * <p>대상 선정은 {@code StockRegistrar.oneUniverseMemberIds}를 그대로 쓴다 — 그쪽이 이미
 * {@code u.is_active}를 안 보고 코드 하나로 좁히며 {@code CollectedMarkets}로도 거른다.
 *
 * <p><b>MUST: 그때 위 두 계산을 함께 다룬다.</b> {@code percentileCalculator.calculate}와
 * {@code sectorRelativeCalculator.calculate}가 <b>{@code u.is_active}를 조인</b>하므로,
 * 비활성 유니버스에 백필하면 <b>원본 지표는 생기는데 {@code *_PCTL}·업종 상대값이 한 행도
 * 안 생기고 잡은 SUCCESS로 끝난다.</b> 나중에 그 유니버스를 켜도 <b>과거 179일자는 영영
 * 빈다</b> — 두 계산기가 거래일 하나씩만 다시 쓰기 때문이다.
 *
 * <p>바로 위 절과 충돌하는 것처럼 보이지만 아니다. 저기서 금지한 것은 <b>모집단을 좁히는
 * 것</b>이고, 여기서 필요한 것은 <b>모집단에 비활성 유니버스를 넣는 것</b>이다 — 방향이 반대다.
 * 그래서 두 계산기는 {@code u.is_active}를 <b>지우지 않고</b> {@code OR u.code = ?}로 하나만
 * 더한다. 통째로 열면 폐기된 지수의 편입 이력이 전부 들어와
 * {@code PercentileCalculator}의 전제(<b>한 종목이 최대 하나의 활성 유니버스</b>)가 즉시 깨지고,
 * JOIN이 행을 늘려 PostgreSQL이 cardinality violation으로 배치를 죽인다.
 *
 * <p><b>그 전제를 이 잡이 먼저 검사한다</b>({@code rejectOverlappingUniverse}). 실측으로
 * {@code US_TOP1000}의 1,000종목이 전부 {@code US_LISTED}에도 편입돼 있으므로(2026-08-31),
 * 겹치는 유니버스를 대상으로 주면 <b>읽기 어려운 cardinality violation</b> 대신 무엇이
 * 겹쳤는지를 말하고 멈춘다.
 */
@Component
public class IndicatorBackfillJob {

    public static final String JOB_NAME = "indicator-backfill";

    /** 매 평일 화해의 ① — 원본층을 {@code asOf} 한 판으로 다시 쓴다({@link #rewriteOriginLayer}). */
    public static final String ORIGIN_JOB_NAME = "indicator-origin-rewrite";

    /** 매 평일 화해의 ② — 보존 창 전체의 파생층을 다시 낸다({@link #rebuildDerived}). */
    public static final String DERIVED_JOB_NAME = "indicator-derived-rebuild";

    private static final Logger log = LoggerFactory.getLogger(IndicatorBackfillJob.class);

    /**
     * 화면·스케줄러·진척 기록 몫으로 남기는 커넥션.
     *
     * <p>워커 하나가 커넥션 하나를 <b>계산하는 내내</b> 쥔다 — {@code IndicatorRunner.run}이
     * 통째로 {@code REQUIRES_NEW} 안에서 돌기 때문이다(ADR-0007). 그래서 워커 수의 상한이
     * 풀 크기에 묶이고, 여기 남기는 만큼이 앱의 몫이다. <b>필요량을 잰 값이 아니라 예비다.</b>
     */
    private static final int RESERVED_CONNECTIONS = 2;

    /**
     * 워커가 끝나기를 기다리는 상한.
     *
     * <p>{@code spring.lifecycle.timeout-per-shutdown-phase}(15초) 안이어야 한다 — 넘으면
     * {@code ops/stop.sh}의 앱 대기(45초)와의 짝이 깨져 배포가 조용히 실패한다(ADR-0078).
     */
    private static final int WORKER_SHUTDOWN_SECONDS = 10;

    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;
    private final IndicatorRunner indicatorRunner;
    private final PercentileCalculator percentileCalculator;
    private final SectorRelativeCalculator sectorRelativeCalculator;
    private final DerivedDayCalculator derivedDayCalculator;
    private final IndicatorRetentionPolicy retentionPolicy;
    private final IndicatorTradeDateIndex tradeDateIndex;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final int configuredParallelism;

    public IndicatorBackfillJob(StockRegistrar stockRegistrar, BatchExecutions batchExecutions,
                                IndicatorRunner indicatorRunner,
                                PercentileCalculator percentileCalculator,
                                SectorRelativeCalculator sectorRelativeCalculator,
                                DerivedDayCalculator derivedDayCalculator,
                                IndicatorRetentionPolicy retentionPolicy,
                                IndicatorTradeDateIndex tradeDateIndex,
                                JdbcTemplate jdbcTemplate,
                                DataSource dataSource,
                                @Value("${pipeline.indicator-backfill.parallelism:6}")
                                int configuredParallelism) {
        this.dataSource = dataSource;
        this.configuredParallelism = configuredParallelism;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
        this.indicatorRunner = indicatorRunner;
        this.percentileCalculator = percentileCalculator;
        this.sectorRelativeCalculator = sectorRelativeCalculator;
        this.derivedDayCalculator = derivedDayCalculator;
        this.retentionPolicy = retentionPolicy;
        this.tradeDateIndex = tradeDateIndex;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@code from}부터 {@code to}까지 매 거래일의 지표를 계산한다.
     *
     * <p>거래일 목록은 {@code price_daily}에서 얻는다. 달력으로 만들면 휴장일까지 돌게 되고,
     * 그날은 시세가 없어 아무 값도 나오지 않는 헛일이 된다. 실제로 시세가 있는 날이
     * 곧 거래일이라는 사실을 쓰는 편이 정확하고 싸다.
     *
     * @param tickers 비면 편입 중인 활성 종목 전체. <b>좁혀도 백분위·업종 상대값은 전 종목에서
     *                나온다</b> — 클래스 javadoc의 근거를 참조한다.
     */
    public BatchStatus run(LocalDate from, LocalDate to,
                           BatchExecutionRecorder.TriggeredBy triggeredBy,
                           List<String> tickers, String universeCode) {
        rejectOverlappingUniverse(universeCode);
        List<LocalDate> tradeDates = tradeDatesBetween(from, to);
        if (tradeDates.isEmpty()) {
            log.info("해당 구간에 시세가 없어 백필할 것이 없다. from={} to={}", from, to);
            return BatchStatus.SUCCESS;
        }

        List<SecurityRef> targets =
                stockRegistrar.toSecurityRefs(resolveTargets(tickers, universeCode));

        // 보존 경계 밖은 계산해도 저장되지 않는다(ADR-0011). 그 사실을 여기서 말하지 않으면
        // 몇 시간을 돌고 0행으로 끝나는데 잡은 SUCCESS다 — 원인을 데이터나 계산기에서 찾게 된다.
        //
        // MUST: 경계를 시장마다 잰다. 전 시장 합계로 세면 국내가 휴장이고 미국이 개장한 날이
        // 전역 「거래일」이 되어 국내 경계가 뒤로 밀리고, 그러면 저장될 값이 있는 구간을
        // "전부 경계 밖"으로 읽어 **돌지 않고 SUCCESS로 끝난다.**
        Map<String, Optional<LocalDate>> retentionCutoffs = retentionPolicy.writeCutoffs();
        List<String> markets = targets.stream()
                .map(SecurityRef::marketCode).distinct().sorted().toList();

        // 「전부 경계 밖」은 **대상 시장 전부**에 대해 참일 때만 참이다. 한 시장이라도 쓸 값이
        // 있으면 돌아야 한다.
        boolean allOutside = !markets.isEmpty() && markets.stream().allMatch(market -> {
            Optional<LocalDate> cutoff = retentionCutoffs.getOrDefault(market, Optional.empty());
            return cutoff.isPresent()
                    && tradeDates.stream().allMatch(date -> !date.isAfter(cutoff.get()));
        });
        if (allOutside) {
            log.warn("요청 구간이 대상 시장 전부에서 보존 경계 밖이라 저장될 값이 없다. 돌지 않는다."
                    + " from={} to={} 거래일={}일 경계={}", from, to, tradeDates.size(),
                    retentionCutoffs);
            return BatchStatus.SUCCESS;
        }
        for (String market : markets) {
            Optional<LocalDate> cutoff = retentionCutoffs.getOrDefault(market, Optional.empty());
            long outside = cutoff
                    .map(c -> tradeDates.stream().filter(date -> !date.isAfter(c)).count())
                    .orElse(0L);
            if (outside > 0) {
                log.warn("{}: 요청 구간의 {}/{}거래일이 보존 경계({}) 밖이라 계산해도 저장되지 않는다."
                                + " 남은 {}거래일만 반영된다.", market, outside, tradeDates.size(),
                        cutoff.orElseThrow(), tradeDates.size() - outside);
            }
        }

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, to, triggeredBy, targets.size());

        // MUST: 거래일 수를 곱한다. 이 잡은 종목 하나를 거래일마다 다시 처리하므로 작업 단위가
        // 종목 수가 아니다. 종목 수만 신고하던 시절에 화면이 43400/350으로 떴다 —
        // 350종목 × 124거래일을 350으로 나눈 값이었고, 데이터가 부풀어 보였다.
        execution.plan(CollectionPhase.INDICATOR, tradeDates.size() * targets.size());

        log.info("지표 백필을 시작한다. 거래일={}일 종목={}개{}", tradeDates.size(), targets.size(),
                tickers == null || tickers.isEmpty()
                        ? ""
                        : " (티커로 좁혔다: " + tickers + " — 백분위·업종 상대값은 그래도 전 종목에서 낸다)");

        // 풀은 잡 하나에 하나다. 날짜마다 만들면 269번 만들고, 워커의 ThreadLocal 메모이저
        // (ADR-0138)가 날짜마다 버려져 첫 종목이 매번 다시 계산한다.
        ExecutorService pool =
                Executors.newFixedThreadPool(resolveWorkers(targets.size()), workerFactory());
        DerivedDayCalculator.Failures derivedFailures = new DerivedDayCalculator.Failures();
        try {
            // 날짜가 바깥 루프인 이유는 백분위 때문이다. 백분위는 "그날 전 종목의 값"을
            // 요구하므로, 한 날짜를 전 종목에 대해 끝낸 뒤에야 그날의 순위를 낼 수 있다.
            //
            // MUST NOT: 아래 두 계산을 targets로 좁히지 않는다. 둘은 거래일만 받고 활성 유니버스
            // 전량을 조인하므로, 종목 루프를 좁혀도 순위의 모집단은 그대로다(클래스 javadoc).
            for (int i = 0; i < tradeDates.size(); i++) {
                LocalDate tradeDate = tradeDates.get(i);
                // 진행률만으로는 남은 시간을 가늠할 수 없다. 어느 날짜를 돌고 있는지가
                // 이 잡에서 실제로 알고 싶은 것이다.
                execution.stage("%s · %d/%d거래일".formatted(tradeDate, i + 1, tradeDates.size()));

                // 지수는 날짜마다 한 번이다. 종목 루프 안에서 읽으면 350번 같은 쿼리를 던진다.
                Map<String, NavigableMap<LocalDate, BigDecimal>> marketIndexes =
                        indicatorRunner.marketIndexesFor(tradeDate);

                // 종목끼리 독립이라 여기만 팬아웃한다(ADR-0138). 아래 두 계산은 배리어
                // 뒤라 그대로 직렬이고, 그 배리어가 곧 이 호출의 조인이다.
                fanOutStocks(pool, execution, targets, tradeDate, retentionCutoffs, marketIndexes,
                        new LongAdder());
                // 백분위·업종 상대값 — 그날 전 종목의 값이 있어야 정해진다. 날짜 하나의 실패는 그 날짜에
                // 가둔다(DerivedDayCalculator.calculateIsolated) — 교착 하나가 나머지 날짜를 다 버리던 자리다(실행 318).
                derivedDayCalculator.calculateIsolated(execution, tradeDate, universeCode, derivedFailures);
            }

            // MUST: 날짜 루프 **밖**이다(ADR-0080). 러너가 「값을 그만 낸」 지표의 옛 행을 보존 창
            // 전체에서 지우면 파생 행이 그만큼 고아가 되고, 날짜별 정리는 그날 하나만 본다.
            // 루프 안에서 부르면 같은 일을 거래일 수만큼 반복한다(지표당 약 200ms × 18).
            // 경계는 시장별 최솟값을 쓴다. 창이 넓어질 뿐이고 판정("그날 원본이 없다")은
            // 시장과 무관하므로 넓은 쪽이 언제나 안전하다.
            Optional<LocalDate> orphanCutoff = retentionCutoffs.values().stream()
                    .flatMap(Optional::stream).min(LocalDate::compareTo);
            execution.stage("고아 파생값 정리");
            percentileCalculator.removeOrphansInWindow(orphanCutoff);
            sectorRelativeCalculator.removeOrphansInWindow(orphanCutoff);

            // MUST: 쓰기와 지우기가 전부 끝난 뒤다. 화면 기준일이 이 색인을 읽으므로
            // (ADR-0095) 여기를 빠뜨리면 새로 채운 날짜가 화면에 영영 안 보인다.
            execution.stage("거래일 색인 갱신");
            log.info("지표 거래일 색인을 갱신했다. 색인={}건", tradeDateIndex.rebuild());

            // 날짜 루프 백필의 작업 단위는 (거래일 × 종목)이라 날짜 실패를 같은 단계에 세지 않는다(ADR-0037) —
            // 나머지 날짜와 정리·색인을 다 돈 뒤 잡을 실패로 끝내 그 날짜를 다시 돌리게 한다.
            if (!derivedFailures.isEmpty()) {
                throw new IllegalStateException("파생층 " + derivedFailures.describe()
                        + " — 그 날짜로 다시 돌린다", derivedFailures.first());
            }
        } catch (DataSourceException e) {
            // 외부 호출이 없으므로 정상적으로는 도달하지 않는다. StockTask의 시그니처가
            // 이 예외를 선언하고 있어 형식상 잡을 뿐이며, 실제로 온다면 그것 자체가 이상 신호다.
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        } finally {
            shutDown(pool);
        }
        return execution.finish();
    }

    /**
     * 매 평일 화해의 ① — 종목마다 <b>{@code asOf} 한 판</b>으로 원본층을 보존 창 전체에 다시 쓴다.
     *
     * <p><b>한 판으로 충분한 이유</b>는 러너의 성질이다 — {@link IndicatorRunner#run}이 창 전체를 계산해
     * 경계 뒤를 전부 쓰고, 재무 지표는 봉마다 {@code KNOWN_ON}이라 날짜 d의 값이 {@code asOf}와
     * 무관하다(ADR-0128). 그래서 {@link #run}의 날짜 루프가 같은 종목을 거래일마다 다시 돌리던 것은
     * 원본층에 대해서는 같은 값을 N번 쓰는 일이었다. 예외는 분모가 {@code asOf}의 주식수를 따라가는
     * {@code RETAINED_TO_MARKET_4Y}이고(그 계산기 javadoc의 설계), 파생 자식이 없다.
     *
     * <p><b>MUST: {@code asOf}를 13:30 일일 갱신의 대상일과 같게 준다</b>(호출자의 몫). 다르면 위 예외가
     * 지금 편입 종목에서 두 판 사이를 매일 오간다 — 일일 갱신이 쓰고 화해가 덮고 다음 날 다시 쓴다.
     *
     * @param tickers 비면 보존 창 편입 전원({@link StockRegistrar#windowMemberIds}). 주면 그 티커의 창 편입
     */
    public BatchStatus rewriteOriginLayer(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                                          List<String> tickers) {
        List<Long> ids = tickers == null || tickers.isEmpty()
                ? stockRegistrar.windowMemberIds()
                : resolveTargets(tickers, null);
        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(ids);
        Map<String, Optional<LocalDate>> retentionCutoffs = retentionPolicy.writeCutoffs();

        BatchExecutionContext execution =
                batchExecutions.start(ORIGIN_JOB_NAME, asOf, triggeredBy, targets.size());
        execution.plan(CollectionPhase.INDICATOR, targets.size());
        log.info("원본층을 다시 쓴다. asOf={} 종목={}개{}", asOf, targets.size(),
                tickers == null || tickers.isEmpty() ? " (보존 창 편입 전원)" : " (티커로 좁혔다: " + tickers + ")");

        ExecutorService pool =
                Executors.newFixedThreadPool(resolveWorkers(targets.size()), workerFactory());
        LongAdder deleted = new LongAdder();
        try {
            execution.stage("원본층 · asOf " + asOf);
            Map<String, NavigableMap<LocalDate, BigDecimal>> marketIndexes =
                    indicatorRunner.marketIndexesFor(asOf);
            fanOutStocks(pool, execution, targets, asOf, retentionCutoffs, marketIndexes, deleted);
        } catch (DataSourceException e) {
            // run()과 같은 자리다 — 외부 호출이 없어 정상적으로는 오지 않는다.
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        } finally {
            shutDown(pool);
        }
        // 쓴·지운 행이 둘 다 0이면 이 판은 아무것도 안 바꿨다 — 관문 대조군(두 번째 화해 = 0)이 이것을 읽는다.
        return execution.finish("원본층 %d종목 · 쓴 %d · 지운 %d"
                .formatted(targets.size(), execution.writtenRowCount(), deleted.sum()));
    }

    /**
     * 매 평일 화해의 ② — <b>보존 창의 모든 거래일</b>에서 파생층(백분위·업종 상대값)을 다시 낸다.
     * 원본층은 안 부른다.
     *
     * <p><b>왜 창 전체인가.</b> 13:30 일일 갱신은 파생층을 최신일 하루만 다시 낸다. 그래서 그보다 앞
     * 날짜의 원본이 바뀌면(봉이 하루 늦은 클래스주 · 재무 정정 · 기업 행위 · ①이 다시 쓴 과거 구성원)
     * 그날의 순위가 낡은 채 남는다 — 2026-10-04 사본에서 09-28·29·30의 백분위가 날짜마다
     * 6,304·7,697·7,134칸 낡아 있었다. 날짜마다 그날 편입 전원을 다시 매기므로 원본을 다시 쓴 종목이
     * 몇이든 이 한 판이 닫는다.
     *
     * <p>성공 단위는 <b>거래일</b>이다 — 대상 수가 거래일 수이고 거래일 하나를 끝낼 때마다 하나를 센다.
     * 쓴 행 수(두 계산기의 반환값 합)는 마감 기록에 남긴다. 값이 같으면 0행이므로, 0이 아닌 날이
     * 곧 무엇인가 바뀐 날이다.
     *
     * <p>MUST: 날짜 루프 뒤에 {@link #run}의 루프 밖 두 단계를 같이 진다 — 고아 파생값 정리(ADR-0080)와
     * 거래일 색인 갱신(ADR-0095). 빼면 새로 채운 날짜가 화면에 안 보인다.
     */
    public BatchStatus rebuildDerived(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        Map<String, Optional<LocalDate>> retentionCutoffs = retentionPolicy.writeCutoffs();
        // 경계는 시장별 최솟값이다 — run()의 고아 정리와 같은 이유로 넓은 쪽이 안전하다. 경계가 없는
        // 시장만 있으면(시세가 보존 기간보다 적게 쌓인 초기) 시세의 첫 거래일부터 전부가 창이다.
        Optional<LocalDate> floor = retentionCutoffs.values().stream()
                .flatMap(Optional::stream).min(LocalDate::compareTo);
        LocalDate from = floor.map(cutoff -> cutoff.plusDays(1)).orElseGet(this::firstPriceDate);
        List<LocalDate> tradeDates = from == null ? List.of() : tradeDatesBetween(from, asOf);

        BatchExecutionContext execution =
                batchExecutions.start(DERIVED_JOB_NAME, asOf, triggeredBy, tradeDates.size());
        execution.plan(CollectionPhase.INDICATOR, tradeDates.size());
        log.info("파생층을 다시 낸다. {} ~ {} 거래일={}일", from, asOf, tradeDates.size());

        LayerChange changed = LayerChange.NONE;
        DerivedDayCalculator.Failures failures = new DerivedDayCalculator.Failures();
        try {
            for (int i = 0; i < tradeDates.size(); i++) {
                LocalDate tradeDate = tradeDates.get(i);
                execution.stage("%s · %d/%d거래일".formatted(tradeDate, i + 1, tradeDates.size()));
                // MUST NOT: 유니버스를 좁히지 않는다 — 두 계산기는 그날 활성 편입 전량을 조인한다.
                LayerChange day = derivedDayCalculator.calculateIsolated(execution, tradeDate, null, failures);
                if (day == null) {
                    // 작업 단위가 거래일이라 그대로 센다 — 하나라도 있으면 PARTIAL_SUCCESS와 「부분 실패」 경고.
                    execution.countExternally(CollectionPhase.INDICATOR, 0, 1, 0);
                    continue;
                }
                changed = changed.plus(day);
                execution.countExternally(CollectionPhase.INDICATOR, 1, 0, day.written());
            }
            // MUST: 전 날짜가 실패하면 FAILED다. 부분 성공으로 두면 신선도가 PARTIAL_SUCCESS를 신선으로 세어
            // (JobFreshnessPolicy) 결정적으로 깨진 SQL이 연체로 영영 안 뜬다.
            if (!tradeDates.isEmpty() && failures.size() == tradeDates.size()) {
                throw failures.first();
            }
            execution.stage("고아 파생값 정리");
            long orphans = percentileCalculator.removeOrphansInWindow(floor)
                    + sectorRelativeCalculator.removeOrphansInWindow(floor);
            execution.stage("거래일 색인 갱신");
            int indexed = tradeDateIndex.rebuild();
            return execution.finish("파생층 %d거래일 · 쓴 %d · 지운 %d · 고아 정리 %d · 색인 %d%s"
                    .formatted(tradeDates.size(), changed.written(), changed.deleted(), orphans, indexed,
                            failures.isEmpty() ? "" : " · " + failures.describe()));
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
    }

    /** 시세의 첫 거래일. 시세가 하나도 없으면 {@code null}이다. */
    private LocalDate firstPriceDate() {
        return jdbcTemplate.queryForObject("SELECT min(trade_date) FROM price_daily", LocalDate.class);
    }

    /**
     * 한 거래일의 종목을 워커에 흩고 <b>전부 돌아올 때까지 기다린다.</b>
     *
     * <p>이 조인이 곧 백분위·업종 상대값의 배리어다 — 둘은 「그날 전 종목의 값」을 요구한다.
     *
     * <p>{@code execution.process}가 스레드 안전하다는 것이 이 팬아웃의 전제다
     * ({@code BatchExecutionContext}의 원자적 카운터와 종료 플래그). 종목별 격리와 실패 기록은
     * 그 아래 {@code StockTaskExecutor}·{@code StockTaskRunner}가 그대로 진다(ADR-0007) —
     * 워커 스레드에는 바깥 트랜잭션이 없으므로 {@code REQUIRES_NEW}가 새 트랜잭션을 연다.
     */
    private void fanOutStocks(ExecutorService pool, BatchExecutionContext execution,
                              List<SecurityRef> targets, LocalDate tradeDate,
                              Map<String, Optional<LocalDate>> retentionCutoffs,
                              Map<String, NavigableMap<LocalDate, BigDecimal>> marketIndexes,
                              LongAdder deleted)
            throws DataSourceException {
        List<Future<?>> futures = new ArrayList<>(targets.size());
        for (SecurityRef target : targets) {
            Optional<LocalDate> cutoff = retentionCutoffs
                    .getOrDefault(target.marketCode(), Optional.empty());
            futures.add(pool.submit(() -> execution.process(target, CollectionPhase.INDICATOR,
                    security -> {
                        LayerChange change = indicatorRunner.run(
                                security.stockId(), tradeDate, cutoff, marketIndexes);
                        // 쓴 행은 실행 기록이 세고(process), 지운 행은 여기서 모은다 — 기록에 그 칸이 없다.
                        deleted.add(change.deleted());
                        return change.written();
                    })));
        }
        awaitAll(futures);
    }

    /**
     * 제출한 것을 <b>전부</b> 조인한다. 첫 실패에서 남은 것을 끊되, 이미 돌고 있던 것의 예외도
     * 버리지 않는다.
     *
     * <p><b>MUST: 끊고 나서도 전부 조인한다.</b> {@code cancel(false)}는 <b>큐에 있는 것만</b>
     * 떨어뜨리고 이미 실행 중인 태스크는 못 끊는다. 그 태스크의 예외는 {@code Future} 안에
     * 남는데 아무도 꺼내지 않으면 그것이 silent fail이다. 첫 번째를 다시 던지고 나머지는
     * {@code addSuppressed}로 싣는다.
     *
     * <p><b>MUST: 큐를 끊는다.</b> 안 끊으면 남은 1,300여 개가 다 돈 뒤에야 풀이 닫히고,
     * 그 지연이 종료 예산(15초)을 통째로 넘겨 ADR-0078이 닫은 결함을 되살린다.
     */
    private void awaitAll(List<Future<?>> futures) throws DataSourceException {
        Throwable first = null;
        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get();
            } catch (CancellationException e) {
                // 우리가 끊은 것이다. 원인은 first가 이미 들고 있다.
            } catch (ExecutionException e) {
                if (first == null) {
                    first = e.getCause();
                    cancelFrom(futures, i + 1);
                } else if (first != e.getCause()) {
                    first.addSuppressed(e.getCause());
                }
            } catch (InterruptedException e) {
                // 인터럽트는 이 저장소에서 종료 말고는 오지 않는다(ADR-0078 — 우리는 중단
                // 신호로 쓰지 않는다). 상태를 복원하고 종료 신호로 올린다.
                Thread.currentThread().interrupt();
                cancelFrom(futures, i + 1);
                throw new BatchShutdownException(
                        "지표 백필의 종목 조인이 인터럽트됐다 — 프로세스가 내려가는 중이다.", e);
            }
        }
        if (first != null) {
            rethrow(first);
        }
    }

    private static void cancelFrom(List<Future<?>> futures, int from) {
        for (int i = from; i < futures.size(); i++) {
            // false다 — 돌고 있는 것을 인터럽트하지 않는다(ADR-0078). 큐만 떨군다.
            futures.get(i).cancel(false);
        }
    }

    /**
     * 워커에서 난 예외를 <b>원본 그대로</b> 잡 스레드에 되던진다.
     *
     * <p>MUST: 감싸지 않는다. {@link BatchShutdownException}을 감싸면
     * {@code failUnexpectedly}의 {@code instanceof} 조기 반환이 안 걸려 <b>종료마다</b>
     * "이미 마감된 …" 경고가 확정적으로 나고, 그러면 그 경고가 진짜 마감 경합을 가리키기를
     * 그만둔다(ADR-0078). {@code DataSourceException}도 같다 — 감싸면 호출부의
     * {@code catch}가 {@code failFatally}로 못 간다.
     */
    private static void rethrow(Throwable cause) throws DataSourceException {
        switch (cause) {
            case DataSourceException e -> throw e;
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            default -> throw new IllegalStateException(
                    "종목 작업이 예상하지 못한 checked 예외를 냈다", cause);
        }
    }

    /**
     * 몇 개로 흩을 것인가. <b>설정값이 상한을 넘을 수 없다</b>(ADR-0138).
     *
     * <p><b>이 기계에 운영 앱이 온디바이스로 함께 산다</b>(2026-09-11 사용자 판단). 평일 14시
     * 사슬은 아예 그 JVM 안에서 이 잡을 돈다. 가용 코어를 전부 끌어다 쓰면 화면이 느려지므로,
     * 유보를 「기본값이 우연히 작은 것」에 맡기지 않고 <b>상한으로</b> 박는다 — 그래야
     * {@code --pipeline.indicator-backfill.parallelism=99}를 줘도 못 넘는다.
     *
     * <p>MUST: 정한 수와 <b>어느 항이 이겼는지</b>를 찍는다. 이 클램프는 평소 아무것도 자르지
     * 않으므로 <b>깎일 때가 조용하다</b> — 풀 설정이 빠졌거나 {@code DB_URL}이 다른 인스턴스를
     * 물으면 워커가 1로 내려가고, 그러면 A/B가 「병렬이 안 빨라졌다」는 거짓 결론을 낸다.
     */
    private int resolveWorkers(int targetCount) {
        int cores = Runtime.getRuntime().availableProcessors();
        // 코어의 1/3(최소 2)을 서버·GC·OS에 남긴다. 코어가 적은 장비로 옮겨도 무너지지 않는다.
        int coreCeiling = cores - Math.max(2, cores / 3);
        int pool = maxPoolSize();
        int poolCeiling = pool - RESERVED_CONNECTIONS;

        // 가장 빡빡한 항과 **그 이름**을 함께 고른다. 이름을 사후에 맞히려 들면 하한 1이
        // 걸릴 때 엉뚱한 항을 지목한다 — 그 로그가 진단의 유일한 단서인데 거짓이 된다.
        List<Bound> bounds = List.of(
                new Bound("설정", configuredParallelism),
                new Bound("대상 수", targetCount),
                new Bound("코어 유보", coreCeiling),
                new Bound("커넥션 풀 여유", poolCeiling));
        Bound tightest = bounds.stream().min(Comparator.comparingInt(Bound::value)).orElseThrow();
        int workers = Math.max(1, tightest.value());

        log.info("종목 루프를 {}워커로 돈다 — {}가 정했다({}). 설정={} 대상={}종목"
                        + " 코어={}→상한{} 풀={}→상한{}",
                workers, tightest.label(), tightest.value(), configuredParallelism, targetCount,
                cores, coreCeiling, pool, poolCeiling);
        return workers;
    }

    /** 워커 수를 묶는 항 하나. 값과 이름이 함께 다녀야 로그가 거짓말을 안 한다. */
    private record Bound(String label, int value) {
    }

    /**
     * 커넥션 풀의 최대 크기. 못 물으면 <b>이 항이 안 자르게</b> 둔다.
     *
     * <p>추측한 수를 넣으면 그 추측이 조용히 워커를 깎는다 — 「모른다」를 「작다」로 바꾸지 않는다.
     */
    private int maxPoolSize() {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getMaximumPoolSize();
        }
        return configuredParallelism + RESERVED_CONNECTIONS;
    }

    /** 로그와 시험이 워커를 세어볼 수 있게 이름을 준다. */
    private static ThreadFactory workerFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "indicator-backfill-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 워커 풀을 닫는다.
     *
     * <p>MUST NOT: {@code try (ExecutorService …)}로 대신하지 않는다. JDK 21의
     * {@code ExecutorService.close()}는 {@code awaitTermination(1L, DAYS)}를 돌고, <b>호출
     * 스레드가 인터럽트되면 {@code shutdownNow()}로 워커 전원을 인터럽트한다.</b> 관리자 화면
     * 경로의 잡은 {@code applicationTaskExecutor} 풀 스레드에서 돌고 그 풀의
     * {@code shutdown.await-termination}이 기본 {@code false}라 빈 파괴가 그 스레드를
     * 인터럽트하므로, 그 인터럽트가 워커로 번진다 — ADR-0078이 기각한 축이다.
     */
    private static void shutDown(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(WORKER_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                log.warn("워커가 {}초 안에 끝나지 않아 끊는다. 종료 예산을 넘기지 않는 것이 우선이다.",
                        WORKER_SHUTDOWN_SECONDS);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * {@link CorporateActionJob}과 같은 자리다 — 오타로 대상이 빈 것과 계산할 것이 없는 것이
     * 로그에서 구분되지 않는다. 이 잡에서는 특히 나쁘다: 무효화 뒤 복구로 부르는 경우가 많고,
     * 그때 <b>0종목 성공</b>은 "그 종목이 화면에서 사라진 채로 끝났다"와 같은 뜻이다.
     */
    private List<Long> resolveTargets(List<String> tickers, String universeCode) {
        List<String> names = tickers == null ? List.of() : tickers;
        if (universeCode != null) {
            // 비활성 유니버스도 대상이 된다 — 그것이 이 갈래의 존재 이유다(클래스 javadoc).
            List<Long> members = stockRegistrar.oneUniverseMemberIds(universeCode, names);
            if (members.isEmpty()) {
                throw new IllegalArgumentException(
                        "유니버스 " + universeCode + "에 대상 종목이 하나도 없다"
                                + (names.isEmpty() ? "" : " (티커 " + names + ")")
                                + " — 유니버스 코드와 수집 대상 시장을 확인하라."
                                + " 대상 0건으로 성공시키지 않는다.");
            }
            return members;
        }
        if (names.isEmpty()) {
            return stockRegistrar.activeUniverseMemberIds();
        }
        List<Long> matched = stockRegistrar.activeUniverseMemberIdsByTicker(tickers);
        if (matched.isEmpty()) {
            throw new IllegalArgumentException(
                    "준 티커 중 편입 중인 활성 종목이 하나도 없다: " + tickers
                            + " — 종목코드를 확인하라. 대상 0건으로 성공시키지 않는다.");
        }
        if (matched.size() < tickers.size()) {
            log.warn("티커 {}개 중 {}개만 편입 중인 활성 종목이다. 나머지는 건너뛴다: {}",
                    tickers.size(), matched.size(), tickers);
        }
        return matched;
    }

    /**
     * 대상 유니버스가 <b>활성 유니버스와 종목을 겹치면</b> 시작 전에 멈춘다.
     *
     * <p>겹친 채로 돌면 두 계산기의 모집단에 같은 종목이 두 번 들어가 {@code ON CONFLICT DO
     * UPDATE}가 같은 행을 두 번 건드리고, PostgreSQL이 <b>cardinality violation</b>으로 배치를
     * 죽인다. 그 예외는 원인을 말하지 않으므로 여기서 무엇이 겹쳤는지를 세어 말한다.
     *
     * <p>MUST NOT: 겹침을 <b>허용</b>하는 쪽으로 고치지 않는다. {@code indicator_value}에
     * 유니버스 축이 없어 종목당 값이 하나뿐이고, 그래서 어느 모집단의 순위인지가 정해지지 않는다.
     *
     * <p><b>MUST: 자기 자신을 겹침에서 뺀다</b>({@code b.universe_code <> a.universe_code}).
     * 이 갈래는 <b>비활성</b> 유니버스를 채우려고 만들었지만 대상이 <b>활성</b>일 수도 있고
     * (리밸런싱 뒤 채우기 · 보존 창 복구), 그때 자기 조인이 <b>전 종목을 자기와 겹치는 것으로</b>
     * 신고한다. 실측으로 {@code US_TOP1000}이 켜진 뒤 1,000종목 전부가 그렇게 걸렸고
     * (자기 제외 시 0), 그 메시지가 <b>「겹치는 유니버스를 먼저 비활성으로 내려라」라는 실행
     * 불가능한 처방</b>을 냈다 — 내려야 할 대상이 자기 자신이다.
     */
    private void rejectOverlappingUniverse(String universeCode) {
        if (universeCode == null) {
            return;
        }
        Long overlapping = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT a.stock_id)
                FROM universe_membership a
                JOIN universe_membership b
                  ON b.stock_id = a.stock_id AND b.effective_to IS NULL
                 AND b.universe_code <> a.universe_code
                JOIN universe u ON u.code = b.universe_code AND u.is_active
                WHERE a.universe_code = ? AND a.effective_to IS NULL
                """, Long.class, universeCode);
        if (overlapping != null && overlapping > 0) {
            throw new IllegalStateException(
                    "유니버스 " + universeCode + "의 " + overlapping
                            + "종목이 이미 활성 유니버스에도 편입돼 있다 — 백분위 모집단이"
                            + " 겹쳐 배치가 cardinality violation으로 죽는다."
                            + " 겹치는 유니버스를 먼저 비활성으로 내리거나 대상을 바꿔라.");
        }
    }

    /**
     * 구간 안의 거래일 — <b>전 시장의 합집합</b>이다.
     *
     * <p>MUST NOT: 이것을 시장별로 가르지 않는다. 날짜 루프의 목적은 <b>어느 날짜를 한 번씩
     * 돌 것인가</b>이고, 그 답은 합집합이 맞다. 한 시장이 쉰 날에 그 시장 종목을 돌려도
     * 러너는 <b>그 종목의 마지막 봉 날짜</b>로 같은 값을 다시 쓸 뿐이라 멱등이다.
     *
     * <p>시장으로 갈라야 하는 것은 이 목록이 아니라 <b>보존 경계</b>다 — 그쪽은 날짜가 밀리면
     * 「저장될 값이 없다」로 읽혀 잡이 돌지 않고 SUCCESS로 끝난다.
     */
    private List<LocalDate> tradeDatesBetween(LocalDate from, LocalDate to) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT trade_date FROM price_daily
                WHERE trade_date BETWEEN ? AND ?
                ORDER BY trade_date
                """, LocalDate.class, from, to);
    }

}
