package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.support.ReadOnlyDb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>미국 과거 명부가 못 담는 것의 크기를 수로 낸다</b>(ADR-0135).
 *
 * <p>{@code UsMarketCapUniverseAdapter}는 {@code listing.fetchActiveAssets()}로 <b>오늘의</b>
 * 활성 목록을 받으므로, 그 사이 상장폐지된 종목은 과거 명부에 <b>원리적으로 못 들어온다.</b>
 *
 * <p><b>MUST NOT: 그것을 「원리적으로 못 잰다」로 읽지 않는다.</b> 막는 것은 어댑터이지 소스가
 * 아니다 — {@code /v2/assets}에서 {@code status}를 빼면 비활성 종목이 함께 오고, 그 심볼의
 * <b>봉이 그 창에 있다는 것이 그날 거래됐다는 증거</b>다. 상장 여부는 유도된다.
 *
 * <p>그래서 이 탐침이 내는 것은 <b>구멍의 상한</b>이다 — 「창 안에 실제로 거래된 비활성 종목」의
 * 수. 그보다 더 좁히려면 그 심볼들의 주식수가 필요하고 그것은 SEC·CIK 문제다(ADR-0089).
 *
 * <p><b>MUST: 이 수를 「복원이 놓친 종목 수」로 읽지 않는다.</b> 그중 상위 1,000에 들었을 것이
 * 몇인지는 <b>주식수 없이 못 가른다.</b> 거래대금을 함께 내는 것은 그 판단의 재료일 뿐이다.
 *
 * <p>월봉을 쓰는 이유는 「거래했는가」만 물으면 되기 때문이다 — 200심볼 × 12개월 = 2,400점이라
 * {@code limit} 10,000 안에 들어 배치당 한 페이지다. 일봉이면 배치당 여섯 페이지가 된다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class UsDelistedUniverseGapProbeLiveTest {

    private static final String DATA_URL = "https://data.alpaca.markets";
    private static final String TRADING_URL = "https://paper-api.alpaca.markets";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 보존 창(미국)의 시작. {@code indicator_trade_date}의 최소값이다. */
    private static final String WINDOW_FROM = "2025-09-03";

    /** 날짜만 주는 {@code end}는 어제도 403이다 — 뉴욕 기준 그날의 끝이 UTC로는 미래가 된다. */
    private static final String WINDOW_TO = LocalDate.now().minusDays(2).toString();

    private static final int BATCH = 200;

    /**
     * 봉을 물어볼 수 있는 심볼의 모양.
     *
     * <p><b>비활성 목록은 깨끗한 종목 집합이 아니다</b>(2026-09-09 실측). {@code 550CVR021}·
     * {@code 286992540}·{@code 5326esc96} 같은 CVR·권리·CUSIP 자리표가 섞여 있고,
     * <b>그 하나가 배치 전체를 400으로 죽인다</b> — {@code /v2/stocks/bars}는 모르는 심볼을
     * 조용히 빼는 것이 아니라 <b>요청을 통째로 거절한다.</b> 그래서 물어보기 전에 거른다.
     */
    private static final java.util.regex.Pattern TRADABLE_SYMBOL =
            java.util.regex.Pattern.compile("^[A-Z]{1,5}(\\.[A-Z])?$");

    /**
     * 폐지 종목의 심볼에 붙는 접미. <b>이것을 떼야 봉이 나온다</b> — 소스가 폐지 뒤 심볼을
     * {@code HYAC_DELISTED}로 바꿔 두는데 봉은 원래 이름으로만 있다.
     */
    private static final String DELISTED_SUFFIX = "_DELISTED";

    @Test
    @DisplayName("[live][probe] 복원이 못 담는 미국 폐지 종목의 상한을 센다 — 약 12콜")
    void measureDelistedGap() throws Exception {
        RestClient trading = client(TRADING_URL);
        RestClient data = client(DATA_URL);

        JsonNode assets = getJson(trading, uri -> uri.path("/v2/assets")
                .queryParam("asset_class", "us_equity")
                .queryParam("exchange", "NYSE,NASDAQ")
                .build());
        assertThat(assets).as("자산 목록을 못 받으면 잴 것이 없다").isNotNull();

        TreeSet<String> activeSymbols = new TreeSet<>();
        TreeSet<String> askable = new TreeSet<>();
        int inactiveCount = 0;
        int notAskable = 0;
        for (JsonNode asset : assets) {
            if ("active".equals(asset.path("status").asText(""))) {
                activeSymbols.add(asset.path("symbol").asText(""));
                continue;
            }
            inactiveCount++;
            String raw = asset.path("symbol").asText("");
            String symbol = raw.endsWith(DELISTED_SUFFIX)
                    ? raw.substring(0, raw.length() - DELISTED_SUFFIX.length())
                    : raw;
            if (TRADABLE_SYMBOL.matcher(symbol).matches()) {
                askable.add(symbol);
            } else {
                notAskable++;
            }
        }
        int active = activeSymbols.size();
        // MUST: 접미를 뗀 이름이 지금 활성인 심볼과 겹치면 뺀다. 안 빼면 그 봉이 **활성 종목의
        // 것**이라 「폐지됐는데 거래됐다」로 잘못 세인다 — 첫 판이 그렇게 세어 LHX·COHR 같은
        // 대형주를 구멍에 넣었다. 그 종목들은 오늘도 상장돼 있고 복원 후보에 정상적으로 든다.
        int shadowed = 0;
        for (String symbol : new ArrayList<>(askable)) {
            if (activeSymbols.contains(symbol)) {
                askable.remove(symbol);
                shadowed++;
            }
        }
        List<String> inactive = new ArrayList<>(askable);
        System.out.println("\n===== 미국 폐지 종목 구멍 (ADR-0135) =====");
        System.out.printf("자산 전체 %d · 활성 %d · 비활성 %d%n",
                active + inactiveCount, active, inactiveCount);
        System.out.printf("그중 봉을 물어볼 수 있는 심볼 %d — 자리표 %d 제외 · 지금 활성인 같은 이름 %d 제외%n",
                inactive.size(), notAskable, shadowed);
        System.out.println("MUST: 비활성 수는 상한이 아니다 — 그중 창 안에 실제로 거래된 것만 셈에 든다.");
        assertThat(inactive)
                .as("status를 빼도 비활성이 0이면 이 탐침이 아무것도 못 잰 것이다")
                .isNotEmpty();

        Map<String, BigDecimal> tradedValue = new LinkedHashMap<>();
        int calls = 1;
        int unmeasuredBatches = 0;
        int droppedSymbols = 0;
        for (int i = 0; i < inactive.size(); i += BATCH) {
            List<String> batch =
                    new ArrayList<>(inactive.subList(i, Math.min(i + BATCH, inactive.size())));
            JsonNode bars = null;
            // 걸러도 남는 모르는 심볼 하나가 배치를 죽이므로, 이름을 지목당하면 빼고 다시 묻는다.
            // 상한을 두는 이유는 무한 재시도가 쿼터를 태우기 때문이다.
            for (int attempt = 0; attempt < 12 && bars == null && !batch.isEmpty(); attempt++) {
                String symbols = String.join(",", batch);
                Attempt result = getBars(data, symbols);
                calls++;
                bars = result.body();
                if (bars == null && result.invalidSymbol() != null) {
                    batch.remove(result.invalidSymbol());
                    droppedSymbols++;
                } else if (bars == null) {
                    break;
                }
            }
            if (bars == null) {
                unmeasuredBatches++;
                System.out.printf("배치 %d: 봉을 못 받았다 — 그 배치는 못 셌다%n", i / BATCH);
                continue;
            }
            JsonNode byTicker = bars.path("bars");
            for (Map.Entry<String, JsonNode> entry : byTicker.properties()) {
                BigDecimal value = BigDecimal.ZERO;
                for (JsonNode bar : entry.getValue()) {
                    value = value.add(BigDecimal.valueOf(bar.path("vw").asDouble(0))
                            .multiply(BigDecimal.valueOf(bar.path("v").asLong(0))));
                }
                tradedValue.put(entry.getKey(), value);
            }
        }

        System.out.printf("%n창(%s ~ %s) 안에 실제로 거래된 비활성 종목: %d / %d — 콜 %d회%n",
                WINDOW_FROM, WINDOW_TO, tradedValue.size(), inactive.size(), calls);
        System.out.printf("못 센 배치 %d개 · 이름을 지목당해 뺀 심볼 %d개%n",
                unmeasuredBatches, droppedSymbols);
        if (unmeasuredBatches > 0) {
            System.out.println("MUST: 못 센 배치가 있으면 위 수는 상한이 아니라 하한이다 — 「없다」로 읽지 마라.");
        }

        List<Map.Entry<String, BigDecimal>> ranked = new ArrayList<>(tradedValue.entrySet());
        ranked.sort(Map.Entry.<String, BigDecimal>comparingByValue().reversed());
        System.out.println("거래대금 상위 20 (달러):");
        for (Map.Entry<String, BigDecimal> entry : ranked.subList(0, Math.min(20, ranked.size()))) {
            System.out.printf("  %-8s %,.0f%n", entry.getKey(), entry.getValue());
        }

        TreeSet<String> known = new TreeSet<>(readTickers());
        long inStock = tradedValue.keySet().stream().filter(known::contains).count();
        System.out.printf("%n그중 stock 표에 이미 있는 것: %d — 나머지 %d는 복원해도 후보가 될 수 없다%n",
                inStock, tradedValue.size() - inStock);
        System.out.println("MUST NOT: 이 수를 「복원이 놓친 종목 수」로 읽지 마라 — 그중 상위 1,000에"
                + " 들었을 것이 몇인지는 주식수 없이 못 가른다. 이것은 구멍의 상한이다.");
    }

    /** 400 본문이 지목한 심볼. 소스가 <b>이름을 말해 주므로</b> 그것만 빼고 다시 물을 수 있다. */
    private record Attempt(JsonNode body, String invalidSymbol) {
    }

    private static Attempt getBars(RestClient data, String symbols) {
        ResponseEntity<String> response = data.get()
                .uri(uri -> uri.path("/v2/stocks/bars")
                        .queryParam("symbols", symbols)
                        .queryParam("timeframe", "1Month")
                        .queryParam("start", WINDOW_FROM).queryParam("end", WINDOW_TO)
                        .queryParam("feed", "sip").queryParam("adjustment", "raw")
                        .queryParam("limit", 10000).queryParam("sort", "asc").build())
                .retrieve().toEntity(String.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            return new Attempt(MAPPER.readTree(response.getBody()), null);
        }
        String body = String.valueOf(response.getBody());
        java.util.regex.Matcher named =
                java.util.regex.Pattern.compile("invalid symbol: ([^\"]+)").matcher(body);
        if (named.find()) {
            return new Attempt(null, named.group(1));
        }
        System.out.printf("HTTP %s — 본문 앞머리: %.200s%n", response.getStatusCode(), body);
        return new Attempt(null, null);
    }

    private static List<String> readTickers() throws Exception {
        List<String> tickers = new ArrayList<>();
        try (Connection connection = ReadOnlyDb.open();
             PreparedStatement statement =
                     connection.prepareStatement("SELECT ticker FROM stock WHERE market_code = 'US'");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                tickers.add(rs.getString(1));
            }
        }
        return tickers;
    }

    private static JsonNode getJson(RestClient client,
                                    java.util.function.Function<org.springframework.web.util.UriBuilder,
                                            java.net.URI> uri) {
        ResponseEntity<String> response = client.get().uri(uri).retrieve().toEntity(String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            // MUST: 상태코드를 안 보고 본문을 파싱하면 오류가 「0건」이 된다(data-pipeline.md).
            System.out.printf("HTTP %s — 본문 앞머리: %.200s%n",
                    response.getStatusCode(), String.valueOf(response.getBody()));
            return null;
        }
        return MAPPER.readTree(response.getBody());
    }

    private static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }
}
