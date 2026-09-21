package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FinancialDataSource;
import com.stockscreener.pipeline.datasource.FinancialFetch;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DART 단일회사 주요계정 어댑터. 국내 재무제표를 받는다.
 *
 * <p>스펙과 실측 결과는 {@code docs/api-spec/dart-financial.md}에 있다.
 *
 * <p>MUST NOT: {@code fnlttSinglIndx}(주요 재무지표)를 쓰지 않는다. 계산된 비율을 반환해
 * spec §7의 "모든 지표는 원자료에서 자체 계산"과 충돌하고, 2023년 3분기 이후만 제공한다.
 */
// 키가 없으면 등록하지 않는다. DataGoKr 어댑터와 같은 이유다.
@ConditionalOnApiKey("dart")
@Component
public class DartFinancialAdapter implements FinancialDataSource {

    private static final Logger log = LoggerFactory.getLogger(DartFinancialAdapter.class);

    /** 보고서 코드 → 우리 회계기간 라벨. Q4는 별도 보고서가 없어 유도한다. */
    private static final Map<String, String> REPORT_CODES = new LinkedHashMap<>(Map.of(
            "11013", "Q1",
            "11012", "Q2",
            "11014", "Q3",
            "11011", "FY"));

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final DartCorpCodeCache corpCodes;
    private final String apiKey;

