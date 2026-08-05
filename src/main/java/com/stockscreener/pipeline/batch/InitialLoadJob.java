package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.FinancialCollector;
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
 * 초기 적재. 시세 2년·재무 3년을 한 번 받아온다(spec §7).
 *
 * <p>MUST: 일일 갱신과 <b>분리된 잡</b>이다({@code data-pipeline.md}). 성격이 다른 1회성
 * 작업이며, 조회 기간과 쓰기 패턴이 다르다. 하나로 합쳐 "첫 실행인가"로 분기하면 그 판단이
 * 틀렸을 때 850종목의 2년치를 매일 다시 받는다.
 *
 * <p>수동 실행을 전제한다({@code spring.batch.job.enabled=false}). 애플리케이션을 재시작할
 * 때마다 시작되면 안 되기 때문이다.
 *
 * <h2>쿼터 소진과 재시작</h2>
 * <p>일일 호출 한도가 있는 소스에서 850종목 × 2년을 한 번에 받는 것은 <b>한 번에 끝나지 않는
 * 것이 정상</b>이다. 이미 적재된 종목을 건너뛰므로, 중단된 뒤 다음 날 다시 실행하면 이어서
 * 진행된다. 이것이 Spring Batch를 도입한 근거이기도 하다(ADR-0005).
 */
@Component
public class InitialLoadJob {

    public static final String JOB_NAME = "initial-load";

    /** spec §7 — 기술적 지표 계산을 위한 최소 시세 이력. */
    private static final int PRICE_HISTORY_YEARS = 2;

    /** spec §7 — 성장률 계산을 위한 최소 재무 이력. */
    private static final int FINANCIAL_HISTORY_YEARS = 3;

    private static final Logger log = LoggerFactory.getLogger(InitialLoadJob.class);

    private final PriceCollector priceCollector;
    private final FinancialCollector financialCollector;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;
    private final JdbcTemplate jdbcTemplate;

    public InitialLoadJob(PriceCollector priceCollector, FinancialCollector financialCollector,
                          StockRegistrar stockRegistrar, BatchExecutions batchExecutions,
                          JdbcTemplate jdbcTemplate) {
        this.priceCollector = priceCollector;
        this.financialCollector = financialCollector;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 아직 적재되지 않은 종목의 이력을 받아온다.
     *
     * @param asOf 적재 기준일. 여기서 2년·3년을 거슬러 올라간다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(stocksNeedingHistory());
        if (targets.isEmpty()) {
            log.info("초기 적재가 필요한 종목이 없다. 모든 편입 종목에 이력이 있다.");
            BatchExecutionContext empty =
                    batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0);
            return empty.finish();
        }

        log.info("초기 적재를 시작한다. 대상 {}종목, 시세 {}년·재무 {}년",
                targets.size(), PRICE_HISTORY_YEARS, FINANCIAL_HISTORY_YEARS);

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size());

        LocalDate priceFrom = asOf.minusYears(PRICE_HISTORY_YEARS);
        int fromFiscalYear = asOf.getYear() - FINANCIAL_HISTORY_YEARS;

        try {
            for (SecurityRef target : targets) {
                // 시세와 재무를 각각 독립된 단계로 처리한다. 재무만 실패한 종목의 시세를
                // 재실행에서 다시 받지 않으려면 실패 기록이 단계를 구분해야 한다.
                execution.process(target, CollectionPhase.PRICE,
                        security -> priceCollector.collect(security, priceFrom, asOf));
                execution.process(target, CollectionPhase.FINANCIAL,
                        security -> financialCollector.collect(security, fromFiscalYear));
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        return execution.finish();
    }

    /**
     * 이력이 없는 편입 종목.
     *
     * <p>이미 적재된 종목을 건너뛰는 것이 재실행 가능성의 근거다. 쿼터 소진으로 중단해도
     * 다음 실행이 남은 종목부터 이어서 처리한다.
     *
     * <p>{@code NOT EXISTS}로 판정하는 이유는 "한 봉이라도 있으면 적재된 것으로 본다"가
     * 이 시점에 충분하기 때문이다. 부분 적재의 정밀한 판정은 시세 이력의 실제 시작일을
     * 봐야 하는데, 상장일이 2년 이내인 종목과 구분할 수 없어 오히려 틀린다.
     */
    private List<Long> stocksNeedingHistory() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                WHERE m.effective_to IS NULL AND s.is_active
                  AND NOT EXISTS (SELECT 1 FROM price_daily p WHERE p.stock_id = m.stock_id)
                ORDER BY m.stock_id
                """, Long.class);
    }
}
