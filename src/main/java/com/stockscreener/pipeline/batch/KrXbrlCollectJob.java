package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.KrEarlierVintages;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrXbrlCollector;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrXbrlStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 국내 보통주 몫 재료 받기(ADR-0192) — 국내 재무 행이 있는 종목 전원의 판마다 원본 재료와 주식총수 명부를 채운다.
 *
 * <p>재료 표 둘과 디스크 캐시를 쓰고, 판정(보통주 몫)은 국내 관문 뒤 유도가 이 재료를 읽는다. <b>예외 하나가 값을 바꾼다</b> — 재료를 받은
 * 그 종목 트랜잭션 안에서 「같은 숫자를 처음 공개한 판」을 넣는다({@link KrEarlierVintages} · ADR-0195): DART 재무 API가 가장 늦은 판만
 * 주어 밀린 첫 공시일을 원본 접수일로 바로잡는다. 재료가 둘 다 있어야 판정되므로 재료를 받는 이 자리가 그 순서의 정본이다. 정기 실행(평일 14:16 사슬 끝)은 한 판에 새로 받는 수를
 * {@code stockscreener.kr-xbrl.max-fetches-per-run}으로 묶는다 — 처음 한 번 밀린 약 14,000판을 한 오후에 몰면 14:18 · 14:20을 민다.
 */
@Component
public class KrXbrlCollectJob {

    public static final String JOB_NAME = "kr-xbrl-collect";

    static final List<String> MARKETS = List.of("KOSPI", "KOSDAQ");

    private static final Logger log = LoggerFactory.getLogger(KrXbrlCollectJob.class);

    private final KrXbrlCollector collector;
    private final KrXbrlStore store;
    private final KrEarlierVintages earlierVintages;
    private final BatchExecutions batchExecutions;
    private final int scheduledBudget;

    public KrXbrlCollectJob(KrXbrlCollector collector, KrXbrlStore store, BatchExecutions batchExecutions,
                            @Value("${stockscreener.kr-xbrl.max-fetches-per-run}") int scheduledBudget,
                            KrEarlierVintages earlierVintages) {
        this.earlierVintages = earlierVintages;
        this.collector = collector;
        this.store = store;
        this.batchExecutions = batchExecutions;
        this.scheduledBudget = scheduledBudget;
    }

    /**
     * @param maxFetches 이번 실행에 새로 받을 판의 상한 — {@code null}이면 정기 상한, 0 이하면 상한 없음(수동 전량)
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy, List<String> tickers,
                           Integer maxFetches) {
        List<KrXbrlStore.Target> targets = collector.targets(tickers);
        if (tickers != null && !tickers.isEmpty() && targets.isEmpty()) {
            throw new IllegalArgumentException("준 티커 중 국내 재무 행이 있는 종목이 하나도 없다: " + tickers
                    + " — 대상 0건으로 성공시키지 않는다.");
        }
        return run(asOf, triggeredBy, tickers, maxFetches, targets);
    }

    /**
     * 리밸런싱 꼬리(편입 결손 보충)가 방금 재무를 채운 종목 — 국내 종목만 받는다(상한 없음 · 몇 종목뿐). 국내가 없으면 실행 행 없이
     * {@code null}이다(미국만 채운 주는 이 잡의 일이 아니다).
     */
    public BatchStatus runForLoadedStatements(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy, List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return null;
        }
        List<KrXbrlStore.Target> targets = collector.targets(tickers);
        return targets.isEmpty() ? null : run(asOf, triggeredBy, tickers, 0, targets);
    }

    private BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy, List<String> tickers,
                            Integer maxFetches, List<KrXbrlStore.Target> targets) {
        boolean narrowed = tickers != null && !tickers.isEmpty();
        BatchExecutionRecorder.DeclaredScope scope = BatchExecutionRecorder.DeclaredScope.byArgs(!narrowed && maxFetches == null);
        MarketCompleteness.Coverage coverage = MarketCompleteness.Coverage.of(MARKETS, market -> collector.hasSource());
        if (targets.isEmpty() || !collector.hasSource()) {
            log.info("국내 보통주 몫 재료를 받을 종목이 없거나 소스가 없다. 대상 {}종목", targets.size());
            return batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0, scope, coverage).finish();
        }
        int budget = maxFetches == null ? scheduledBudget : maxFetches <= 0 ? Integer.MAX_VALUE : maxFetches;
        AtomicInteger remaining = new AtomicInteger(budget);
        log.info("국내 보통주 몫 재료를 받는다. 대상 {}종목 · 새로 받기 상한 {} · 미해결(시작) {}",
                targets.size(), budget == Integer.MAX_VALUE ? "없음" : budget, store.unresolved());
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size(), scope, coverage);
        execution.plan(CollectionPhase.XBRL_MATERIAL, targets.size());
        execution.stage("종목별 판 재료 · 주식총수 명부");
        AtomicInteger fetched = new AtomicInteger();
        AtomicInteger reread = new AtomicInteger();
        AtomicInteger noFile = new AtomicInteger();
        AtomicInteger registers = new AtomicInteger();
        AtomicInteger unbound = new AtomicInteger();
        AtomicInteger earlier = new AtomicInteger();
        try {
            for (KrXbrlStore.Target target : targets) {
                SecurityRef security = new SecurityRef(target.stockId(), target.market(), target.ticker());
                execution.process(security, CollectionPhase.XBRL_MATERIAL, s -> {
                    KrXbrlCollector.Collected c = collector.collect(target, asOf, remaining);
                    fetched.addAndGet(c.fetched());
                    reread.addAndGet(c.reread());
                    noFile.addAndGet(c.noFile());
                    registers.addAndGet(c.registers());
                    unbound.addAndGet(c.unbound());
                    int inserted = earlierVintages.insert(target.stockId());
                    earlier.addAndGet(inserted);
                    return c.fetched() + c.reread() + c.noFile() + c.registers() + inserted;
                });
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        String note = "받음=%d 캐시재판독=%d 원본없음=%d 명부=%d 짝없는판=%d 이른판=%d 남은상한=%s 미해결=%d".formatted(fetched.get(),
                reread.get(), noFile.get(), registers.get(), unbound.get(), earlier.get(),
                budget == Integer.MAX_VALUE ? "없음" : String.valueOf(remaining.get()), store.unresolved());
        log.info("국내 보통주 몫 재료 받기를 마쳤다. {}", note);
        return execution.finish(note);
    }
}
