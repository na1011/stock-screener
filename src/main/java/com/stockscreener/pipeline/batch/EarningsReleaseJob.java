package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.EarningsReleaseStore;
import com.stockscreener.pipeline.collector.EarningsReleaseStore.Unresolved;
import com.stockscreener.pipeline.collector.EarningsReleaseStore.Unswept;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedRelease;
import com.stockscreener.pipeline.datasource.kr.DartCorpCodeCache;
import com.stockscreener.pipeline.datasource.kr.DartEarningsReleaseNames;
import com.stockscreener.pipeline.datasource.kr.DartFilingListClient;
import com.stockscreener.pipeline.datasource.kr.DartFilingListClient.ListRow;
import com.stockscreener.pipeline.datasource.us.SecCurrentReportClient;
import com.stockscreener.pipeline.datasource.us.SecCurrentReportClient.CurrentReport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실적 발표 감지(ADR-0195) — 공시 목록({@link FilingListingJob})이 날마다 넣은 실적 발표 줄을 마무리하고, 일일 목록이 덮지 못하는 과거를
 * 종목마다 채운다. 평일 13:30 일일 갱신 앞 · 14:16 사슬과 {@code --job=earnings-release}가 부른다.
 *
 * <p><b>값을 바꾸는 표를 읽지도 쓰지도 않는다</b> — {@code earnings_release}·{@code earnings_release_sweep} 둘만 쓴다. 지표·프리셋 판정은
 * 이 잡과 무관하고, 화면의 재무 기준 표시({@code FinancialBasisReader})만 읽는다.
 *
 * <p>두 일을 한다. 시장마다 따로 돌고 한 종목(CIK)의 실패는 그 종목만 남긴다 — 다음 실행이 다시 묻는다.
 * <ol>
 *   <li><b>항목 묻기(미국)</b>: 일일 색인에는 8-K의 항목이 없다. 항목을 모르는 줄을 CIK마다 submissions 한 콜로 묻는다. 접수번호가 아직
 *       submissions에 없으면 그 줄만 모르는 채로 남는다 — 그 종목의 그 구간은 판정 불가로 보이고, 시장의 다른 종목은 막지 않는다.</li>
 *   <li><b>과거 훑기</b>: 일일 목록은 이 기능이 생긴 뒤의 날만 덮는다. 훑기 표지가 없는 감지 범위 종목을 감지 시작일부터 원장에 물어 채운다
 *       — 미국 submissions(페이지 포함) · 국내 공시검색(회사 코드 · I001 · I002). 첫 실행이 곧 백필이고, 그 뒤로는 새로 편입된 종목만
 *       남는다. 한 실행이 훑는 종목 수는 {@code sweepLimit}이 묶는다 — 정기 사슬이 13:30 잠금을 오래 쥐지 않게.</li>
 * </ol>
 */
@Component
public class EarningsReleaseJob {

    public static final String JOB_NAME = "earnings-release";

    /** 정기 실행 한 판이 훑는 시장별 종목 수. 첫 백필(미국 약 1,200 · 국내 약 470)은 손 실행({@code --sweep-limit})으로 끝낸다. */
    public static final int SCHEDULED_SWEEP_LIMIT = 50;

    static final List<String> MARKETS = List.of("US", "KR");

    private static final Logger log = LoggerFactory.getLogger(EarningsReleaseJob.class);
    private static final ZoneId US_EAST = ZoneId.of("America/New_York");

    private final EarningsReleaseStore store;
    private final BatchExecutions batchExecutions;
    private final SecCurrentReportClient sec;
    private final DartFilingListClient dart;
    private final DartCorpCodeCache corpCodes;

    @Autowired
    public EarningsReleaseJob(EarningsReleaseStore store, BatchExecutions batchExecutions,
                              ObjectProvider<SecCurrentReportClient> sec, ObjectProvider<DartFilingListClient> dart,
                              ObjectProvider<DartCorpCodeCache> corpCodes) {
        this(store, batchExecutions, sec.getIfAvailable(), dart.getIfAvailable(), corpCodes.getIfAvailable());
    }

    EarningsReleaseJob(EarningsReleaseStore store, BatchExecutions batchExecutions, SecCurrentReportClient sec,
                       DartFilingListClient dart, DartCorpCodeCache corpCodes) {
        this.store = store;
        this.batchExecutions = batchExecutions;
        this.sec = sec;
        this.dart = dart;
        this.corpCodes = corpCodes;
    }

