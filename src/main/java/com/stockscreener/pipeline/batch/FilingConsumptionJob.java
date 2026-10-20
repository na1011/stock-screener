package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.FilingLedgerStore;
import com.stockscreener.pipeline.collector.FilingLedgerStore.OpenRow;
import com.stockscreener.pipeline.collector.FinancialWriter;
import com.stockscreener.pipeline.collector.FullStatementCollector;
import com.stockscreener.pipeline.datasource.FilingTrace;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.us.SecFilingConsumption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 소비 장부의 열린 행을 지금 편입 종목의 좁힌 FR·FSR로 먹인다(ADR-0176 2차). 평일 14:16 크론이 목록 뒤에 부르고
 * {@code --job=filing-consumption}이 손으로 부른다.
 *
 * <p><b>표시는 그 종목을 쓴 트랜잭션 안에서 한다</b>(FR·FSR의 소비 훅). 「썼다」와 「소비됨」이 갈리지 않는다 — 종목이 실패하면
 * 둘 다 되돌아가고 행은 열린 채 다음 실행이 다시 먹인다. 근거는 그 FR·FSR이 받은 응답이다:
 * <ul>
 *   <li><b>미국</b>: {@link SecFilingConsumption} — companyfacts(보충 전) · 보충 표 · submissions의 XBRL 없음. FR은 기본 창이다
 *       (SEC 경로는 회수가 있어 창을 좁히면 출력이 달라질 수 있고, companyfacts는 어차피 1콜이다).</li>
 *   <li><b>국내</b>: (사업연도, 보고서 코드) 응답의 접수번호 ≥ 목록 접수번호. 창은 대상 행 사업연도 최솟값부터 올해까지로 좁힌다 —
 *       DART 경로는 회수가 없고 두 어댑터의 출력이 해마다 닫혀 있다(FSR의 비지배 게이트는 창 안의 최댓값이라 아래쪽을 잘라도
 *       남은 해의 판정이 같다). FR 몫이 끝난 행만 FSR(분기 포함)로 먹인다.</li>
 * </ul>
 *
 * <p>정기 경로에서는 FR·FSR을 {@code GAP_FILL}로 부른다 — 시계 셋이 안 센다(ADR-0172·0174). 대상이 없으면 부르지 않는다(비면 전량이 된다).
 * 정정이 지운 지표는 14:20 화해가 채운다(정기 FR과 같다).
 *
 * <p>활성 편입 종목의 열린 행이 소비가 맡은 뒤(처음 본 시각과 지금 편입이 생긴 시각 중 늦은 쪽) 평일 {@value #OVERDUE_WEEKDAYS}일을
 * 넘으면 사유 머리(「원장 아직」·「우리 쪽」)별로 알린다.
 */
@Component
public class FilingConsumptionJob {

    public static final String JOB_NAME = "filing-consumption";

    /** 열린 행이 이 평일 수를 넘으면 알린다. */
    static final int OVERDUE_WEEKDAYS = 5;

    /** 미국 순위 후보의 공시 소비(ADR-0178) — 편입 소비와 실행·알림·신선도를 가른다. */
    public static final String CANDIDATE_JOB_NAME = "candidate-consumption";

    /** 후보 행의 시도 상한 — 편입 지연 경보와 같은 평일 수다. */
    static final int CANDIDATE_MAX_ATTEMPTS = OVERDUE_WEEKDAYS;

    /** 후보 행이 이 평일 수를 넘게 열려 있으면 note에 센다(경보는 아니다 — 화면 값이 아니다). */
    static final int CANDIDATE_STALE_WEEKDAYS = 20;

    static final List<String> MARKETS = List.of("US", "KR");

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(FilingConsumptionJob.class);

    private final FilingLedgerStore store;
    private final FinancialRefillJob financialRefill;
    private final FullStatementRefillJob fullStatementRefill;
    private final SecFilingConsumption secConsumption;
    private final BatchExecutions batchExecutions;

    @Autowired
    public FilingConsumptionJob(FilingLedgerStore store, FinancialRefillJob financialRefill,
                                FullStatementRefillJob fullStatementRefill,
                                ObjectProvider<SecFilingConsumption> secConsumption,
                                BatchExecutions batchExecutions) {
        this(store, financialRefill, fullStatementRefill, secConsumption.getIfAvailable(), batchExecutions);
    }

    FilingConsumptionJob(FilingLedgerStore store, FinancialRefillJob financialRefill,
                         FullStatementRefillJob fullStatementRefill, SecFilingConsumption secConsumption,
                         BatchExecutions batchExecutions) {
        this.store = store;
        this.financialRefill = financialRefill;
        this.fullStatementRefill = fullStatementRefill;
        this.secConsumption = secConsumption;
        this.batchExecutions = batchExecutions;
    }

    /** 한 시장의 결과. */
    private record MarketResult(String note, boolean failed, int consumed) {
    }

    public BatchStatus run(LocalDate todayKst, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, todayKst, triggeredBy, MARKETS.size(),
                BatchExecutionRecorder.DeclaredScope.FULL);
        execution.plan(CollectionPhase.FILING_CONSUMPTION, MARKETS.size());
        // 정기 경로의 좁힌 FR·FSR은 GAP_FILL이다(정의: 「정기 경로가 티커로 좁혀 부른 실행」). 손 실행은 MANUAL 그대로 — 티커가
        // 있으므로 기록 계층이 NARROW로 찍는다. 어느 쪽이든 시계를 안 민다.
        BatchExecutionRecorder.TriggeredBy narrowBy = triggeredBy == BatchExecutionRecorder.TriggeredBy.SCHEDULER
                ? BatchExecutionRecorder.TriggeredBy.GAP_FILL : triggeredBy;
        int succeeded = 0;
        int failed = 0;
        long consumed = 0;
        List<String> notes = new ArrayList<>();
        try {
            for (String market : MARKETS) {
                execution.stage(market + " 공시 소비");
                MarketResult result = "US".equals(market)
                        ? consumeUs(todayKst, narrowBy)
                        : consumeKr(todayKst, narrowBy);
                notes.add(market + ": " + result.note());
                consumed += result.consumed();
                if (result.failed()) {
                    failed++;
                } else {
                    succeeded++;
                }
            }
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        execution.countExternally(CollectionPhase.FILING_CONSUMPTION, succeeded, failed, consumed);
        String overdue = overdue(todayKst);
        if (overdue != null) {
            execution.alertOperator(AlertSeverity.WARNING, "공시가 소비되지 않고 쌓였다", overdue);
        }
        return execution.finish(String.join(" | ", notes));
    }

    private MarketResult consumeUs(LocalDate today, BatchExecutionRecorder.TriggeredBy by) {
        return feedUs(store.openForActiveMembers("US"),
                (tickers, hook) -> financialRefill.runForConsumption(today, by, null, tickers, hook));
    }

    /** 미국 행을 먹일 FR — 편입은 창 편입으로, 후보는 순위 후보로 티커를 푼다. 판정은 둘 다 {@link #judgeUs}다. */
    @FunctionalInterface
    private interface UsRefill {
        FinancialRefillJob.Invalidation run(List<String> tickers, FinancialRefillJob.StockHook hook);
    }

    private MarketResult feedUs(List<OpenRow> open, UsRefill refill) {
        if (open.isEmpty()) {
            return new MarketResult("대상 0", false, 0);
        }
        if (secConsumption == null) {
            return new MarketResult("열린 행 %d · 소스 없음(SEC User-Agent가 없어 어댑터가 등록되지 않았다)".formatted(open.size()),
                    true, 0);
        }
        Counts counts = new Counts();
        Set<Long> reached = new HashSet<>();
        List<String> tickers = tickersOf(open);
        FinancialRefillJob.Invalidation run = refill.run(tickers,
                (executionId, security, outcome) -> {
                    reached.add(security.stockId());
                    judgeUs(executionId, security, outcome, counts);
                });
        BatchStatus status = run.status();
        int unreached = settleUnreachedUs(open, reached, Objects.requireNonNull(run.executionId(), "FR 실행 id"), counts);
        return new MarketResult("열린 행 %d(%d종목) · FR %s · 소비 %d · 열림 %d · 종목 실패로 못 닿음 %d".formatted(open.size(),
                tickers.size(), status, counts.consumed.get(), counts.stillOpen.get(), unreached),
                status == BatchStatus.FAILED, counts.consumed.get());
    }

    /**
     * 미국 <b>순위 후보</b>의 열린 행을 먹인다(ADR-0178) — 평일 14:16 사슬의 넷째 단계(목록 → 편입 소비 → 따라잡기 뒤 · 자기 try)이고
     * {@code --job=candidate-consumption}이 손으로 부른다. 편입 일이 먼저 끝나고, 여기서 던져도 앞 셋의 결과는 그대로다.
     *
     * <p>후보 행은 화면 값이 아니라 순위 재료(주식수)다 — 지연 경보를 안 내고, 대신 시도 상한({@value #CANDIDATE_MAX_ATTEMPTS} ·
     * 편입 지연 경보와 같은 평일 수)에 막 닿은 행을 한 번 알린다. 상한에 닿은 종목은 새 공시(새 행)가 올 때까지 안 부른다 — 같은 답을
     * 날마다 다시 물으면 그 종목의 부분 실패 알림이 상시가 된다. 손 실행은 상한 없이 다시 묻는다(코드를 고친 뒤 다시 물을 길).
     */
    public BatchStatus runCandidates(LocalDate todayKst, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        BatchExecutionContext execution = batchExecutions.start(CANDIDATE_JOB_NAME, todayKst, triggeredBy, 1,
                BatchExecutionRecorder.DeclaredScope.FULL);
        execution.plan(CollectionPhase.FILING_CONSUMPTION, 1);
        BatchExecutionRecorder.TriggeredBy narrowBy = triggeredBy == BatchExecutionRecorder.TriggeredBy.SCHEDULER
                ? BatchExecutionRecorder.TriggeredBy.GAP_FILL : triggeredBy;
        boolean manual = triggeredBy == BatchExecutionRecorder.TriggeredBy.MANUAL;
        int cap = manual ? Integer.MAX_VALUE : CANDIDATE_MAX_ATTEMPTS;
        Instant since = store.now();
        MarketResult result;
        try {
            execution.stage("US 후보 공시 소비");
            result = feedUs(store.openForCandidates(cap),
                    (tickers, hook) -> financialRefill.runForCandidateConsumption(todayKst, narrowBy, tickers, hook));
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        execution.countExternally(CollectionPhase.FILING_CONSUMPTION, result.failed() ? 0 : 1, result.failed() ? 1 : 0,
                result.consumed());
        List<OpenRow> capped = manual ? List.of() : store.candidatesReachingCap(CANDIDATE_MAX_ATTEMPTS, since);
        if (!capped.isEmpty()) {
            execution.alertOperator(AlertSeverity.WARNING, "후보 공시가 시도 상한에 닿아 새 공시까지 멈춘다",
                    cappedBody(capped));
        }
        long stale = store.openForCandidates(Integer.MAX_VALUE).stream()
                .filter(r -> FilingListingJob.weekdaysBetween(r.firstSeenAt().atZone(KST).toLocalDate(), todayKst)
                        > CANDIDATE_STALE_WEEKDAYS)
                .count();
        return execution.finish("US 후보: %s · 상한 닿음 %d · 평일 %d일 넘게 열린 후보 행 %d".formatted(result.note(),
                capped.size(), CANDIDATE_STALE_WEEKDAYS, stale));
    }

    /** 상한에 막 닿은 후보 행 — 종목·공시·마지막 사유. */
    private static String cappedBody(List<OpenRow> capped) {
        StringBuilder body = new StringBuilder("%d건 — 순위 재료(주식수)가 이 공시 앞 판에 머문다. 새 공시가 오면 다시 묻고, 코드를 고쳤으면 "
                .formatted(capped.size()) + ManualWriteDoor.WHEN + " --job=candidate-consumption(손 실행은 상한 없음)으로 다시 묻는다:\n");
        capped.stream().limit(20).forEach(r -> body.append("  %s %s %s(%s) — %s\n".formatted(r.ticker(), r.filingNo(),
                r.form(), r.filedOn(), r.lastReason())));
        if (capped.size() > 20) {
            body.append("  … 외 ").append(capped.size() - 20).append("건\n");
        }
        return body.toString();
    }

    private void judgeUs(long executionId, SecurityRef security, FinancialWriter.Outcome outcome, Counts counts) {
        List<OpenRow> rows = store.openFrOf("US", security.stockId());
        Map<Long, List<OpenRow>> byCik = new LinkedHashMap<>();
        for (OpenRow row : rows) {
            byCik.computeIfAbsent(row.cik(), c -> new ArrayList<>()).add(row);
        }
        Set<String> carried = outcome.filingTrace().accessions();
        for (Map.Entry<Long, List<OpenRow>> entry : byCik.entrySet()) {
            List<SecFilingConsumption.OpenFiling> filings = entry.getValue().stream()
                    .map(r -> new SecFilingConsumption.OpenFiling(r.id(), r.filingNo(), r.form(), r.filedOn()))
                    .toList();
            Map<String, String> statuses = store.supplementStatus(entry.getKey(),
                    filings.stream().map(SecFilingConsumption.OpenFiling::accession).toList());
            for (SecFilingConsumption.Verdict v : secConsumption.judge(entry.getKey(), filings, carried, statuses)) {
                if (v.consumed() && !outcome.unfetchedClassFacts().isEmpty()) {
                    // 클래스 축 인스턴스를 못 받았다(관문 뒤 · ADR-0179) — 닫으면 그 판이 숨김으로 남고, 정기 FR이 없는 순위 후보는
                    // 다음 공시까지 다시 안 받는다. 어느 공시가 못 받혔는지는 결과에 없으므로 그 종목의 열린 행을 다 연 채 둔다.
                    store.recordAttempt(v.filing().id(), "클래스 축 인스턴스 미해결 " + outcome.unfetchedClassFacts()
                            + " — 다음 실행이 다시 받는다");
                    counts.stillOpen.incrementAndGet();
                } else if (v.consumed()) {
                    counts.consumed.addAndGet(store.markFrConsumed(v.filing().id(), v.ground().name(), v.evidence(),
                            executionId));
                } else {
                    store.recordAttempt(v.filing().id(), v.reason());
                    counts.stillOpen.incrementAndGet();
                }
            }
        }
    }

    private MarketResult consumeKr(LocalDate today, BatchExecutionRecorder.TriggeredBy by) {
        List<OpenRow> open = store.openForActiveMembers("KR");
        if (open.isEmpty()) {
            return new MarketResult("대상 0", false, 0);
        }
        // 사업연도·보고서 코드를 못 정한 행은 먹일 키가 없다 — 목록이 사유를 적어 두었고 경보가 「우리 쪽」으로 센다.
        List<OpenRow> keyed = open.stream().filter(r -> r.bsnsYear() != null && r.reprtCode() != null).toList();
        int unkeyed = open.size() - keyed.size();

        Counts fr = new Counts();
        List<OpenRow> frRows = keyed.stream().filter(OpenRow::frOpen).toList();
        String frNote = "FR 대상 0";
        boolean failed = false;
        if (!frRows.isEmpty()) {
            Set<Long> reached = new HashSet<>();
            BatchStatus status = financialRefill.runForConsumption(today, by, yearsBack(today, frRows), tickersOf(frRows),
                    (executionId, security, outcome) -> {
                        reached.add(security.stockId());
                        judgeKr(store.openFrOf("KR", security.stockId()), outcome.filingTrace(), fr,
                                (row, latest) -> store.markFrConsumed(row.id(), "DART_RESPONSE", latest, executionId),
                                "FR");
                    }).status();
            int unreached = markUnreached(frRows, reached, OpenRow::frOpen, "FR");
            failed = status == BatchStatus.FAILED;
            frNote = "FR %s · 소비 %d · 열림 %d · 못 닿음 %d".formatted(status, fr.consumed.get(), fr.stillOpen.get(),
                    unreached);
        }

        // FSR 몫은 FR 몫이 끝난 행만 — 방금 끝난 행을 포함하도록 다시 읽는다.
        Counts fsr = new Counts();
        List<OpenRow> fsrRows = store.openForActiveMembers("KR").stream()
                .filter(r -> r.bsnsYear() != null && r.reprtCode() != null && !r.frOpen() && r.fsrOpen())
                .toList();
        String fsrNote = "FSR 대상 0";
        if (!fsrRows.isEmpty()) {
            Set<Long> reached = new HashSet<>();
            BatchStatus status = fullStatementRefill.runForConsumption(today, by, yearsBack(today, fsrRows),
                    tickersOf(fsrRows), (executionId, security, collected) -> {
                        reached.add(security.stockId());
                        judgeKr(store.openFsrOf(security.stockId()), collected.outcome().filingTrace(), fsr,
                                (row, latest) -> store.markFsrConsumed(row.id(), latest, executionId), "FSR");
                    });
            int unreached = markUnreached(fsrRows, reached, r -> !r.frOpen() && r.fsrOpen(), "FSR");
            failed |= status == BatchStatus.FAILED;
            fsrNote = "FSR %s · 소비 %d · 열림 %d · 못 닿음 %d".formatted(status, fsr.consumed.get(), fsr.stillOpen.get(),
                    unreached);
        }
        return new MarketResult("열린 행 %d · 키 없음 %d · %s · %s".formatted(open.size(), unkeyed, frNote, fsrNote),
                failed, fr.consumed.get() + fsr.consumed.get());
    }

    @FunctionalInterface
    private interface Marker {
        int mark(OpenRow row, String latest);
    }

    /** 응답의 (사업연도, 보고서 코드) 접수번호가 목록 접수번호 이상이면 소비됐다 — 그 응답이 그 공시 또는 그 뒤 판을 실었다. */
    private void judgeKr(List<OpenRow> rows, FilingTrace trace, Counts counts, Marker marker, String part) {
        for (OpenRow row : rows) {
            if (row.bsnsYear() == null || row.reprtCode() == null) {
                continue;
            }
            String latest = trace.latestByReport().get(new FilingTrace.ReportKey(row.bsnsYear(), row.reprtCode()));
            String reason = krReason(row.filingNo(), latest, part);
            if (reason == null) {
                counts.consumed.addAndGet(marker.mark(row, latest));
            } else {
                store.recordAttempt(row.id(), reason);
                counts.stillOpen.incrementAndGet();
            }
        }
    }

    /** 소비됐으면 {@code null}, 아니면 사유. */
    static String krReason(String listed, String latest, String part) {
        if (latest == null) {
            return SecFilingConsumption.SOURCE_PENDING + part + " 응답에 그 보고서가 없다(013 · 아직 반영 전)";
        }
        if (latest.compareTo(listed) < 0) {
            return SecFilingConsumption.SOURCE_PENDING + part + " 응답 접수번호 " + latest + " < 목록 " + listed;
        }
        return null;
    }

    /**
     * 미국 — FR이 그 종목에서 실패해 소비 훅에 못 닿은 종목의 열린 행. submissions가 XBRL 없음으로 싣는 공시는 FR이 담을 값이 원래
     * 없으므로 FR의 성패와 무관하게 닫는다(근거 NO_XBRL · 표시는 그 종목이 실패한 FR 실행으로 — 컬럼의 뜻 「FR 몫의 실행」 그대로). 「썼다와 소비됨이 갈리지 않는다」가 안 깨진다 —
     * 그 공시로는 쓸 것이 없다. companyfacts·보충 근거는 그 FR의 응답이 있어야 하므로 여기서 안 쓴다. 나머지는 「우리 쪽」 사유로 연다.
     *
     * <p>왜: companyfacts가 404인 발행사(XBRL 재무가 없는 외국 발행사 · 6-K 위주)는 FR이 종목 실패로 끝나 판정까지 못 가고 행이 매일
     * 다시 물렸다 — 후보 D4(2026-10-15)에서 31종목 중 8곳 · 열린 15행이 그랬고 그 6-K는 전부 XBRL이 없다(submissions 7콜).
     *
     * @return 그 뒤에도 열린 행 수
     */
    private int settleUnreachedUs(List<OpenRow> rows, Set<Long> reached, long executionId, Counts counts) {
        Map<Long, List<OpenRow>> byStock = new LinkedHashMap<>();
        for (OpenRow row : rows) {
            if (!reached.contains(row.stockId()) && row.frOpen()) {
                byStock.computeIfAbsent(row.stockId(), k -> new ArrayList<>()).add(row);
            }
        }
        int stillOpen = 0;
        for (List<OpenRow> stockRows : byStock.values()) {
            Map<Long, List<OpenRow>> byCik = new LinkedHashMap<>();
            for (OpenRow row : stockRows) {
                byCik.computeIfAbsent(row.cik(), c -> new ArrayList<>()).add(row);
            }
            for (Map.Entry<Long, List<OpenRow>> entry : byCik.entrySet()) {
                List<SecFilingConsumption.OpenFiling> filings = entry.getValue().stream()
                        .map(r -> new SecFilingConsumption.OpenFiling(r.id(), r.filingNo(), r.form(), r.filedOn()))
                        .toList();
                for (SecFilingConsumption.Verdict v : secConsumption.judge(entry.getKey(), filings, Set.of(), Map.of())) {
                    if (v.ground() == SecFilingConsumption.Ground.NO_XBRL) {
                        counts.consumed.addAndGet(store.markFrConsumed(v.filing().id(), v.ground().name(), v.evidence(),
                                executionId));
                    } else {
                        store.recordAttempt(v.filing().id(),
                                SecFilingConsumption.OUR_SIDE + "FR이 그 종목을 끝내지 못했다(실행 기록의 종목 실패)");
                        stillOpen++;
                    }
                }
            }
        }
        return stillOpen;
    }

    /**
     * 소비 훅에 닿지 못한 종목(그 종목이 실패해 쓰기가 되돌아감 · 창 편입 밖)의 열린 행에 사유를 남긴다. 훅 밖이라 그 종목의
     * 쓰기와 무관한 별도 쓰기다.
     */
    private int markUnreached(List<OpenRow> rows, Set<Long> reached, Predicate<OpenRow> stillOpen, String part) {
        int n = 0;
        for (OpenRow row : rows) {
            if (!reached.contains(row.stockId()) && stillOpen.test(row)) {
                store.recordAttempt(row.id(), SecFilingConsumption.OUR_SIDE + part + "이 그 종목을 끝내지 못했다(실행 기록의 종목 실패)");
                n++;
            }
        }
        return n;
    }

    /** 국내 창 — 대상 행 사업연도 최솟값부터 올해까지(상한은 정기 FR의 기본). */
    static int yearsBack(LocalDate today, List<OpenRow> rows) {
        int min = rows.stream().mapToInt(OpenRow::bsnsYear).min().orElseThrow();
        return Math.max(0, Math.min(FinancialRefillJob.DEFAULT_YEARS_BACK, today.getYear() - min));
    }

    private static List<String> tickersOf(List<OpenRow> rows) {
        return rows.stream().map(OpenRow::ticker).distinct().sorted().toList();
    }

    /** 활성 편입 종목의 열린 행이 평일 {@value #OVERDUE_WEEKDAYS}일을 넘은 것을 사유 머리별로. 없으면 {@code null}. */
    private String overdue(LocalDate today) {
        Map<String, List<String>> byHead = new TreeMap<>();
        for (String market : MARKETS) {
            for (OpenRow row : store.openForActiveMembers(market)) {
                LocalDate due = row.dueFrom().atZone(KST).toLocalDate();
                if (FilingListingJob.weekdaysBetween(due, today) <= OVERDUE_WEEKDAYS) {
                    continue;
                }
                String reason = row.lastReason() == null ? "사유 없음(아직 한 번도 시도하지 않았다)" : row.lastReason();
                // 국내 키를 못 정한 행은 먹일 길이 없다 — 목록이 적은 사유가 무엇이든 우리 쪽이다.
                boolean unkeyed = "KR".equals(market) && (row.bsnsYear() == null || row.reprtCode() == null);
                String head = unkeyed || reason.startsWith(SecFilingConsumption.OUR_SIDE) ? "우리 쪽"
                        : reason.startsWith(SecFilingConsumption.SOURCE_PENDING) ? "원장 아직" : "그 밖";
                byHead.computeIfAbsent(head, h -> new ArrayList<>())
                        .add("%s %s %s(%s) — %s".formatted(market, row.ticker(), row.filingNo(), row.filedOn(), reason));
            }
        }
        if (byHead.isEmpty()) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        byHead.forEach((head, lines) -> {
            body.append("[").append(head).append("] ").append(lines.size()).append("건\n");
            lines.stream().limit(20).forEach(l -> body.append("  ").append(l).append('\n'));
            if (lines.size() > 20) {
                body.append("  … 외 ").append(lines.size() - 20).append("건\n");
            }
        });
        log.warn("소비되지 않은 공시가 평일 {}일을 넘었다:\n{}", OVERDUE_WEEKDAYS, body);
        return body.toString();
    }

    private static final class Counts {
        final AtomicInteger consumed = new AtomicInteger();
        final AtomicInteger stillOpen = new AtomicInteger();
    }
}
