package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorRetentionPolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 무효화로 <b>지워진 지표를 다시 채운다.</b> 지우는 잡과 짝이고, 짝이 안 붙으면 그 종목이
 * 화면에서 통째로 사라지는데 <b>배치는 전부 SUCCESS다.</b>
 *
 * <h2>왜 스케줄러 밖에 있는가</h2>
 * <p>이 로직은 {@code PipelineScheduler}의 {@code private} 메서드였는데, 그 클래스가
 * {@code @ConditionalOnProperty(pipeline.scheduler.enabled)}라 <b>CLI({@code BootstrapRunner})와
 * 관리자 JVM에는 빈이 아예 없다.</b> 그래서 같은 사슬을 손으로 돌리면 백필이 원리적으로
 * 안 붙었고, 그 사실은 <b>어느 로그에도 안 뜬다</b> — 지워진 지표는 조용히 비어 있을 뿐이다.
 *
 * <h2>MUST: 상한을 둘로 둔다 — 사건률이 다르다</h2>
 * <p>둘을 하나로 합치면 어느 한쪽이 반드시 틀린다. 기업 행위는 월 2~3건이고 재무 정정은
 * 분기 공시 뒤에 몰린다. 근거는 각 상수의 javadoc에 있다.
 *
 * <h2>MUST NOT: 여기에 「무엇이 지웠는가」를 더 넣지 않는다</h2>
 * <p>이 클래스가 답하는 것은 <b>「지워졌으니 어디부터 어디까지 다시 도는가」</b> 하나다.
 * 무효화를 만든 잡이 무엇인지는 호출자가 알고, 그것을 여기로 들이면 잡마다 분기가 생겨
 * {@code backend.md}의 「조건별 하드코딩 분기」 금지에 그대로 걸린다.
 */
@Component
public class InvalidationBackfiller {

    private static final Logger log = LoggerFactory.getLogger(InvalidationBackfiller.class);

    /**
     * 기업 행위로 무효화된 종목이 이보다 많으면 백필을 <b>자동으로 돌리지 않고 알린다.</b>
     *
     * <p>실측 사건률이 월 2~3건이므로 한 실행이 이보다 많은 종목을 무효화했다면 그것은 새 분할이
     * 아니라 <b>원장을 크게 고친 것</b>이고, 그때 백필은 시간 단위로 커진다
     * ({@code AdjustmentRebuildJob} javadoc의 「2.6~8시간」). 다음 평일 13:30 일일 갱신까지
     * 22시간이 남지만, 스케줄러 스레드 풀이 1이라 그 사이의 다른 스케줄을 전부 민다.
     *
     * <p><b>이 값은 예산이지 실측이 아니다</b> — 종목당 백필 소요를 지금 모집단(1,350)에서
     * 재본 적이 없다. 재고 나면 이 상수의 근거를 그 수로 바꾼다.
     */
    static final int AUTO_BACKFILL_STOCK_LIMIT = 10;

    /**
     * 재무 정정·교정으로 무효화된 종목의 자동 백필 상한. <b>위 상수와 따로 둔다.</b>
     *
     * <p>위 값 10의 근거는 <b>기업 행위 사건률</b>(월 2~3건)인데, 재무 정정은 분기 공시 뒤에
     * 몰려 사건률이 다르다 — 실측으로 국내 표본 30종목 실행이 <b>2종목</b>을 무효화했고
     * (350종목 외삽 ≈ 23) 미국 창 11년 전수 재적재는 <b>757종목</b>이었다. 10을 그대로 쓰면
     * <b>정기 실행마다 상한을 넘어 자동 백필이 한 번도 안 돈다.</b>
     *
     * <p>비용 쪽 근거: 티커를 준 백필은 종목 수에 비례하고 전 종목(1,350) 단일 날짜가 실측
     * <b>3분 45초</b>다. 100종목이면 그보다 훨씬 싸다. 그보다 많으면 전수 재적재 같은 예외적
     * 상황이므로 사람이 창을 정해 손으로 도는 것이 맞다.
     */
    static final int AUTO_BACKFILL_FINANCIAL_STOCK_LIMIT = 100;

    private final IndicatorBackfillJob indicatorBackfillJob;
    private final IndicatorRetentionPolicy retentionPolicy;

    public InvalidationBackfiller(IndicatorBackfillJob indicatorBackfillJob,
                                  IndicatorRetentionPolicy retentionPolicy) {
        this.indicatorBackfillJob = indicatorBackfillJob;
        this.retentionPolicy = retentionPolicy;
    }

