package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.GovernanceCollector;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 정기보고서 주요정보 여덟을 받아 원문으로 쌓는다(ADR-0081).
 *
 * <p>MUST NOT: 이 잡을 관리자 화면에 올리지 않는다. 전량이 <b>보수 예산 10,000의 절반을 넘어</b>
 * {@code AdminJobLauncher}가 {@code InitialLoadJob}을 뺀 근거에 정확히 걸린다. 유도 잡은
 * 외부 호출이 0이라 화면에 올려도 무해하다.
 *
 * <p>MUST: 사업보고서({@code 11011})만 부른다. 보고서 넷을 다 부르면 같은 일에 콜이 네 배가
 * 되고 <b>그 네 배는 아무 에러 없이 다른 잡의 여유를 먹는다</b> — 우리 리미터가 DART 전체를
 * 버킷 하나로 세기 때문이다.
 *
 * <p>표마다 <b>받을 해가 다르다.</b> 근거는 실측이다(§16-C) — 배당은 한 응답이 3개년을 열로
 * 주고, 최대주주 변동은 요청 연도까지의 누적이라 한 해면 되며, 이익소각은 상장 이래 누적이라
 * 3년 차분에 끝점 둘이 필요해 넉 해를 받는다.
 */
@Component
public class GovernanceLoadJob {

    public static final String JOB_NAME = "governance-load";

    /** 사업보고서. §5의 MUST가 이 하나로 좁히는 근거다. */
    public static final String ANNUAL_REPORT = "11011";

    private static final Logger log = LoggerFactory.getLogger(GovernanceLoadJob.class);

    /**
     * 표마다 «최신 해에서 몇 해 뒤까지» 받는가. 근거는 §16-C의 실측이다.
     *
     * <p>MUST: 이 표를 바꾸면 콜 수가 함께 바뀐다. 지금 350종목 기준 <b>5,950콜</b>이고
     * 보수 예산 10,000의 59.5%다.
     */
    private static final Map<GovernanceEndpoint, Integer> YEARS_BACK =
            new EnumMap<>(Map.of(
                    // 이익소각이 상장 이래 누적이라 3년 차분에 끝점 둘(Y-3·Y)이 필요하다.
                    GovernanceEndpoint.SHARE_TOTALS, 4,
                    // 한 응답이 3개년을 열로 준다.
                    GovernanceEndpoint.DIVIDEND, 1,
                    // 요청한 해만 준다(가이드의 fscl_year는 실측에서 '-'였다). 수준 지표뿐이라 둘이면 된다.
                    GovernanceEndpoint.EXEC_PAY, 2,
                    // 3년 합이 필요하다.
                    GovernanceEndpoint.OUTSIDE_DIRECTOR, 3,
                    GovernanceEndpoint.LARGEST_HOLDER, 2,
                    GovernanceEndpoint.MINORITY_HOLDER, 2,
                    // 요청 연도까지의 누적이라 최신 해 하나면 창을 우리가 자를 수 있다.
                    GovernanceEndpoint.CONTROL_CHANGE, 1,
                    // 1콜이 3개년이라 두 번이면 여섯 해가 온다(감사인 교체의 비교가 셋 선다).
                    GovernanceEndpoint.AUDIT_OPINION, 4));

    /** {@link GovernanceEndpoint#AUDIT_OPINION}처럼 한 응답이 3개년을 주는 표의 호출 간격. */
    private static final Map<GovernanceEndpoint, Integer> YEAR_STRIDE =
            new EnumMap<>(Map.of(
                    GovernanceEndpoint.DIVIDEND, 3,
                    GovernanceEndpoint.AUDIT_OPINION, 3));

    private final GovernanceCollector collector;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;

    public GovernanceLoadJob(GovernanceCollector collector, StockRegistrar stockRegistrar,
                             BatchExecutions batchExecutions) {
        this.collector = collector;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
    }

