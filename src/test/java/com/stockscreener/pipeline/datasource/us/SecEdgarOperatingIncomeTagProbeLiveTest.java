package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 영업이익이 비는 미국 종목에서 <b>유도가 몇 (종목, 회계연도) 쌍을 덮는가.</b>
 *
 * <p>ADR-0102가 「뒤 판이 앞 판을 지운」 갈래를 닫은 뒤에도 {@code OPM_MIN_5Y}가 막히는
 * 코호트가 <b>199종목 · 막는 FY 행 924개</b>다. 그중 <b>113종목이 SIC 6xxx</b>(은행·보험)이고
 * 그쪽은 영업이익이 <b>원리적으로 없다</b> — {@code AssetsCurrent}가 없는 것과 같은 성질이다.
 * 그래서 이 탐침의 표본은 <b>금융업을 뺀 86종목</b>이다.
 *
 * <p><b>세는 단위가 태그 개수가 아니라 (종목, 회계연도) 쌍이다.</b> 예전 형태는 태그별 연간
 * 팩트 <b>개수</b>만 세어 <i>"그 결손 연도에 재료가 있는가"</i>에 답하지 못했다.
 *
 * <p><b>연간 판정을 {@code fp}로 하지 않는다.</b> 어댑터는 {@code fp}를 안 믿고 <b>기간 길이</b>로
 * 회계연도를 만든다({@code SecEdgarFacts.ANNUAL_MIN/MAX}, 350~380일). {@code fp=="FY"}로 세면
 * <b>적재의 창과 다른 모집단</b>을 재게 된다.
 *
 * <p><b>MUST: 이 탐침은 무엇을 더할지만 정하고 적재는 하지 않는다.</b> 컬럼을 더하는 것 자체가
 * 정정을 만들고({@code hasAnyValue}·{@code conflicts}·{@code currencyOf}가 모두
 * {@code SecEdgarColumn.values()}를 순회한다) 그것은 미국 지표가 0행인 동안에만 안전하다.
 *
 * <p>표본은 {@code -Dprobe.tickers=AA,ADM,…}로 준다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+",
        disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecEdgarOperatingIncomeTagProbeLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_TICKERS = "AA,AAON,ACA,ADM,ADP,ADSK,AEP,AER,AES,AIR,BMY,IBM";

    private static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                // MUST NOT: 이 값을 출력에 찍지 않는다 — 이메일이 들어 있다.
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private static void pause() {
        try {
            Thread.sleep(220);   // 공표 초당 10회의 절반 아래로 둔다(api-sources skill)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("[live][probe] 영업이익이 비는 (종목, 회계연도)에 유도 재료가 있는가")
    void probeDerivationInputs() {
        List<String> tickers = List.of(
                System.getProperty("probe.tickers", DEFAULT_TICKERS).split(","));

        RestClient www = client("https://www.sec.gov");
        RestClient data = client("https://data.sec.gov");

        JsonNode map = MAPPER.readTree(
                www.get().uri("/files/company_tickers_exchange.json").retrieve().body(String.class));
        JsonNode fields = map.path("fields");
        int iTicker = -1, iCik = -1;
        for (int i = 0; i < fields.size(); i++) {
            String name = fields.get(i).asString("");
            if ("ticker".equals(name)) { iTicker = i; }
            if ("cik".equals(name)) { iCik = i; }
        }
        Map<String, Long> cikOf = new LinkedHashMap<>();
        for (JsonNode row : map.path("data")) {
            cikOf.putIfAbsent(row.get(iTicker).asString(""), row.get(iCik).asLong());
        }

        // 유도에 필요한 태그. 영업이익 = GrossProfit − OperatingExpenses
        //                     또는 = Revenues − CostsAndExpenses
        List<String> wanted = List.of("OperatingIncomeLoss", "GrossProfit", "OperatingExpenses",
                "CostsAndExpenses", "Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax");

        int pairs = 0, missingOi = 0, byGrossProfit = 0, byCostsAndExpenses = 0, byEither = 0;
        System.out.println("\n===== (종목, 회계연도)별 유도 재료 =====");
        for (String t : tickers) {
            Long cik = cikOf.get(t.trim());
            if (cik == null) {
                System.out.printf("%-6s CIK 매핑 없음%n", t);
                continue;
            }
            pause();
            String body = data.get()
                    .uri("/api/xbrl/companyfacts/CIK%010d.json".formatted(cik))
                    .retrieve().body(String.class);
            if (body == null || body.isBlank() || body.startsWith("<")) {
                System.out.printf("%-6s companyfacts 응답 없음/HTML%n", t);
                continue;
            }
            JsonNode gaap = MAPPER.readTree(body).path("facts").path("us-gaap");

            // 연도 → 그 해에 12개월 팩트가 있는 태그 집합. 길이로 가른다(fp를 안 믿는다).
            Map<Integer, java.util.Set<String>> byYear = new TreeMap<>();
            for (String tag : wanted) {
                for (JsonNode unit : gaap.path(tag).path("units").path("USD")) {
                    String start = unit.path("start").asString("");
                    String end = unit.path("end").asString("");
                    if (start.isEmpty() || end.isEmpty()) { continue; }
                    LocalDate s0 = LocalDate.parse(start), e0 = LocalDate.parse(end);
                    long days = ChronoUnit.DAYS.between(s0, e0);
                    if (days < 350 || days > 380) { continue; }
                    byYear.computeIfAbsent(e0.getYear(), k -> new java.util.TreeSet<>()).add(tag);
                }
            }

            List<String> line = new ArrayList<>();
            for (Map.Entry<Integer, java.util.Set<String>> e : byYear.entrySet()) {
                java.util.Set<String> tags = e.getValue();
                pairs++;
                if (tags.contains("OperatingIncomeLoss")) { continue; }
                missingOi++;
                boolean gp = tags.contains("GrossProfit") && tags.contains("OperatingExpenses");
                boolean ce = tags.contains("CostsAndExpenses")
                        && (tags.contains("Revenues")
                            || tags.contains("RevenueFromContractWithCustomerExcludingAssessedTax"));
                if (gp) { byGrossProfit++; }
                if (ce) { byCostsAndExpenses++; }
                if (gp || ce) { byEither++; }
                line.add(e.getKey() + (gp ? " GP-OE" : "") + (ce ? " REV-CE" : "")
                        + (!gp && !ce ? " (재료없음)" : ""));
            }
            if (!line.isEmpty()) {
                System.out.printf("%-6s 영업이익 없는 해: %s%n", t, String.join(" · ", line));
            }
        }

        System.out.println("\n===== 집계 =====");
        System.out.printf("표본 %d종목 · 연간 창 %d쌍 · 그중 영업이익 없는 쌍 %d%n",
                tickers.size(), pairs, missingOi);
        System.out.printf("  GrossProfit − OperatingExpenses 로 유도 가능: %d (%.1f%%)%n",
                byGrossProfit, missingOi == 0 ? 0.0 : 100.0 * byGrossProfit / missingOi);
        System.out.printf("  Revenues − CostsAndExpenses 로 유도 가능:    %d (%.1f%%)%n",
                byCostsAndExpenses, missingOi == 0 ? 0.0 : 100.0 * byCostsAndExpenses / missingOi);
        System.out.printf("  둘 중 하나로 유도 가능:                       %d (%.1f%%)%n",
                byEither, missingOi == 0 ? 0.0 : 100.0 * byEither / missingOi);
        System.out.println("\nMUST: 이 수는 유도의 **상한**이다. 실제 적재는 어댑터의 라벨링을"
                + " 거치므로 여기서 센 창과 완전히 같지 않다.");
    }

}