    /**
     * 기업 행위 재구축이 무효화한 종목만 다시 채운다.
     *
     * <p>MUST: 구간을 보존 경계로 자른다. 무효화는 {@code trade_date >= 그 종목 첫 봉}으로
     * 지우므로 {@code earliest}가 <b>보존 창보다 훨씬 앞</b>일 수 있는데(실측: 국내 첫 봉
     * 2020-01-02 대 보존 창 189거래일), 경계 밖 일자는 계산해도 저장되지 않는다(ADR-0011).
     * 자르지 않으면 <b>8배를 돌고 그 대부분을 버린다.</b>
     *
     * <p>MUST NOT: 종목 수가 많을 때도 그냥 돌리지 않는다. {@link #AUTO_BACKFILL_STOCK_LIMIT}가
     * 그 자리이고, 넘으면 <b>알리고 멈춘다</b> — 자동으로 시간 단위 작업을 시작하면 그날의
     * 다른 스케줄이 전부 밀린다.
     */
    public void backfillAdjustment(AdjustmentRebuildJob.Invalidation invalidation, LocalDate asOf,
                                   BatchExecutionRecorder.TriggeredBy triggeredBy) {
        List<String> tickers = invalidation.tickers();
        if (tickers.size() > AUTO_BACKFILL_STOCK_LIMIT) {
            log.warn("무효화된 종목이 {}개로 자동 백필 상한({})을 넘는다. 돌리지 않는다 — "
                            + "지표가 지워진 채로 남으므로 손으로 백필해야 한다. 종목={}",
                    tickers.size(), AUTO_BACKFILL_STOCK_LIMIT, tickers);
            return;
        }

        LocalDate from = clampToRetention(invalidation.earliest());
        log.info("무효화된 {}종목을 다시 채운다. from={} asOf={} 종목={}",
                tickers.size(), from, asOf, tickers);
        indicatorBackfillJob.run(from, asOf, triggeredBy, tickers, null);
    }

    /**
     * 재무 정정·교정으로 지표를 잃은 종목을 다시 채운다.
     *
     * <p><b>무효화 시작일을 받지 않고 보존 경계부터 채운다.</b> 재무 정정은
     * {@code report.periodStart()}부터 지우는데 사업보고서면 그것이 보존 창 전량이라,
     * 더 좁게 잡을 이득이 없다. {@link #clampToRetention}에 {@code null}을 주는 것이 그 경계다.
     */
    public void backfillFinancial(List<String> tickers, LocalDate asOf,
                                  BatchExecutionRecorder.TriggeredBy triggeredBy) {
        if (tickers.size() > AUTO_BACKFILL_FINANCIAL_STOCK_LIMIT) {
            log.warn("재무 정정으로 무효화된 종목이 {}개로 자동 백필 상한({})을 넘는다. 돌리지 "
                            + "않는다 — 그 종목들의 지표가 지워진 채로 남으므로 손으로 백필해야 "
                            + "한다. 종목={}",
                    tickers.size(), AUTO_BACKFILL_FINANCIAL_STOCK_LIMIT, tickers);
            return;
        }

        LocalDate from = clampToRetention(null);
        log.info("재무 정정으로 무효화된 {}종목을 다시 채운다. from={} asOf={} 종목={}",
                tickers.size(), from, asOf, tickers);
        indicatorBackfillJob.run(from, asOf, triggeredBy, tickers, null);
    }

    /**
     * 보존 경계보다 앞인 일자를 잘라 낸다.
     *
     * <p>MUST: 시장별 경계 중 <b>가장 이른 것</b>을 쓴다. 늦은 것을 쓰면 보존이 더 긴 시장의
     * 저장 가능한 구간을 함께 잘라, <b>그 시장 지표가 지워진 채로 남는다.</b>
     */
    private LocalDate clampToRetention(LocalDate earliest) {
        Optional<LocalDate> floor = retentionPolicy.writeCutoffs().values().stream()
                .flatMap(Optional::stream)
                .min(LocalDate::compareTo)
                .map(cutoff -> cutoff.plusDays(1));
        if (earliest == null) {
            return floor.orElseThrow(() -> new IllegalStateException(
                    "무효화 시작일도 보존 경계도 없다 — 백필 구간을 정할 근거가 없다."));
        }
        return floor.filter(earliest::isBefore).orElse(earliest);
    }
}
