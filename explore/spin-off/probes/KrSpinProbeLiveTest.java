package com.stockscreener.probe;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/** 임시 탐침 — 국내 인적분할 7건: DART cmpDvDecsn의 신설법인 · 공공데이터포털의 신설법인 상장 봉. 커밋하지 않는다. */
@Tag("live")
class KrSpinProbeLiveTest {

    @Test
    void sweep() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String dartKey = System.getenv("DART_API_KEY");
        String goKey = System.getenv("DATA_GO_KR_SERVICE_KEY");
        RestClient dart = RestClient.builder().baseUrl("https://opendart.fss.or.kr/api").build();
        RestClient go = RestClient.builder().baseUrl("https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService").build();
        byte[] zip = dart.get().uri(u -> u.path("/corpCode.xml").queryParam("crtfc_key", dartKey).build()).retrieve().body(byte[].class);
        String xml;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            z.getNextEntry();
            xml = new String(z.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> corp = new HashMap<>();
        Matcher m = Pattern.compile("<corp_code>(\\d+)</corp_code>\\s*<corp_name>[^<]*</corp_name>\\s*(?:<corp_eng_name>[^<]*</corp_eng_name>\\s*)?<stock_code>(\\d{6})</stock_code>").matcher(xml);
        while (m.find()) {
            corp.put(m.group(2), m.group(1));
        }
        DateTimeFormatter f = DateTimeFormatter.BASIC_ISO_DATE;
        String[][] evs = {{"036830", "2020-08-06"}, {"017670", "2021-11-29"}, {"005440", "2023-04-10"}, {"010060", "2023-05-30"},
                {"012450", "2024-09-27"}, {"207940", "2025-11-24"}, {"000880", "2026-08-25"}};
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("probe.out"))))) {
            w.printf("corp map size=%d%n", corp.size());
            for (String[] ev : evs) {
                String cc = corp.get(ev[0]);
                LocalDate d = LocalDate.parse(ev[1]);
                w.printf("== %s %s corp=%s%n", ev[0], ev[1], cc);
                if (cc == null) continue;
                Thread.sleep(300);
                String body = dart.get().uri(u -> u.path("/cmpDvDecsn.json").queryParam("crtfc_key", dartKey)
                        .queryParam("corp_code", cc).queryParam("bgn_de", d.minusYears(1).format(f))
                        .queryParam("end_de", d.format(f)).build()).retrieve().body(String.class);
                JsonNode root = mapper.readTree(body);
                w.printf("dart status=%s%n", root.path("status").asString(""));
                for (JsonNode it : root.path("list")) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, JsonNode> e : it.properties()) {
                        String v = e.getValue().asString("");
                        if (!v.isBlank() && !v.equals("-")) sb.append(e.getKey()).append('=').append(v.length() > 120 ? v.substring(0, 120) : v).append(" | ");
                    }
                    w.println(sb);
                }
            }
        }
    }
}
