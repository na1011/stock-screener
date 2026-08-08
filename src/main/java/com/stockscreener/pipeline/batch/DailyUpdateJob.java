package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorRetentionPolicy;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.indicator.PercentileCalculator;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.MarketCalendar;
import com.stockscreener.pipeline.collector.PriceCollector;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.watchlist.WatchlistAlertRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 일일 갱신. 장 마감 후 전일 종가를 수집하고 그것으로 지표를 계산한다.
 *
 * <p>수집과 계산을 한 잡에 둔 이유는 <b>순서가 강제되어야 하기 때문</b>이다. 지표는 그날의
 * 시세가 저장된 뒤에만 계산할 수 있다. 둘을 별도 잡으로 나누면 스케줄 간격이 곧 그 전제가
 * 되고, 수집이 늦어진 날 지표가 어제 값으로 계산되는데 그것은 조용히 그럴듯하다.
 *
 * <p>MUST NOT: 초기 적재 분기를 여기 섞지 않는다({@code data-pipeline.md}). 초기 적재는
 * 조회 기간(2년 vs 1일)과 쓰기 패턴이 다른 1회성 작업이므로 {@link InitialLoadJob}에 있다.
 * 분기를 섞으면 "오늘이 첫 실행인가"를 매일 판단하게 되고, 그 판단이 틀리면 850종목의
 * 2년치를 매일 다시 받는다.
 *
 * <p>재무는 여기서 받지 않는다. 재무제표는 분기에 한 번 공시되므로 매일 850종목을 조회하면
 * 쿼터만 태운다. 공시 시즌에 별도로 돌리는 것이 맞고, v1에서는 초기 적재 이후 수동으로
 * 실행한다 — 자동화는 공시 일정을 아는 것이 전제이며 그것 자체가 또 하나의 수집 대상이다.
 */
@Component
public class DailyUpdateJob {

    public static final String JOB_NAME = "daily-update";

    private static final Logger log = LoggerFactory.getLogger(DailyUpdateJob.class);

    private final PriceCollector priceCollector;
    private final StockRegistrar stockRegistrar;
    private final MarketCalendar marketCalendar;
    private final BatchExecutions batchExecutions;
    private final IndicatorRunner indicatorRunner;
    private final PercentileCalculator percentileCalculator;
    private final IndicatorRetentionPolicy retentionPolicy;
    private final WatchlistAlertRunner watchlistAlertRunner;
    private final JdbcTemplate jdbcTemplate;

    public DailyUpdateJob(PriceCollector priceCollector, StockRegistrar stockRegistrar,
                          MarketCalendar marketCalendar, BatchExecutions batchExecutions,
                          IndicatorRunner indicatorRunner, PercentileCalculator percentileCalculator,
                          IndicatorRetentionPolicy retentionPolicy,
                          WatchlistAlertRunner watchlistAlertRunner,
                          JdbcTemplate jdbcTemplate) {
        this.priceCollector = priceCollector;
        this.stockRegistrar = stockRegistrar;
        this.marketCalendar = marketCalendar;
        this.batchExecutions = batchExecutions;
        this.indicatorRunner = indicatorRunner;
        this.percentileCalculator = percentileCalculator;
        this.retentionPolicy = retentionPolicy;
        this.watchlistAlertRunner = watchlistAlertRunner;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 대상 일자의 시세를 수집한다.
     *
     * @param targetDate 수집 대상 거래일. 실행 시각이 아니다 — 새벽에 도는 배치의 대상은 전일이다.
     */
    public BatchStatus run(LocalDate targetDate, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        if (marketCalendar.isWeekend(targetDate)) {
            log.info("주말이므로 일일 갱신을 건너뛴다. targetDate={}", targetDate);
            return BatchStatus.SUCCESS;
        }

        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(currentUniverseMembers());
        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, targetDate, triggeredBy, targets.size());

        return collect(execution, targets, targetDate, targetDate);
    }

    /**
     * 실패한 종목만 다시 수집한다. 관리자의 부분 재실행이 이 경로를 쓴다.
     *
     * <p>초기 적재 분기가 아니다 — 대상 목록만 좁아질 뿐 처리 로직은 같다. 그것이
     * 재실행과 초기 적재를 가르는 기준이다.
     */
    public BatchStatus retryFailures(long previousExecutionId, LocalDate targetDate) {
        List<StockFailure> failures =
                batchExecutions.recorder().unresolvedFailures(previousExecutionId);
        List<Long> stockIds = failures.stream()
                .filter(failure -> failure.phase() == CollectionPhase.PRICE)
                .map(StockFailure::stockId)
                .toList();

        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(stockIds);
        BatchExecutionContext execution = batchExecutions.startRetry(
                JOB_NAME, targetDate, targets.size(), previousExecutionId);

        return collect(execution, targets, targetDate, targetDate, previousExecutionId);
    }

    private BatchStatus collect(BatchExecutionContext execution, List<SecurityRef> targets,
                                LocalDate from, LocalDate to) {
        return collect(execution, targets, from, to, null);
    }

