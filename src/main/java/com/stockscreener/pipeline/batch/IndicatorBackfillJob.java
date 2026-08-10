package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.indicator.PercentileCalculator;
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
 */
@Component
public class IndicatorBackfillJob {

    public static final String JOB_NAME = "indicator-backfill";

    private static final Logger log = LoggerFactory.getLogger(IndicatorBackfillJob.class);

    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;
    private final IndicatorRunner indicatorRunner;
    private final PercentileCalculator percentileCalculator;
    private final JdbcTemplate jdbcTemplate;

    public IndicatorBackfillJob(StockRegistrar stockRegistrar, BatchExecutions batchExecutions,
                                IndicatorRunner indicatorRunner,
                                PercentileCalculator percentileCalculator,
                                JdbcTemplate jdbcTemplate) {
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
        this.indicatorRunner = indicatorRunner;
        this.percentileCalculator = percentileCalculator;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@code from}부터 {@code to}까지 매 거래일의 지표를 계산한다.
     *
     * <p>거래일 목록은 {@code price_daily}에서 얻는다. 달력으로 만들면 휴장일까지 돌게 되고,
     * 그날은 시세가 없어 아무 값도 나오지 않는 헛일이 된다. 실제로 시세가 있는 날이
     * 곧 거래일이라는 사실을 쓰는 편이 정확하고 싸다.
     */
    public BatchStatus run(LocalDate from, LocalDate to,
                           BatchExecutionRecorder.TriggeredBy triggeredBy) {
        List<LocalDate> tradeDates = tradeDatesBetween(from, to);
        if (tradeDates.isEmpty()) {
            log.info("해당 구간에 시세가 없어 백필할 것이 없다. from={} to={}", from, to);
            return BatchStatus.SUCCESS;
        }

        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(currentUniverseMembers());
        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, to, triggeredBy, targets.size());

        // MUST: 거래일 수를 곱한다. 이 잡은 종목 하나를 거래일마다 다시 처리하므로 작업 단위가
        // 종목 수가 아니다. 종목 수만 신고하던 시절에 화면이 43400/350으로 떴다 —
        // 350종목 × 124거래일을 350으로 나눈 값이었고, 데이터가 부풀어 보였다.
        execution.plan(CollectionPhase.INDICATOR, tradeDates.size() * targets.size());

        log.info("지표 백필을 시작한다. 거래일={}일 종목={}개", tradeDates.size(), targets.size());

        try {
            // 날짜가 바깥 루프인 이유는 백분위 때문이다. 백분위는 "그날 전 종목의 값"을
            // 요구하므로, 한 날짜를 전 종목에 대해 끝낸 뒤에야 그날의 순위를 낼 수 있다.
            for (int i = 0; i < tradeDates.size(); i++) {
                LocalDate tradeDate = tradeDates.get(i);
                // 진행률만으로는 남은 시간을 가늠할 수 없다. 어느 날짜를 돌고 있는지가
                // 이 잡에서 실제로 알고 싶은 것이다.
                execution.stage("%s · %d/%d거래일".formatted(tradeDate, i + 1, tradeDates.size()));

                for (SecurityRef target : targets) {
                    execution.process(target, CollectionPhase.INDICATOR,
                            security -> indicatorRunner.run(security.stockId(), tradeDate));
                }
                percentileCalculator.calculate(tradeDate);
            }
        } catch (DataSourceException e) {
            // 외부 호출이 없으므로 정상적으로는 도달하지 않는다. StockTask의 시그니처가
            // 이 예외를 선언하고 있어 형식상 잡을 뿐이며, 실제로 온다면 그것 자체가 이상 신호다.
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        return execution.finish();
    }

    private List<LocalDate> tradeDatesBetween(LocalDate from, LocalDate to) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT trade_date FROM price_daily
                WHERE trade_date BETWEEN ? AND ?
                ORDER BY trade_date
                """, LocalDate.class, from, to);
    }

    private List<Long> currentUniverseMembers() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                WHERE m.effective_to IS NULL AND s.is_active
                ORDER BY m.stock_id
                """, Long.class);
    }
}
