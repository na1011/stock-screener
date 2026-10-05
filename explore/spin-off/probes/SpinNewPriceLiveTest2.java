package com.stockscreener.probe;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/** 임시 탐침 — 분사 신설 회사의 분리일 근처 종가(asof=분리일 · 실체 고정). 커밋하지 않는다. */
@Tag("live")
class SpinNewPriceLiveTest {

    @Test
    void sweep() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RestClient data = RestClient.builder().baseUrl("https://data.alpaca.markets")
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY")).build();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("probe.out"))))) {
            for (String line : Files.readAllLines(Path.of(System.getProperty("probe.file")))) {
                String[] f = line.split("\t");
                String sym = f[0];
                LocalDate ex = LocalDate.parse(f[1]);
                Thread.sleep(400);
                String body = data.get().uri(uri -> uri.path("/v2/stocks/bars").queryParam("symbols", sym)
                        .queryParam("timeframe", "1Day").queryParam("start", ex.toString())
                        .queryParam("end", (ex.plusDays(6).isAfter(LocalDate.now().minusDays(1)) ? LocalDate.now().minusDays(1) : ex.plusDays(6)).toString()).queryParam("feed", "sip")
                        .queryParam("adjustment", "raw").queryParam("asof", ex.toString())
                        .queryParam("limit", 10).queryParam("sort", "asc").build()).retrieve().body(String.class);
                JsonNode bars = mapper.readTree(body).path("bars").path(sym);
                if (bars.isEmpty()) {
                    w.printf("%s\t%s\t-\t-%n", sym, ex);
                } else {
                    JsonNode b = bars.get(0);
                    w.printf("%s\t%s\t%s\t%s%n", sym, ex, b.path("t").asString("").substring(0, 10), b.path("c").asString(""));
                }
            }
        }
    }
}
