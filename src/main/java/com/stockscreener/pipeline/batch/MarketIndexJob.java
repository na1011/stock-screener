package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.MarketIndexCalendar;
import com.stockscreener.pipeline.collector.MarketIndexCollector;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 시장 지수 시계열을 수집한다(ADR-0053).
 *
 * <p>진입점이 둘이다 — {@link #run}은 스스로 실행 행을 열고(초기 전 구간 적재),
 * {@link #collectInto}는 이미 열린 실행 안에서 한 단계로 돈다(일일 갱신이 <b>종목 루프 앞에서</b>
 * 부른다). 순서가 강제되어야 하기 때문에 별도 스케줄로 나누지 않았다 — 지표는 그날 지수가
 * 저장된 뒤에만 계산할 수 있고, 둘을 갈라 놓으면 스케줄 간격이 곧 그 전제가 된다.
 *
 * <p><b>실패가 시세 수집을 죽이지 않는다.</b> 지수가 없으면 그날 {@code EXCESS_RETURN_26W}가
 * 안 써질 뿐 나머지 지표는 정상이므로, 여기서 예외를 올려 일일 갱신을 통째로 실패시키는 것은
 * 손해가 더 크다. 대신 <b>반드시 알림으로 나간다</b> — 조용하면 그 지표를 조건에 건 화면이
 * 빈 것을 아무도 모른다.
 *
 * <p>MUST NOT: {@link BatchExecutionContext#process}를 쓰지 않는다. 처리 단위가 종목이 아니라
 * 지수이고 {@code batch_execution_failure.stock_id}는 NOT NULL + FK다
 * ({@link CollectionPhase#MARKET_INDEX}). {@link UniverseRebalanceJob}과 같은 자리다.
 */
@Component
public class MarketIndexJob {

    public static final String JOB_NAME = "market-index";

    private static final Logger log = LoggerFactory.getLogger(MarketIndexJob.class);

    private final MarketIndexCollector collector;
    private final MarketIndexCalendar calendar;
    private final DataSourceRegistry registry;
    private final BatchExecutions batchExecutions;

    public MarketIndexJob(MarketIndexCollector collector, MarketIndexCalendar calendar,
                          DataSourceRegistry registry, BatchExecutions batchExecutions) {
        this.collector = collector;
        this.calendar = calendar;
        this.registry = registry;
        this.batchExecutions = batchExecutions;
    }

    /** 등록된 지수 코드. 어댑터가 없으면(키 없음) 빈 목록이다. */
    public List<String> targetIndexCodes() {
        return registry.marketIndexCodes().stream().sorted().toList();
    }

    /**
     * {@code [from, asOf]}를 받아 적재한다. 초기 전 구간 적재와 관리자 재실행이 쓴다.
     *
     * @param from 비면 소스 이력의 시작인 2020-01-02을 쓴다. 기간조회 1콜이라 창을 넓혀도
     *             콜이 거의 안 는다 — 전 구간 1,620행이 지수당 1~2콜이다
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           LocalDate from) {
        List<String> codes = targetIndexCodes();
        LocalDate windowStart = from != null ? from : SOURCE_HISTORY_START;

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, codes.size());
        if (codes.isEmpty()) {
            // 키가 없어 어댑터가 등록조차 되지 않은 상태다. 0건 성공으로 끝내지 않는다 —
            // 그대로 두면 지수가 영영 안 들어오는데 잡은 초록이다.
            execution.alertOperator(AlertSeverity.ERROR, "지수 소스가 등록되지 않았다",
                    "DATA_GO_KR_SERVICE_KEY가 없어 지수 어댑터가 빈으로 등록되지 않았다. "
                            + "이 상태로는 지수를 쓰는 지표 셋(EXCESS_RETURN_26W·"
                            + "EXCESS_RETURN_52W·BETA_104W)이 영영 안 나온다.");
            return execution.finish();
        }

        if (windowStart.isAfter(asOf)) {
            execution.failUnexpectedly(new IllegalArgumentException(
                    "조회 창의 시작이 대상일보다 늦다: " + windowStart + " > " + asOf));
            return BatchStatus.FAILED;
        }

        execution.plan(CollectionPhase.MARKET_INDEX, codes.size());
        execution.stage("지수별 수집");
        collectAll(execution, codes, windowStart, asOf);
        return execution.finish();
    }

    /**
     * 이미 열린 실행 안에서 지수를 받는다. 일일 갱신이 <b>종목 루프에 들어가기 전에</b> 부른다.
     *
     * <p>단계를 신고하는 것도 여기서 한다 — 호출부가 잊으면 화면에 분모 없이 뜬다.
     */
    public void collectInto(BatchExecutionContext execution, LocalDate from, LocalDate to) {
        List<String> codes = targetIndexCodes();
        execution.plan(CollectionPhase.MARKET_INDEX, codes.size());
        if (codes.isEmpty()) {
            // MUST NOT: 여기서 알리지 않는다. 어댑터가 없는 것은 <b>구성의 상태</b>이지 그날의
            // 사건이 아니라, 거래일마다 같은 알림이 오면 곧 무시된다. 그날 실제로 무엇이
            // 비었는가는 DailyUpdateJob의 "대상일 지수가 없다"가 답하고, 그쪽은 어댑터가 아니라
            // **대상 종목이 필요로 하는 시장**으로 묻기 때문에 이 상태에서도 발화한다.
            log.warn("지수 어댑터가 등록되지 않았다(키 없음). 지수 수집을 건너뛴다. {}~{}", from, to);
            return;
        }
        execution.stage("시장 지수 수집");
        collectAll(execution, codes, from, to);
    }

    /**
     * 지수마다 따로 잡는다. 하나가 실패해도 나머지는 받는다 — 코스닥이 실패했다고 코스피
     * 종목까지 판정불가로 만들 이유가 없다.
     */
    private void collectAll(BatchExecutionContext execution, List<String> codes,
                            LocalDate from, LocalDate to) {
        for (String code : codes) {
            // 이 잡은 process()도 루프 안 stage()도 쓰지 않아 자동 게이트가 안 닿는다.
            // 그래서 명시적으로 묻는다 — 없으면 종료 뒤에도 남은 지수를 마저 받는다.
            execution.abortIfAbandoned();
            try {
                long written = collector.collect(code, from, to);
                execution.countExternally(CollectionPhase.MARKET_INDEX, 1, 0, written);
            } catch (DataSourceException e) {
                execution.countExternally(CollectionPhase.MARKET_INDEX, 0, 1, 0L);
                log.error("지수 수집에 실패했다. index={} {}~{}", code, from, to, e);
                execution.alertOperator(AlertSeverity.ERROR, "지수 수집 실패: " + code,
                        ("%s를 %s~%s 구간으로 받지 못했다(%s). 이 지수를 쓰는 종목의 "
                                + "26주 초과 수익률은 그날 한 행도 쓰이지 않는다 — "
                                + "화면에서는 조건에 안 맞은 것과 같아 보인다.")
                                .formatted(code, from, to, e.kind()));
            }
        }
    }

    /**
     * 소스 이력의 시작. {@code data-go-kr-market-index.md} 실측으로 코스피·코스닥 둘 다
     * 2020-01-02부터 1,620거래일이고, <b>시세 쪽 경계와 정확히 일치한다.</b>
     *
     * <p>{@code price_daily}의 최초 거래일을 묻지 않는 이유는 그 값이 <b>우리가 적재한 범위</b>일
     * 뿐이기 때문이다. 지수는 종목과 무관하게 전 구간이 필요하고, 넓게 잡아도 지수당 1~2콜이다.
     */
    static final LocalDate SOURCE_HISTORY_START = LocalDate.of(2020, 1, 2);
}
