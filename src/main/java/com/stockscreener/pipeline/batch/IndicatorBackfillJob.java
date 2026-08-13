package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorRetentionPolicy;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.indicator.PercentileCalculator;
import com.stockscreener.indicator.SectorRelativeCalculator;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

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
 * 필터가 없으면 한 종목의 판정을 고칠 때마다 180거래일 × 350종목을 다시 돌아야 하고
 * (실측 2.6~8시간), 그 비용이 <b>"판정을 한 번에 다 끝내야 한다"</b>는 제약을 만든다.
 */
@Component
public class IndicatorBackfillJob {

    public static final String JOB_NAME = "indicator-backfill";

    private static final Logger log = LoggerFactory.getLogger(IndicatorBackfillJob.class);

    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;
    private final IndicatorRunner indicatorRunner;
    private final PercentileCalculator percentileCalculator;
    private final SectorRelativeCalculator sectorRelativeCalculator;
    private final IndicatorRetentionPolicy retentionPolicy;
    private final JdbcTemplate jdbcTemplate;

    public IndicatorBackfillJob(StockRegistrar stockRegistrar, BatchExecutions batchExecutions,
                                IndicatorRunner indicatorRunner,
                                PercentileCalculator percentileCalculator,
                                SectorRelativeCalculator sectorRelativeCalculator,
                                IndicatorRetentionPolicy retentionPolicy,
                                JdbcTemplate jdbcTemplate) {
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
        this.indicatorRunner = indicatorRunner;
        this.percentileCalculator = percentileCalculator;
        this.sectorRelativeCalculator = sectorRelativeCalculator;
        this.retentionPolicy = retentionPolicy;
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
                           List<String> tickers) {
        List<LocalDate> tradeDates = tradeDatesBetween(from, to);
        if (tradeDates.isEmpty()) {
            log.info("해당 구간에 시세가 없어 백필할 것이 없다. from={} to={}", from, to);
            return BatchStatus.SUCCESS;
        }

        // 보존 경계 밖은 계산해도 저장되지 않는다(ADR-0011). 그 사실을 여기서 말하지 않으면
        // 몇 시간을 돌고 0행으로 끝나는데 잡은 SUCCESS다 — 원인을 데이터나 계산기에서 찾게 된다.
        Optional<LocalDate> retentionCutoff = retentionPolicy.writeCutoff();
        long outsideRetention = retentionCutoff
                .map(cutoff -> tradeDates.stream().filter(date -> !date.isAfter(cutoff)).count())
                .orElse(0L);
        if (outsideRetention == tradeDates.size()) {
            log.warn("요청 구간이 전부 보존 경계({}) 밖이라 저장될 값이 없다. 돌지 않는다."
                    + " from={} to={} 거래일={}일", retentionCutoff.orElseThrow(),
                    from, to, tradeDates.size());
            return BatchStatus.SUCCESS;
        }
        if (outsideRetention > 0) {
            log.warn("요청 구간의 {}/{}거래일이 보존 경계({}) 밖이라 계산해도 저장되지 않는다."
                    + " 남은 {}거래일만 반영된다.", outsideRetention, tradeDates.size(),
                    retentionCutoff.orElseThrow(), tradeDates.size() - outsideRetention);
        }

        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(resolveTargets(tickers));
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

                for (SecurityRef target : targets) {
                    execution.process(target, CollectionPhase.INDICATOR,
                            security -> indicatorRunner.run(
                                    security.stockId(), tradeDate, retentionCutoff, marketIndexes));
                }
                percentileCalculator.calculate(tradeDate);
                // 업종 상대값도 같은 자리다 — 그날 전 종목의 값이 있어야 정해진다.
                // 백분위와 순서를 가릴 이유는 없다. 둘 다 원본 지표만 읽고 서로를 읽지 않는다.
                sectorRelativeCalculator.calculate(tradeDate);
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

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * {@link CorporateActionJob}과 같은 자리다 — 오타로 대상이 빈 것과 계산할 것이 없는 것이
     * 로그에서 구분되지 않는다. 이 잡에서는 특히 나쁘다: 무효화 뒤 복구로 부르는 경우가 많고,
     * 그때 <b>0종목 성공</b>은 "그 종목이 화면에서 사라진 채로 끝났다"와 같은 뜻이다.
     */
    private List<Long> resolveTargets(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
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

    private List<LocalDate> tradeDatesBetween(LocalDate from, LocalDate to) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT trade_date FROM price_daily
                WHERE trade_date BETWEEN ? AND ?
                ORDER BY trade_date
                """, LocalDate.class, from, to);
    }

}
