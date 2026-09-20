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
import java.util.LinkedHashMap;
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
 * <p>표마다 <b>받을 해가 다르다.</b> 근거는 실측이다(dart-governance.md의 「배당」 절) — 배당은 한 응답이
 * 3개년을 열로
 * 주고, 최대주주 변동은 요청 연도까지의 누적이라 한 해면 되며, 이익소각은 상장 이래 누적이라
 * 3년 차분에 끝점 둘이 필요해 넉 해를 받는다.
 */
@Component
public class GovernanceLoadJob {

    public static final String JOB_NAME = "governance-load";

    /** 사업보고서. 보고서 코드를 이 하나로 좁히는 근거는 {@code batch-load} skill에 있다(넷을 다 부르면 콜이 4배다). */
    public static final String ANNUAL_REPORT = "11011";

    private static final Logger log = LoggerFactory.getLogger(GovernanceLoadJob.class);

    /**
     * 표마다 «최신 해에서 몇 해 뒤까지» 받는가. 근거는 dart-governance.md의 실측이다.
     *
     * <p>MUST: 이 표를 바꾸면 콜 수가 함께 바뀐다. 지금 350종목 기준 <b>8,050콜</b>이고
     * 보수 예산 10,000의 81%다. **막는 값은 `dart.daily-quota: 18000`이고 10,000은 API당 계획값이다.**
     */
    private static final Map<GovernanceEndpoint, Integer> YEARS_BACK =
            new EnumMap<>(Map.of(
                    // 이익소각이 상장 이래 누적이라 3년 차분에 끝점 둘(Y-3·Y)이 필요하고,
                    // 창 앞부분에서도 그 차분이 서려면 한 해가 더 필요하다.
                    GovernanceEndpoint.SHARE_TOTALS, 5,
                    // 한 응답이 3개년을 열로 주지만 **세 해를 부른다.**
                    // 둘인 이유(국내): 한 번만 부르면 FY2024 배당을 2026년 3월에야 알게 된 것이
                    // 되어 창 앞 137거래일이 통째로 빈다(2026-09-08 실측).
                    // 셋인 이유(미국): 여기서 bsnsYear는 ex_date의 역년이라 52/53주 회계연도가
                    // **세 역년에 걸친다** — SNA의 FY2025가 2024-12-29~2026-01-03이고
                    // coversWholeWindow가 그 셋을 다 요구한다. 두 해면 최신 회계연도가 나오는
                    // 종목이 879, 세 해면 994다(2026-09-20 실측 · 편입 1,000).
                    //
                    // **MUST NOT: 미국이 창 앞부분에 더 필요한 한 해를 여기에 더하지 않는다.**
                    // 이 표는 시장 공용이라 국내에도 같은 해가 붙는데, 국내에서는 **해로운 값**이
                    // 생긴다 — 2026-09-20 탐침으로 DART는 옛 역년을 물어도 **정정본의 접수번호**를
                    // 주고(010130·000880·161580), 정정이 없던 종목만 원본 공시일을 돌려준다
                    // (006360의 bsns_year=2022 → 20230316001014). 그러면 figuresOn이 창 앞에서
                    // **두세 해 낡은 배당**을 고른다. 결측이 아니라 그럴듯하게 틀린 값이고
                    // A41이 방금 닫은 결손과 같은 형태다.
                    // 미국 몫은 GovernanceDataSource.extraCalendarYears가 시장마다 더한다.
                    GovernanceEndpoint.DIVIDEND, 3,
                    // 요청한 해만 준다(가이드의 fscl_year는 실측에서 '-'였다).
                    GovernanceEndpoint.EXEC_PAY, 2,
                    // 3년 합이 창 앞부분에서도 서려면 넉 해가 필요하다.
                    GovernanceEndpoint.OUTSIDE_DIRECTOR, 4,
                    GovernanceEndpoint.LARGEST_HOLDER, 2,
                    GovernanceEndpoint.MINORITY_HOLDER, 2,
                    // 요청 연도까지의 누적이라 창은 우리가 자르지만, 창 앞부분에서도 값이 서려면
                    // 그 시점에 이미 나와 있던 보고서가 필요하다.
                    GovernanceEndpoint.CONTROL_CHANGE, 2,
                    // 1콜이 3개년이라 세 번이면 아홉 해가 온다.
                    GovernanceEndpoint.AUDIT_OPINION, 7));

