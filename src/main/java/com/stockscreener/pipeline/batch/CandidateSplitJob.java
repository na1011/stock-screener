package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.CorporateActionCollector;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.us.AlpacaCorporateActionAdapter;
import com.stockscreener.pipeline.datasource.us.UsMarket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 미국 <b>순위 후보</b>의 분할·병합을 원장에 적는다(ADR-0178) — 평일 14:00 사슬에서 기업 행위 수집 뒤 · 조정 재구축 앞에 돈다.
 *
 * <p><b>왜</b>: 순위는 저장 주식수를 그 판의 공시일에서 기준일까지 원장의 {@code APPLIED} 분할로 옮긴다
 * ({@code JdbcStoredShareCounts.SHIFT_TO_ASOF}). 원장은 보존 창 편입만 채우므로 후보는 공시 뒤 분할이 안 실리고, 병합 전 주식수 ×
 * 병합 뒤 종가가 그럴듯한 시총으로 나온다 — BYND가 1:30 병합 전 주식수로 30배 잡혀 잘못 편입된 꼴이다. 2026-10-15에 후보 194종목이
 * 그 상태였다(후보 계획 v2 §1 · Alpaca 시장 전체 2콜).
 *
 * <p><b>무엇을 적나</b>: 시장 전체 분할·병합({@link AlpacaCorporateActionAdapter#fetchMarketSplits} · 1~3콜)을 지금 심볼로 묶고, 순위가
 * 읽는 바로 그 판독기({@link StoredShareCounts#activeByMarket})의 후보 중 <b>보존 창 편입이 아닌</b> 종목에, 그 판의 공시일 뒤 ·
 * 기준일 이하 사건만 적는다 — {@code SHIFT_TO_ASOF}의 앵커와 같다. 편입 종목은 기업 행위 수집이 전 종류를 담는다. 분사는 안 담는다 —
 * 원장의 분사 {@code ratio}는 주식 비율(1)이라 순위에 안 닿고, 후보는 봉이 없어 가치 판정이 미해결로 남는다.
 *
 * <p>적는 경로는 {@link CorporateActionCollector#recordAll} 하나다(근접중복·상태 규칙 그대로) — 공시는 {@code APPROVED}로 들어가고,
 * 같은 사슬의 조정 재구축이 그 종목을 대상에 넣어({@code StockRegistrar.unappliedApprovalIdsOutsideWindow}) {@code APPLIED}로 올린다.
 * 봉이 없으면 다시 쓸 시세가 없고 표시만 한다.
 */
@Component
public class CandidateSplitJob {

    public static final String JOB_NAME = "candidate-splits";

    private static final Logger log = LoggerFactory.getLogger(CandidateSplitJob.class);

    /** 순위 판독기의 주식수 최신성 경계({@code UsMarketCapUniverseAdapter.SHARES_MAX_AGE_YEARS})와 같다. */
    static final int SHARES_MAX_AGE_YEARS = 2;

    private final AlpacaCorporateActionAdapter source;
    private final StoredShareCounts shareCounts;
    private final StockRegistrar stockRegistrar;
    private final CorporateActionCollector collector;
    private final BatchExecutions batchExecutions;

    @Autowired
    public CandidateSplitJob(ObjectProvider<AlpacaCorporateActionAdapter> source, StoredShareCounts shareCounts,
                             StockRegistrar stockRegistrar, CorporateActionCollector collector,
                             BatchExecutions batchExecutions) {
        this(source.getIfAvailable(), shareCounts, stockRegistrar, collector, batchExecutions);
    }

    CandidateSplitJob(AlpacaCorporateActionAdapter source, StoredShareCounts shareCounts, StockRegistrar stockRegistrar,
                      CorporateActionCollector collector, BatchExecutions batchExecutions) {
        this.source = source;
        this.shareCounts = shareCounts;
        this.stockRegistrar = stockRegistrar;
        this.collector = collector;
        this.batchExecutions = batchExecutions;
    }

    /** 적을 종목 하나 — 원장에 갈 사건, 또는 소스 레코드가 깨져 그 심볼을 실패로 남길 예외. */
    private record Target(SecurityRef security, List<CorporateAction> actions, DataSourceException malformed) {
    }

    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        // 대상 수를 실행 행에 바로 싣도록 조회·계획을 먼저 한다(대상 0으로 열면 「성공 + 실패 = 대상」이 깨진다 — D4).
        // 조회가 실패하면 그 자리에서 실행 행을 열어 치명 실패로 남긴다 — 조용히 아무 행도 없이 끝나지 않게.
        List<Target> plan;
        try {
            plan = planFromSource(asOf);
        } catch (DataSourceException e) {
            batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0, BatchExecutionRecorder.DeclaredScope.FULL)
                    .failFatally(e);
            return BatchStatus.FAILED;
        }
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, asOf, triggeredBy, plan.size(),
                BatchExecutionRecorder.DeclaredScope.FULL);
        List<CorporateActionCollector.NearDuplicate> conflicts = new ArrayList<>();
        try {
            execution.plan(CollectionPhase.ADJUSTMENT, plan.size());
            execution.stage("후보 분할 기록");
            for (Target target : plan) {
                execution.process(target.security(), CollectionPhase.ADJUSTMENT, security -> {
                    if (target.malformed() != null) {
                        throw target.malformed();
                    }
                    CorporateActionCollector.Result result = collector.recordAll(security, target.actions());
                    conflicts.addAll(result.nearDuplicates());
                    return result.recorded();
                });
            }
            if (!conflicts.isEmpty()) {
                StringBuilder body = new StringBuilder("%d건 — 원장의 근접중복 창 안에 다른 날짜 행이 있어 적지 않았다. 사람이 고른다(--job=corporate-action-review):\n"
                        .formatted(conflicts.size()));
                conflicts.stream().limit(10).forEach(c -> body.append("  %s %s ↔ 기존 %s (%s)\n".formatted(c.ticker(),
                        c.proposed(), c.existing(), c.source())));
                execution.alertOperator(AlertSeverity.WARNING, "후보 분할이 원장의 근접중복에 걸렸다", body.toString());
            }
        } catch (DataSourceException e) {
            // 종목 루프의 치명 실패(인증 등) — 종목 실패가 아니라 실행을 끝낸다.
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        return execution.finish("공시일 뒤 분할이 있는 후보 %d종목 · 기록 %d · 근접중복 %d".formatted(plan.size(),
                execution.writtenRowCount(), conflicts.size()));
    }

    /** 순위 판독기의 후보 → 시장 전체 분할 조회 → 적을 종목. 후보 주식수가 없으면 부르지 않고 빈 계획이다. */
    private List<Target> planFromSource(LocalDate asOf) throws DataSourceException {
        if (source == null) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, "alpaca:corporate-action",
                    "Alpaca 키가 없어 기업 행위 어댑터가 등록되지 않았다 — 후보 분할을 못 받는다.");
        }
        List<StoredShareCounts.ShareCount> counts = shareCounts.activeByMarket(UsMarket.CODE,
                asOf.minusYears(SHARES_MAX_AGE_YEARS), asOf);
        if (counts.isEmpty()) {
            log.info("후보 분할: 순위 판독기에 미국 주식수가 없다 — 조회하지 않는다.");
            return List.of();
        }
        LocalDate from = counts.stream().map(StoredShareCounts.ShareCount::publishedAt).min(LocalDate::compareTo)
                .orElseThrow().plusDays(1);
        if (from.isAfter(asOf)) {
            return List.of();
        }
        return plan(counts, source.fetchMarketSplits(from, asOf), asOf);
    }

    /**
     * 순위 후보 중 보존 창 편입이 아니고, 그 판의 공시일 뒤 · 기준일 이하 분할이 있는 종목. 소스 레코드가 깨진 심볼은 사건 대신 예외를
     * 싣는다(그 심볼의 다른 사건도 믿지 않는다).
     */
    private List<Target> plan(List<StoredShareCounts.ShareCount> counts, AlpacaCorporateActionAdapter.MarketSplits splits,
                              LocalDate asOf) {
        Set<Long> window = new HashSet<>(stockRegistrar.windowMemberIds());
        Map<String, LocalDate> anchors = new LinkedHashMap<>();
        for (StoredShareCounts.ShareCount count : counts) {
            String ticker = count.ticker();
            if (splits.malformed().containsKey(ticker)) {
                anchors.put(ticker, count.publishedAt());
                continue;
            }
            List<CorporateAction> events = splits.bySymbol().getOrDefault(ticker, List.of());
            if (events.stream().anyMatch(a -> a.effectiveDate().isAfter(count.publishedAt())
                    && !a.effectiveDate().isAfter(asOf))) {
                anchors.put(ticker, count.publishedAt());
            }
        }
        if (anchors.isEmpty()) {
            return List.of();
        }
        Map<String, Long> ids = stockRegistrar.existingIdsByTicker(UsMarket.CODE, List.copyOf(anchors.keySet()));
        List<Long> candidates = ids.values().stream().filter(id -> !window.contains(id)).sorted().toList();
        List<Target> plan = new ArrayList<>();
        for (SecurityRef security : stockRegistrar.toSecurityRefs(candidates)) {
            LocalDate anchor = anchors.get(security.ticker());
            DataSourceException malformed = splits.malformed().get(security.ticker());
            List<CorporateAction> actions = malformed != null ? List.of()
                    : splits.bySymbol().get(security.ticker()).stream()
                            .filter(a -> a.effectiveDate().isAfter(anchor) && !a.effectiveDate().isAfter(asOf))
                            .toList();
            plan.add(new Target(security, actions, malformed));
        }
        log.info("후보 분할: 공시일 뒤 분할이 있는 순위 후보 {} · 그중 창 편입이라 뺀 것 {} · 깨진 레코드 심볼 {}", anchors.size(),
                anchors.size() - plan.size(), plan.stream().filter(t -> t.malformed() != null).count());
        return plan;
    }
}
