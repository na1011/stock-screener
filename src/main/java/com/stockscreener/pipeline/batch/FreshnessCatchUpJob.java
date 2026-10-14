package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.StockRefreshStore;
import com.stockscreener.pipeline.collector.StockRefreshStore.Behind;
import com.stockscreener.pipeline.datasource.CollectedMarkets;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * 편입 원자료 따라잡기(ADR-0177) — 편입 뒤로 FR·FSR·G가 정기 범위로 맞춘 적이 없는 활성 편입 종목을 그 잡의 좁힌 실행으로 맞춘다.
 *
 * <p><b>왜</b>: 리밸런싱 꼬리는 축마다 0행인 신규 편입만 채우고, 무거운 잡 셋은 25일 간격이라 원자료가 이미 있는 편입(재편입 ·
 * 일괄 적재로 행이 있는 종목)은 다음 정기 실행까지 그 시점의 원자료·규칙으로 남는다(F6). 2부 소비는 09-24 뒤 공시가 있는 편입만
 * 깨우고, 국내는 창을 좁혀 옛 연도를 안 맞추며, G는 장부가 깨우지 않는다.
 *
 * <p><b>무엇이 「맞췄다」인가</b>: 잡의 종목 람다가 범위가 정기 실행 이상일 때 같은 트랜잭션에서 쓰는 표지({@code stock_job_refresh})다.
 * 고르는 술어는 {@link StockRefreshStore#behind} 하나 — 전역 시각이 없어 상한으로 나눠 돌거나 손으로 일부만 돌거나 치명 실패로
 * 멈춰도 남은 종목이 그대로 다음 대상이다. 정기 실행의 종목 실패는 여기서 안 집는다 — 답 못 받은 실패는 그 실행을 시장 미완료로
 * 내려 다음 차례에 잡 전체가 다시 돌고(ADR-0174), FULL로 끝난 실행에 남는 실패는 소스가 답한 것(폐지 증거)뿐이다.
 *
 * <p><b>도는 자리 둘</b>: 리밸런싱 꼬리(토 20:00 · 손·관리자 리밸런싱 포함 — {@link #runAfterRebalance})가 주 자리다. 월 13:30
 * 일일 갱신과 워치리스트 판정(하루 한 번 · 되돌릴 수 없는 엣지 알림)이 새 원자료로 돌아야 하므로 무효화된 지표를 그 자리에서
 * 화해한다(14:00 사슬과 같은 꼴). 평일 14:16 사슬 끝({@link #run})은 꼬리가 안 돌았거나 상한·실패로 남은 종목을 집는다 — 같은
 * 오후 14:18 유도와 14:20 화해가 덮으므로 사슬 안 화해를 안 한다.
 */
@Component
public class FreshnessCatchUpJob {

    public static final String JOB_NAME = "refresh-behind";

    /** 뒤처진 지 이만큼(편입 행이 생긴 날부터 평일)을 넘으면 알린다 — 소비 경보와 같은 문턱. */
    static final int OVERDUE_WEEKDAYS = 5;

    /**
     * 한 번에 잡·시장마다 맞출 종목 수. 넘는 종목은 다음 차례로 넘어가고 술어에 남는다.
     * 국내는 FSR이 종목당 약 45콜(실행 #706 · 1종목 38콜)이라 50이면 약 2,250콜, G는 표 24개다. 미국은 SEC 10/s라 200이면
     * FR 약 500콜(#775 · 22종목 56콜)이다.
     */
    static final int LIMIT_KR = 50;
    static final int LIMIT_US = 200;

    /**
     * 유니버스별 컷오프 — {@code stock_job_refresh}가 생기기 전 그 유니버스 전원을 실패 0으로 덮은 마지막 넓은 실행의 시작이다.
     * 표지가 NULL인 종목은 지금 편입이 이 뒤에 생겼을 때만 뒤처졌다(그 앞 편입은 다른 편입과 같은 실행으로 함께 맞춰졌다).
     * 그 잡의 정기 실행이 한 번 돌면 그 실행이 표지를 써서 이 상수는 뜻을 잃는다.
     * 근거는 {@code batch_execution}이다 — FR #683(미국 1,000) · #645(KOSPI 200) · #658(KOSDAQ 150) · FSR #663(KOSPI 200) ·
     * #648(KOSDAQ 150 — #664는 편출 목록이었다) · G #632(1,350). 실패 행은 모두 0이다.
     */
    static final Map<String, Map<String, Instant>> CUTOFFS = Map.of(
            FinancialRefillJob.JOB_NAME, Map.of(
                    "US_TOP1000", at("2026-09-28T01:14:23.31365+09:00"),
                    "KOSPI_TOP200", at("2026-09-21T22:15:40.228813+09:00"),
                    "KOSDAQ_TOP150", at("2026-09-22T09:43:02.882759+09:00")),
            FullStatementRefillJob.JOB_NAME, Map.of(
                    "KOSPI_TOP200", at("2026-09-23T00:09:45.619088+09:00"),
                    "KOSDAQ_TOP150", at("2026-09-21T23:19:06.336469+09:00")),
            GovernanceLoadJob.JOB_NAME, Map.of(
                    "US_TOP1000", at("2026-09-20T15:18:55.30564+09:00"),
                    "KOSPI_TOP200", at("2026-09-20T15:18:55.30564+09:00"),
                    "KOSDAQ_TOP150", at("2026-09-20T15:18:55.30564+09:00")));

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(FreshnessCatchUpJob.class);

    private final StockRefreshStore store;
    private final FinancialRefillJob financialRefill;
    private final FullStatementRefillJob fullStatementRefill;
    private final GovernanceLoadJob governanceLoad;
    private final GovernanceDeriveJob governanceDerive;
    private final IndicatorReconciler reconciler;
    private final DataSourceRegistry registry;
    private final BatchExecutions batchExecutions;
    private final AlertPort alertPort;

    public FreshnessCatchUpJob(StockRefreshStore store, FinancialRefillJob financialRefill,
                               FullStatementRefillJob fullStatementRefill, GovernanceLoadJob governanceLoad,
                               GovernanceDeriveJob governanceDerive, IndicatorReconciler reconciler,
                               DataSourceRegistry registry, BatchExecutions batchExecutions, AlertPort alertPort) {
        this.store = store;
        this.financialRefill = financialRefill;
        this.fullStatementRefill = fullStatementRefill;
        this.governanceLoad = governanceLoad;
        this.governanceDerive = governanceDerive;
        this.reconciler = reconciler;
        this.registry = registry;
        this.batchExecutions = batchExecutions;
        this.alertPort = alertPort;
    }

    /**
     * 평일 14:16 사슬 끝 · 손 실행. 같은 오후 14:18 유도·14:20 화해가 덮으므로 화해하지 않는다.
     *
     * @param onlyTickers 비면 술어 전부. 값이 있으면 술어와의 교집합만(손 실행이 더 좁힐 때)
     */
    public BatchStatus run(LocalDate todayKst, BatchExecutionRecorder.TriggeredBy triggeredBy, List<String> onlyTickers) {
        return run(todayKst, triggeredBy, onlyTickers, false);
    }

    /**
     * 리밸런싱 꼬리. 맞춘 종목의 지표를 그 자리에서 화해한다 — 월 13:30 일일 갱신·워치리스트 판정이 새 원자료로 돌게. 입력 유도
     * (주식수 기저)는 부르지 않는다 — 14:00 사슬과 같은 사유로, 새 행의 판정 NULL은 13:30과 같은 의미(OK)이고 같은 평일 14:18이 덮는다.
     *
     * <p>던지지 않는다 — 리밸런싱은 이미 끝났고, 여기서 던지면 그 실패가 리밸런싱 실패처럼 읽힌다. 실패는 ERROR와 알림으로 남기고,
     * 남은 종목은 평일 14:16이 집는다.
     */
    public void runAfterRebalance(BatchExecutionRecorder.TriggeredBy triggeredBy) {
        LocalDate today = LocalDate.now(KST);
        try {
            run(today, triggeredBy, List.of(), true);
        } catch (BatchShutdownException e) {
            log.info("편입 원자료 따라잡기가 종료로 중단됐다 — 리밸런싱은 이미 끝났다. 남은 종목은 평일 14:16이 집는다.");
        } catch (RuntimeException e) {
            log.error("리밸런싱 꼬리의 편입 원자료 따라잡기가 실패했다. 리밸런싱은 정상이며 되돌리지 않는다.", e);
            alertPort.send(AlertEvent.toOperator(AlertSeverity.ERROR, "편입 원자료 따라잡기 실패",
                    "유니버스 리밸런싱은 정상이다. 원자료가 이미 있던 새 편입을 정기 범위로 맞추지 못했다 — 평일 14:16이 다시 집는다: " + e,
                    "refresh-behind-tail"));
        }
    }

    private BatchStatus run(LocalDate today, BatchExecutionRecorder.TriggeredBy triggeredBy, List<String> onlyTickers,
                            boolean reconcileInChain) {
        boolean narrowed = onlyTickers != null && !onlyTickers.isEmpty();
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, today, triggeredBy, 3,
                BatchExecutionRecorder.DeclaredScope.byArgs(!narrowed));
        execution.plan(CollectionPhase.REFRESH_BEHIND, 3);
        // 정기 경로의 좁힌 실행은 GAP_FILL이다 — 시계 셋이 안 센다. 손 실행은 MANUAL 그대로(티커가 있으므로 NARROW).
        BatchExecutionRecorder.TriggeredBy by = triggeredBy == BatchExecutionRecorder.TriggeredBy.SCHEDULER
                ? BatchExecutionRecorder.TriggeredBy.GAP_FILL : triggeredBy;
        Predicate<Behind> wanted = narrowed ? b -> onlyTickers.contains(b.ticker()) : b -> true;
        List<String> notes = new ArrayList<>();
        Set<String> touched = new LinkedHashSet<>();
        int succeeded = 0;
        int failed = 0;
        try {
            for (String job : List.of(FinancialRefillJob.JOB_NAME, FullStatementRefillJob.JOB_NAME,
                    GovernanceLoadJob.JOB_NAME)) {
                execution.stage(job + " 따라잡기");
                JobResult result = catchUp(job, today, by, wanted, reconcileInChain);
                notes.add(job + ": " + result.note());
                touched.addAll(result.tickers());
                if (result.failed()) {
                    failed++;
                } else {
                    succeeded++;
                }
            }
            if (reconcileInChain && !touched.isEmpty()) {
                execution.stage("맞춘 종목 화해");
                // 화해의 기준일은 13:30 일일 갱신의 대상일이고 지표 달력 최신일을 넘지 않는다(IndicatorReconciler.dataAsOf).
                BatchStatus reconciled = reconciler.reconcile(reconciler.dataAsOf(today), List.copyOf(touched), by);
                notes.add("화해 %s(%d종목)".formatted(reconciled, touched.size()));
            }
        } catch (BatchShutdownException e) {
            throw e;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        execution.countExternally(CollectionPhase.REFRESH_BEHIND, succeeded, failed, touched.size());
        String overdue = overdue(today);
        if (overdue != null) {
            execution.alertOperator(AlertSeverity.WARNING, "편입 원자료가 정기 범위로 맞춰지지 않고 남았다", overdue);
        }
        return execution.finish(String.join(" | ", notes));
    }

    /** 한 잡의 결과 — 맞추려고 부른 티커와 그 잡 실행이 실패했는가. */
    private record JobResult(String note, boolean failed, List<String> tickers) {
    }

    private JobResult catchUp(String job, LocalDate today, BatchExecutionRecorder.TriggeredBy by,
                              Predicate<Behind> wanted, boolean deriveGovernance) {
        // 그날 그 잡의 넓은 실행이 시작됐으면 건너뛴다 — 방금 같은 종목을 시도했고, 같은 날 다시 돌면 그 경로의 일일 한도
        // (국내 FSR 경로 실측 15,844/18,000)를 넘길 수 있다. 남은 종목은 다음 차례에 집는다.
        if (store.wideRunStartedOn(job, today)) {
            return new JobResult("오늘 넓은 실행이 돌아 건너뜀", false, List.of());
        }
        List<Behind> behind = store.behind(job, marketsWithSource(job), CUTOFFS.getOrDefault(job, Map.of()))
                .stream().filter(wanted).toList();
        List<String> tickers = limited(behind);
        if (tickers.isEmpty()) {
            return new JobResult("대상 0", false, List.of());
        }
        // MUST: 빈 목록이면 부르지 않는다 — 셋 다 비면 전량이 된다(FR·G resolveTargets · FSR은 분기 요구가 IAE를 던진다).
        BatchStatus status;
        if (FinancialRefillJob.JOB_NAME.equals(job)) {
            status = financialRefill.runAndReport(today, by, null, tickers, null).status();
        } else if (FullStatementRefillJob.JOB_NAME.equals(job)) {
            // 티커를 준 실행의 종목별 범위는 정기 실행(전 종목 분기)과 같다 — includeQuarters.
            status = fullStatementRefill.run(today, by, null, tickers, true, false);
        } else {
            status = governanceLoad.run(today, by, null, null, List.of(), tickers);
            if (deriveGovernance && status != BatchStatus.FAILED) {
                // 적재만 하면 governance_fact가 안 바뀐 채 둘 다 성공으로 끝난다. 평일 경로는 14:18 전원 유도가 2분 뒤라 안 부른다.
                governanceDerive.run(today, by, tickers);
            }
        }
        int left = behind.size() - tickers.size();
        return new JobResult("뒤처짐 %d · 부름 %d · %s%s".formatted(behind.size(), tickers.size(), status,
                left > 0 ? " · 상한으로 넘김 " + left : ""), status == BatchStatus.FAILED, tickers);
    }

    /** 그 잡의 소스가 있는 시장 — 소스 없는 시장의 종목은 영영 못 맞추므로 고르지 않는다. */
    private Set<String> marketsWithSource(String job) {
        Set<String> markets = new LinkedHashSet<>();
        for (String market : CollectedMarkets.ALL.stream().sorted().toList()) {
            boolean has = switch (job) {
                case FinancialRefillJob.JOB_NAME -> registry.hasFinancialSource(market);
                case FullStatementRefillJob.JOB_NAME -> registry.hasFullStatementSource(market);
                case GovernanceLoadJob.JOB_NAME -> registry.hasGovernanceSource(market);
                default -> throw new IllegalArgumentException("따라잡기 대상 잡이 아니다: " + job);
            };
            if (has) {
                markets.add(market);
            }
        }
        return markets;
    }

    /** 시장마다 상한까지 — 술어가 이미 오래 실패한 순 뒤로 미뤘다. */
    static List<String> limited(List<Behind> behind) {
        Map<String, Integer> taken = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        for (Behind b : behind) {
            int limit = "US".equals(b.market()) ? LIMIT_US : LIMIT_KR;
            int n = taken.merge(b.market(), 1, Integer::sum);
            if (n <= limit) {
                out.add(b.ticker());
            }
        }
        return out;
    }

    /** 뒤처진 지 평일 {@value #OVERDUE_WEEKDAYS}일을 넘은 종목을 잡별로. 없으면 {@code null}. */
    private String overdue(LocalDate today) {
        Map<String, List<String>> byJob = new TreeMap<>();
        for (String job : List.of(FinancialRefillJob.JOB_NAME, FullStatementRefillJob.JOB_NAME,
                GovernanceLoadJob.JOB_NAME)) {
            for (Behind b : store.behind(job, marketsWithSource(job), CUTOFFS.getOrDefault(job, Map.of()))) {
                LocalDate joined = b.joinedAt().atZone(KST).toLocalDate();
                if (FilingListingJob.weekdaysBetween(joined, today) <= OVERDUE_WEEKDAYS) {
                    continue;
                }
                byJob.computeIfAbsent(job, j -> new ArrayList<>()).add("%s %s(편입 %s%s)".formatted(b.market(), b.ticker(),
                        joined, b.lastFailure() == null ? "" : " · 마지막 실패 " + b.lastFailure().atZone(KST).toLocalDate()));
            }
        }
        if (byJob.isEmpty()) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        byJob.forEach((job, rows) -> {
            body.append("[").append(job).append("] ").append(rows.size()).append("종목\n");
            rows.stream().limit(20).forEach(r -> body.append("  ").append(r).append('\n'));
        });
        body.append("사유는 그 잡의 실행 기록(실패 행)에 있다. 같은 종목이 거듭 남으면 특수 갈래를 덧대지 말고 원장이 판마다 다른지부터 잰다.");
        log.warn("정기 범위로 맞춰지지 않은 편입이 평일 {}일을 넘었다:\n{}", OVERDUE_WEEKDAYS, body);
        return body.toString();
    }

    private static Instant at(String offsetDateTime) {
        return OffsetDateTime.parse(offsetDateTime).toInstant();
    }
}
