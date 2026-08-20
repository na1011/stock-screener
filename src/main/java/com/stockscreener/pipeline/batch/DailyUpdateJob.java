package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorRetentionPolicy;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.indicator.PercentileCalculator;
import com.stockscreener.indicator.SectorRelativeCalculator;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.MarketCalendar;
import com.stockscreener.pipeline.collector.MarketIndexCalendar;
import com.stockscreener.pipeline.collector.PriceCalendar;
import com.stockscreener.pipeline.collector.PriceCollector;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.MarketIndexCodes;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.watchlist.WatchlistAlertRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * 일일 갱신. 소스가 공개한 최근 거래일들의 종가를 수집하고 그것으로 지표를 계산한다.
 *
 * <p>대상은 <b>당일이 아니다.</b> 소스는 거래일 T의 종가를 T의 다음 <b>영업일</b> 13시 이후에
 * 내므로(ADR-0042), 실행일보다 앞선 날짜를 받는다. 그리고 하루가 아니라 {@link #PRICE_LOOKBACK_DAYS}일
 * 창으로 받는다 — 그 이유는 그 상수의 주석에 있다.
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

    /**
     * 시세 조회 창의 폭(일). 대상일 하나가 아니라 이만큼 거슬러 올라가 함께 받는다.
     *
     * <p>소스가 기간조회를 <b>1콜</b>로 주므로(`likeSrtnCd` + `beginBasDt`/`endBasDt`)
     * 창을 넓혀도 API 콜 수가 늘지 않는다. 늘어나는 것은 upsert 행 수뿐이다.
     *
     * <p>폭이 이 값이어야 하는 이유는 <b>대상 일자가 공휴일을 모르기 때문</b>이다(ADR-0042).
     * 거래일 T는 T의 다음 <b>영업일</b> 13시에 공개되는데, 대상 일자는 직전 <i>평일</i>만
     * 낼 수 있어 연휴를 건너뛰지 못한다. 창이 그 어긋남을 흡수한다 — 그리고 창은 곧
     * <b>재시도 기제</b>다. 한 번 놓친 날짜를 이후 실행이 계속 다시 시도하므로 별도의 만회
     * 배치가 필요 없다.
     */
    public static final int PRICE_LOOKBACK_DAYS = 15;

    private static final Logger log = LoggerFactory.getLogger(DailyUpdateJob.class);

    private final PriceCollector priceCollector;
    private final StockRegistrar stockRegistrar;
    private final MarketCalendar marketCalendar;
    private final PriceCalendar priceCalendar;
    private final MarketIndexCalendar marketIndexCalendar;
    private final MarketIndexJob marketIndexJob;
    private final BatchExecutions batchExecutions;
    private final IndicatorRunner indicatorRunner;
    private final PercentileCalculator percentileCalculator;
    private final SectorRelativeCalculator sectorRelativeCalculator;
    private final IndicatorRetentionPolicy retentionPolicy;
    private final WatchlistAlertRunner watchlistAlertRunner;

    public DailyUpdateJob(PriceCollector priceCollector, StockRegistrar stockRegistrar,
                          MarketCalendar marketCalendar, PriceCalendar priceCalendar,
                          MarketIndexCalendar marketIndexCalendar, MarketIndexJob marketIndexJob,
                          BatchExecutions batchExecutions,
                          IndicatorRunner indicatorRunner, PercentileCalculator percentileCalculator,
                          SectorRelativeCalculator sectorRelativeCalculator,
                          IndicatorRetentionPolicy retentionPolicy,
                          WatchlistAlertRunner watchlistAlertRunner) {
        this.priceCollector = priceCollector;
        this.stockRegistrar = stockRegistrar;
        this.marketCalendar = marketCalendar;
        this.priceCalendar = priceCalendar;
        this.marketIndexCalendar = marketIndexCalendar;
        this.marketIndexJob = marketIndexJob;
        this.batchExecutions = batchExecutions;
        this.indicatorRunner = indicatorRunner;
        this.percentileCalculator = percentileCalculator;
        this.sectorRelativeCalculator = sectorRelativeCalculator;
        this.retentionPolicy = retentionPolicy;
        this.watchlistAlertRunner = watchlistAlertRunner;
    }

    /**
     * 조회 창의 시작일.
     *
     * <p>{@code public static}인 이유는 {@code PipelineSchedulerTest}가 <b>크론·대상 일자
     * 규칙·창 셋을 합쳐</b> "모든 거래일이 어느 실행엔가 덮이는가"를 검사하기 때문이다.
     * 테스트에 창 폭을 복사해 적으면 그 상수와 실제 창이 갈리고, 그러면 그 테스트는
     * 자기 자신을 검사하게 된다.
     */
    public static LocalDate priceWindowStart(LocalDate to) {
        return to.minusDays(PRICE_LOOKBACK_DAYS);
    }

    /**
     * 대상 일자의 시세를 수집한다.
     *
     * <p>실제 조회는 {@link #priceWindowStart}부터 대상일까지의 <b>창</b>이다. 대상일은
     * 그 창의 끝이자 결손 판정·지표 계산의 기준이지, 조회 범위 전체가 아니다.
     *
     * <p>{@code isWeekend} 조기 반환은 스케줄러 경로에서는 발화하지 않는다 — 대상 일자가
     * 언제나 평일이기 때문이다(ADR-0042). <b>죽은 코드가 아니다</b>: 관리자가 {@code /admin}에서
     * 주말 일자를 직접 넣는 경로가 남아 있고, 그때 850종목을 헛돌리지 않게 막는다.
     *
     * @param targetDate 수집 대상 거래일. 실행 시각이 아니다 — 소스가 거래일 T를 T의 다음
     *                   영업일에 내므로 대상은 언제나 실행일보다 앞이다.
     */
    public BatchStatus run(LocalDate targetDate, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        if (marketCalendar.isWeekend(targetDate)) {
            log.info("주말이므로 일일 갱신을 건너뛴다. targetDate={}", targetDate);
            return BatchStatus.SUCCESS;
        }

        List<SecurityRef> targets =
                stockRegistrar.toSecurityRefs(stockRegistrar.activeUniverseMemberIds());
        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, targetDate, triggeredBy, targets.size());

        return collect(execution, targets, priceWindowStart(targetDate), targetDate);
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

        return collect(execution, targets, priceWindowStart(targetDate), targetDate);
    }

    /**
     * 대상 종목의 시세를 수집하고, 이어서 지표를 계산한다.
     *
     * <p>해결 표시는 여기서 하지 않는다 — {@link BatchExecutionContext#process}가
     * <b>어느 잡이든 한 종목·한 단계를 성공으로 끝낼 때</b> 그 종목의 이전 실패를 닫는다.
     * 그 전에는 이 자리에 시세 단계 전용 호출이 있었고, 그래서 나머지 여섯 단계는 데이터가
     * 채워진 뒤에도 미해결로 남았다.
     *
     * <p>MUST NOT: 대상 전체를 무조건 해결로 표시하지 않는다. 두 번 연속 실패한 종목이
     * 미해결 목록에서 사라져, 실패를 조용히 삼키지 않겠다는 파이프라인 전체의 전제가 바로
     * 이 지점에서 깨진다. 성공 여부로 가르는 것이 그 전제를 지킨다.
     */
    private BatchStatus collect(BatchExecutionContext execution, List<SecurityRef> targets,
                                LocalDate from, LocalDate to) {
        // 종목당 두 단계를 돌므로 작업 단위는 종목 수의 두 배다. 종목 수만 신고하면 화면이
        // 700/350으로 뜬다 — 지표 백필이 43400/350으로 뜨던 것과 같은 어긋남이다.
        //
        // 지표 쪽은 **상한**이다. 시세가 실패한 종목은 계산할 새 원자료가 없어 건너뛰므로(아래)
        // 처리 수가 계획에 못 미칠 수 있고, 그 차이가 곧 건너뛴 종목 수다. 실측으로 덮어쓰지
        // 않는 이유가 그것이다 — 덮어쓰면 그 신호가 사라진다.
        execution.plan(CollectionPhase.PRICE, targets.size());
        execution.plan(CollectionPhase.INDICATOR, targets.size());

        // MUST: 지수를 종목 루프 **앞에서** 받는다. EXCESS_RETURN_26W가 그날 지수를 입력으로
        // 쓰므로 뒤에 두면 그날 그 지표가 안 써지고, 그 사실은 화면에서 "조건에 안 맞았다"와
        // 같아 보인다. 실패해도 여기서 예외를 올리지 않는다 — 지수 하나 때문에 시세 수집과
        // 나머지 지표를 함께 잃는 편이 손해가 크다(MarketIndexJob의 판단).
        marketIndexJob.collectInto(execution, from, to);

        execution.stage("종목별 수집·계산");

        // 종목마다 묻지 않는다 — 350번 같은 답을 받는다. 이 루프 안에서 오늘치 시세가 들어와
        // 경계가 하루 밀리지만, 그 하루는 아래 purge가 정상 회전으로 걷어 간다.
        Optional<LocalDate> retentionCutoff = retentionPolicy.writeCutoff();
        // 지수도 종목마다 묻지 않는다. asOf 단위로 한 번 읽어 러너에 넘긴다 — readRawData는
        // 종목마다 불리므로 거기서 읽으면 350번 같은 쿼리를 던진다(ADR-0054).
        Map<String, NavigableMap<LocalDate, BigDecimal>> marketIndexes =
                indicatorRunner.marketIndexesFor(to);

        try {
            for (SecurityRef target : targets) {
                boolean collected = execution.process(target, CollectionPhase.PRICE,
                        security -> priceCollector.collect(security, from, to));

                // 수집이 예외 없이 끝난 종목만 지표를 계산한다. 실패한 종목은 계산할 새 원자료가
                // 없으므로, 굳이 돌려서 실패를 하나 더 만들 이유가 없다.
                //
                // MUST: collected를 "봉이 들어왔다"로 읽지 않는다. 빈 응답은 예외가 아니라 0행이라
                // 여기서도 성공이고(BatchExecutionContext.process), 그래서 한 봉도 못 받은 종목도
                // 지표 계산에 들어간다. 휴장일에 전 종목이 통째로 재계산되는 것이 그 결과다.
                // 그 행들이 시세 0건을 덮어 alertIfNothingArrived를 죽여 놓았던 것이 실제 사고다.
                //
                // 조건을 "행을 썼는가"로 좁히지 않는 이유는 위 resolveFailure가 같은 값을 쓰기
                // 때문이다. 거래정지처럼 정상적으로 빈 종목이 영원히 미해결로 남게 된다.
                if (collected) {
                    execution.process(target, CollectionPhase.INDICATOR,
                            security -> indicatorRunner.run(
                                    security.stockId(), to, retentionCutoff, marketIndexes));
                }
            }

            alertIfNothingArrived(execution, targets.size(), to);
            alertIfIndexMissingWhilePricesArrived(execution, targets, to);

            // MUST: 여기부터의 기준일은 대상일이 아니라 **실제로 봉이 적재된** 최신 거래일이다.
            //
            // IndicatorRunner는 봉의 날짜로 저장하지만(IndicatorPoint.tradeDate) PercentileCalculator는
            // `iv.trade_date = ?`로 정확히 일치시킨다. 대상일이 공휴일이면 그 비대칭이 드러난다 —
            // 지표는 직전 거래일 날짜로 쓰이는데 백분위만 0행이 되고, 화면의 기준 일자는
            // IndicatorCalendar가 지표 쪽으로 잡는다. 그러면 백분위 조건이 전부 판정 불가가 된 채
            // **결과가 비는 것이 아니라 그럴듯하게 좁아진다.**
            //
            // 루프 뒤여야 한다 — 봉이 커밋된 뒤에만 옳은 값이 나온다.
            LocalDate asOf = priceCalendar.latestTradeDateOnOrBefore(to).orElse(to);

            // 루프가 끝난 뒤에도 할 일이 남아 있다. 구간을 신고하지 않으면 이 몇 분이 화면에
            // "100%인데 안 끝남"으로 보이고, 그것은 멎은 것과 구분되지 않는다.
            execution.stage("백분위 계산");
            // 백분위는 전 종목의 값이 저장된 뒤에야 계산할 수 있다. 종목 루프 안에서는 원리적으로
            // 불가능하므로 루프가 끝난 뒤 하루치를 한 번에 처리한다.
            percentileCalculator.calculate(asOf);
            // 업종 상대값도 같은 이유로 루프 뒤다. 워치리스트보다 앞이어야 하는 것도 같다 —
            // 업종 조건을 건 화면이 그 앞에서 판정되면 어제 값으로 오늘을 판정한다.
            sectorRelativeCalculator.calculate(asOf);

            execution.stage("워치리스트 판정");
            // 워치리스트 판정은 백분위 뒤여야 한다. 백분위 조건("PER 하위 20%")을 건 화면이
            // 그 앞에서 판정되면 어제 백분위로 오늘을 판정하게 되고, 그것은 조용히 그럴듯하다.
            evaluateWatchlist(execution, asOf);
        } catch (DataSourceException e) {
            // AUTH처럼 복구 불가능한 실패다. 850종목을 마저 시도할 이유가 없다.
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        // MUST: 루프 뒤의 단계들을 try 밖으로 빼지 않는다. 밖에 있으면 여기서 던진 예외가
        // finish()·failUnexpectedly() 어느 쪽에도 닿지 않아 **행이 RUNNING으로 남는다** —
        // 프로세스는 멀쩡한데 마감만 안 된 상태이고, 그 행에 스위퍼가 나중에 붙이는
        // "프로세스가 사라져"는 거짓이 된다. 실제로 percentileCalculator가 그 자리에 있었다.

        // 보존 정리(ADR-0011)는 마감 전에 하되 실패해도 배치를 죽이지 않는다. 오래된 행이
        // 하루 더 남는 것은 저장 공간 문제일 뿐이지만, 여기서 예외가 올라가면 그날의 수집과
        // 계산이 통째로 실패한 것처럼 기록된다.
        try {
            execution.stage("지표 보존 정리");
            retentionPolicy.purge();
        } catch (RuntimeException e) {
            log.warn("지표 보존 정리에 실패했다. 수집·계산은 정상이며 다음 실행에서 다시 시도한다.", e);
        }

        return execution.finish();
    }

    /**
     * 한 종목도 새 시세를 받지 못했으면 알린다.
     *
     * <p>IMPORTANT — 이 경로가 없으면 그 상황이 <b>완전한 성공으로 기록된다.</b> 빈 응답은
     * 예외가 아니라 0행이라 {@code process}가 성공을 세고, 마감은 850종목 성공·0행으로
     * {@code SUCCESS}가 된다. 관리자 화면도 초록이고 알림도 없다. 그래서 "배치는 잘 도는데
     * 데이터가 안 늘어난다"를 며칠 뒤 화면의 기준 일자를 보고서야 알게 된다.
     *
     * <p>원인이 둘이고 여기서는 구분할 수 없다 — <b>휴장</b>이거나, <b>소스가 아직 그날치를
     * 내지 않은 것</b>이다. 구분하지 못한 채로 알리는 편이 맞다: 공휴일은 연 15일 남짓이라
     * 그만큼의 WARNING은 감당할 수 있고, 반대로 배치 시각이 너무 이르면 <b>거래일마다</b>
     * 오므로 하루 만에 드러난다. 후자를 놓치는 비용이 전자의 소음보다 훨씬 크다.
     *
     * <p>MUST: 판정을 <b>이 실행이 쓴 행 수</b>로 하지 않는다. 물어야 하는 것은 "이 실행이
     * 무언가를 받았는가"가 아니라 <b>"대상일에 데이터가 있는가"</b>이고, 둘은 다른 질문이다.
     * 쓰기 행 수로 물으면 이 경로가 두 가지 방식으로 죽는다.
     *
     * <ul>
     *   <li><b>합계로 물으면</b> 지표가 덮는다 — 시세가 0행이어도 {@code collected}는
     *       {@code true}라(예외가 안 났으므로) 지표 단계가 돌고 종목당 수천 행을 남긴다.
     *       실제로 2026-08-07 대상 실행이 <b>시세 0행·총 979,541행</b>으로 끝나 금요일 종가
     *       결손이 아무에게도 알려지지 않았다.</li>
     *   <li><b>{@code PRICE} 단계만 세도 죽는다</b> — 수집이 하루가 아니라 창으로 돌기
     *       때문이다(ADR-0042). 대상일이 비어 있어도 창 안의 과거 날짜 재적재가 수천 행을
     *       채우므로 <b>알림이 원리적으로 발화하지 않는다.</b> 이번에는 {@code PRICE} 단계가
     *       스스로를 덮는다.</li>
     * </ul>
     *
     * <p>그래서 {@link PriceCalendar#countOn}에 묻는다. 창을 넓혀도, 재실행해도, 쓰기가
     * 멱등이어도 같은 답을 낸다.
     *
     * <p>창이 생긴 뒤로는 공휴일마다 최대 2회 온다 — 공휴일 당일(직전 거래일이 아직 미공개)과
     * 다음 거래일(대상일이 공휴일). 위 교환은 그대로 유효하다.
     */
    private void alertIfNothingArrived(BatchExecutionContext execution, int targetCount, LocalDate to) {
        if (targetCount == 0 || priceCalendar.countOn(to) > 0) {
            return;
        }
        execution.alertOperator(AlertSeverity.WARNING, "대상일 시세가 한 건도 없다",
                "%d종목을 조회했으나 대상일 %s에 저장된 행이 0이다. 휴장이거나, 소스가 아직 그날치를 내지 않았다. "
                        .formatted(targetCount, to)
                        + "거래일마다 이 알림이 오면 배치 시각이 소스의 공개 시각보다 이르다는 뜻이다.");
    }

    /**
     * 시세는 들어왔는데 <b>그 일자의 지수만</b> 없으면 알린다.
     *
     * <p>IMPORTANT — 이 비대칭이 조용한 실패를 만든다. 시세와 나머지 지표는 대상일 T에 쓰이는데
     * 지수만 없으면 그날 {@code EXCESS_RETURN_26W}가 한 행도 안 써지고,
     * {@code IndicatorCalendar}는 {@code max(trade_date)}로 T를 기준일로 고른다. 그러면 그
     * 지표를 조건에 건 화면이 <b>매일 전 종목 0건</b>인데 수집은 SUCCESS이고 화면에서는
     * "조건에 맞는 종목이 없다"와 구별되지 않는다.
     *
     * <p>MUST: 판정을 <b>수집한 행 수</b>가 아니라 {@link MarketIndexCalendar#countOn}으로 한다.
     * 수집이 15일 창으로 도는 이상 대상일이 비어 있어도 창 안 과거 날짜의 재적재가 쓰기 행 수를
     * 채우므로, 행 수로 물으면 이 알림이 <b>원리적으로 발화하지 않는다.</b>
     *
     * <p>시세도 함께 없으면 알리지 않는다 — 그것은 휴장이거나 소스 미공개이고
     * {@link #alertIfNothingArrived}가 이미 말한다. 여기서 물어야 하는 것은 <b>둘이 갈린 경우</b>뿐이다.
     *
     * <p>MUST: 기대하는 지수를 <b>대상 종목이 속한 시장</b>에서 뽑는다. 등록된 어댑터에서 뽑으면
     * 키가 없어 어댑터가 아예 없는 환경에서 목록이 비어 <b>이 알림이 원리적으로 발화하지 않는다</b> —
     * 그것이 정확히 가장 조용한 실패다.
     */
    private void alertIfIndexMissingWhilePricesArrived(BatchExecutionContext execution,
                                                       List<SecurityRef> targets, LocalDate to) {
        if (priceCalendar.countOn(to) == 0) {
            return;
        }
        List<String> missing = targets.stream()
                .map(SecurityRef::marketCode)
                .distinct()
                // 아직 지수를 갖기로 하지 않은 시장(미국, v1.1)은 묻지 않는다. 그것은 결손이
                // 아니라 범위 밖이고, 물으면 거래일마다 오는 알림이 되어 곧 무시된다.
                .filter(MarketIndexCodes.ALL::contains)
                .sorted()
                .filter(code -> marketIndexCalendar.countOn(code, to) == 0)
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        execution.alertOperator(AlertSeverity.WARNING, "대상일 지수가 없다",
                ("시세는 %s에 들어왔는데 지수 %s가 그날 없다. 그 지수를 쓰는 종목의 "
                        + "26주 초과 수익률은 그날 한 행도 쓰이지 않으며, 화면에서는 조건에 "
                        + "안 맞은 것과 같아 보인다. "
                        + "지수의 공개 지연이 시세보다 늦다는 뜻이면 이 알림이 거래일마다 온다.")
                        .formatted(to, missing));
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

}
