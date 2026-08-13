package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FullStatementDataSource;
import com.stockscreener.pipeline.datasource.FullStatementResult;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StatementPeriod;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DART 단일회사 <b>전체</b> 재무제표 어댑터({@code fnlttSinglAcntAll}).
 *
 * <p>주요계정({@link DartFinancialAdapter})이 주지 않는 것을 받는다. 실측(§7)에서 이 전환으로
 * 실제로 열리는 것은 <b>영업활동현금흐름 하나</b>이고, 그 밖에 자본변동표가 함께 와서 §8의
 * 이익잉여금 결손을 여는 길이 된다. 이자비용·감가상각비는 <b>여전히 얻지 못한다</b> —
 * 표본 4사 중 금융지주에만 표준코드로 왔다.
 *
 * <p>주요계정과의 요청 차이는 {@code fs_div}가 <b>필수</b>라는 것뿐이다. 응답 행에는
 * {@code fs_div}가 없으므로 어느 쪽을 받았는지는 요청만 안다. 연결이 비면 별도로 다시 부르고,
 * 그때만 호출이 2배가 된다.
 *
 * <p>MUST NOT: 분기 보고서의 영업활동현금흐름을 저장하지 않는다. 개발가이드가 [3개월] 금액
 * 이라고 밝힌 것은 <b>(포괄)손익계산서뿐</b>이고 현금흐름표는 누적인지 3개월인지 실측되지
 * 않았다(§11-E). 연간만 쓰는 동안은 드러나지 않지만, 모른 채로 저장해 두면 분기 OCF를 쓰는
 * 조건이 붙는 순간 <b>4배나 1/4로 조용히 틀린다.</b>
 */
// 키가 없으면 등록하지 않는다. 같은 패키지의 다른 DART 어댑터와 같은 이유다.
@ConditionalOnApiKey("dart")
@Component
public class DartFullStatementAdapter implements FullStatementDataSource {

    private static final Logger log = LoggerFactory.getLogger(DartFullStatementAdapter.class);

    /** 우리 회계기간 → DART 보고서 코드. Q4는 보고서가 없어 여기 없다. */
    private static final Map<StatementPeriod, String> REPORT_CODES =
            new EnumMap<StatementPeriod, String>(Map.of(
                    StatementPeriod.Q1, "11013",
                    StatementPeriod.Q2, "11012",
                    StatementPeriod.Q3, "11014",
                    StatementPeriod.FY, "11011"));

    private static final String CONSOLIDATED = "CFS";
    private static final String SEPARATE = "OFS";

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final DartCorpCodeCache corpCodes;
    private final String apiKey;

    public DartFullStatementAdapter(ExternalApiClientFactory clientFactory,
                                    DataSourceProperties properties,
                                    @Qualifier("dartRateLimiter") RateLimiter rateLimiter,
                                    DartCorpCodeCache corpCodes) {
        DataSourceProperties.Source source = properties.getDart();
        this.client = clientFactory.create(source);
        this.rateLimiter = rateLimiter;
        this.corpCodes = corpCodes;
        this.apiKey = source.getApiKey();
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return Set.of("KOSPI", "KOSDAQ");
    }

    @Override
    public String sourceName() {
        return DartResponse.SOURCE_NAME;
    }

