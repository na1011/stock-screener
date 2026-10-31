package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/**
 * 지주회사 재편 승계 발행인(8-K12B)의 재무가 원장에 어떻게 나뉘어 있는가 — XOM(2026-07-01 · 옛 등록인 Exxon Mobil Corp
 * {@code 0000034088} → 새 등록인 ExxonMobil Holdings Corp {@code 0002115436}).
 *
 * <p>두 등록인의 companyfacts마다 매출 · 순이익 · 표지 주식수 팩트를 (접수번호 · 양식 · 기간 끝)으로 찍고, 두 원장이 같은 기간을 같은 값으로
 * 겹치는지(새 등록인의 첫 10-Q가 옛 등록인 기간을 비교 열로 싣는지)를 본다. <b>판정하지 않는다</b> — 승계 발행인 연속 규칙의 재료다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+",
        disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecSuccessorIssuerProbeLiveTest {

    private static final Map<String, Long> REGISTRANTS = Map.of("옛 0000034088", 34088L, "새 0002115436", 2115436L);
    private static final String[] TAGS = {"Revenues", "NetIncomeLoss", "EntityCommonStockSharesOutstanding"};

    @Test
    @DisplayName("[live][probe] XOM 두 등록인의 companyfacts가 어느 기간을 어느 값으로 싣는가")
    void probeXomRegistrants() throws Exception {
        RestClient data = RestClient.builder()
                .baseUrl("https://data.sec.gov")
                // MUST NOT: 이 값을 출력에 찍지 않는다 — 이메일이 들어 있다.
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
        ObjectMapper mapper = new ObjectMapper();
        for (Map.Entry<String, Long> registrant : new TreeMap<>(REGISTRANTS).entrySet()) {
            String body = data.get().uri("/api/xbrl/companyfacts/CIK%010d.json".formatted(registrant.getValue()))
                    .retrieve().body(String.class);
            System.out.printf("%n===== %s =====%n", registrant.getKey());
            if (body == null || !body.trim().startsWith("{")) {
                System.out.println("companyfacts 응답 없음/HTML(404 포함) — 길이 " + (body == null ? 0 : body.length()));
                continue;
            }
            JsonNode facts = mapper.readTree(body).path("facts");
            for (String tag : TAGS) {
                JsonNode units = facts.path(tag.startsWith("Entity") ? "dei" : "us-gaap").path(tag).path("units");
                System.out.printf("-- %s%n", tag);
                int printed = 0;
                for (Map.Entry<String, JsonNode> unit : units.properties()) {
                    for (JsonNode f : unit.getValue()) {
                        String end = f.path("end").asString("");
                        if (end.compareTo("2024-06-30") < 0) {
                            continue;
                        }
                        System.out.printf("   %s %s fy=%s fp=%s %s~%s val=%s accn=%s filed=%s%n", unit.getKey(), f.path("form").asString(""),
                                f.path("fy").asString(""), f.path("fp").asString(""), f.path("start").asString(""), end,
                                f.path("val").asString(""), f.path("accn").asString(""), f.path("filed").asString(""));
                        if (++printed >= 40) {
                            break;
                        }
                    }
                }
            }
            Thread.sleep(250);
        }
    }
}