    /** 정기 실행 — 훑기를 {@link #SCHEDULED_SWEEP_LIMIT}로 묶는다. */
    public BatchStatus run(LocalDate todayKst, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        return run(todayKst, triggeredBy, SCHEDULED_SWEEP_LIMIT);
    }

    /**
     * @param sweepLimit 시장별로 이 실행이 훑을 종목 수. 남은 종목은 다음 실행이 잇는다 — 남은 종목의 발표는 판정 불가로 보인다
     */
    public BatchStatus run(LocalDate todayKst, BatchExecutionRecorder.TriggeredBy triggeredBy, int sweepLimit) {
        Map<String, Boolean> available = Map.of("US", sec != null, "KR", dart != null && corpCodes != null);
        // 한 시장이라도 소스가 없거나 끝까지 못 했으면 시장 미완료다(ADR-0174) — 시계를 안 민다.
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, todayKst, triggeredBy, MARKETS.size(),
                BatchExecutionRecorder.DeclaredScope.FULL, MarketCompleteness.Coverage.of(MARKETS, available::get));
        execution.plan(CollectionPhase.EARNINGS_RELEASE, MARKETS.size());
        int succeeded = 0;
        int failed = 0;
        long written = 0;
        List<String> notes = new ArrayList<>();
        try {
            for (String market : MARKETS) {
                execution.stage(market + " 실적 발표");
                if (!available.get(market)) {
                    failed++;
                    notes.add(market + ": 소스 없음(API 키가 없어 어댑터가 등록되지 않았다)");
                    continue;
                }
                Outcome outcome = "US".equals(market) ? runUs(todayKst, sweepLimit) : runKr(todayKst, sweepLimit);
                written += outcome.written();
                if (!outcome.staleUnknown().isEmpty()) {
                    execution.alertOperator(AlertSeverity.WARNING, market + " 실적 발표 항목을 못 받은 8-K",
                            "평일 %d일 넘게 submissions에 접수번호가 없다 — 그 종목의 재무 기준 표시가 판정 불가로 남는다: %s"
                                    .formatted(STALE_UNKNOWN_WEEKDAYS, String.join(", ", outcome.staleUnknown())));
                }
                notes.add(market + ": " + outcome.note());
                if (outcome.failures().isEmpty() && outcome.unsweptLeft() == 0) {
                    succeeded++;
                } else {
                    failed++;
                    if (!outcome.failures().isEmpty()) {
                        execution.recordUnfinishedListing(market, "실적 발표 " + outcome.failures().size() + "종목 실패");
                        log.warn("실적 발표 감지에서 종목이 실패했다 — 다음 실행이 다시 묻는다. market={} 실패={}", market,
                                outcome.failures());
                    } else {
                        execution.recordUnfinishedListing(market, "실적 발표 과거 훑기 " + outcome.unsweptLeft() + "종목 남음");
                    }
                }
            }
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        execution.countExternally(CollectionPhase.EARNINGS_RELEASE, succeeded, failed, written);
        return execution.finish(String.join(" | ", notes));
    }

    /**
     * @param failures    실패한 종목(티커 · CIK)과 사유
     * @param unsweptLeft 상한 때문에 이번에 못 훑고 남은 종목 수(정확히는 다음 판 질의가 다시 낼 수 — 상한에 닿았으면 1 이상)
     */
    private record Outcome(long written, List<String> failures, int unsweptLeft, String note, List<String> staleUnknown) {
    }

    /** 항목을 이 평일 수 넘게 못 받은 줄은 알린다 — 화면에서는 그 종목이 판정 불가로만 보여 사람이 원인을 모른다. */
    static final int STALE_UNKNOWN_WEEKDAYS = 3;