    @Override
    public FullStatementResult fetchStatements(SecurityRef security, int fromFiscalYear,
                                               int toFiscalYear, Set<StatementPeriod> periods)
            throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, DartResponse.SOURCE_NAME,
                    "DART_API_KEY가 설정되지 않았다.");
        }
        if (periods == null || periods.isEmpty()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                    "받을 회계기간이 지정되지 않았다. ticker=" + security.ticker());
        }
        if (toFiscalYear < fromFiscalYear) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                    "회계연도 범위가 뒤집혔다: " + fromFiscalYear + "~" + toFiscalYear);
        }
        String corpCode = corpCodes.findCorpCode(security.ticker())
                .orElseThrow(() -> new DataSourceException(DataSourceException.Kind.NOT_FOUND,
                        DartResponse.SOURCE_NAME,
                        "DART에 등록되지 않은 종목코드다: " + security.ticker()));

        List<RawFinancialReport> reports = new ArrayList<>();
        int calls = 0;
        int missing = 0;

        for (int year = fromFiscalYear; year <= toFiscalYear; year++) {
            // 선언 순서(FY, Q1..Q3)가 아니라 enum 순서로 돈다. 로그를 읽을 때 기간이 늘 같은
            // 순서로 나와야 종목 간 비교가 된다.
            JsonNode annual = null;
            for (StatementPeriod period : StatementPeriod.values()) {
                if (!periods.contains(period)) {
                    continue;
                }
                Fetched fetched = fetchOne(corpCode, year, period, security);
                calls += fetched.calls();
                if (fetched.list() == null) {
                    missing++;
                    continue;
                }
                reports.add(toReport(fetched.list(), year, period, security));
                if (period == StatementPeriod.FY) {
                    annual = fetched.list();
                }
            }
            if (annual != null && periods.contains(StatementPeriod.Q3)) {
                reports.add(toFourthQuarterBalance(annual, year, security));
            }
        }
        return new FullStatementResult(reports, calls, missing);
    }

    /**
     * 한 (연도, 기간)의 전체 재무제표를 받는다. 연결이 비면 별도로 다시 부른다.
     *
     * <p>{@code status 013}(데이터 없음)을 예외로 올리지 않는 이유는 주요계정 어댑터와 같다 —
     * 아직 공시되지 않은 분기와 <b>상장 전 회계연도</b>가 정상이기 때문이다. 이 phase에서는
     * 후자가 특히 중요하다: {@code 013}이 곧 "그 해의 사업보고서가 존재하지 않는다"의 답이고,
     * 그것으로 <b>적재로 메울 수 있는 결손과 원리적으로 없는 결손이 갈린다.</b>
     */
    private Fetched fetchOne(String corpCode, int year, StatementPeriod period, SecurityRef security)
            throws DataSourceException {
        JsonNode consolidated = call(corpCode, year, period, CONSOLIDATED, security);
        if (consolidated != null && !consolidated.isEmpty()) {
            return new Fetched(consolidated, 1);
        }
        JsonNode separate = call(corpCode, year, period, SEPARATE, security);
        if (separate != null && !separate.isEmpty()) {
            return new Fetched(separate, 2);
        }
        return new Fetched(null, 2);
    }

    private JsonNode call(String corpCode, int year, StatementPeriod period, String fsDiv,
                          SecurityRef security) throws DataSourceException {
        String reportCode = REPORT_CODES.get(period);
        String context = "ticker=" + security.ticker() + " " + year + "/" + period
                + " reprt=" + reportCode + " fs_div=" + fsDiv;

        // MUST: 이 가드를 지우지 않는다. 리미터의 acquire()는 분당 간격만 대기하고 일일 한도는
        // 보지 않는다 — 여기가 없으면 한도를 조용히 넘는다. 어댑터마다 같은 블록이 있는 이유다.
        if (!rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED,
                    DartResponse.SOURCE_NAME,
                    "일일 호출 한도를 소진했다(사용 " + rateLimiter.usedToday() + "회). " + context);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "호출 대기 중 인터럽트됐다. " + context, e);
        }

        ResponseEntity<JsonNode> response;
        try {
            response = client.get().uri(uri -> uri
                            .path("/fnlttSinglAcntAll.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", year)
                            .queryParam("reprt_code", reportCode)
                            .queryParam("fs_div", fsDiv)
                            .build())
                    .retrieve()
                    .toEntity(JsonNode.class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }

        try {
            return DartResponse.requireList(response.getBody(), context);
        } catch (DataSourceException e) {
            if (e.kind() == DataSourceException.Kind.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    /**
     * 응답 행을 포트 DTO로 옮긴다.
     *
     * <p>손익을 {@code IS}와 {@code CIS} 양쪽에서 찾되 <b>{@code IS}를 먼저 본다.</b> 표본에서
     * 삼성전자만 둘 다 갖고 나머지 셋은 {@code CIS}뿐이었다. 둘 다 있을 때 매출·영업이익은
     * {@code IS}에 있으므로 순서가 뜻을 정한다.
     */
    private RawFinancialReport toReport(JsonNode list, int year, StatementPeriod period,
                                        SecurityRef security) {
        List<JsonNode> balance = DartFullAccountMapper.sections(
                list, DartFullAccountMapper.SJ_BALANCE);
        List<JsonNode> income = DartFullAccountMapper.sections(
                list, DartFullAccountMapper.SJ_INCOME, DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME);
        List<JsonNode> cashFlow = DartFullAccountMapper.sections(
                list, DartFullAccountMapper.SJ_CASH_FLOW);

        JsonNode any = list.get(0);
        String label = period.label();
        LocalDate[] range = DartReportPeriods.periodRange(year, label);

        BigDecimal retainedEarnings = DartFullAccountMapper.find(
                balance, DartFullAccountMapper.ID_RETAINED_EARNINGS, DartAccountMapper.RETAINED_EARNINGS);
        if (retainedEarnings == null) {
            // §8의 세 종목이 여기 걸린다. 재무상태표에 행 자체가 없으므로 자본변동표의
            // 기말 잔액으로 대신한다 — 규칙의 근거는 그 메서드에 있다.
            retainedEarnings = DartFullAccountMapper.retainedEarningsFromEquityChanges(
                    list, security.ticker(), year, label);
        }

        return new RawFinancialReport(
                year, label, range[0], range[1],
                DartReportPeriods.publishedAt(any, year),
                any.path("reprt_code").asString(null),
                any.path("currency").asString("KRW"),
                DartFullAccountMapper.find(
                        income, DartFullAccountMapper.ID_REVENUE, DartAccountMapper.REVENUE),
                DartFullAccountMapper.find(
                        income, DartFullAccountMapper.ID_OPERATING_INCOME,
                        DartAccountMapper.OPERATING_INCOME),
                DartFullAccountMapper.find(
                        income, DartFullAccountMapper.ID_NET_INCOME, DartAccountMapper.NET_INCOME),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_ASSETS, DartAccountMapper.TOTAL_ASSETS),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_EQUITY, DartAccountMapper.TOTAL_EQUITY),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_LIABILITIES,
                        DartAccountMapper.TOTAL_LIABILITIES),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CURRENT_ASSETS,
                        DartAccountMapper.CURRENT_ASSETS),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CURRENT_LIABILITIES,
                        DartAccountMapper.CURRENT_LIABILITIES),
                retainedEarnings,
                operatingCashFlow(cashFlow, period),
                // 발행주식수·EPS·BPS는 이 엔드포인트의 대상이 아니다. 주요계정 어댑터와 같이
                // 비워 두며, 채우려면 stockTotqySttus가 따로 필요하다.
                null, null, null);
    }

    /**
     * 4분기 <b>잔액</b>만 담은 보고서. 손익과 현금흐름은 비운다.
     *
     * <p>4분기 보고서는 존재하지 않으므로 그 행은 원래 사업보고서에서 유도된 것이고, 이 어댑터가
     * 사업보고서만 다시 받으면 그 행의 결손은 <b>영영 남는다.</b> §8의 이익잉여금이 정확히 그
     * 자리다 — 채우지 않으면 {@code ALTMAN_Z}가 4분기 말을 최신으로 보는 구간(대략 1월~1분기
     * 공시일)에서만 값을 내지 못하고, 그 구멍은 보존 창 한가운데에 생겨 원인을 찾기 어렵다.
     *
     * <p>IMPORTANT — <b>잔액만</b> 싣는다. 재무상태표는 시점 값이라 연말 잔액이 곧 4분기 말
     * 잔액이지만, 손익과 현금흐름은 <b>유량</b>이라 사업보고서 값을 그대로 쓰면 1년치가 한
     * 분기에 들어간다. 유도하려면 3분기 <b>누적</b>을 빼야 하는데 이 어댑터는 누적 컬럼을 읽지
     * 않으므로(분기 누적의 의미가 §11-E에서 미확인이다) 아예 내지 않는다. 비워 두면
     * {@code fillMissing}이 건드리지 않아 기존 유도값이 그대로 남는다.
     *
     * <p>MUST NOT: 분기를 요청하지 않은 경로에서 부르지 않는다. 전 종목 연간 적재가 이것을
     * 함께 내면 4분기 행이 <b>없던 종목·연도에 잔액만 있는 행</b>이 생긴다.
     */
    private RawFinancialReport toFourthQuarterBalance(JsonNode list, int year, SecurityRef security) {
        List<JsonNode> balance = DartFullAccountMapper.sections(
                list, DartFullAccountMapper.SJ_BALANCE);
        JsonNode any = list.get(0);
        LocalDate[] range = DartReportPeriods.periodRange(year, "Q4");

        BigDecimal retainedEarnings = DartFullAccountMapper.find(
                balance, DartFullAccountMapper.ID_RETAINED_EARNINGS, DartAccountMapper.RETAINED_EARNINGS);
        if (retainedEarnings == null) {
            retainedEarnings = DartFullAccountMapper.retainedEarningsFromEquityChanges(
                    list, security.ticker(), year, "Q4");
        }

        return new RawFinancialReport(
                year, "Q4", range[0], range[1],
                DartReportPeriods.publishedAt(any, year),
                any.path("reprt_code").asString(null),
                any.path("currency").asString("KRW"),
                // 유량은 비운다 — 위 IMPORTANT 참조.
                null, null, null,
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_ASSETS, DartAccountMapper.TOTAL_ASSETS),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_EQUITY, DartAccountMapper.TOTAL_EQUITY),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_LIABILITIES,
                        DartAccountMapper.TOTAL_LIABILITIES),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CURRENT_ASSETS,
                        DartAccountMapper.CURRENT_ASSETS),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CURRENT_LIABILITIES,
                        DartAccountMapper.CURRENT_LIABILITIES),
                retainedEarnings,
                null, null, null, null);
    }

    /**
     * 영업활동현금흐름. <b>연간만</b> 낸다.
     *
     * <p>분기 값을 버리는 것이 아깝게 보이지만, 그 값이 3개월인지 누적인지 모르는 채로
     * 저장하면 나중에 그것을 쓰는 쪽이 <b>모른다는 사실조차 모른다.</b> 실측이 그 절(§11-E)에
     * 적히면 그때 연다.
     */
    private static BigDecimal operatingCashFlow(List<JsonNode> cashFlow, StatementPeriod period) {
        if (period != StatementPeriod.FY) {
            return null;
        }
        return DartFullAccountMapper.operatingCashFlow(cashFlow);
    }

    /** 받은 행과 그것을 받는 데 든 호출 수. 연결이 비어 별도로 다시 부르면 2다. */
    private record Fetched(JsonNode list, int calls) {
    }
}
