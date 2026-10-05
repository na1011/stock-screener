package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** A2 탐침(스크래치 · 커밋 안 함) — 목록에서 빠진 뒤에도 시세·기업행위가 나오는가. 대조군 TAP. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+")
class A2PostFlipProbeLiveTest {
    @Test
    void probe() throws Exception {
        Path out = Path.of(Objects.requireNonNull(System.getProperty("probe.a2.out"), "probe.a2.out"));
        Files.createDirectories(out);
        System.out.println("probe.a2.out=" + out);
        RestClient data = RestClient.builder().baseUrl("https://data.alpaca.markets")
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(s -> true, (rq, rs) -> { }).build();
        String syms = "ET,DDS,SUN,USAC,TAP";
        Files.writeString(out.resolve("min-bars-1005.json"), data.get().uri(u -> u.path("/v2/stocks/bars")
                .queryParam("symbols", syms).queryParam("timeframe", "1Hour")
                .queryParam("start", "2026-10-05T13:00:00Z")
                .queryParam("feed", "iex").queryParam("limit", 10000).build()).retrieve().body(String.class));
        Files.writeString(out.resolve("day-bars-0928.json"), data.get().uri(u -> u.path("/v2/stocks/bars")
                .queryParam("symbols", syms).queryParam("timeframe", "1Day")
                .queryParam("start", "2026-09-28").queryParam("end", "2026-10-04")
                .queryParam("feed", "sip").queryParam("adjustment", "raw").queryParam("limit", 10000).build()).retrieve().body(String.class));
        if (false) Files.writeString(out.resolve("ca-2026.json"), data.get().uri(u -> u.path("/v1/corporate-actions")
                .queryParam("symbols", syms).queryParam("start", "2026-01-01").queryParam("end", "2026-10-05")
                .queryParam("limit", 1000).build()).retrieve().body(String.class));
    }
}