    /** {@link GovernanceEndpoint#AUDIT_OPINION}처럼 한 응답이 3개년을 주는 표의 호출 간격. */
    private static final Map<GovernanceEndpoint, Integer> YEAR_STRIDE =
            new EnumMap<>(Map.of(GovernanceEndpoint.AUDIT_OPINION, 3));

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
     * @param span             비면 표마다 정해진 해 수. 값이 있으면 <b>그것으로 덮는다</b> —
     *                         이미 받아 둔 해에 한 해만 얹을 때 쓴다. 없으면 그 한 해를 위해
     *                         표 전체를 다시 받아야 하고 콜이 몇 배가 된다
     * @param endpoints        비면 여덟 전부. <b>나눠 돌릴 때 이것으로 좁힌다</b> — 없으면 두 번째
     *                         실행이 3개년을 주는 표를 다시 불러 콜이 조용히 는다
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           Integer latestFiscalYear, Integer span,
                           List<GovernanceEndpoint> endpoints, List<String> tickers) {
        List<GovernanceEndpoint> targets = endpoints == null || endpoints.isEmpty()
                ? List.of(GovernanceEndpoint.values()) : endpoints;
        List<SecurityRef> stocks = stockRegistrar.toSecurityRefs(resolveTargets(tickers));
        if (stocks.isEmpty()) {
            log.info("거버넌스를 받을 종목이 없다.");
            return batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0).finish();
        }

        // 시장마다 답하는 표가 다르고 <b>받을 역년도 다르다.</b> 표를 안 좁히면 소스가 안 주는
        // 표에 거짓 「데이터 없음」이 쌓이고, 역년을 안 가르면 bsnsYear의 뜻이 다른 소스에
        // 남의 규칙이 실린다(GovernanceDataSource.latestRequestableYear).
        //
        // MUST: 둘 다 종목 루프 밖에서 시장마다 한 번만 정한다. 종목마다 물으면 1,350번
        // 같은 답을 받고, 소스가 없는 시장에서는 경고가 1,350줄 쌓여 진짜 장애가 묻힌다.
        Map<String, Set<GovernanceEndpoint>> endpointsByMarket = new LinkedHashMap<>();
        Map<String, Map<GovernanceEndpoint, List<Integer>>> yearsByMarket = new LinkedHashMap<>();
        Map<String, Integer> callsByMarket = new LinkedHashMap<>();
        for (SecurityRef stock : stocks) {
            String market = stock.marketCode();
            if (!endpointsByMarket.containsKey(market)) {
                Set<GovernanceEndpoint> supported;
                int latest;
                int extra;
                try {
                    supported = collector.supportedEndpoints(market);
                    // MUST: 손으로 준 연도가 이긴다. 그때는 사람이 무엇을 받는지 알고 있고,
                    // 소스 규칙으로 덮으면 「2019를 받아라」가 조용히 다른 해가 된다.
                    latest = latestFiscalYear != null
                            ? latestFiscalYear
                            : collector.latestRequestableYear(market, asOf);
                    // 아래쪽 끝도 소스가 답한다 — 위쪽 끝과 같은 비대칭의 반대편이다.
                    // MUST: span을 손으로 준 경우에는 안 더한다. 그때는 사람이 역년을 세고 있다.
                    extra = span != null ? 0 : collector.extraCalendarYears(market);
                } catch (DataSourceException e) {
                    // 소스가 없는 시장이다. 여기서 던지면 잡 전체가 죽으므로 비워 두고
                    // 아래에서 수를 남긴다 — 종목마다 실패하면 부분 실패 알림이
                    // 상시화되고 진짜 장애가 묻힌다.
                    log.warn("거버넌스 소스가 없는 시장이라 건너뛴다. market={} 사유={}",
                            market, e.getMessage());
                    endpointsByMarket.put(market, Set.of());
                    yearsByMarket.put(market, Map.of());
                    continue;
                }
                endpointsByMarket.put(market, supported);
                Map<GovernanceEndpoint, List<Integer>> years =
                        new EnumMap<>(GovernanceEndpoint.class);
                for (GovernanceEndpoint endpoint : targets) {
                    years.put(endpoint, yearsFor(endpoint, latest, span, extra));
                }
                yearsByMarket.put(market, years);
            }
            Set<GovernanceEndpoint> supported = endpointsByMarket.get(market);
            Map<GovernanceEndpoint, List<Integer>> years = yearsByMarket.get(market);
            int calls = 0;
            for (GovernanceEndpoint endpoint : targets) {
                if (supported.contains(endpoint) && years.containsKey(endpoint)) {
                    calls += years.get(endpoint).size();
                }
            }
            callsByMarket.merge(market, calls, Integer::sum);
        }

        // MUST: 예산을 소스별로 센다. 한 수로 뭉쳐 「보수 예산 10,000의 N%」라고 적으면
        // DART 예산에 Alpaca 호출이 섞여, 어느 쪽이 얼마나 남았는지 아무도 모른다.
        log.info("거버넌스 수집을 시작한다. 대상 {}종목 · 표 {}개 · 시장별 예상 호출 {} "
                        + "(DART 보수 예산은 10,000이고 Alpaca는 분당 100 · 일일 무제한이다)",
                stocks.size(), targets.size(), callsByMarket);

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, stocks.size());
        execution.plan(CollectionPhase.GOVERNANCE, stocks.size());
        execution.stage("종목별 정기보고서 주요정보 수집");

        // MUST: 시장별로 센다. 합집합을 쓰면 그 시장이 안 받은 역년에 0행 UPDATE가 종목마다
        // 쌓인다 — 아래 「표가 하나뿐인 시장에는 형제가 없다」와 같은 이유다.
        Map<String, Set<Integer>> touchedYearsByMarket = new LinkedHashMap<>();
        yearsByMarket.forEach((market, years) -> {
            Set<Integer> touched = new LinkedHashSet<>();
            years.values().forEach(touched::addAll);
            touchedYearsByMarket.put(market, touched);
        });

        try {
            for (SecurityRef stock : stocks) {
                Set<GovernanceEndpoint> supported =
                        endpointsByMarket.getOrDefault(stock.marketCode(), Set.of());
                Map<GovernanceEndpoint, List<Integer>> years =
                        yearsByMarket.getOrDefault(stock.marketCode(), Map.of());
                Set<Integer> touchedYears =
                        touchedYearsByMarket.getOrDefault(stock.marketCode(), Set.of());
                execution.process(stock, CollectionPhase.GOVERNANCE, security -> {
                    long written = 0;
                    for (GovernanceEndpoint endpoint : targets) {
                        if (!supported.contains(endpoint)) {
                            continue;
                        }
                        written += collector.collect(security, endpoint,
                                years.get(endpoint), ANNUAL_REPORT);
                    }
                    // 표를 다 받은 뒤라야 「비어 온 표」에 같은 보고서의 접수번호를 찍을 수 있다.
                    // 표가 하나뿐인 시장에는 형제가 없으므로 부르지 않는다 — 부르면 종목마다
                    // 0행 UPDATE가 쌓인다.
                    if (supported.size() > 1) {
                        for (int year : touchedYears) {
                            collector.stampNoDataFromSiblings(
                                    security.stockId(), year, ANNUAL_REPORT);
                        }
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
            // 표를 여럿 받는 시장에서만 생긴다. 표가 하나인 시장의 행은 어댑터가 스스로
            // 찍으므로 여기 안 세어진다.
            log.info("접수번호를 못 찍은 '데이터 없음' 행이 {}건이다 — 그 종목의 그 해는 받은 표가 다 비었다.",
                    unstamped);
        }
        return execution.finish();
    }

    /**
     * 그 표에서 받을 회계연도. 한 응답이 3개년을 주는 표는 <b>세 해씩 건너뛴다.</b>
     *
     * <p>안 건너뛰면 같은 3개년을 세 번 받아 콜이 세 배가 되는데, 결과가 같아 <b>어디에도 안 뜬다.</b>
     */
    static List<Integer> yearsFor(GovernanceEndpoint endpoint, int latestFiscalYear,
                                  Integer override, int extra) {
        int span = override != null
                ? override
                : YEARS_BACK.getOrDefault(endpoint, 1) + extra;
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
