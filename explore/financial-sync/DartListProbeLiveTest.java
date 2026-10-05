package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** (나) 국내 탐침(스크래치 · 커밋 안 함) — DART 공시검색이 정기보고서를 날짜 구간으로 어떻게 주는가. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+")
class DartListProbeLiveTest {
    @Test
    void probe() throws Exception {
        Path out = Path.of(Objects.requireNonNull(System.getProperty("probe.nakr.out"), "probe.nakr.out"));
        Files.createDirectories(out);
        System.out.println("probe.nakr.out=" + out);
        RestClient dart = RestClient.builder().baseUrl("https://opendart.fss.or.kr/api")
                .defaultStatusHandler(s -> true, (rq, rs) -> { }).build();
        for (int page = 1; page <= 2; page++) {
            final int p = page;
            Files.writeString(out.resolve("list-A-p" + page + ".json"), dart.get().uri(u -> u.path("/list.json")
                    .queryParam("crtfc_key", System.getenv("DART_API_KEY"))
                    .queryParam("bgn_de", "20260801").queryParam("end_de", "20261005")
                    .queryParam("pblntf_ty", "A").queryParam("page_no", p).queryParam("page_count", 100).build())
                    .retrieve().body(String.class));
            Thread.sleep(500);
        }
    }
}
