package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미국 유니버스를 <b>시총 상위 N</b>으로 정의하기 전에 재야 하는 것들.
 *
 * <p><b>왜 어댑터가 아니라 탐침인가.</b> ADR-0088 §버린 대안이 「여러 종목을 한 콜로 묶는다」를
 * 기각하면서 <i>"탐침은 묶는다 — 거기서는 격리가 목적이 아니다"</i>를 함께 적었다. 이 클래스는
 * 그 예외 안이고, {@code PriceDataSource} 구현을 한 줄도 건드리지 않는다.
 *
 * <p><b>MUST: {@link AlpacaBarsProbeLiveTest}에 메서드를 더하지 않는다.</b> 그 클래스에
 * {@code @Test}가 넷이고 {@code --tests} 패턴이 <b>클래스</b>에 걸려 전부 돈다 —
 * {@code probeUniverseCoverage} 하나만도 2일 × 43청크 = 87콜이라, 여기를 거기 넣으면
 * 한 번 돌릴 때마다 130콜 이상을 태운다.
 *
 * <p><b>이 경로는 {@code alpacaRateLimiter}(100/분)를 안 탄다.</b> 어댑터를 거치지 않고
 * {@link RestClient}를 직접 세우므로, 속도를 정하는 것은 아래 {@link #PACING_MS}뿐이다 —
 * 실효 약 171콜/분으로 공표 200/분 아래이나 <b>앱 자신의 설정값의 1.7배</b>다.
 *
 * <p><b>TSV를 표준출력이 아니라 파일로 쓴다.</b> {@code build.gradle}이
 * {@code showStandardStreams = true}라 stdout이 Gradle 이벤트 줄과 섞이고, 그것을
 * {@code \copy}로 먹이면 <b>조용히 일부만</b> 들어간다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class UsUniverseAxisProbeLiveTest {

    private static final String DATA_URL = "https://data.alpaca.markets";
    private static final String TRADING_URL = "https://paper-api.alpaca.markets";
    private static final String SEC_TICKERS = "https://www.sec.gov/files/company_tickers_exchange.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int CHUNK = 200;
    private static final long PACING_MS = 350L;

    /** 창을 여럿 잰다 — 넓히면 결측이 얼마나 주는지가 이 탐침의 산출물이다. */
    private static final int[] WINDOWS = {1, 5, 10};

    /**
     * 결손이 대형주에 걸리는지를 재는 대조군. 빠진 심볼 목록에 이 중 하나라도 들어가면
     * 그 자리에서 멈추고 원인을 본다 — 「상위가 통째로 빠진 유니버스」는 화면에서 즉시 틀렸다고
     * 읽히는데, 수만 보면 드러나지 않는다.
     */
    private static final List<String> MEGA_CAP = List.of(
            "AAPL", "MSFT", "NVDA", "GOOGL", "AMZN", "META", "BRK.B", "AVGO", "TSLA", "LLY",
            "JPM", "WMT", "V", "XOM", "UNH", "MA", "ORCL", "COST", "PG", "JNJ");

    private static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    @Test
    @DisplayName("[live][probe] 시총 분포의 재료와 배제축 후보를 잰다")
    void probeMarketCapAxis() throws InterruptedException {
        RestClient trading = client(TRADING_URL);
        RestClient data = client(DATA_URL);

        List<String> symbols = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        JsonNode assets = MAPPER.readTree(trading.get()
                .uri("/v2/assets?status=active&asset_class=us_equity&exchange=NYSE,NASDAQ")
                .retrieve().body(String.class));
        assets.forEach(a -> {
            String symbol = a.path("symbol").asString("");
            symbols.add(symbol);
            names.put(symbol, a.path("name").asString(""));
        });
        System.out.printf("%n===== 미국 유니버스 축 =====%n자산 목록: %d종목%n", symbols.size());
        assertThat(symbols).as("자산 목록이 비면 아래 전부가 무의미하다").isNotEmpty();

        // ── 창별 결측 ────────────────────────────────────────────────────────
        Map<String, Bar> latest = null;
        for (int window : WINDOWS) {
            Map<String, Bar> bars = fetchLatestBars(data, symbols, window);
            Set<String> missing = new LinkedHashSet<>(symbols);
            missing.removeAll(bars.keySet());
            List<String> missingMega = MEGA_CAP.stream().filter(missing::contains).toList();
            System.out.printf("창 %2d일: 응답 %d/%d (%.1f%%) · 결측 %d · 그중 메가캡 표본 %s%n",
                    window, bars.size(), symbols.size(), 100.0 * bars.size() / symbols.size(),
                    missing.size(), missingMega.isEmpty() ? "없음" : missingMega);
            if (window == WINDOWS[WINDOWS.length - 1]) {
                latest = bars;
                System.out.printf("결측 표본 20: %s%n",
                        missing.stream().limit(20).toList());
                assertThat(missingMega)
                        .as("메가캡이 빠지면 「상위 1,000」의 정의가 상단에 구멍을 뚫은 채 선다")
                        .isEmpty();
            }
        }

        // ── 이름 × CIK 교차표 (ADR-0086이 「재본 적 없다」로 열어 둔 수) ──────────
        Map<String, Long> cikByTicker = fetchCikMap();
        Map<Long, Integer> cikCount = new LinkedHashMap<>();
        for (String symbol : symbols) {
            Long cik = cikByTicker.get(symbol);
            if (cik == null) {
                cik = cikByTicker.get(symbol.replace('.', '-'));
            }
            if (cik != null) {
                cikCount.merge(cik, 1, Integer::sum);
            }
        }
        Map<String, int[]> cross = new TreeMap<>();
        for (String symbol : symbols) {
            Long cik = cikByTicker.get(symbol);
            if (cik == null) {
                cik = cikByTicker.get(symbol.replace('.', '-'));
            }
            int col = cik == null ? 2 : (cikCount.get(cik) > 1 ? 1 : 0);
            cross.computeIfAbsent(labelOf(names.getOrDefault(symbol, "")), k -> new int[3])[col]++;
        }
        System.out.printf("%n%-22s%12s%12s%12s%n", "이름 라벨", "CIK 단독", "CIK 중복", "CIK 없음");
        cross.forEach((label, row) ->
                System.out.printf("%-22s%12d%12d%12d%n", label, row[0], row[1], row[2]));
        System.out.println("↑ 「CIK 단독 × Preferred류」가 CIK 축의 구멍, "
                + "「CIK 중복 × 미분류」가 이름 축의 구멍이다");

        // ── TSV: 종가·거래량·CIK. 시총 조인은 psql이 한다 ────────────────────────
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "us-bars-latest.tsv");
        StringBuilder tsv = new StringBuilder();
        for (Map.Entry<String, Bar> e : latest.entrySet()) {
            Long cik = cikByTicker.get(e.getKey());
            if (cik == null) {
                cik = cikByTicker.get(e.getKey().replace('.', '-'));
            }
            tsv.append(e.getKey()).append('\t').append(e.getValue().tradeDate()).append('\t')
                    .append(e.getValue().close()).append('\t').append(e.getValue().volume())
                    .append('\t').append(cik == null ? "" : cik).append('\t')
                    .append(labelOf(names.getOrDefault(e.getKey(), ""))).append('\n');
        }
        write(out, tsv.toString());
        System.out.printf("%nTSV %d행 → %s%n", latest.size(), out);
        System.out.println("컬럼: symbol, trade_date, close, volume, cik, name_label");
    }

    /** 심볼 → 그 창의 <b>마지막</b> 봉. 창을 넓히면 무거래로 빠지는 종목이 준다. */
    private Map<String, Bar> fetchLatestBars(RestClient data, List<String> symbols, int windowDays)
            throws InterruptedException {
        String start = LocalDate.now().minusDays(windowDays + 4L).toString();
        Map<String, Bar> out = new LinkedHashMap<>();
        for (int i = 0; i < symbols.size(); i += CHUNK) {
            List<String> slice = symbols.subList(i, Math.min(i + CHUNK, symbols.size()));
            Set<String> requested = new LinkedHashSet<>(slice);
            String chunk = String.join(",", slice);
            String pageToken = null;
            do {
                final String token = pageToken;
                ResponseEntity<String> response = data.get()
                        .uri(uri -> {
                            uri.path("/v2/stocks/bars")
                                    .queryParam("symbols", chunk).queryParam("timeframe", "1Day")
                                    .queryParam("start", start)
                                    // MUST: RFC-3339 시각이다. 날짜만 주면 어제도 403이고
                                    // 그 403은 AUTH → fatal()이라 배치 전체가 죽는다(ADR-0088).
                                    .queryParam("end", java.time.Instant.now()
                                            .minusSeconds(960).toString())
                                    .queryParam("feed", "sip").queryParam("adjustment", "raw")
                                    .queryParam("limit", 10000).queryParam("sort", "asc");
                            if (token != null) {
                                uri.queryParam("page_token", token);
                            }
                            return uri.build();
                        })
                        .retrieve().toEntity(String.class);
                // MUST: 상태코드를 먼저 본다. 403 본문에는 bars 키가 없어 「봉 0개」로 접히고,
                // 그 0에서 정반대 결론이 나온 전례가 있다(data-pipeline.md).
                assertThat(response.getStatusCode().is2xxSuccessful())
                        .as("HTTP %s — %s", response.getStatusCode(), response.getBody())
                        .isTrue();
                JsonNode body = MAPPER.readTree(response.getBody());
                body.path("bars").properties().forEach(e -> {
                    assertThat(requested).as("요청하지 않은 심볼 %s — 청크 조립이 깨졌다", e.getKey())
                            .contains(e.getKey());
                    JsonNode series = e.getValue();
                    if (series.isEmpty()) {
                        return;
                    }
                    JsonNode last = series.get(series.size() - 1);
                    out.put(e.getKey(), new Bar(last.path("t").asString("").substring(0, 10),
                            new BigDecimal(last.path("c").asString("0")),
                            last.path("v").asLong(0L)));
                });
                pageToken = body.path("next_page_token").isNull()
                        ? null : body.path("next_page_token").asString(null);
                Thread.sleep(PACING_MS);
            } while (pageToken != null);
        }
        return out;
    }

    /** 티커 → CIK. {@code SecTickerCikCache}가 쓰는 것과 같은 파일이라 새 소스가 아니다. */
    private Map<String, Long> fetchCikMap() {
        RestClient sec = RestClient.builder()
                .defaultHeader("User-Agent", System.getenv().getOrDefault(
                        "SEC_EDGAR_USER_AGENT", "stock-screener probe"))
                .build();
        JsonNode root = MAPPER.readTree(sec.get().uri(SEC_TICKERS).retrieve().body(String.class));
        List<String> fields = new ArrayList<>();
        root.path("fields").forEach(f -> fields.add(f.asString("")));
        int cikAt = fields.indexOf("cik");
        int tickerAt = fields.indexOf("ticker");
        Map<String, Long> map = new LinkedHashMap<>();
        root.path("data").forEach(row ->
                map.put(row.get(tickerAt).asString(""), row.get(cikAt).asLong(0L)));
        return map;
    }

    /**
     * 증권 유형의 <b>이름 문자열</b> 추정. ADR-0086이 <i>"커버리지를 재본 적이 없다"</i>로 열어 둔
     * 축이고, 이 탐침이 재는 것이 바로 그 커버리지다 — <b>판정에 쓰지 않는다.</b>
     *
     * <p><b>MUST: 토큰 경계로 센다.</b> 부분문자열로 재면 회사명 안에서 우연히 일치한다 —
     * {@code NETFLIX}가 {@code ETF}에, {@code Curtiss-Wright}·{@code Bright*}가 {@code RIGHT}에,
     * {@code First United}가 {@code UNIT}에 걸린다. 2026-08-31 실측으로 {@code %UNIT%}인데
     * {@code % UNIT%}가 아닌 종목이 8,504 중 <b>133</b>이었고, 그 오분류가 아래 교차표에
     * 그대로 실려 <b>다른 규칙에 인용되면 틀린 근거</b>가 된다(ADR-0099 §규칙).
     */
    private static String labelOf(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (hasToken(upper, "PREFERRED") || hasToken(upper, "PFD")) {
            return "Preferred";
        }
        if (hasToken(upper, "WARRANTS?")) {
            return "Warrant";
        }
        if (hasToken(upper, "UNITS?")) {
            return "Unit";
        }
        if (hasToken(upper, "RIGHTS?")) {
            return "Right";
        }
        if (hasToken(upper, "DEPOSITARY")) {
            return "Depositary";
        }
        if (hasToken(upper, "NOTES?") || hasToken(upper, "DEBENTURES?") || hasToken(upper, "BONDS?")) {
            return "Note/Bond";
        }
        if (hasToken(upper, "ETFS?") || hasToken(upper, "FUNDS?") || hasToken(upper, "TRUSTS?")) {
            return "Fund/Trust";
        }
        if (upper.contains("COMMON STOCK") || upper.contains("ORDINARY SHARES")) {
            return "Common Stock";
        }
        return "(미분류)";
    }

    /** 낱말 경계로 찾는다. {@code token}은 정규식 조각이라 {@code S?} 같은 접미를 받는다. */
    private static boolean hasToken(String upper, String token) {
        return java.util.regex.Pattern.compile("\\b" + token + "\\b").matcher(upper).find();
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Bar(String tradeDate, BigDecimal close, long volume) {
    }
}