    private Outcome runUs(LocalDate todayKst, int sweepLimit) {
        List<String> failures = new ArrayList<>();
        long written = 0;
        // ① 항목 묻기 — CIK마다 한 콜. 가장 이른 줄의 제출일부터 묻는다(그 앞 페이지는 안 받는다).
        Map<Long, List<Unresolved>> byCik = new LinkedHashMap<>();
        for (Unresolved u : store.unresolvedUs()) {
            byCik.computeIfAbsent(u.cik(), k -> new ArrayList<>()).add(u);
        }
        int resolved = 0;
        int stillUnknown = 0;
        List<String> stale = new ArrayList<>();
        for (Map.Entry<Long, List<Unresolved>> e : byCik.entrySet()) {
            LocalDate since = e.getValue().stream().map(Unresolved::filedOn).min(LocalDate::compareTo).orElseThrow();
            Map<String, String> items = new HashMap<>();
            try {
                for (CurrentReport report : sec.currentReports(e.getKey(), since)) {
                    items.put(report.accession(), report.items());
                }
            } catch (DataSourceException ex) {
                failures.add("CIK " + e.getKey() + " " + ex.kind() + " " + ex.getMessage());
                continue;
            }
            for (Unresolved u : e.getValue()) {
                String found = items.get(u.filingNo());
                if (found == null) {
                    // submissions에 아직 없다 — 그 줄만 모르는 채 남는다(그 종목의 그 구간은 판정 불가).
                    stillUnknown++;
                    if (FilingListingJob.weekdaysBetween(u.filedOn(), todayKst) > STALE_UNKNOWN_WEEKDAYS) {
                        stale.add("CIK %d %s(%s)".formatted(u.cik(), u.filingNo(), u.filedOn()));
                    }
                    continue;
                }
                store.resolve(u.id(), found);
                resolved++;
            }
        }
        // ② 과거 훑기 — submissions가 지금까지의 제출을 다 준다. 미국 동부 어제까지를 훑은 것으로 남긴다(오늘 제출은 이 콜 뒤에도 온다).
        LocalDate sweptThrough = LocalDate.now(US_EAST).minusDays(1);
        List<Unswept> unswept = store.unswept("US", sweepLimit + 1);
        int swept = 0;
        for (Unswept stock : unswept.subList(0, Math.min(sweepLimit, unswept.size()))) {
            List<CurrentReport> reports;
            try {
                reports = sec.currentReports(stock.cik(), EarningsReleaseStore.FIRST_DATE);
            } catch (DataSourceException ex) {
                failures.add(stock.ticker() + " " + ex.kind() + " " + ex.getMessage());
                continue;
            }
            List<ListedRelease> releases = new ArrayList<>();
            Map<String, String> items = new HashMap<>();
            for (CurrentReport report : reports) {
                releases.add(new ListedRelease(report.accession(), stock.stockId(), report.form(), null, report.filed()));
                items.put(report.accession(), report.items());
            }
            written += store.recordSweep("US", stock.stockId(), releases, items, sweptThrough);
            swept++;
        }
        int left = Math.max(0, unswept.size() - sweepLimit);
        return new Outcome(written + resolved, failures, left,
                "항목 %d줄 답 · 아직 모름 %d줄 · 훑음 %d종목%s · 실패 %d".formatted(resolved, stillUnknown, swept,
                        left > 0 ? " · 남음 있음" : "", failures.size()), stale);
    }

    private Outcome runKr(LocalDate todayKst, int sweepLimit) {
        List<String> failures = new ArrayList<>();
        long written = 0;
        // 국내는 일일 목록이 이름으로 이미 판정해 넣는다 — 물을 항목이 없다. 과거 훑기만 한다. 오늘 접수분은 오후에도 오므로 KST 어제까지를
        // 훑은 것으로 남긴다(공시 목록이 오늘을 닫지 않는 것과 같은 이유).
        LocalDate sweptThrough = todayKst.minusDays(1);
        List<Unswept> unswept = store.unswept("KR", sweepLimit + 1);
        int swept = 0;
        for (Unswept stock : unswept.subList(0, Math.min(sweepLimit, unswept.size()))) {
            List<ListedRelease> releases = new ArrayList<>();
            try {
                String corpCode = corpCodes.findCorpCode(stock.ticker()).orElse(null);
                if (corpCode == null) {
                    failures.add(stock.ticker() + " 회사 코드 없음");
                    continue;
                }
                for (ListRow row : dart.releases(corpCode, EarningsReleaseStore.FIRST_DATE, sweptThrough)) {
                    if (DartEarningsReleaseNames.isEarningsRelease(row.reportName())) {
                        releases.add(new ListedRelease(row.rceptNo(), stock.stockId(), row.detailType(), row.reportName(),
                                row.rceptDate()));
                    }
                }
            } catch (DataSourceException ex) {
                failures.add(stock.ticker() + " " + ex.kind() + " " + ex.getMessage());
                continue;
            }
            written += store.recordSweep("KR", stock.stockId(), releases, Map.of(), sweptThrough);
            swept++;
        }
        int left = Math.max(0, unswept.size() - sweepLimit);
        return new Outcome(written, failures, left,
                "훑음 %d종목%s · 실패 %d".formatted(swept, left > 0 ? " · 남음 있음" : "", failures.size()), List.of());
    }
}
