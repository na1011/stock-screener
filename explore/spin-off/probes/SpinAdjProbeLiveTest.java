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

/** 임시 탐침 — 분사 모체의 raw 대 adjustment=spin-off 종가(소스가 쓰는 계수 관례 대조). 커밋하지 않는다. */
@Tag("live")
class SpinAdjProbeLiveTest {

    @Test
    void sweep() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RestClient data = RestClient.builder().baseUrl("https://data.alpaca.markets")
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY")).build();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("probe.out"))))) {
            for (String line : Files.readAllLines(Path.of(System.getProperty("probe.file")))) {
                String[] f = line.split("\t");
                String sym = f[0];
                LocalDate ex = LocalDate.parse(f[1]);
                LocalDate end = ex.plusDays(4).isAfter(yesterday) ? yesterday : ex.plusDays(4);
                for (String adj : new String[]{"raw", "spin-off", "all"}) {
                    Thread.sleep(400);
                    String body;
                    try {
                        body = data.get().uri(uri -> uri.path("/v2/stocks/bars").queryParam("symbols", sym)
                                .queryParam("timeframe", "1Day").queryParam("start", ex.minusDays(5).toString())
                                .queryParam("end", end.toString()).queryParam("feed", "sip")
                                .queryParam("adjustment", adj).queryParam("limit", 20).queryParam("sort", "asc")
                                .build()).retrieve().body(String.class);
                    } catch (Exception e) {
                        w.printf("%s\t%s\t%s\tERR\t%s%n", sym, ex, adj, e.getMessage());
                        continue;
                    }
                    for (JsonNode b : mapper.readTree(body).path("bars").path(sym)) {
                        w.printf("%s\t%s\t%s\t%s\t%s%n", sym, ex, adj, b.path("t").asString("").substring(0, 10),
                                b.path("c").asString(""));
                    }
                }
            }
        }
    }
}
