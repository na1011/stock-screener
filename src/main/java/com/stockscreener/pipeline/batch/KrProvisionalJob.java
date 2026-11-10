package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.KrProvisionalStore;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.kr.DartViewerClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>국내 실적 발표 공시 본문</b>(잠정실적 I002 · 손익구조 변동 I001) — 국내 실적 발표 줄({@code earnings_release}) 중 캐시에 없는 공시의
 * 본문을 DART 웹 뷰어에서 받아 캐시에 쓰고, 국내 잠정 관문이 켜졌으면 캐시 전부를 해석해 관문 표에 올린다(0콜 · 덮어 맞춘다). 받기는 관문
 * 전에도 돈다 — 값을 안 바꾼다. 공시 하나의 실패는 그 공시만 남기고 다음 실행이 다시 묻는다. 평일 13:30 사슬의 게시 시각 뒤 · 일일 갱신
 * 앞에 돈다(일일 갱신이 그 해석으로 잠정 행 · 실적 발표 판정을 짓는다).
 */
@Component
public class KrProvisionalJob {

    public static final String JOB_NAME = "kr-provisional";

    /** 정기 실행이 한 번에 받는 공시 상한 — 밀린 과거가 13:30 사슬을 막지 않게(공시당 2콜 · 1초 1건 · 하루 새 발표는 대개 수십). */
    public static final int SCHEDULED_FETCH_LIMIT = 150;

    private static final Logger log = LoggerFactory.getLogger(KrProvisionalJob.class);

    private final DartViewerClient client;
    private final KrProvisionalStore store;
    private final BatchExecutions batchExecutions;

    public KrProvisionalJob(DartViewerClient client, KrProvisionalStore store, BatchExecutions batchExecutions) {
        this.client = client;
        this.store = store;
        this.batchExecutions = batchExecutions;
    }

    /** @param fetchLimit 이 실행이 받을 공시 상한(최근 접수부터) — 남은 공시는 다음 실행이 잇는다 */
    public BatchStatus run(LocalDate todayKst, BatchExecutionRecorder.TriggeredBy triggeredBy, int fetchLimit) {
        List<KrProvisionalStore.Release> missing = store.releases().stream().filter(r -> !store.cached(r.rceptNo())).toList();
        List<KrProvisionalStore.Release> targets = missing.subList(0, Math.min(missing.size(), fetchLimit));
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, todayKst, triggeredBy, targets.size(),
                BatchExecutionRecorder.DeclaredScope.FULL);
        execution.plan(CollectionPhase.FILING_LISTING, targets.size());
        int succeeded = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (KrProvisionalStore.Release r : targets) {
                execution.stage("공시 본문 " + r.rceptNo());
                try {
                    store.writeCache(r.rceptNo(), client.document(r.rceptNo()));
                    succeeded++;
                } catch (DataSourceException e) {
                    failed++;
                    failures.add(r.rceptNo() + ": " + e.getMessage());
                    log.warn("공시 본문을 못 받았다 — 다음 실행이 다시 묻는다. rcept_no={} {}", r.rceptNo(), e.getMessage());
                }
            }
            String loaded = "";
            if (store.gateOn()) {
                int[] n = store.loadAll();
                loaded = " · 표에 올림 %d(못 읽음 %d)".formatted(n[0], n[1]);
            }
            if (failed > 0) {
                execution.recordUnfinishedListing("KR", "공시 본문 " + failed + "건 실패");
            }
            execution.countExternally(CollectionPhase.FILING_LISTING, succeeded, failed, succeeded);
            return execution.finish("받은 공시 %d · 실패 %d · 남은 공시 %d%s%s".formatted(succeeded, failed,
                    missing.size() - targets.size(), loaded,
                    failures.isEmpty() ? "" : " · " + String.join(" | ", failures.subList(0, Math.min(5, failures.size())))));
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
    }
}
