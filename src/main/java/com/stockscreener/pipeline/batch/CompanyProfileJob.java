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
 *
 * <p><b>{@code --universe}는 비활성 유니버스를 손으로 고르는 경로다</b>(ADR-0090의 두 잡과
 * 같은 형태). 미국 업종이 그 경로로만 닿는다 — {@code US_LISTED}가 비활성이라 기본 대상
 * 질의의 {@code universe.is_active} 조인에 한 건도 안 걸리기 때문이다.
 * <b>MUST NOT: 이 갈래를 스케줄러·{@link AdminJobLauncher}에 걸지 않는다</b> — 자동 실행은
 * 활성 유니버스만 돈다.
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
     * @param universeCode 값이 있으면 <b>그 유니버스의 편입 종목</b>이 대상이고 활성 여부를
     *                     보지 않는다. 자동 실행에서는 언제나 null이다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           List<String> tickers, String universeCode) {
        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(
                universeCode == null || universeCode.isBlank()
                        ? resolveTargets(tickers)
                        : resolveUniverseTargets(universeCode, tickers));
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

    /**
     * 유니버스를 손으로 골랐을 때의 대상(ADR-0092 · ADR-0090의 두 잡과 같은 형태).
     *
     * <p>MUST: <b>0건이면 예외로 끝낸다.</b> {@code oneUniverseMemberIds}는 활성 여부를 보지
     * 않는 대신 <b>예외를 던지지 않고 빈 목록을 낸다</b> — 그 자리를 여기서 채우지 않으면
     * 위쪽 {@code targets.isEmpty()} 갈래로 빠져 <b>SUCCESS로 끝난다.</b>
     * <b>이 잡이 그 사고에 가장 취약하다</b>: 업종은 원래 재실행에서 0행이 정상이라
     * 위 {@code resolveTargets}가 티커 경로에 이미 같은 방어를 두었고, 그 이유가 여기에도
     * 그대로 걸린다. 조용한 0건은 "채울 것이 없다"와 구별되지 않는다(ADR-0088 §규칙).
     */
    private List<Long> resolveUniverseTargets(String universeCode, List<String> tickers) {
        List<String> requested = tickers == null ? List.of() : tickers;
        List<Long> members = stockRegistrar.oneUniverseMemberIds(universeCode, requested);
        if (members.isEmpty()) {
            throw new IllegalArgumentException(
                    "유니버스 '" + universeCode + "'에 대상이 하나도 없다"
                            + (requested.isEmpty() ? "" : " (티커 " + requested + ")")
                            + " — 유니버스 코드와, 그 시장이 CollectedMarkets에 있는지 확인하라."
                            + " 대상 0건으로 성공시키지 않는다.");
        }
        log.info("유니버스 {} 편입 {}종목이 대상이다. 활성 여부와 업종 결손을 보지 않았다.",
                universeCode, members.size());
        return members;
    }
}
