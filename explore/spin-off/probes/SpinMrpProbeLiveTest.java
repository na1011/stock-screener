package com.stockscreener.probe;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/** 임시 탐침 — 분사 자식의 ex 전 when-issued 봉이 어떤 심볼로든 오는가 + MBGL·RAL 가격. 커밋하지 않는다. */
@Tag("live")
class SpinMrpProbeLiveTest {

    @Test
    void sweep() throws Exception {
        RestClient data = RestClient.builder().baseUrl("https://data.alpaca.markets")
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY")).build();
        String[][] qs = {{"MRP.WI", "2025-01-15", "2025-02-07"}, {"MRP", "2025-01-15", "2025-02-07"},
                {"LEN", "2025-01-15", "2025-02-07"}, {"LEN.B", "2025-01-15", "2025-01-24"},
                {"SNREY", "2024-11-08", "2024-11-18"}, {"SNRBY", "2024-11-08", "2024-12-31"},
                {"LBTYB", "2024-11-08", "2024-11-18"}};
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("probe.out"))))) {
            for (String[] q : qs) {
                for (String sym : new String[]{q[0]}) {
                    Thread.sleep(400);
                    try {
                        String body = data.get().uri(uri -> uri.path("/v2/stocks/bars").queryParam("symbols", sym)
                                .queryParam("timeframe", "1Day").queryParam("start", q[1]).queryParam("end", q[2])
                                .queryParam("feed", "sip").queryParam("adjustment", "raw").queryParam("limit", 100)
                                .queryParam("sort", "asc").build()).retrieve().body(String.class);
                        w.printf("%s\t%s\t%s%n", q[0], sym, body.replaceAll("\"(h|l|n|o|v|vw)\":[^,}]+,?", ""));
                    } catch (Exception e) {
                        w.printf("%s\t%s\tERR\t%s%n", q[0], sym, e.getMessage());
                    }
                }
            }
        }
    }
}
