package com.stockscreener.probe;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/** 임시 탐침 — 자식 가격이 없던 분사 3건을 asof 없이 · 넓은 창 · feed 둘로 다시 묻는다. 커밋하지 않는다. */
@Tag("live")
class SpinChildRetryLiveTest {

    @Test
    void sweep() throws Exception {
        RestClient data = RestClient.builder().baseUrl("https://data.alpaca.markets")
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY")).build();
        String[][] qs = {{"MRP", "2025-01-10", "2025-02-20"}, {"SNRBY", "2024-11-01", "2024-12-10"},
                {"THNPF", "2021-02-05", "2021-03-10"}, {"Q", "2025-10-28", "2025-11-05"}};
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("probe.out"))))) {
            for (String[] q : qs) {
                for (String feed : new String[]{"sip", "iex"}) {
                    Thread.sleep(400);
                    try {
                        String body = data.get().uri(uri -> uri.path("/v2/stocks/bars").queryParam("symbols", q[0])
                                .queryParam("timeframe", "1Day").queryParam("start", q[1]).queryParam("end", q[2])
                                .queryParam("feed", feed).queryParam("adjustment", "raw").queryParam("limit", 100)
                                .queryParam("sort", "asc").build()).retrieve().body(String.class);
                        w.printf("%s\t%s\t%s%n", q[0], feed, body.length() > 1500 ? body.substring(0, 1500) : body);
                    } catch (Exception e) {
                        w.printf("%s\t%s\tERR\t%s%n", q[0], feed, e.getMessage());
                    }
                }
            }
        }
    }
}