    public DartFinancialAdapter(ExternalApiClientFactory clientFactory,
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
    public FinancialFetch fetchReports(SecurityRef security, int fromFiscalYear)
            throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, DartResponse.SOURCE_NAME,
                    "DART_API_KEY가 설정되지 않았다.");
        }
        String corpCode = corpCodes.findCorpCode(security.ticker())
                .orElseThrow(() -> new DataSourceException(DataSourceException.Kind.NOT_FOUND,
                        DartResponse.SOURCE_NAME,
                        "DART에 등록되지 않은 종목코드다: " + security.ticker()));

        int currentYear = LocalDate.now().getYear();
        List<RawFinancialReport> reports = new ArrayList<>();

        for (int year = fromFiscalYear; year <= currentYear; year++) {
            // 연간 누계와 3분기 누적을 함께 들고 있어야 Q4를 유도할 수 있다.
            Map<String, PeriodRows> byPeriod = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : REPORT_CODES.entrySet()) {
                PeriodRows rows = fetchOne(corpCode, year, entry.getKey(), security);
                if (rows != null) {
                    byPeriod.put(entry.getValue(), rows);
                    reports.add(toReport(rows, year, entry.getValue(), false));
                }
            }
            deriveFourthQuarter(byPeriod, year).ifPresent(reports::add);
        }
        // DART는 이 축을 안 본다 — 빈 목록은 「모른다」이지 「없다」가 아니다.
        // 같은 판정을 낼 재료는 있다(DartFullAccountMapper.find가 후보 account_id가 손익 행에
        // 하나도 없을 때 null을 낸다 · 추가 콜 0). 국내 54종목이 미국 380과 같은 상황인데
        // 이번에 안 열었고, 그 사실과 수는 이 변경의 ADR §결과에 있다.
        return FinancialFetch.of(reports);
    }

    /**
     * 보고서 하나를 받는다. 데이터가 없으면({@code status 013}) null을 낸다.
     *
     * <p>{@code 013}을 예외로 올리지 않는 이유는, 아직 공시되지 않은 분기가 <b>정상</b>이기
     * 때문이다. 예외로 만들면 최근 연도를 조회할 때마다 종목 실패로 기록된다.
     */
    private PeriodRows fetchOne(String corpCode, int year, String reportCode, SecurityRef security)
            throws DataSourceException {
        String context = "ticker=" + security.ticker() + " " + year + "/" + reportCode;
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
                            .path("/fnlttSinglAcnt.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", year)
                            .queryParam("reprt_code", reportCode)
                            .build())
                    .retrieve()
                    .toEntity(JsonNode.class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }

        JsonNode list;
        try {
            list = DartResponse.requireList(response.getBody(), context);
        } catch (DataSourceException e) {
            if (e.kind() == DataSourceException.Kind.NOT_FOUND) {
                // 아직 공시되지 않은 기간이다. 정상이므로 조용히 건너뛴다.
                return null;
            }
            throw e;
        }

        // CFS(연결) 우선, 없으면 OFS(별도). 연결재무제표가 없는 회사가 있다.
        List<JsonNode> consolidated = rowsOf(list, "CFS");
        List<JsonNode> rows = consolidated.isEmpty() ? rowsOf(list, "OFS") : consolidated;
        if (rows.isEmpty()) {
            return null;
        }
        return new PeriodRows(rows, consolidated.isEmpty() ? "OFS" : "CFS");
    }

    private static List<JsonNode> rowsOf(JsonNode list, String fsDiv) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : list) {
            if (fsDiv.equals(row.path("fs_div").asString(""))) {
                rows.add(row);
            }
        }
        return rows;
    }

    private RawFinancialReport toReport(PeriodRows periodRows, int year, String period,
                                        boolean cumulative) throws DataSourceException {
        List<JsonNode> income = filterBy(periodRows.rows(), DartAccountMapper.SJ_INCOME);
        List<JsonNode> balance = filterBy(periodRows.rows(), DartAccountMapper.SJ_BALANCE);
        JsonNode any = periodRows.rows().get(0);

        LocalDate publishedAt = publishedAt(any, year);
        LocalDate[] range = periodRange(year, period);

        return new RawFinancialReport(
                year, period, range[0], range[1], publishedAt,
                any.path("reprt_code").asString(null),
                any.path("currency").asString("KRW"),
                DartAccountMapper.find(income, DartAccountMapper.REVENUE, cumulative),
                DartAccountMapper.find(income, DartAccountMapper.OPERATING_INCOME, cumulative),
                DartAccountMapper.find(income, DartAccountMapper.NET_INCOME, cumulative),
                // 재무상태표는 시점 잔액이므로 누적 개념이 없다. 항상 당기 금액을 쓴다.
                DartAccountMapper.find(balance, DartAccountMapper.TOTAL_ASSETS, false),
                DartAccountMapper.find(balance, DartAccountMapper.TOTAL_EQUITY, false),
                // 지배주주 자본은 **이 엔드포인트가 안 준다**(fnlttSinglAcnt 요약본). 전체
                // 재무제표(fnlttSinglAcntAll)의 account_id로만 오고, 실측(2026-09-21 전수 탐침)
                // 직접 태그 321/326이 거기서 왔다. 여기 null은 결손이 아니라 소스의 경계다.
                null,
                DartAccountMapper.find(balance, DartAccountMapper.TOTAL_LIABILITIES, false),
                DartAccountMapper.find(balance, DartAccountMapper.CURRENT_ASSETS, false),
                DartAccountMapper.find(balance, DartAccountMapper.CURRENT_LIABILITIES, false),
                DartAccountMapper.find(balance, DartAccountMapper.RETAINED_EARNINGS, false),
                // 주요계정에는 영업활동현금흐름이 없다. 전체 재무제표 API에만 있으며,
                // 종목당 호출을 배로 늘릴 만큼 v1에 필요하지 않다.
                null,
                // 발행주식수는 stockTotqySttus 별도 호출이 필요하다. Phase 3에서 붙인다.
                null,
                null, null,
                // 매출원가·현금및현금성자산·자본적지출·재고자산은 주요계정에 없다.
                // 앞의 둘과 재고자산은 손익·재무상태표에 있지만 이 엔드포인트가 총계만 주고,
                // 자본적지출은 현금흐름표라 이 엔드포인트가 아예 내지 않는다(dart-financial.md의 「단일회사 주요계정」 절).
                null, null, null, null,
                // 지배주주 귀속 순이익도 이 엔드포인트로는 원리적으로 얻을 수 없다.
                // 중복된 `당기순이익(손실)` 두 행의 값이 같아 지분을 가를 수 없고
                // (dart-financial.md의 「숫자 파싱과 중복 행」 절), account_id가 없어 다른 길도 없다.
                // 전체 재무제표가 채운다 — 여기서 null인 것은 결손이 아니라 이 소스의 경계다.
                null,
                null, null, null, null, null, null, null, null,
                // 주요계정은 세율 재료를 주지 않는다 — 전체 재무제표 전용이다.
                null, null);
    }

    /**
     * Q4를 유도한다. 4분기 보고서는 존재하지 않는다.
     *
     * <p>{@code Q4(손익) = 사업보고서 − 3분기 누적}이다.
     *
     * <p>IMPORTANT — <b>손익만 차감한다.</b> 재무상태표는 시점 잔액이므로 차감하면 자산총계가
     * 음수가 된다. 아래에서 BS는 FY 값을 그대로 쓴다.
     *
     * <p>IMPORTANT — <b>통화가 갈리면 손익을 내지 않는다</b>(ADR-0110). 회사가 그 해에 보고
     * 통화를 갈아타면 이 뺄셈이 <b>통화를 건너뛴다</b> — 실측으로 241560의 2023 Q4 매출이
     * {@code -7,435,793,333,222}(USD 라벨)로 저장돼 있었다. 값이 그럴듯해 어떤 에러도 나지
     * 않는다. <b>잔액은 그대로 낸다</b> — 그것은 FY의 연말 잔액이고 뺄셈을 거치지 않아 참이다.
     */
    private Optional<RawFinancialReport> deriveFourthQuarter(Map<String, PeriodRows> byPeriod,
                                                            int year) throws DataSourceException {
        PeriodRows annual = byPeriod.get("FY");
        PeriodRows thirdQuarter = byPeriod.get("Q3");
        if (annual == null || thirdQuarter == null) {
            return Optional.empty();
        }

        List<JsonNode> annualIncome = filterBy(annual.rows(), DartAccountMapper.SJ_INCOME);
        List<JsonNode> thirdIncome = filterBy(thirdQuarter.rows(), DartAccountMapper.SJ_INCOME);
        List<JsonNode> annualBalance = filterBy(annual.rows(), DartAccountMapper.SJ_BALANCE);
        JsonNode any = annual.rows().get(0);

        String annualCurrency = singleCurrency(annual.rows());
        boolean subtractable = annualCurrency != null
                && annualCurrency.equals(singleCurrency(thirdQuarter.rows()))
                && samePeriodTerm(annual.rows(), thirdQuarter.rows());

        return Optional.of(new RawFinancialReport(
                year, "Q4",
                LocalDate.of(year, 10, 1), LocalDate.of(year, 12, 31),
                publishedAt(any, year),
                any.path("reprt_code").asString(null),
                annualCurrency != null ? annualCurrency : any.path("currency").asString("KRW"),
                subtractable ? subtract(annualIncome, thirdIncome, DartAccountMapper.REVENUE) : null,
                subtractable
                        ? subtract(annualIncome, thirdIncome, DartAccountMapper.OPERATING_INCOME)
                        : null,
                subtractable
                        ? subtract(annualIncome, thirdIncome, DartAccountMapper.NET_INCOME)
                        : null,
                // BS는 차감하지 않는다 — 연말 잔액이 곧 Q4 말 잔액이다. 통화가 갈려도 참이다.
                DartAccountMapper.find(annualBalance, DartAccountMapper.TOTAL_ASSETS, false),
                DartAccountMapper.find(annualBalance, DartAccountMapper.TOTAL_EQUITY, false),
                // 여기도 요약본이라 지배주주 자본이 안 온다(위와 같은 이유).
                // **MUST NOT: 「국내는 분자도 비지배 포함이라 기저가 맞는다」를 근거로 적지 않는다**
                // — 그 문장이 여기 있었고 틀렸다. 그것은 ROE에만 서고 PBR에는 안 선다.
                null,
                DartAccountMapper.find(annualBalance, DartAccountMapper.TOTAL_LIABILITIES, false),
                DartAccountMapper.find(annualBalance, DartAccountMapper.CURRENT_ASSETS, false),
                DartAccountMapper.find(annualBalance, DartAccountMapper.CURRENT_LIABILITIES, false),
                DartAccountMapper.find(annualBalance, DartAccountMapper.RETAINED_EARNINGS, false),
                null, null, null, null, null, null, null, null,
                // 지배주주 귀속 순이익. 위와 같은 이유로 이 엔드포인트가 낼 수 없다.
                null,
                null, null, null, null, null, null, null, null,
                // 주요계정은 세율 재료를 주지 않는다 — 전체 재무제표 전용이다.
                null, null,
                // 우리가 만든 행이다(사업보고서 − 3분기 누계). 저장이 이것을 알아야 유도
                // 규칙을 고쳤을 때 옛 값을 지운다.
                true,
                // **MUST: 국내는 컬럼 단위 부재 판정을 담지 않는다**(RawFinancialReport.assertedAbsent).
                // 담을 자격은 「어댑터가 **여러 판을 합쳐** 판정했는가」인데, 이 경로에는
                // SecEdgarFacts의 carryForward에 해당하는 기제가 없다 — 한 응답이 안 실은 계정은
                // 그냥 안 실린 것이다. 담으면 「안 실었다」가 「없다」로 읽혀 멀쩡한 값이 지워진다
                // (2026-09-22 실측: 국내 net_income이 null인 행 298개).
                java.util.Set.of()));
    }

    /**
     * 그 행들이 <b>한 통화로 일치하면</b> 그 통화, 아니면 {@code null}.
     *
     * <p>「아무 행의 통화」를 라벨로 쓰지 않으려고 둔다 — 섞인 보고서에서 첫 행을 집으면
     * <b>라벨이 실제와 다른 값</b>이 되고, 그 오류는 값이 나오는 형태라 드러나지 않는다.
     */
    /**
     * <b>사업보고서와 3분기보고서가 같은 회계연도인가.</b> {@code thstrm_nm}의 <b>기수</b>로 판정한다.
     *
     * <p><b>같은 {@code bsns_year}가 같은 회계연도를 뜻하지 않는다.</b> DART의 그 필드는
     * <b>기간 종료일의 달력연도</b>라, 결산월이 1~3월이면 사업보고서와 분기보고서가
     * <b>서로 다른 회계연도</b>가 된다. 그런데 {@code Q4 = FY − Q3누적}은 둘이 같은 회계연도임을
     * 전제한다 — <b>12개월 떨어진 두 기간을 빼면 값이 그럴듯한 채로 틀린다.</b>
     *
     * <p><b>실측(2026-09-16 · DART 직접 조회)</b>
     * <ul>
     *   <li>신영증권(3월 결산) {@code bsns_year=2024}: 사업보고서가 <b>제 70 기</b>
     *       ({@code thstrm_dt=2024.03.31}), 3분기가 <b>제 71 기 3분기말</b>({@code 2024.12.31}).
     *       저장된 Q4 매출 <b>691,931,711,604</b>가 정확히 그 뺄셈이다.
     *   <li>비츠로셀(6월 → 12월 결산 변경) {@code bsns_year=2017}: 사업보고서 <b>제 33 기</b>,
     *       3분기 <b>제 32 기</b>. 저장된 Q4 매출이 <b>−60,993,170,139</b>다.
     * </ul>
     *
     * <p><b>MUST: 기수로 판정한다 — {@code thstrm_dt}로는 못 한다.</b> 전체 재무제표
     * ({@code fnlttSinglAcntAll})는 <b>{@code thstrm_dt}를 아예 안 준다</b>(같은 날 실측:
     * 4호출 전부 필드 없음 · {@code docs/api-spec/dart-financial.md}의 필드 목록이 맞고
     * ADR-0056의 <i>"준다"</i>가 거짓이었다). <b>기수는 두 엔드포인트가 모두 준다.</b>
     *
     * <p>둘 중 하나라도 기수를 못 읽으면 <b>참을 낸다</b> — 12월 결산에서 지금 동작을 바꾸지
     * 않는 쪽이 안전하다. 잔액은 이 게이트와 무관하게 그대로 낸다(연말 잔액은 참이다).
     */
    private static boolean samePeriodTerm(List<JsonNode> annualRows, List<JsonNode> quarterRows) {
        Integer annualTerm = periodTerm(annualRows);
        Integer quarterTerm = periodTerm(quarterRows);
        if (annualTerm == null || quarterTerm == null) {
            return true;
        }
        if (!annualTerm.equals(quarterTerm)) {
            log.warn("사업보고서와 3분기보고서의 기수가 다르다 — 4분기 손익을 유도하지 않는다. "
                    + "사업={}기 3분기={}기", annualTerm, quarterTerm);
            return false;
        }
        return true;
    }

    private static final java.util.regex.Pattern TERM =
            java.util.regex.Pattern.compile("제\\s*(\\d+)\\s*기");

    /** {@code thstrm_nm}의 기수. 행마다 같으므로 첫 값을 쓴다. */
    private static Integer periodTerm(List<JsonNode> rows) {
        for (JsonNode row : rows) {
            java.util.regex.Matcher m = TERM.matcher(row.path("thstrm_nm").asString(""));
            if (m.find()) {
                return Integer.valueOf(m.group(1));
            }
        }
        return null;
    }

    private static String singleCurrency(List<JsonNode> rows) {
        String found = null;
        for (JsonNode row : rows) {
            String currency = row.path("currency").asString(null);
            if (currency == null || currency.isBlank()) {
                continue;
            }
            if (found == null) {
                found = currency;
            } else if (!found.equals(currency)) {
                return null;
            }
        }
        return found;
    }

    private static BigDecimal subtract(List<JsonNode> annual, List<JsonNode> thirdQuarter,
                                       Set<String> candidates) {
        BigDecimal full = DartAccountMapper.find(annual, candidates, false);
        BigDecimal cumulative = DartAccountMapper.find(thirdQuarter, candidates, true);
        if (full == null || cumulative == null) {
            return null;
        }
        return full.subtract(cumulative);
    }

    private static List<JsonNode> filterBy(List<JsonNode> rows, String sjDiv) {
        List<JsonNode> filtered = new ArrayList<>();
        for (JsonNode row : rows) {
            if (sjDiv.equals(row.path("sj_div").asString(""))) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    /**
     * 공시일과 회계기간은 {@link DartReportPeriods}가 정한다.
     *
     * <p>MUST: 여기에 되돌려 놓지 않는다. 전체 재무제표 어댑터가 같은 보고서를 같은
     * {@code publishedAt}으로 옮겨야 {@code FinancialWriter}가 그것을 정정공시로 오인하지
     * 않는다 — 그 오인의 대가가 해당 종목 지표의 보존 창 전량 삭제다.
     */
    private static LocalDate publishedAt(JsonNode row, int fallbackYear) {
        return DartReportPeriods.publishedAt(row, fallbackYear);
    }

    private static LocalDate[] periodRange(int year, String period) {
        return DartReportPeriods.periodRange(year, period);
    }

    /** 한 보고서의 행들과 어느 연결기준을 썼는지. */
    private record PeriodRows(List<JsonNode> rows, String fsDiv) {
    }
}