    /**
     * 대상 종목의 시세를 수집하고, 이어서 지표를 계산한다.
     *
     * <p>{@code resolvingExecutionId}가 있으면 부분 재실행이다. 이때
     * <b>실제로 성공한 종목만</b> 해결로 표시한다. 대상 전체를 무조건 해결로 표시하면 두 번
     * 연속 실패한 종목이 미해결 목록에서 사라져, 실패를 조용히 삼키지 않겠다는 파이프라인
     * 전체의 전제가 바로 이 지점에서 깨진다.
     */
    private BatchStatus collect(BatchExecutionContext execution, List<SecurityRef> targets,
                                LocalDate from, LocalDate to, Long resolvingExecutionId) {
        try {
            for (SecurityRef target : targets) {
                boolean collected = execution.process(target, CollectionPhase.PRICE,
                        security -> priceCollector.collect(security, from, to));

                if (collected && resolvingExecutionId != null) {
                    batchExecutions.recorder().resolveFailure(
                            resolvingExecutionId, target.stockId(), CollectionPhase.PRICE);
                }

                // 시세가 들어온 종목만 지표를 계산한다. 수집에 실패한 종목은 계산할 새 원자료가
                // 없으므로, 굳이 돌려서 실패를 하나 더 만들 이유가 없다.
                if (collected) {
                    execution.process(target, CollectionPhase.INDICATOR,
                            security -> indicatorRunner.run(security.stockId(), to));
                }
            }
        } catch (DataSourceException e) {
            // AUTH처럼 복구 불가능한 실패다. 850종목을 마저 시도할 이유가 없다.
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }

        // 백분위는 전 종목의 값이 저장된 뒤에야 계산할 수 있다. 종목 루프 안에서는 원리적으로
        // 불가능하므로 루프가 끝난 뒤 하루치를 한 번에 처리한다.
        percentileCalculator.calculate(to);

        // 워치리스트 판정은 백분위 뒤여야 한다. 백분위 조건("PER 하위 20%")을 건 화면이
        // 그 앞에서 판정되면 어제 백분위로 오늘을 판정하게 되고, 그것은 조용히 그럴듯하다.
        evaluateWatchlist(execution, to);

        // 보존 정리(ADR-0011)는 마감 전에 하되 실패해도 배치를 죽이지 않는다. 오래된 행이
        // 하루 더 남는 것은 저장 공간 문제일 뿐이지만, 여기서 예외가 올라가면 그날의 수집과
        // 계산이 통째로 실패한 것처럼 기록된다.
        try {
            retentionPolicy.purge();
        } catch (RuntimeException e) {
            log.warn("지표 보존 정리에 실패했다. 수집·계산은 정상이며 다음 실행에서 다시 시도한다.", e);
        }

        return execution.finish();
    }

    /**
     * 워치리스트 조건을 판정해 새로 충족된 것을 사용자에게 알린다(spec §7).
     *
     * <p>실패가 그날의 수집·계산을 무효로 만들지 않게 격리한다 — 그 둘은 이미 끝났고,
     * 여기서 예외가 올라가면 배치 전체가 실패한 것으로 기록된다. 보존 정리와 같은 판단이다.
     *
     * <p>다만 보존 정리와 달리 <b>운영자에게 알린다.</b> 오래된 지표 행이 하루 더 남는 것은
     * 저장 공간 문제일 뿐이지만, 워치리스트 판정이 며칠 조용히 안 돌면 사용자는 알림이
     * 없는 것을 "조건에 안 맞았나 보다"로 읽는다 — 기능이 죽은 채로 정상처럼 보인다.
     */
    private void evaluateWatchlist(BatchExecutionContext execution, LocalDate asOf) {
        try {
            WatchlistAlertRunner.Result result = watchlistAlertRunner.run(asOf);
            if (result.hasFailure()) {
                execution.alertOperator(AlertSeverity.WARNING, "워치리스트 판정 부분 실패",
                        "전략 %d개의 조건을 판정하지 못했다. 판정 %d건, 통지 %d명."
                                .formatted(result.failedScreens(), result.evaluated(),
                                        result.notifiedUsers()));
            }
        } catch (RuntimeException e) {
            log.error("워치리스트 판정에 실패했다. 수집·계산은 정상이며 다음 실행에서 다시 시도한다.", e);
            execution.alertOperator(AlertSeverity.ERROR, "워치리스트 판정 실패",
                    "수집·계산은 정상이다. 다음 실행에서 다시 시도한다: " + e);
        }
    }

    /**
     * 현재 편입 중인 전 지수의 종목.
     *
     * <p>{@code DISTINCT}인 이유는 한 종목이 여러 지수에 동시에 편입될 수 있기 때문이다.
     * 중복을 걸러내지 않으면 같은 종목을 두 번 수집해 쿼터를 낭비한다.
     */
    private List<Long> currentUniverseMembers() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                WHERE m.effective_to IS NULL AND s.is_active
                ORDER BY m.stock_id
                """, Long.class);
    }
}
