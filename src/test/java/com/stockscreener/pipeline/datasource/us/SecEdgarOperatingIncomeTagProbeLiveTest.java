package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 영업이익이 비는 미국 종목이 <b>어떤 태그로</b> 그것을 내는가.
 *
 * <p><b>§0의 착수 판단이 여기서 나온다.</b> {@link SecEdgarColumn#OPERATING_INCOME}의 후보가
 * {@code OperatingIncomeLoss} <b>하나뿐인 유일한 손익 컬럼</b>이고(매출 5·순이익 3·매출원가 4),
 * 그 결과 편입 1,000 중 {@code OPM_MIN_5Y} 산출 가능이 <b>537</b>뿐이며
 * {@code PROFIT_STREAK_Y}는 <b>350종목에 거짓·과소 값</b>을 낸다(실측 2026-08-31).
 *
 * <p>같은 파일 javadoc이 이미 예고했다 — <i>"후보를 여럿 두는 이유는 회사마다, 그리고 같은
 * 회사도 연도마다 태그가 다르기 때문이다 … 표준 태그 하나만 보면 결측이 대량으로 난다."</i>
 *
 * <p><b>MUST: 후보를 더하는 것 자체가 정정을 만든다</b>(같은 파일). 그러므로 이 탐침은
 * <b>무엇을 더할지만 정하고 적재는 하지 않는다.</b> 실제 확장은 미국 지표가 0행인 동안에만
 * 안전하다 — {@code FINANCIAL_REVISED}가 보존 창 전체를 지운다.
 *
 * <p>표본은 {@code -Dprobe.tickers=AA,ADM,...}로 준다. 코드에 박으면 결손 종목이 바뀔 때마다
 * 커밋에 섞인다.
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
    @DisplayName("[live][probe] 영업이익이 비는 종목이 어떤 us-gaap 태그를 쓰는가")
    void probeOperatingIncomeTags() {
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

        // 태그 → (그 태그를 연간으로 내는 종목 수, 총 연간 팩트 수)
        Map<String, int[]> agg = new TreeMap<>();
        System.out.println("\n===== 종목별: 연간 팩트를 가진 'Operating' 계열 us-gaap 태그 =====");
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
            List<String> hits = new ArrayList<>();
            for (String tag : (Iterable<String>) () -> gaap.propertyNames().iterator()) {
                if (!tag.contains("Operating") && !tag.contains("GrossProfit")
                        && !tag.contains("CostsAndExpenses")) {
                    continue;
                }
                int annual = 0;
                for (JsonNode unit : gaap.path(tag).path("units").path("USD")) {
                    String fp = unit.path("fp").asString("");
                    if ("FY".equals(fp) && unit.hasNonNull("start")) { annual++; }
                }
                if (annual > 0) {
                    hits.add(tag + "(" + annual + ")");
                    int[] a = agg.computeIfAbsent(tag, k -> new int[2]);
                    a[0]++; a[1] += annual;
                }
            }
            hits.sort(Comparator.naturalOrder());
            System.out.printf("%-6s %s%n", t, hits.isEmpty() ? "(없음)" : String.join(" ", hits));
        }

        System.out.println("\n===== 집계: 표본 " + tickers.size() + "종목 중 몇이 그 태그를 쓰는가 =====");
        agg.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(25)
                .forEach(e -> System.out.printf("  %-3d종목 · 연간팩트 %-4d  %s%n",
                        e.getValue()[0], e.getValue()[1], e.getKey()));
        System.out.println("\n지금 SecEdgarColumn.OPERATING_INCOME 후보 = [OperatingIncomeLoss]");
    }
}
