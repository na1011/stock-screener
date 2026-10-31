package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.BarGapReader;
import com.stockscreener.pipeline.collector.MarketCalendarCollector;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 거래소 세션 달력을 적재하고, 그 위에서 봉 결측을 판정한다(ADR-0091).
 *
 * <p>잡 이름이 둘이다. {@link #JOB_NAME}은 소스를 부르고 표를 채우며, {@link #REVIEW_JOB_NAME}은
 * <b>외부 호출 0으로 판정만 찍는다.</b> 한 클래스에 둔 이유는 <b>판정 요약을 짓는 코드가 하나</b>
 * 이기 때문이다 — 적재 뒤 알림과 조회 출력이 같은 문장을 쓴다. 나누면 그것이 사본이 되고,
 * 사본이 갈리면 <b>화면이 말한 결측 수와 알림이 말한 수가 달라진다.</b>
 * ({@code CorporateActionJudgeJob}이 같은 형태다.)
 *
 * <p>{@link MarketIndexJob}과 같은 자리다 — 처리 단위가 종목이 아니라 <b>거래소</b>다. 그래서:
 * <ul>
 *   <li>MUST NOT: {@link BatchExecutionContext#process}를 쓰지 않는다.
 *       {@code batch_execution_failure.stock_id}가 NOT NULL + FK다.</li>
 *   <li>MUST: 루프 안에서 {@link BatchExecutionContext#abortIfAbandoned()}를 손으로 부른다.
 *       {@code process}/{@code stage}/{@code plan}을 안 쓰는 루프라 자동 게이트가 안 닿는다.</li>
 *   <li>거래소마다 따로 잡는다. 하나가 실패해도 나머지는 받는다.</li>
 * </ul>
 *
 * <p><b>평일 13:30 일일 갱신 뒤에 돈다</b>({@code PipelineScheduler#runDailyUpdate}). 미국 장은 한국 시각 오전 5~6시에
 * 닫으므로 13:30이면 대상일 세션이 끝나 있다. 크론 밖에 두었을 때 달력이 한 번 받은 날(2026-08-28)에 멈춰
 * 그 뒤 거래일이 판정 밖으로 빠졌다 — 판정 창이 달력 범위로 잘리므로 에러 없이 좁아진다.
 */
@Component
public class MarketCalendarJob {

    public static final String JOB_NAME = "market-calendar";

    /** 판정만 하는 이름. 외부 호출 0이고 {@code batch_execution}에 행을 만들지 않는다. */
    public static final String REVIEW_JOB_NAME = "bar-gap-review";

    /**
     * 창을 안 주면 여기서 시작한다.
     *
     * <p>소스 이력은 훨씬 깊지만(1990년도 답한다, 실측) 그만큼 받을 이유가 없다. 이 값은
     * <b>우리 시세가 닿을 수 있는 가장 이른 구간</b>에서 왔다 — 시세 소스의 이력이 2016년부터라는
     * 서술이 그것이고, 우리가 그 앞의 봉을 받을 일이 없으므로 그 앞의 세션도 쓸 데가 없다.
     * 거래소당 1콜이라 넓혀도 콜이 안 는다(2016~2026이 한 응답 805KB, 실측).
     */
    static final LocalDate SOURCE_HISTORY_START = LocalDate.of(2016, 1, 1);

    /** 알림에 이름을 적는 상한. 넘으면 수만 적고 나머지는 로그로 보낸다. */
    private static final int GAPS_IN_ALERT = 20;

    private static final Logger log = LoggerFactory.getLogger(MarketCalendarJob.class);

    private final MarketCalendarCollector collector;
    private final BarGapReader gapReader;
    private final DataSourceRegistry registry;
    private final BatchExecutions batchExecutions;

    public MarketCalendarJob(MarketCalendarCollector collector, BarGapReader gapReader,
                             DataSourceRegistry registry, BatchExecutions batchExecutions) {
        this.collector = collector;
        this.gapReader = gapReader;
        this.registry = registry;
        this.batchExecutions = batchExecutions;
    }

    /** 달력을 받을 수 있는 거래소. 어댑터가 없으면(키 없음) 빈 목록이다. */
    public List<String> targetExchanges() {
        return registry.marketCalendarExchangeCodes().stream().sorted().toList();
    }

    /**
     * {@code [from, asOf]}를 받아 적재하고, 그 위에서 결측을 판정해 요약을 알린다.
     *
     * @param from 비면 {@link #SOURCE_HISTORY_START}
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           LocalDate from) {
        List<String> exchanges = targetExchanges();
        LocalDate windowStart = from != null ? from : SOURCE_HISTORY_START;

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, exchanges.size(),
                        // 창의 시작을 손으로 주면 소스 이력 전체를 받는 기본 실행과 범위가 다르다.
                        BatchExecutionRecorder.DeclaredScope.byArgs(from == null));
        if (exchanges.isEmpty()) {
            // 키가 없어 어댑터가 등록조차 되지 않은 상태다. 0건 성공으로 끝내지 않는다 —
            // 그대로 두면 달력이 영영 안 들어오는데 잡은 초록이고, 그러면 봉 결측이
            // 전부 판정불가인 채로 「판정할 수 있다」고 믿게 된다.
            execution.alertOperator(AlertSeverity.ERROR, "마켓 캘린더 소스가 등록되지 않았다",
                    "APCA_API_KEY_ID가 없어 캘린더 어댑터가 빈으로 등록되지 않았다. "
                            + "이 상태로는 미국 봉 결측을 휴장과 가를 수 없다.");
            return execution.finish();
        }

        try {
            requireWindow(windowStart, asOf);
        } catch (IllegalArgumentException e) {
            execution.failUnexpectedly(e);
            return BatchStatus.FAILED;
        }

        execution.plan(CollectionPhase.MARKET_CALENDAR, exchanges.size());
        execution.stage("거래소별 세션 달력 수집");
        collectAll(execution, exchanges, windowStart, asOf);

        // 표를 채우고 아무도 안 보면 이 잡은 아무것도 답하지 않는다. 그래서 여기서 한 번 잰다.
        alertOnGaps(execution);
        return execution.finish();
    }

    /**
     * 외부 호출 없이 판정만 찍는다. {@code batch_execution}에 행을 만들지 않는다.
     *
     * <p>{@code CorporateActionJudgeJob.review}와 같은 계약이다 — 몇 초짜리라 <b>적재가 도는
     * 중에 치게 되는 명령</b>이고, 실행 행을 열면 그 자체가 관측 대상을 흔든다.
     */
    public BatchStatus review(List<String> tickers, LocalDate from) {
        BarGapReader.Report report = gapReader.read(tickers, from);
        log.info("봉 결측 판정 — {}", summary(report));
        for (BarGapReader.Gap gap : report.gaps()) {
            log.info("  {} {} {}", gap.verdict(), gap.ticker(), gap.date());
        }
        if (report.truncated()) {
            log.warn("판정 결과가 상한에 걸려 잘렸다 — 위 목록이 전부가 아니다. "
                    + "--tickers 나 --from 으로 좁혀 다시 본다.");
        }
        return BatchStatus.SUCCESS;
    }

    /**
     * MUST: 대상일이 미래면 거절한다.
     *
     * <p>기본값이 오늘이라 「마침 안전한」 상태이지만, 그것과 <b>위험한 값을 거절하는 것</b>은
     * 다르다. 소스는 2030년부터 휴장일을 모르면서 <b>평일을 그대로 세션으로 낸다</b>(실측:
     * 알려진 휴장이 2029년 10일 → 2030년 1일 → 2031년 0일). 그 구간을 담으면 크리스마스가
     * 개장일이 되고, 봉이 없는 것이 전부 결측으로 뒤집힌다.
     */
    private static void requireWindow(LocalDate windowStart, LocalDate asOf) {
        if (windowStart.isAfter(asOf)) {
            throw new IllegalArgumentException(
                    "조회 창의 시작이 대상일보다 늦다: " + windowStart + " > " + asOf);
        }
        LocalDate today = LocalDate.now();
        if (asOf.isAfter(today)) {
            throw new IllegalArgumentException(
                    "대상일이 미래다: " + asOf + " > " + today + " — 소스가 먼 미래의 휴장일을 "
                            + "모르면서 평일을 그대로 세션으로 준다. 담으면 휴일이 개장일이 된다.");
        }
    }

    private void collectAll(BatchExecutionContext execution, List<String> exchanges,
                            LocalDate from, LocalDate to) {
        for (String exchange : exchanges) {
            // 이 잡은 process()도 루프 안 stage()도 쓰지 않아 자동 게이트가 안 닿는다.
            execution.abortIfAbandoned();
            try {
                long written = collector.collect(exchange, from, to);
                execution.countExternally(CollectionPhase.MARKET_CALENDAR, 1, 0, written);
            } catch (DataSourceException e) {
                execution.countExternally(CollectionPhase.MARKET_CALENDAR, 0, 1, 0L);
                log.error("세션 달력 수집에 실패했다. exchange={} {}~{}", exchange, from, to, e);
                execution.alertOperator(AlertSeverity.ERROR, "세션 달력 수집 실패: " + exchange,
                        ("%s의 %s~%s 구간을 받지 못했다(%s). 이 거래소에 상장된 종목은 봉이 없는 날을 "
                                + "휴장과 가를 수 없고, 화면에서는 판정불가로만 보인다.")
                                .formatted(exchange, from, to, e.kind()));
            }
        }
    }

    /**
     * 적재 뒤 결측을 한 번 재서 요약을 알린다.
     *
     * <p>이것이 없으면 표를 채우고도 아무도 결과를 안 본다 — 미국은 일일 갱신 대상이 아니라
     * {@code DailyUpdateJob}의 결손 알림이 원리적으로 닿지 않는다.
     */
    private void alertOnGaps(BatchExecutionContext execution) {
        BarGapReader.Report report = gapReader.read(List.of(), null);
        log.info("봉 결측 판정 — {}", summary(report));
        if (report.gaps().isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder(summary(report)).append('\n');
        report.gaps().stream().limit(GAPS_IN_ALERT)
                .forEach(g -> body.append("  ").append(g.verdict()).append(' ')
                        .append(g.ticker()).append(' ').append(g.date()).append('\n'));
        if (report.gaps().size() > GAPS_IN_ALERT) {
            body.append("  나머지 ").append(report.gaps().size() - GAPS_IN_ALERT)
                    .append("건은 로그에 있다. --job=").append(REVIEW_JOB_NAME)
                    .append(" 로 다시 본다.\n");
        }
        body.append("BAR_ON_NON_SESSION은 달력이 세션을 빠뜨렸다는 뜻이다 — 그쪽이 먼저다.");
        execution.alertOperator(AlertSeverity.WARNING, "봉 결측이 있다", body.toString());
    }

    private static String summary(BarGapReader.Report report) {
        return ("판정 대상 %d종목 · 세션인데 봉 없음 %d건 · 비세션인데 봉 있음 %d건 · "
                + "봉이 0행인 종목 %d · 달력이 없어 판정불가 %d종목%s")
                .formatted(report.stocksInScope(),
                        report.count(BarGapReader.Verdict.SESSION_WITHOUT_BAR),
                        report.count(BarGapReader.Verdict.BAR_ON_NON_SESSION),
                        report.stocksWithoutBars(), report.stocksWithoutCalendar(),
                        report.truncated() ? " (목록 잘림)" : "");
    }
}
