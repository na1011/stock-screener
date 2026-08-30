package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FullStatementDataSource;
import com.stockscreener.pipeline.datasource.FullStatementResult;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StatementPeriod;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
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
 * <p>주요계정({@link DartFinancialAdapter})이 주지 않는 것을 받는다. 실측(dart-financial.md의 「계정별 가용성」
 * 절)에서 이 전환으로
 * 실제로 열리는 것은 <b>영업활동현금흐름 하나</b>이고, 그 밖에 자본변동표가 함께 와서 dart-financial.md의
 * 「계정 집합이 회사마다 다르다」 절의
 * 이익잉여금 결손을 여는 길이 된다. 이자비용·감가상각비는 <b>여전히 얻지 못한다</b> —
 * 표본 4사 중 금융지주에만 표준코드로 왔다.
 *
 * <p>주요계정과의 요청 차이는 {@code fs_div}가 <b>필수</b>라는 것뿐이다. 응답 행에는
 * {@code fs_div}가 없으므로 어느 쪽을 받았는지는 요청만 안다. 연결이 비면 별도로 다시 부르고,
 * 그때만 호출이 2배가 된다.
 *
 * <p>MUST NOT: 분기 보고서의 영업활동현금흐름을 저장하지 않는다. 개발가이드가 [3개월] 금액
 * 이라고 밝힌 것은 <b>(포괄)손익계산서뿐</b>이고 현금흐름표는 누적인지 3개월인지 실측되지
 * 않았다(dart-financial.md의 「전체 재무제표」 절). 연간만 쓰는 동안은 드러나지 않지만, 모른 채로 저장해 두면
 * 분기 OCF를 쓰는
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
            // Q4의 지배주주 순이익을 유도하려면 그 해 FY와 Q1~Q3가 한자리에 있어야 한다.
            List<RawFinancialReport> thisYear = new ArrayList<>();
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
                RawFinancialReport report = toReport(fetched.list(), year, period, security);
                reports.add(report);
                thisYear.add(report);
                if (period == StatementPeriod.FY) {
                    annual = fetched.list();
                }
            }
            if (annual != null && periods.contains(StatementPeriod.Q3)) {
                reports.add(toFourthQuarterBalance(annual, year, security, thisYear));
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
            // dart-financial.md의 「계정 집합이 회사마다 다르다」 절의 세 종목이 여기 걸린다. 재무상태표에 행 자체가 없으므로 자본변동표의
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
                null, null, null,
                // 계정명 폴백을 두지 않는다. 매출원가는 회사마다 이름이 다양해 폴백이
                // 「매출원가성 비용」류를 집을 위험이 표준코드 미사용분(2~9%)을 메우는
                // 이득보다 크다.
                DartFullAccountMapper.find(
                        income, DartFullAccountMapper.ID_COST_OF_SALES, null),
                // 재무상태표에서만 읽는다. 현금흐름표의 같은 이름은 기초·기말 잔액이다.
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CASH_AND_EQUIVALENTS, null),
                capex(cashFlow, period),
                // 재무상태표에서만 읽는다 — 현금흐름표의 같은 낱말은 증감분이고 손익의 것은
                // 평가손실이다(dart-financial.md의 「계정별 가용성」 절).
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_INVENTORIES, null),
                // 지배기업 소유주 귀속 순이익. 계정명 폴백을 두지 않는 이유가 특히 강하다 —
                // 한 계정을 회사마다 일곱 가지 이름으로 부르고, 새면 연결 총액이 그 자리에
                // 앉아 값이 그럴듯하게 틀린다(ID_NET_INCOME_CONTROLLING javadoc).
                DartFullAccountMapper.find(
                        income, DartFullAccountMapper.ID_NET_INCOME_CONTROLLING, null));
    }

    /**
     * 4분기 <b>잔액</b>만 담은 보고서. 손익과 현금흐름은 비운다.
     *
     * <p>4분기 보고서는 존재하지 않으므로 그 행은 원래 사업보고서에서 유도된 것이고, 이 어댑터가
     * 사업보고서만 다시 받으면 그 행의 결손은 <b>영영 남는다.</b> dart-financial.md의 「계정 집합이 회사마다
     * 다르다」 절의 이익잉여금이 정확히 그
     * 자리다 — 채우지 않으면 {@code ALTMAN_Z}가 4분기 말을 최신으로 보는 구간(대략 1월~1분기
     * 공시일)에서만 값을 내지 못하고, 그 구멍은 보존 창 한가운데에 생겨 원인을 찾기 어렵다.
     *
     * <p>IMPORTANT — <b>잔액과 지배주주 순이익만</b> 싣는다. 재무상태표는 시점 값이라 연말
     * 잔액이 곧 4분기 말 잔액이지만, 나머지 손익과 현금흐름은 <b>유량</b>이라 사업보고서 값을
     * 그대로 쓰면 1년치가 한 분기에 들어간다. 비워 두면 {@code fillMissing}이 건드리지 않아
     * 주요계정이 만든 기존 유도값이 그대로 남는다.
     *
     * <p><b>지배주주 순이익만 예외인 이유는 주요계정이 그것을 원리적으로 못 만들기 때문이다</b>
     * (ADR-0098). 그쪽의 {@code deriveFourthQuarter}는 {@code FY − 3분기 누적}으로 Q4를 유도하는데,
     * 요약본에는 지분을 가르는 계정이 아예 없다. 그래서 <b>여기서 같은 산술을 한다</b> —
     * 누적 컬럼 대신 이미 만든 {@code Q1+Q2+Q3}(각 3개월)를 더해 뺀다. 둘은 같은 값이다
     * (삼성전자 2025 연결: {@code 8.2229+5.1164+12.2257 = 25.5650}, {@code 45.2068−25.5650 = 19.6417}로
     * 주요계정의 유도값과 일치).
     *
     * <p><b>Q4가 없으면 이 결정 전체가 무력해진다.</b> TTM은 최근 4분기이고 12월 결산 종목의
     * 그 창에 Q4가 반드시 들어가는데, {@code FinancialMath}의 폴백이 <b>창 단위</b>라 한 분기만
     * 비어도 넷 다 연결 총액으로 간다 — <b>실측으로 Q4 채움이 0%일 때 PER이 한 종목도 안 바뀌었다.</b>
     *
     * <p>MUST NOT: 분기를 요청하지 않은 경로에서 부르지 않는다. 전 종목 연간 적재가 이것을
     * 함께 내면 4분기 행이 <b>없던 종목·연도에 잔액만 있는 행</b>이 생긴다.
     *
     * @param thisYear 같은 회계연도의 FY·Q1~Q3 보고서. Q4 지배주주 순이익의 유도 재료다
     */
    private RawFinancialReport toFourthQuarterBalance(JsonNode list, int year, SecurityRef security,
                                                      List<RawFinancialReport> thisYear) {
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
                // 나머지 유량은 비운다 — 위 IMPORTANT 참조.
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
                null, null, null, null,
                // 매출원가는 유량이라 여기서 비운다 — 위 IMPORTANT 참조.
                null,
                // 현금및현금성자산은 잔액이므로 4분기 행에도 담는다.
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CASH_AND_EQUIVALENTS, null),
                // 자본적지출은 유량이고 연간만 담는다 — 아래 capex() 참조.
                null,
                // 재고자산은 잔액이므로 4분기 행에도 담는다. capex와 갈리는 자리다.
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_INVENTORIES, null),
                // 손익이지만 이것만 예외다 — 주요계정이 원리적으로 못 만든다(위 javadoc).
                deriveFourthQuarterControlling(thisYear, year));
    }

    /**
     * Q4의 <b>지배기업 소유주 귀속</b> 순이익 = {@code FY − (Q1 + Q2 + Q3)}.
     *
     * <p>MUST: 넷 중 하나라도 없으면 {@code null}을 돌린다. 빠진 분기를 0으로 보면 그만큼
     * Q4가 부풀고, <b>그 값은 그럴듯해 드러나지 않는다.</b> null이면 {@code FinancialMath}의
     * 창 단위 폴백이 그 종목의 TTM을 통째로 연결 총액으로 돌린다 — 뜻이 섞이는 것보다 낫다.
     */
    static BigDecimal deriveFourthQuarterControlling(List<RawFinancialReport> thisYear,
                                                             int year) {
        BigDecimal annual = null;
        BigDecimal quarterSum = BigDecimal.ZERO;
        int quarters = 0;
        for (RawFinancialReport report : thisYear) {
            if (report.fiscalYear() != year || report.netIncomeControlling() == null) {
                continue;
            }
            if (StatementPeriod.FY.label().equals(report.fiscalPeriod())) {
                annual = report.netIncomeControlling();
            } else {
                quarterSum = quarterSum.add(report.netIncomeControlling());
                quarters++;
            }
        }
        if (annual == null || quarters != 3) {
            return null;
        }
        return annual.subtract(quarterSum);
    }

    /**
     * 영업활동현금흐름. <b>연간만</b> 낸다.
     *
     * <p>분기 값을 버리는 것이 아깝게 보이지만, 그 값이 3개월인지 누적인지 모르는 채로
     * 저장하면 나중에 그것을 쓰는 쪽이 <b>모른다는 사실조차 모른다.</b> 실측이 그 절(dart-financial.md의 「전체
     * 재무제표」 절)에
     * 적히면 그때 연다.
     */
    private static BigDecimal operatingCashFlow(List<JsonNode> cashFlow, StatementPeriod period) {
        if (period != StatementPeriod.FY) {
            return null;
        }
        return DartFullAccountMapper.operatingCashFlow(cashFlow);
    }

    /**
     * 자본적지출(유형자산의 취득). <b>연간만</b> 낸다 — 영업활동현금흐름과 같은 이유다.
     *
     * <p>둘이 같은 창에서만 값을 갖는 것이 중요하다. 잉여현금흐름은 둘의 차이인데 한쪽만
     * 분기에 값이 있으면 <b>기간이 다른 두 수를 빼게 된다.</b>
     *
     * <p>MUST NOT: 무형자산의 취득을 여기 더하지 않는다. 실측에서 둘 다 8/8로 오지만
     * (dart-financial.md의 「계정별 가용성」 절) 합칠지는 지표가 정할 문제이고, 원자료 계층에서 합치면
     * <b>나중에 가를 수
     * 없다.</b> 원자료만 담는다는 선은 여기서도 지킨다.
     */
    private static BigDecimal capex(List<JsonNode> cashFlow, StatementPeriod period) {
        if (period != StatementPeriod.FY) {
            return null;
        }
        return DartFullAccountMapper.find(cashFlow, DartFullAccountMapper.ID_CAPEX, null);
    }

    /** 받은 행과 그것을 받는 데 든 호출 수. 연결이 비어 별도로 다시 부르면 2다. */
    private record Fetched(JsonNode list, int calls) {
    }
}
