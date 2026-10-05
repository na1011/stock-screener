package com.stockscreener.probe;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 임시 탐침 — 미국 보존 창 편입의 분사(spin_off) 전수. 커밋하지 않는다. */
@Tag("live")
class SpinOffSweepLiveTest {

    @Test
    void sweep() throws Exception {
        List<String> tickers = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(System.getProperty("probe.file")))) {
            tickers.add(line.split("\t")[0]);
        }
        Path out = Path.of(System.getProperty("probe.out"));
        ObjectMapper mapper = new ObjectMapper();
        RestClient data = RestClient.builder().baseUrl("https://data.alpaca.markets")
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY")).build();
        int calls = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            for (int i = 0; i < tickers.size(); i += 100) {
                String symbols = String.join(",", tickers.subList(i, Math.min(i + 100, tickers.size())));
                String token = null;
                do {
                    Thread.sleep(400);
                    calls++;
                    String t = token;
                    String body = data.get().uri(uri -> {
                        uri.path("/v1/corporate-actions").queryParam("symbols", symbols)
                                .queryParam("types", "spin_off").queryParam("start", "2019-01-01")
                                .queryParam("end", "2026-10-05").queryParam("limit", 1000);
                        if (t != null) {
                            uri.queryParam("page_token", t);
                        }
                        return uri.build();
                    }).retrieve().body(String.class);
                    JsonNode root = mapper.readTree(body);
                    for (JsonNode ev : root.path("corporate_actions").path("spin_offs")) {
                        w.printf("%s\t%s\t%s\t%s\t%s\t%s\t%s%n", ev.path("source_symbol").asString(""),
                                ev.path("new_symbol").asString(""), ev.path("source_rate").asString(""),
                                ev.path("new_rate").asString(""), ev.path("ex_date").asString(""),
                                ev.path("record_date").asString(""), ev.path("process_date").asString(""));
                    }
                    token = root.path("next_page_token").isNull() ? null : root.path("next_page_token").asString(null);
                } while (token != null && !token.isEmpty());
            }
        }
        System.out.printf("PROBE6 calls=%d%n", calls);
    }
}
