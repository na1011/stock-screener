package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.KrFilingTimeStore;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.kr.DartFilingTimeClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>국내 공시 게시 시각</b>(ADR-0204) — 국내 재무 · 거버넌스 · XBRL 장부 · 실적 발표의 공시일 중 캐시에 없는 지난 날짜를 DART 웹 「오늘의 공시」에서
 * 받아 캐시에 쓰고, 국내 관문이 켜졌으면 캐시를 관문 표 {@code kr_filing_time}에 올린다(0콜 · 덮어 맞춘다). 오늘(KST) 날짜는 받지 않는다 — 그날
 * 목록은 계속 는다(그날 공시는 시각을 모르는 동안 다음 날부터 보이고, 다음 실행이 받으면 매 평일 화해가 맞춘다 · ADR-0166). 날짜 하나의 실패는
 * 그 날짜만 남기고 다음 실행이 다시 묻는다. 평일 13:30 사슬의 공시 소비 뒤 · 일일 갱신 앞에 돈다.
 */
@Component
public class KrFilingTimeJob {

    public static final String JOB_NAME = "kr-filing-time";

    /** 정기 실행이 한 번에 받는 날짜 상한 — 밀린 날짜가 13:30 사슬을 막지 않게(하루 대개 10 ~ 31쪽 · 1초 1건). 손 실행은 상한이 없다. */
    public static final int SCHEDULED_DAY_LIMIT = 5;

    private static final Logger log = LoggerFactory.getLogger(KrFilingTimeJob.class);

    private final DartFilingTimeClient client;
    private final KrFilingTimeStore store;
    private final JdbcTemplate jdbc;
    private final BatchExecutions batchExecutions;
    private final boolean krGate;

    public KrFilingTimeJob(DartFilingTimeClient client, KrFilingTimeStore store, JdbcTemplate jdbc, BatchExecutions batchExecutions,
                           @Value("${stockscreener.gate.kr}") boolean krGate) {
        this.client = client;
        this.store = store;
        this.jdbc = jdbc;
        this.batchExecutions = batchExecutions;
        this.krGate = krGate;
    }

    /** 그 시점의 국내 원자료 공시일 중 캐시에 없는 지난 날짜(오래된 순). */
    List<LocalDate> targets(LocalDate todayKst) {
        List<LocalDate> days = jdbc.queryForList("""
                SELECT d FROM (
                    SELECT f.published_at AS d FROM financial_statement f JOIN stock s ON s.id = f.stock_id WHERE s.sec_cik IS NULL
                    UNION SELECT rcept_date FROM kr_report_xbrl
                    UNION SELECT to_date(left(rcept_no, 8), 'YYYYMMDD') FROM kr_report_xbrl
                    UNION SELECT g.published_at FROM governance_fact g JOIN stock s ON s.id = g.stock_id WHERE s.sec_cik IS NULL
                    UNION SELECT filed_on FROM earnings_release WHERE market = 'KR'
                ) x WHERE d < ? ORDER BY d
                """, LocalDate.class, todayKst);
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate day : days) {
            if (!store.cached(day)) {
                missing.add(day);
            }
        }
        return missing;
    }

    public BatchStatus run(LocalDate todayKst, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        return run(todayKst, triggeredBy, Integer.MAX_VALUE);
    }

    /** @param dayLimit 이 실행이 받을 날짜 상한(최근 날짜부터) — 남은 날짜는 다음 실행이 잇는다 */
    public BatchStatus run(LocalDate todayKst, BatchExecutionRecorder.TriggeredBy triggeredBy, int dayLimit) {
        List<LocalDate> all = targets(todayKst);
        // 최근 날짜가 먼저다 — 새 공시의 시각이 밀린 과거보다 급하다.
        List<LocalDate> targets = new ArrayList<>(all.subList(Math.max(0, all.size() - dayLimit), all.size()));
        java.util.Collections.reverse(targets);
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, todayKst, triggeredBy, targets.size(),
                BatchExecutionRecorder.DeclaredScope.FULL);
        execution.plan(CollectionPhase.FILING_LISTING, targets.size());
        int succeeded = 0;
        int failed = 0;
        long rows = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (LocalDate day : targets) {
                execution.stage("게시 시각 " + day);
                try {
                    List<com.stockscreener.pipeline.datasource.kr.DartDayList.Row> list = client.day(day);
                    store.writeCache(day, list);
                    rows += list.size();
                    succeeded++;
                } catch (DataSourceException e) {
                    failed++;
                    failures.add(day + ": " + e.getMessage());
                    log.warn("게시 시각 날짜를 못 받았다 — 다음 실행이 다시 묻는다. day={} {}", day, e.getMessage());
                }
            }
            long loaded = krGate ? store.loadAll() : 0;
            if (failed > 0) {
                execution.recordUnfinishedListing("KR", "게시 시각 " + failed + "일 실패");
            }
            execution.countExternally(CollectionPhase.FILING_LISTING, succeeded, failed, rows);
            return execution.finish("받은 날짜 %d · 실패 %d · 줄 %d · 표에 올림 %d%s".formatted(succeeded, failed, rows, loaded,
                    failures.isEmpty() ? "" : " · " + String.join(" | ", failures.subList(0, Math.min(5, failures.size())))));
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
    }
}