    /**
     * @param latestFiscalYear 가장 최근 <b>사업보고서가 나온</b> 회계연도. 비면 {@code asOf}의
     *                         전년이다 — 사업보고서는 이듬해 3월에 나오므로 올해분은 아직 없다
     * @param endpoints        비면 여덟 전부. <b>나눠 돌릴 때 이것으로 좁힌다</b> — 없으면 두 번째
     *                         실행이 3개년을 주는 표를 다시 불러 콜이 조용히 는다
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           Integer latestFiscalYear, List<GovernanceEndpoint> endpoints,
                           List<String> tickers) {
        int latest = latestFiscalYear != null ? latestFiscalYear : asOf.getYear() - 1;
        List<GovernanceEndpoint> targets = endpoints == null || endpoints.isEmpty()
                ? List.of(GovernanceEndpoint.values()) : endpoints;
        List<SecurityRef> stocks = stockRegistrar.toSecurityRefs(resolveTargets(tickers));
        if (stocks.isEmpty()) {
            log.info("거버넌스를 받을 종목이 없다.");
            return batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0).finish();
        }

        Map<GovernanceEndpoint, List<Integer>> yearsByEndpoint = new EnumMap<>(GovernanceEndpoint.class);
        int expectedCalls = 0;
        for (GovernanceEndpoint endpoint : targets) {
            List<Integer> years = yearsFor(endpoint, latest);
            yearsByEndpoint.put(endpoint, years);
            expectedCalls += years.size() * stocks.size();
        }
        log.info("거버넌스 수집을 시작한다. 대상 {}종목 × 표 {}개, 예상 호출 {}회 (보수 예산 10,000의 {}%)",
                stocks.size(), targets.size(), expectedCalls, expectedCalls / 100);

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, stocks.size());
        execution.plan(CollectionPhase.GOVERNANCE, stocks.size());
        execution.stage("종목별 정기보고서 주요정보 수집");

        Set<Integer> touchedYears = new LinkedHashSet<>();
        yearsByEndpoint.values().forEach(touchedYears::addAll);

        try {
            for (SecurityRef stock : stocks) {
                execution.process(stock, CollectionPhase.GOVERNANCE, security -> {
                    long written = 0;
                    for (GovernanceEndpoint endpoint : targets) {
                        written += collector.collect(security, endpoint,
                                yearsByEndpoint.get(endpoint), ANNUAL_REPORT);
                    }
                    // 여덟을 다 받은 뒤라야 「비어 온 표」에 같은 보고서의 접수번호를 찍을 수 있다.
                    for (int year : touchedYears) {
                        collector.stampNoDataFromSiblings(security.stockId(), year, ANNUAL_REPORT);
                    }
                    return written;
                });
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }

        long unstamped = collector.unstampedNoDataRows();
        if (unstamped > 0) {
            // 그 종목·그 해는 여덟이 다 비었다는 뜻이다. 시점을 모르므로 지표가 그 사실을 못 쓴다.
            log.info("접수번호를 못 찍은 '데이터 없음' 행이 {}건이다 — 그 종목의 그 해는 여덟이 다 비었다.",
                    unstamped);
        }
        return execution.finish();
    }

    /**
     * 그 표에서 받을 회계연도. 한 응답이 3개년을 주는 표는 <b>세 해씩 건너뛴다.</b>
     *
     * <p>안 건너뛰면 같은 3개년을 세 번 받아 콜이 세 배가 되는데, 결과가 같아 <b>어디에도 안 뜬다.</b>
     */
    static List<Integer> yearsFor(GovernanceEndpoint endpoint, int latestFiscalYear) {
        int span = YEARS_BACK.getOrDefault(endpoint, 1);
        int stride = YEAR_STRIDE.getOrDefault(endpoint, 1);
        List<Integer> years = new ArrayList<>();
        for (int offset = 0; offset < span; offset += stride) {
            years.add(latestFiscalYear - offset);
        }
        return years;
    }

    private List<Long> resolveTargets(List<String> tickers) {
        if (tickers != null && !tickers.isEmpty()) {
            List<Long> matched = stockRegistrar.activeUniverseMemberIdsByTicker(tickers);
            if (matched.isEmpty()) {
                throw new IllegalArgumentException(
                        "준 티커 중 편입 중인 활성 종목이 하나도 없다: " + tickers
                                + " — 종목코드를 확인하라. 대상 0건으로 성공시키지 않는다.");
            }
            return matched;
        }
        return stockRegistrar.activeUniverseMemberIds();
    }
}
