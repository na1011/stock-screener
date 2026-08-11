package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.CompanyProfileCollector;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 편입 종목의 업종(KSIC)과 결산월을 채운다. 종목당 1콜이다.
 *
 * <p>업종은 거의 바뀌지 않으므로 <b>초기 1회 + 신규 편입 때만</b> 돈다. 그래서 일일 갱신에
 * 붙이지 않았다 — 매일 350콜을 태우면서 거의 아무것도 바꾸지 않는다.
 *
 * <p>MUST NOT: {@link UniverseRebalanceJob}에 합치지 않는다. 그 잡은 <b>종목 단위 실패 격리를
 * 일부러 쓰지 않는다</b> — 유니버스는 전체가 하나의 정합적 스냅샷이라 부분 적용이 의미가 없기
 * 때문이다. 거기에 종목당 외부 호출을 얹으면 DART 실패 하나가 지수 갱신 전체를 되돌린다.
 * 대신 리밸런싱 뒤에 이 잡을 이어 돌린다({@code batch-load} skill).
 *
 * <p>기본 대상이 <b>업종이 비어 있는 종목만</b>인 이유는 재실행을 싸게 만들기 위해서다.
 * 전량 갱신이 필요하면 {@code --tickers}로 지정한다.
 */
@Component
public class CompanyProfileJob {

    public static final String JOB_NAME = "company-profile";

    private static final Logger log = LoggerFactory.getLogger(CompanyProfileJob.class);

    private final CompanyProfileCollector collector;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;

    public CompanyProfileJob(CompanyProfileCollector collector, StockRegistrar stockRegistrar,
                             BatchExecutions batchExecutions) {
        this.collector = collector;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
    }

    /**
     * 업종이 비어 있는 편입 종목의 개황을 받는다.
     *
     * @param tickers 비면 <b>업종이 비어 있는 편입 종목 전체</b>. 값이 있으면 편입 종목과의
     *                교집합을 <b>업종 유무와 무관하게</b> 다시 받는다 — 손으로 지정하는 것은
     *                "다시 받아라"는 뜻이므로 결손 필터를 걸면 아무 일도 하지 않는다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           List<String> tickers) {
        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(resolveTargets(tickers));
        if (targets.isEmpty()) {
            log.info("업종을 채울 종목이 없다. 편입 종목 전부에 업종이 이미 있다.");
            return batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0).finish();
        }

        // 종목당 1콜이다. 착수 전에 쿼터를 가늠할 수 있어야 한다.
        log.info("기업개황 수집을 시작한다. 대상 {}종목, 예상 호출 {}회", targets.size(), targets.size());

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size());
        execution.plan(CollectionPhase.SECURITY_MASTER, targets.size());
        execution.stage("종목별 업종 수집");

        try {
            for (SecurityRef target : targets) {
                execution.process(target, CollectionPhase.SECURITY_MASTER, collector::collect);
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        return execution.finish();
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * 대상 0건으로 조용히 성공하면 오타 하나가 "돌렸는데 아무것도 안 바뀌었다"로 끝나는데,
     * 업종은 원래 재실행에서 0행이 정상이라 <b>그 둘이 로그에서 구분되지 않는다.</b>
     */
    private List<Long> resolveTargets(List<String> tickers) {
        if (tickers != null && !tickers.isEmpty()) {
            List<Long> matched = stockRegistrar.activeUniverseMemberIdsByTicker(tickers);
            if (matched.isEmpty()) {
                throw new IllegalArgumentException(
                        "준 티커 중 편입 중인 활성 종목이 하나도 없다: " + tickers
                                + " — 종목코드를 확인하라. 대상 0건으로 성공시키지 않는다.");
            }
            if (matched.size() < tickers.size()) {
                log.warn("티커 {}개 중 {}개만 편입 중인 활성 종목이다. 나머지는 건너뛴다: {}",
                        tickers.size(), matched.size(), tickers);
            }
            return matched;
        }
        return stockRegistrar.activeUniverseMemberIdsWithoutIndustry();
    }
}
