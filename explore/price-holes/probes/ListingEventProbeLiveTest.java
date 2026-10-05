package com.stockscreener.probe;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 임시 탐침 — SEC submissions가 상장 사건 서류(8-A·CERT·424B4·25)의 날짜를 주는가. 커밋하지 않는다. */
@Tag("live")
class ListingEventProbeLiveTest {

    private static final Set<String> FORMS = Set.of("8-A12B", "8-A12G", "CERTNYS", "CERTNAS", "CERTPAC",
            "424B4", "S-1", "F-1", "25-NSE", "25", "15-12B", "8-K12B", "8-A12B/A", "S-4", "424B3");

    @Test
    void probe() throws Exception {
        String agent = System.getenv("SEC_EDGAR_USER_AGENT");
        String tickers = System.getProperty("probe.symbols", "CAI");
        ObjectMapper mapper = new ObjectMapper();
        RestClient www = RestClient.builder().baseUrl("https://www.sec.gov")
                .defaultHeader("User-Agent", agent).build();
        RestClient data = RestClient.builder().baseUrl("https://data.sec.gov")
                .defaultHeader("User-Agent", agent).build();
        JsonNode map = mapper.readTree(www.get().uri("/files/company_tickers_exchange.json").retrieve().body(String.class));
        Map<String, Long> cikByTicker = new HashMap<>();
        for (JsonNode row : map.path("data")) {
            cikByTicker.put(row.get(2).asString(), row.get(0).asLong());
        }
        for (String ticker : tickers.split(",")) {
            Long cik = cikByTicker.get(ticker);
            if (cik == null) {
                System.out.printf("PROBE4\t%s\tCIK 없음%n", ticker);
                continue;
            }
            Thread.sleep(200);
            JsonNode sub = mapper.readTree(data.get().uri("/submissions/CIK%010d.json".formatted(cik))
                    .retrieve().body(String.class));
            JsonNode recent = sub.path("filings").path("recent");
            TreeMap<String, List<String>> byForm = new TreeMap<>();
            for (int i = 0; i < recent.path("form").size(); i++) {
                String form = recent.path("form").get(i).asString();
                if (FORMS.contains(form)) {
                    byForm.computeIfAbsent(form, f -> new ArrayList<>())
                            .add(recent.path("filingDate").get(i).asString());
                }
            }
            StringBuilder line = new StringBuilder();
            byForm.forEach((form, dates) -> line.append(form).append('=')
                    .append(dates.get(dates.size() - 1)).append(dates.size() > 1 ? "(+" + (dates.size() - 1) + ")" : "")
                    .append(' '));
            System.out.printf("PROBE4\t%s\tcik=%d\tname=%s\tolder_files=%d\t%s%n", ticker, cik,
                    sub.path("name").asString(), sub.path("filings").path("files").size(), line);
        }
    }
}
