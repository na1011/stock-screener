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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 임시 탐침 — 미국 보존 창 편입 전원의 SEC 상장·폐지 서류(2019 뒤). 커밋하지 않는다. */
@Tag("live")
class ListingEventSweepLiveTest {

    private static final Set<String> FORMS = Set.of("8-A12B", "8-A12B/A", "8-A12G", "CERTNYS", "CERTNAS",
            "CERTPAC", "CERTARCA", "424B4", "10-12B", "10-12B/A", "25-NSE", "25", "15-12B", "S-4", "F-1", "S-1");
    private static final String FROM = "2019-01-01";

    @Test
    void sweep() throws Exception {
        String agent = System.getenv("SEC_EDGAR_USER_AGENT");
        Path in = Path.of(System.getProperty("probe.file"));
        Path out = Path.of(System.getProperty("probe.out"));
        ObjectMapper mapper = new ObjectMapper();
        RestClient www = RestClient.builder().baseUrl("https://www.sec.gov").defaultHeader("User-Agent", agent).build();
        RestClient data = RestClient.builder().baseUrl("https://data.sec.gov").defaultHeader("User-Agent", agent).build();
        JsonNode map = mapper.readTree(www.get().uri("/files/company_tickers_exchange.json").retrieve().body(String.class));
        Map<String, Long> cikByTicker = new HashMap<>();
        for (JsonNode row : map.path("data")) {
            cikByTicker.put(row.get(2).asString(), row.get(0).asLong());
        }
        Map<Long, String> done = new HashMap<>();
        int calls = 1;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            for (String line : Files.readAllLines(in)) {
                String ticker = line.split("\t")[0];
                Long cik = cikByTicker.get(ticker);
                if (cik == null) {
                    cik = cikByTicker.get(ticker.replace('.', '-'));
                }
                if (cik == null) {
                    w.printf("%s\t-\t-\tNO_CIK%n", ticker);
                    continue;
                }
                String cached = done.get(cik);
                if (cached != null) {
                    w.printf("%s\t%d\t%s%n", ticker, cik, cached);
                    continue;
                }
                Thread.sleep(130);
                calls++;
                JsonNode sub = mapper.readTree(data.get().uri("/submissions/CIK%010d.json".formatted(cik))
                        .retrieve().body(String.class));
                TreeMap<String, List<String>> events = new TreeMap<>();
                collect(sub.path("filings").path("recent"), events);
                for (JsonNode file : sub.path("filings").path("files")) {
                    if (file.path("filingTo").asString("").compareTo(FROM) < 0) {
                        continue;
                    }
                    Thread.sleep(130);
                    calls++;
                    JsonNode older = mapper.readTree(data.get().uri("/submissions/" + file.path("name").asString())
                            .retrieve().body(String.class));
                    collect(older, events);
                }
                StringBuilder ev = new StringBuilder();
                events.forEach((date, forms) -> ev.append(date).append(':').append(String.join("+", forms)).append(' '));
                String row = sub.path("name").asString().replace('\t', ' ') + "\t" + ev.toString().trim();
                done.put(cik, row);
                w.printf("%s\t%d\t%s%n", ticker, cik, row);
            }
        }
        System.out.printf("PROBE5 calls=%d out=%s%n", calls, out);
    }

    private static void collect(JsonNode filings, TreeMap<String, List<String>> events) {
        JsonNode forms = filings.path("form");
        JsonNode dates = filings.path("filingDate");
        for (int i = 0; i < forms.size(); i++) {
            String form = forms.get(i).asString();
            String date = dates.get(i).asString();
            if (FORMS.contains(form) && date.compareTo(FROM) >= 0) {
                List<String> list = events.computeIfAbsent(date, d -> new ArrayList<>());
                if (!list.contains(form)) {
                    list.add(form);
                }
            }
        }
    }
}
