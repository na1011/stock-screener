package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 한 종목의 <b>자본 사건</b>(감자·증자·거래정지 전후)을 소스 셋에 한꺼번에 묻는다.
 *
 * <p>거래정지 중 감자와 제3자배정 증자가 겹친 종목은 {@code price_daily}에 「기준가 × 새 주식수」가
 * 남아 시총이 수백 배로 튄다(ADR-0126의 출력 축이 그렇게 신고한다). 그 계단이 <b>원장이 준 사실</b>인지
 * <b>우리가 합성한 값</b>인지를 가르려면 세 소스를 같은 창으로 나란히 봐야 한다 —
 * data.go.kr의 {@code mrktTotAmt}·{@code lstgStCnt}, DART 공시 목록, DART 감자·증자 결정.
 *
 * <p>테스트가 아니라 <b>탐침</b>이다. 단언이 없고 관측을 표준출력으로 낸다.
 * 표본은 밖에서 준다: {@code -Dprobe.ticker=046070 -Dprobe.from=2024-01-01 -Dprobe.to=2026-09-17}.
 * 콜은 data.go.kr 1 + DART 공시목록 페이지 수 + 결정 2 + 증자(감자) 현황 보고서 수다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class KrCapitalEventProbeLiveTest {

    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 증자(감자) 현황을 부를 보고서. 사업보고서가 그해 12-31까지를 담으므로 분기는 마지막 해만 본다. */
    private static final Map<String, String> REPORTS = Map.of(
            "11011", "사업", "11012", "반기", "11013", "1분기", "11014", "3분기");

    @Test
    @DisplayName("[live][probe] 한 종목의 감자·증자·정지 전후를 소스 셋에 나란히 묻는다")
    void probeCapitalEvents() throws Exception {
        String ticker = System.getProperty("probe.ticker", "046070");
        LocalDate from = LocalDate.parse(System.getProperty("probe.from", "2024-01-01"));
        LocalDate to = LocalDate.parse(System.getProperty("probe.to", LocalDate.now().toString()));
        System.out.printf("%n===== 표본 %s  창 %s ~ %s =====%n", ticker, from, to);

        probePrices(ticker, from, to);

        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient dart = DartProbeSupport.client();
        String corpCode = cache.findCorpCode(ticker).orElseThrow();
        System.out.println("\ncorp_code=" + corpCode);

        probeDisclosureList(dart, corpCode, from, to);
        for (String endpoint : List.of("crDecsn", "piicDecsn", "fricDecsn", "pifricDecsn")) {
            probeDecision(dart, corpCode, endpoint, from, to);
        }
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            List<String> codes = year < to.getYear() ? List.of("11011")
                    : List.of("11013", "11012", "11014");
            for (String code : codes) {
                probeIrds(dart, corpCode, year, code);
            }
        }
    }

    /**
     * 기준일마다 시장 전종목을 1콜로 받아 <b>시총 상위 N×1.2 안의 거래정지(trqu=0) 종목</b>을 찍는다.
     *
     * <p>유니버스 순위({@code MarketCapUniverseAdapter})가 소스 {@code mrktTotAmt}를 그대로 쓰므로,
     * 정지 중 합성 시총이 순위에 닿을 수 있는 자리의 크기가 이것이다.
     * {@code -Dprobe.dates=2026-09-04,2025-11-21 -Dprobe.market=KOSDAQ -Dprobe.topn=150}.
     */
    @Test
    @DisplayName("[live][probe] 기준일마다 시총 상위 근처의 거래정지 종목을 센다")
    void probeHaltedNearTopN() {
        String market = System.getProperty("probe.market", "KOSDAQ");
        int topN = Integer.parseInt(System.getProperty("probe.topn", "KOSPI".equals(market) ? "200" : "150"));
        List<LocalDate> dates = java.util.Arrays.stream(System.getProperty("probe.dates", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(LocalDate::parse).toList();
        DataSourceProperties properties = new DataSourceProperties();
        String key = System.getenv("DATA_GO_KR_SERVICE_KEY");
        properties.getDataGoKr().setApiKey(key == null ? "" : key);
        RestClient client = new ExternalApiClientFactory().create(properties.getDataGoKr());
        String serviceKey = ExternalApiClientFactory.normalizeServiceKey(key == null ? "" : key);
        for (LocalDate date : dates) {
            JsonNode body = DartProbeSupport.get(client, uri -> uri
                    .path("/getStockPriceInfo")
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("resultType", "json")
                    .queryParam("numOfRows", 3000)
                    .queryParam("pageNo", 1)
                    .queryParam("basDt", date.format(BAS_DT))
                    .queryParam("mrktCls", market)
                    .build());
            JsonNode payload = body.path("response").path("body");
            java.util.List<JsonNode> rows = new java.util.ArrayList<>();
            payload.path("items").path("item").forEach(item -> {
                String ticker = item.path("srtnCd").asString("");
                String cap = item.path("mrktTotAmt").asString("").replace(",", "");
                if (ticker.matches("\\d{5}0") && !cap.isBlank() && !"-".equals(cap)) {
                    rows.add(item);
                }
            });
            rows.sort(java.util.Comparator.comparing(
                    (JsonNode n) -> new java.math.BigDecimal(n.path("mrktTotAmt").asString("0").replace(",", ""))).reversed());
            int limit = Math.min(rows.size(), (int) (topN * 1.2));
            System.out.printf("%n--- %s %s totalCount=%s 보통주후보=%d (상위 %d까지 본다)%n", date, market,
                    payload.path("totalCount").asString("?"), rows.size(), limit);
            int halted = 0;
            for (int i = 0; i < limit; i++) {
                JsonNode n = rows.get(i);
                if ("0".equals(n.path("trqu").asString(""))) {
                    halted++;
                    System.out.printf("  순위 %3d%s %s %s clpr=%s lstgStCnt=%s mrktTotAmt=%s%n", i + 1,
                            i < topN ? "(편입권)" : "(경계)", n.path("srtnCd").asString(""),
                            n.path("itmsNm").asString(""), n.path("clpr").asString(""),
                            n.path("lstgStCnt").asString(""), n.path("mrktTotAmt").asString(""));
                }
            }
            System.out.printf("  정지 종목 %d (상위 %d 안)%n", halted, limit);
        }
    }

    /** 가격·주식수·시총·거래량이 바뀌는 행만 찍는다. 창 전체를 찍으면 수백 행이라 계단이 묻힌다. */
    private static void probePrices(String ticker, LocalDate from, LocalDate to) {
        DataSourceProperties properties = new DataSourceProperties();
        String key = System.getenv("DATA_GO_KR_SERVICE_KEY");
        properties.getDataGoKr().setApiKey(key == null ? "" : key);
        RestClient client = new ExternalApiClientFactory().create(properties.getDataGoKr());
        String serviceKey = ExternalApiClientFactory.normalizeServiceKey(key == null ? "" : key);

        JsonNode body = DartProbeSupport.get(client, uri -> uri
                .path("/getStockPriceInfo")
                .queryParam("serviceKey", serviceKey)
                .queryParam("resultType", "json")
                .queryParam("numOfRows", 2000)
                .queryParam("pageNo", 1)
                .queryParam("likeSrtnCd", ticker)
                .queryParam("beginBasDt", from.format(BAS_DT))
                .queryParam("endBasDt", to.plusDays(1).format(BAS_DT))
                .build());
        JsonNode response = body.path("response");
        JsonNode payload = response.isMissingNode() ? body : response.path("body");
        System.out.println("\n--- data.go.kr getStockPriceInfo totalCount="
                + payload.path("totalCount").asString("?")
                + " header=" + body.path("response").path("header"));
        JsonNode items = payload.path("items").path("item");
        System.out.println("  basDt     srtnCd itmsNm  clpr  mkp hipr lopr  trqu  lstgStCnt  mrktTotAmt");
        String prevKey = null;
        String prevLine = null;
        // 소스가 최신순으로 준다 — 날짜 오름차순으로 뒤집어 계단을 읽기 쉽게 한다.
        java.util.List<JsonNode> rows = new java.util.ArrayList<>();
        items.forEach(rows::add);
        rows.sort(java.util.Comparator.comparing(n -> n.path("basDt").asString("")));
        for (JsonNode item : rows) {
            String line = String.join(" ",
                    item.path("basDt").asString(""), item.path("srtnCd").asString(""),
                    item.path("itmsNm").asString(""), item.path("clpr").asString(""),
                    item.path("mkp").asString(""), item.path("hipr").asString(""),
                    item.path("lopr").asString(""), item.path("trqu").asString(""),
                    item.path("lstgStCnt").asString(""), item.path("mrktTotAmt").asString(""));
            String key2 = item.path("clpr").asString("") + "|" + item.path("lstgStCnt").asString("")
                    + "|" + ("0".equals(item.path("trqu").asString("")) ? "halt" : "trade");
            if (!key2.equals(prevKey)) {
                if (prevLine != null) {
                    System.out.println("  (직전) " + prevLine);
                }
                System.out.println("  " + line);
            }
            prevKey = key2;
            prevLine = line;
        }
        if (prevLine != null) {
            System.out.println("  (마지막) " + prevLine);
        }
    }

    private static void probeDisclosureList(RestClient dart, String corpCode,
                                            LocalDate from, LocalDate to) {
        for (int page = 1; page <= 10; page++) {
            final int pageNo = page;
            JsonNode body = DartProbeSupport.get(dart, uri -> uri
                    .path("/list.json")
                    .queryParam("crtfc_key", DartProbeSupport.apiKey())
                    .queryParam("corp_code", corpCode)
                    .queryParam("bgn_de", from.format(BAS_DT))
                    .queryParam("end_de", to.format(BAS_DT))
                    .queryParam("page_no", pageNo)
                    .queryParam("page_count", 100)
                    .build());
            System.out.println("\n--- DART list.json page=" + page + " status="
                    + DartProbeSupport.describeStatus(body)
                    + " total_count=" + body.path("total_count").asString("?")
                    + " total_page=" + body.path("total_page").asString("?"));
            JsonNode list = body.path("list");
            for (JsonNode row : list) {
                System.out.printf("  %s %s %s%n", row.path("rcept_dt").asString(""),
                        row.path("rcept_no").asString(""), row.path("report_nm").asString("").trim());
            }
            if (page >= body.path("total_page").asInt(1)) {
                return;
            }
        }
    }

    /** 결정 공시는 필드가 넓고 이름을 외워 두면 틀린다 — 행마다 전 필드를 찍는다. */
    private static void probeDecision(RestClient dart, String corpCode, String endpoint,
                                      LocalDate from, LocalDate to) {
        JsonNode body = DartProbeSupport.get(dart, uri -> uri
                .path("/" + endpoint + ".json")
                .queryParam("crtfc_key", DartProbeSupport.apiKey())
                .queryParam("corp_code", corpCode)
                .queryParam("bgn_de", from.format(BAS_DT))
                .queryParam("end_de", to.format(BAS_DT))
                .build());
        System.out.println("\n--- DART " + endpoint + " status=" + DartProbeSupport.describeStatus(body));
        for (JsonNode row : body.path("list")) {
            System.out.println("  {");
            row.properties().forEach(e -> System.out.println("    " + e.getKey() + " = " + e.getValue()));
            System.out.println("  }");
        }
    }

    private static void probeIrds(RestClient dart, String corpCode, int year, String reportCode) {
        JsonNode body = DartProbeSupport.get(dart, uri -> uri
                .path("/irdsSttus.json")
                .queryParam("crtfc_key", DartProbeSupport.apiKey())
                .queryParam("corp_code", corpCode)
                .queryParam("bsns_year", year)
                .queryParam("reprt_code", reportCode)
                .build());
        System.out.println("\n--- DART irdsSttus " + year + " " + REPORTS.get(reportCode)
                + " status=" + DartProbeSupport.describeStatus(body));
        for (JsonNode row : body.path("list")) {
            System.out.printf("  %s | %s | %s | 수량=%s | 액면=%s | 가액=%s | rcept=%s%n",
                    row.path("isu_dcrs_de").asString(""), row.path("isu_dcrs_stle").asString(""),
                    row.path("isu_dcrs_stock_knd").asString(""), row.path("isu_dcrs_qy").asString(""),
                    row.path("isu_dcrs_mstvdv_fval_amount").asString(""),
                    row.path("isu_dcrs_mstvdv_amount").asString(""), row.path("rcept_no").asString(""));
        }
    }
}
