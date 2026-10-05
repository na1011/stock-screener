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

/** 임시 탐침 — 분사 자식의 ex 전 7일 봉(본 심볼 · .WI)과 ex일 봉. 커밋하지 않는다. */
@Tag("live")
class SpinCumProbeLiveTest {

    @Test
    void sweep() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RestClient data = RestClient.builder().baseUrl("https://data.alpaca.markets")
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY")).build();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("probe.out"))))) {
            for (String line : Files.readAllLines(Path.of(System.getProperty("probe.file")))) {
                String[] f = line.split("\t");
                String src = f[0], child = f[1];
                LocalDate ex = LocalDate.parse(f[2]);
                for (String sym : new String[]{child, child + ".WI"}) {
                    Thread.sleep(350);
                    try {
                        String body = data.get().uri(uri -> uri.path("/v2/stocks/bars").queryParam("symbols", sym)
                                .queryParam("timeframe", "1Day").queryParam("start", ex.minusDays(7).toString())
                                .queryParam("end", ex.toString()).queryParam("feed", "sip")
                                .queryParam("adjustment", "raw").queryParam("limit", 50).queryParam("sort", "asc")
                                .build()).retrieve().body(String.class);
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode b : mapper.readTree(body).path("bars").path(sym)) {
                            sb.append(b.path("t").asString("").substring(0, 10)).append('=').append(b.path("c").asString("")).append(' ');
                        }
                        w.printf("%s\t%s\t%s\t%s\t%s%n", src, child, ex, sym, sb.toString().trim());
                    } catch (Exception e) {
                        w.printf("%s\t%s\t%s\t%s\tERR %s%n", src, child, ex, sym, e.getMessage());
                    }
                }
            }
        }
    }
}
