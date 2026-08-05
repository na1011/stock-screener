package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.MarketCalendar;
import com.stockscreener.pipeline.collector.PriceCollector;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 일일 갱신. 장 마감 후 전일 종가를 수집한다.
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
    private final JdbcTemplate jdbcTemplate;

    public DailyUpdateJob(PriceCollector priceCollector, StockRegistrar stockRegistrar,
                          MarketCalendar marketCalendar, BatchExecutions batchExecutions,
                          JdbcTemplate jdbcTemplate) {
        this.priceCollector = priceCollector;
        this.stockRegistrar = stockRegistrar;
        this.marketCalendar = marketCalendar;
        this.batchExecutions = batchExecutions;
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

        BatchStatus status = collect(execution, targets, targetDate, targetDate);

        // 성공한 것을 해결로 표시한다. 이것이 없으면 "미해결 건수"가 영원히 줄지 않는다.
        for (SecurityRef target : targets) {
            batchExecutions.recorder().resolveFailure(
                    previousExecutionId, target.stockId(), CollectionPhase.PRICE);
        }
        return status;
    }

    private BatchStatus collect(BatchExecutionContext execution, List<SecurityRef> targets,
                                LocalDate from, LocalDate to) {
        try {
            for (SecurityRef target : targets) {
                execution.process(target, CollectionPhase.PRICE,
                        security -> priceCollector.collect(security, from, to));
            }
        } catch (DataSourceException e) {
            // AUTH처럼 복구 불가능한 실패다. 850종목을 마저 시도할 이유가 없다.
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        return execution.finish();
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
