package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** (가) 탐침 2(스크래치 · 커밋 안 함) — 80종목 submissions·companyfacts 전수 + 대조군 공시의 XBRL 인스턴스. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+")
class SecGapProbe2LiveTest {

    @Test
    void probe() throws Exception {
        Path out = Path.of(Objects.requireNonNull(System.getProperty("probe.ga.out"), "probe.ga.out"));
        String ciks = Objects.requireNonNull(System.getProperty("probe.ga.ciks"), "probe.ga.ciks");
        String instances = System.getProperty("probe.ga.instances", "");
        Files.createDirectories(out);
        System.out.println("probe.ga.out=" + out + " ciks=" + ciks.split(",").length + " instances=" + instances);
        RestClient data = RestClient.builder().baseUrl("https://data.sec.gov")
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultStatusHandler(s -> true, (rq, rs) -> { }).build();
        RestClient www = RestClient.builder().baseUrl("https://www.sec.gov")
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultStatusHandler(s -> true, (rq, rs) -> { }).build();
        for (String cik : ciks.split(",")) {
            if (cik.isBlank()) continue;
            String padded = String.format("%010d", Long.parseLong(cik.trim()));
            Path subs = out.resolve("subs-" + cik.trim() + ".json");
            if (!Files.exists(subs)) {
                Files.writeString(subs, data.get().uri("/submissions/CIK{c}.json", padded).retrieve().body(String.class));
                Thread.sleep(250);
            }
            Path facts = out.resolve("facts-" + cik.trim() + ".json");
            if (!Files.exists(facts)) {
                Files.writeString(facts, data.get().uri("/api/xbrl/companyfacts/CIK{c}.json", padded).retrieve().body(String.class));
                Thread.sleep(250);
            }
        }
        String idx = System.getProperty("probe.ga.xbrlidx", "");
        for (String q : idx.split(",")) {
            if (q.isBlank()) continue;
            var r = www.get().uri("/Archives/edgar/full-index/{q}/xbrl.idx", q).retrieve().toEntity(String.class);
            Files.writeString(out.resolve("xbrlidx-" + q.replace("/", "-") + ".txt"), r.getStatusCode() + "\n" + (r.getBody() == null ? "" : r.getBody()));
            Thread.sleep(250);
        }
        // 인스턴스 — cik:accession(대시 포함) 목록
        ObjectMapper mapper = new ObjectMapper();
        for (String spec : instances.split(",")) {
            if (spec.isBlank()) continue;
            String[] p = spec.split(":");
            String cik = p[0];
            String acc = p[1].replace("-", "");
            String index = www.get().uri("/Archives/edgar/data/{c}/{a}/index.json", cik, acc).retrieve().body(String.class);
            Files.writeString(out.resolve("index-" + cik + "-" + acc + ".json"), index);
            Thread.sleep(250);
            for (JsonNode item : mapper.readTree(index).path("directory").path("item")) {
                String name = item.path("name").asString("");
                if (name.endsWith("_htm.xml")) {
                    Files.writeString(out.resolve("inst-" + cik + "-" + acc + ".xml"),
                            www.get().uri("/Archives/edgar/data/{c}/{a}/{n}", cik, acc, name).retrieve().body(String.class));
                    Thread.sleep(250);
                }
            }
        }
    }
}
