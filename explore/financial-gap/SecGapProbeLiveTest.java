package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** (가) 탐침(스크래치 · 커밋 안 함) — companyfacts가 빠뜨린 공시를 다른 SEC 구조화 경로가 주는가. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+")
class SecGapProbeLiveTest {

    @Test
    void probe() throws Exception {
        Path out = Path.of(Objects.requireNonNull(System.getProperty("probe.ga.out"), "probe.ga.out"));
        Files.createDirectories(out);
        System.out.println("probe.ga.out=" + out);
        RestClient data = RestClient.builder().baseUrl("https://data.sec.gov")
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultStatusHandler(s -> true, (rq, rs) -> { }).build();
        RestClient www = RestClient.builder().baseUrl("https://www.sec.gov")
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultStatusHandler(s -> true, (rq, rs) -> { }).build();
        // 1. frames — 분기 순간값 · 분기 흐름값
        save(out, "frames-Assets-CY2026Q2I.json", data.get().uri("/api/xbrl/frames/us-gaap/Assets/USD/CY2026Q2I.json").retrieve().toEntity(String.class));
        Thread.sleep(300);
        save(out, "frames-NetIncomeLoss-CY2026Q2.json", data.get().uri("/api/xbrl/frames/us-gaap/NetIncomeLoss/USD/CY2026Q2.json").retrieve().toEntity(String.class));
        Thread.sleep(300);
        // 2. 공시 묶음 색인 — ABT 2026-06-30 10-Q · 대조군 AAPL 2026-06-27 10-Q
        save(out, "index-ABT.json", www.get().uri("/Archives/edgar/data/1800/000162828026050134/index.json").retrieve().toEntity(String.class));
        Thread.sleep(300);
        // 3. SEC 재무제표 데이터셋 2026q3 · 2026q2 — 머리만
        for (String q : new String[] {"2026q3", "2026q2"}) {
            var head = www.head().uri("/files/dera/data/financial-statement-data-sets/{q}.zip", q).retrieve().toBodilessEntity();
            Files.writeString(out.resolve("fsds-" + q + ".head"), head.getStatusCode() + " " + head.getHeaders());
            Thread.sleep(300);
        }
    }

    static void save(Path out, String name, org.springframework.http.ResponseEntity<String> r) throws Exception {
        Files.writeString(out.resolve(name), r.getBody() == null ? "" : r.getBody());
        Files.writeString(out.resolve(name + ".status"), String.valueOf(r.getStatusCode()));
    }
}
