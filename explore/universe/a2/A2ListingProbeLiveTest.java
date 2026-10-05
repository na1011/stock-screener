package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A2 탐침(스크래치 · 커밋 안 함) — 상장 목록의 출처. 응답을 그대로 파일로 떨군다. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+")
class A2ListingProbeLiveTest {

    static final Path OUT = Path.of(java.util.Objects.requireNonNull(System.getProperty("probe.a2.out"), "probe.a2.out"));

    @Test
    void probe() throws Exception {
        Files.createDirectories(OUT);
        RestClient trading = RestClient.builder().baseUrl("https://paper-api.alpaca.markets")
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(s -> true, (rq, rs) -> { }).build();
        RestClient data = RestClient.builder().baseUrl("https://data.alpaca.markets")
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(s -> true, (rq, rs) -> { }).build();
        RestClient sec = RestClient.builder().baseUrl("https://data.sec.gov")
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultStatusHandler(s -> true, (rq, rs) -> { }).build();

        String facts = System.getProperty("probe.a2.facts");
        if (facts != null) {
            System.out.println("probe.a2.facts=" + facts);
            for (String cik : facts.split(",")) {
                save("facts-" + cik + ".json", sec.get().uri("/api/xbrl/companyfacts/CIK{c}.json", String.format("%010d", Long.parseLong(cik)))
                        .retrieve().body(String.class));
                Thread.sleep(250);
            }
            return;
        }
        boolean barsOnly = Boolean.parseBoolean(System.getProperty("probe.a2.barsOnly", "false"));
        System.out.println("probe.a2.barsOnly=" + barsOnly + " out=" + OUT);
        if (!barsOnly) {
        // 1·2. 거래소·상태 인자 없이 전 자산 · 비활성 전 거래소
        save("assets-all-any.json", trading.get().uri("/v2/assets?asset_class=us_equity").retrieve().body(String.class));
        save("assets-inactive-any.json", trading.get().uri("/v2/assets?status=inactive&asset_class=us_equity").retrieve().body(String.class));
        // 3. 단건 — SEC NYSE/Nasdaq인데 목록에 없는 표본 · 대조군 TAP
        for (String s : List.of("ET", "DDS", "SUN", "USAC", "ASND", "PHYS", "SEB", "UEC", "TAP")) {
            save("asset-" + s + ".json", trading.get().uri("/v2/assets/{s}", s).retrieve().body(String.class));
        }
        }
        // 4. 봉 — 목록에 없는 SEC 상장 티커 전부(200개씩) · 09-22 ~ 10-03
        List<String> miss = Files.readAllLines(Path.of(java.util.Objects.requireNonNull(System.getProperty("probe.a2.miss"), "probe.a2.miss"))).stream()
                .map(l -> l.split("\t")[0]).toList();
        for (int i = 0; i < miss.size(); i += 200) {
            String syms = String.join(",", miss.subList(i, Math.min(miss.size(), i + 200)));
            String token = null; int page = 0;
            do {
                final String t = token;
                String body = data.get().uri(u -> {
                    var b = u.path("/v2/stocks/bars").queryParam("symbols", syms).queryParam("timeframe", "1Day")
                            .queryParam("start", "2026-09-22").queryParam("end", "2026-10-04")
                            .queryParam("adjustment", "raw").queryParam("feed", "sip").queryParam("limit", 10000);
                    if (t != null) b = b.queryParam("page_token", t);
                    return b.build();
                }).retrieve().body(String.class);
                save("bars-" + i + "-" + page++ + ".json", body);
                var node = new tools.jackson.databind.ObjectMapper().readTree(body);
                token = node.path("next_page_token").isNull() ? null : node.path("next_page_token").asString(null);
            } while (token != null && !token.isEmpty());
        }
        if (barsOnly) return;
        // 5. SEC frames — 발행주식수 전 발행사 한 콜(분기 순간값)
        for (String f : List.of("CY2026Q2I", "CY2026Q1I")) {
            save("frames-" + f + ".json", sec.get().uri("/api/xbrl/frames/dei/EntityCommonStockSharesOutstanding/shares/{f}.json", f)
                    .retrieve().body(String.class));
            Thread.sleep(200);
        }
    }

    static void save(String name, String body) throws Exception {
        Files.writeString(OUT.resolve(name), body == null ? "" : body);
    }
}
