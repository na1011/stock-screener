package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.FilingLedgerStore;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingListSource;
import com.stockscreener.pipeline.datasource.FilingListSource.FilingListing;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedDay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 원장 공시 목록을 받아 소비 장부에 쌓는다(ADR-0176 · 재무 동기 2부 1차). 평일 13:30 일일 갱신 앞 · 14:16 크론과 {@code --job=filing-listing}이
 * 부른다.
 *
 * <p><b>값을 바꾸는 표를 읽지도 쓰지도 않는다</b> — 쓰는 것은 {@code filing_listing_day}·{@code filing_consumption} 둘뿐이고,
 * 장부 행을 먹는 소비 단계는 아직 없다(2차).
 *
 * <p>시장마다 커서(닫은 마지막 날) 다음 날부터 받는다. 한 시장의 받기 실패와 장부 쓰기 실패({@code DataAccessException})는 그
 * 시장의 커서만 멈추고(그 시장 실패 1) 다른 시장은 돈다. 그 시장은 그 실행의 미완료 시장이라(ADR-0174) 실행이 신선도 시계를
 * 안 민다 — 한 시장이 매일 실패하면 신선도 예산(5일)이 걸린다. 커서가 평일 {@value #LAG_ALERT_WEEKDAYS}일을 넘게 늦으면 마지막 실패
 * 사유와 함께 알린다 — 예산보다 먼저 보이게.
 */
@Component
public class FilingListingJob {

    public static final String JOB_NAME = "filing-listing";

    /** 이 평일 수를 넘게 커서가 늦으면 알린다. 연방 공휴일 하나(미국)는 넘기게 2다. */
    static final int LAG_ALERT_WEEKDAYS = 2;

    /** 장부의 시장 — 늘 둘 다 묻는다. 소스가 없으면(키 없음) 그 시장은 실패다. */
    static final List<String> MARKETS = List.of("US", "KR");

    private static final Logger log = LoggerFactory.getLogger(FilingListingJob.class);

    private final Map<String, FilingListSource> sources = new LinkedHashMap<>();
    private final FilingLedgerStore store;
    private final BatchExecutions batchExecutions;
    private final Map<String, LocalDate> firstDates;

    @Autowired
    public FilingListingJob(ObjectProvider<FilingListSource> sources, FilingLedgerStore store,
                            BatchExecutions batchExecutions,
                            @Value("${stockscreener.filing-listing.first-date.us}") String firstDateUs,
                            @Value("${stockscreener.filing-listing.first-date.kr}") String firstDateKr) {
        this(sources.orderedStream().toList(), store, batchExecutions,
                Map.of("US", LocalDate.parse(firstDateUs), "KR", LocalDate.parse(firstDateKr)));
    }

    FilingListingJob(List<FilingListSource> sources, FilingLedgerStore store, BatchExecutions batchExecutions,
                     Map<String, LocalDate> firstDates) {
        for (FilingListSource source : sources) {
            if (!MARKETS.contains(source.market()) || this.sources.put(source.market(), source) != null) {
                throw new IllegalStateException("공시 목록 소스의 시장이 장부 시장이 아니거나 둘이다: " + source.market());
            }
        }
        this.store = store;
        this.batchExecutions = batchExecutions;
        this.firstDates = Map.copyOf(firstDates);
    }

    /** @param todayKst 실행일(KST) — 그 뒤의 날은 묻지 않고, 국내는 그날을 닫지 않는다 */
    public BatchStatus run(LocalDate todayKst, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        // 시장 판정(ADR-0174) — 한 시장이라도 그날까지 못 닫으면 이 실행은 시계를 안 민다(부분 성공이 신선도 예산을 날마다 덮지 않게).
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, todayKst, triggeredBy, MARKETS.size(),
                BatchExecutionRecorder.DeclaredScope.FULL, MarketCompleteness.Coverage.of(MARKETS, sources::containsKey));
        execution.plan(CollectionPhase.FILING_LISTING, MARKETS.size());
        int succeeded = 0;
        int failed = 0;
        long inserted = 0;
        List<String> notes = new ArrayList<>();
        List<String> lagging = new ArrayList<>();
        try {
            for (String market : MARKETS) {
                execution.stage(market + " 공시 목록");
                LocalDate cursor = store.cursor(market).orElse(firstDates.get(market).minusDays(1));
                FilingListSource source = sources.get(market);
                String failure;
                LocalDate newCursor = cursor;
                if (source == null) {
                    failure = "소스 없음(API 키가 없어 어댑터가 등록되지 않았다)";
                    notes.add(market + ": " + failure);
                } else {
                    FilingListing listing = source.list(cursor, todayKst, store.stocksByTicker(market));
                    int newRows = 0;
                    try {
                        for (ListedDay day : listing.closedDays()) {
                            newRows += store.recordDay(market, day);
                            newCursor = day.day();
                        }
                        newRows += store.recordOpen(market, listing.openFilings());
                        failure = describe(listing.failure());
                    } catch (DataAccessException e) {
                        // 장부 쓰기 실패도 그 시장의 실패다 — 실행 전체를 세우면 다음 시장의 커서까지 멈춘다(2026-10-20·21: 미국 한 날의
                        // 제약 위반이 국내 목록까지 세웠다 · 실행 869·887). 그날은 롤백돼 안 닫히고 다음 실행이 다시 받는다.
                        // 같은 실행의 받기 실패 사유가 있으면 함께 남긴다 — 덮으면 두 원인 중 하나가 사라진다.
                        String received = describe(listing.failure());
                        failure = "장부 쓰기 실패 " + e.getMostSpecificCause().getMessage()
                                + (received == null ? "" : " · 받기 실패 " + received);
                        log.error("공시 장부를 못 썼다 — 커서가 {}에 멈춘다. market={}", newCursor, market, e);
                        execution.alertOperator(AlertSeverity.ERROR, market + " 공시 장부 쓰기 실패",
                                "커서 %s에서 멈췄다: %s".formatted(newCursor, failure));
                        execution.recordUnfinishedListing(market, "공시 목록 장부 쓰기");
                    }
                    if (listing.failure() != null) {
                        execution.recordUnfinishedListing(market,
                                "공시 목록 " + MarketCompleteness.unansweredWord(listing.failure().kind()));
                    }
                    inserted += newRows;
                    notes.add("%s: 닫은 날 %d · 새 장부 %d · 커서 %s%s".formatted(market, listing.closedDays().size(),
                            newRows, newCursor, failure == null ? "" : " · 실패 " + failure));
                    if (failure != null) {
                        log.warn("공시 목록을 끝까지 못 받았다 — 커서가 {}에 멈춘다. market={} 사유={}", newCursor, market, failure);
                    }
                }
                if (failure == null) {
                    succeeded++;
                } else {
                    failed++;
                }
                int lag = weekdaysBetween(newCursor, todayKst);
                if (lag > LAG_ALERT_WEEKDAYS) {
                    lagging.add("%s 커서 %s — 평일 %d일 늦다(마지막 실패: %s)".formatted(market, newCursor, lag,
                            failure == null ? "없음 — 원장이 아직 안 냈다" : failure));
                }
            }
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        execution.countExternally(CollectionPhase.FILING_LISTING, succeeded, failed, inserted);
        if (!lagging.isEmpty()) {
            execution.alertOperator(AlertSeverity.WARNING, "공시 목록 커서가 늦다", String.join("\n", lagging));
        }
        return execution.finish(String.join(" | ", notes));
    }

    private static String describe(DataSourceException failure) {
        return failure == null ? null : failure.kind() + " " + failure.getMessage();
    }

    /** {@code after}와 {@code before} 사이(양 끝 제외)의 평일 수 — 공휴일은 모른다. */
    static int weekdaysBetween(LocalDate after, LocalDate before) {
        int count = 0;
        for (LocalDate d = after.plusDays(1); d.isBefore(before); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }
}
