package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alpaca <b>{@code /v2/stocks/bars}</b>가 실제로 무엇을 주는지 잰다. <b>P21의 착수 판단이
 * 여기서 나온다.</b>
 *
 * <p>어댑터를 거치지 않고 {@code RestClient}를 직접 세운다 — 이 시점에는 어댑터가 없고,
 * 재는 대상도 우리 코드가 아니라 <b>소스</b>이기 때문이다({@link AlpacaAssetProbeLiveTest}와
 * 같은 이유·같은 형태다).
 *
 * <p><b>Market Data API다.</b> Base URL이 자산 마스터({@code paper-api.alpaca.markets})와
 * 다르고, 인증 헤더는 두 계열이 같아 섞으면 <b>404가 「키 문제」로 보이지 않는다.</b>
 *
 * <p><b>개발가이드가 이미 답한 것은 다시 재지 않는다.</b> {@code us-market-api-dev-guide.md}가
 * 2026-08-19에 무료 페이퍼 계정으로 확인한 것들이다 — 응답 봉투(§3.4) · 일봉 {@code t}의
 * 서머타임 이동(§3.4) · {@code limit} 기본 1000이 <b>전체 기준</b>이라는 것과 심볼 우선
 * 정렬(§3.5) · {@code adjustment} 기본 raw와 조정가의 소급 변동(§3.6) · {@code asof} 기본
 * 활성과 리네임 당일 미동작(§3.8) · 15분 규칙(§3.2) · SIP와 IEX의 값 차이(7.1).
 * 다시 재면 콜을 태우면서 <b>두 번째 정본</b>을 만든다.
 *
 * <p>여기서 재는 것은 그 문서가 <b>미검증으로 남긴 것</b>(§9)과 <b>이 저장소 고유의 제약</b>이다:
 * <ul>
 *   <li><b>다종목 페이징 실동작</b> — §9가 🔴로 남겼다. 순회를 안 하면 조용히 잘린다.</li>
 *   <li><b>{@code symbols} 개수 상한</b> — 문서에 없다. 묶음 크기를 정하려면 필요하다.</li>
 *   <li><b>{@code o/h/l/c}의 실제 소수 자릿수와 최저가</b> — {@code price_daily}가
 *       {@code numeric(18,4)}다. 넘으면 저장값과 파생값이 어긋난다.</li>
 *   <li><b>모르는 심볼의 응답 형태</b> — 포트 계약이 「빈 목록」과 {@code NOT_FOUND}를 가른다.</li>
 *   <li><b>{@code end}를 날짜만 줬을 때 오늘이 포함되는가</b> — 포함되면 15분 규칙에 걸린다.</li>
 *   <li><b>국내 휴장·미국 개장일의 수</b> — 그날이 전역 「거래일」이 되면 국내 기준일·보존
 *       경계·결손 판정이 함께 어긋난다.</li>
 * </ul>
 *
 * <p><b>커버리지는 「봉이 오는 비율」이라 부르지 않는다.</b> 가이드 §3.7②가 <i>"거래가 없으면
 * 봉 자체가 생성되지 않는다 … 결측이 정상 동작이다"</i>라고 적었으므로, 하루 표본은
 * 「데이터가 없다」와 「그날 거래가 없었다」를 구분하지 못한다. 그래서 이름이
 * <b>「그날 거래된 종목 비율」</b>이고 날짜를 둘 이상 잡는다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaBarsProbeLiveTest {

    private static final String DATA_URL = "https://data.alpaca.markets";
    private static final String TRADING_URL = "https://paper-api.alpaca.markets";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 파일럿 창. NVDA의 2024-06-10 10:1 분할이 이 안에 있다. */
    private static final String YEAR_FROM = "2024-01-01";
    private static final String YEAR_TO = "2024-12-31";

    /**
     * 날짜만 주는 {@code end}의 안전한 상한. <b>어제 날짜도 403이 난다</b> —
     * 날짜만 주면 뉴욕 기준 그날의 끝으로 해석되어 UTC로는 미래가 되기 때문이다(실측).
     */
    private static final String SAFE_END = LocalDate.now().minusDays(2).toString();

    /** 지표 보존창(최근 180거래일) 안의 국내 휴장 평일. 2026-08-29에 실 DB로 셌다. */
    private static final List<String> KR_CLOSED_WEEKDAYS = List.of(
            "2025-12-25", "2025-12-31", "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18",
            "2026-03-02", "2026-05-01", "2026-05-05", "2026-05-25", "2026-06-03", "2026-07-17",
            "2026-08-17");

    private static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                // 4xx에 예외를 던지지 않게 한다 — 상태코드와 본문을 그대로 봐야 한다.
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    @Test
    @DisplayName("[live][probe] 봉의 모양·자릿수·페이징·모르는 심볼을 기록한다")
    void probeBars() {
        RestClient data = client(DATA_URL);

        System.out.println("\n===== /v2/stocks/bars 실측 =====");

        // ── 1종목 1년: 봉 수·바이트·자릿수 ────────────────────────────────
        ResponseEntity<String> raw = data.get()
                .uri(uri -> uri.path("/v2/stocks/bars")
                        .queryParam("symbols", "AAPL")
                        .queryParam("timeframe", "1Day")
                        .queryParam("start", YEAR_FROM)
                        .queryParam("end", YEAR_TO)
                        .queryParam("feed", "sip")
                        .queryParam("adjustment", "raw")
                        .queryParam("limit", 10000)
                        .queryParam("sort", "asc")
                        .build())
                .retrieve().toEntity(String.class);
        String body = raw.getBody() == null ? "" : raw.getBody();
        JsonNode root = MAPPER.readTree(body);
        JsonNode aapl = root.path("bars").path("AAPL");
        System.out.printf("AAPL %s~%s http=%s 봉=%d 바이트=%d next_page_token=%s%n",
                YEAR_FROM, YEAR_TO, raw.getStatusCode(), aapl.size(),
                body.getBytes(StandardCharsets.UTF_8).length,
                root.path("next_page_token").isNull() ? "null" : root.path("next_page_token").asString(""));
        assertThat(raw.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(aapl.size()).as("2024년 미국 거래일은 250 안팎이다").isGreaterThan(200);
        System.out.printf("  첫 봉 t=%s  마지막 봉 t=%s%n",
                aapl.get(0).path("t").asString(""), aapl.get(aapl.size() - 1).path("t").asString(""));

        // ── adjustment=raw가 분할을 반영하지 않는가 (NVDA 2024-06-10 10:1) ──
        JsonNode nvdaRaw = barsOf(data, "NVDA", "2024-05-01", "2024-05-31", "raw");
        JsonNode nvdaAll = barsOf(data, "NVDA", "2024-05-01", "2024-05-31", "all");
        BigDecimal rawClose = new BigDecimal(nvdaRaw.get(0).path("c").asString("0"));
        BigDecimal allClose = new BigDecimal(nvdaAll.get(0).path("c").asString("0"));
        System.out.printf("NVDA 2024-05 첫 봉 종가: raw=%s / all=%s → 비율 %.4f%n",
                rawClose, allClose,
                allClose.signum() == 0 ? 0.0 : rawClose.doubleValue() / allClose.doubleValue());
        assertThat(rawClose)
                .as("raw가 분할 전 가격이어야 한다 — all과 같으면 adjustment가 안 걸린 것이다")
                .isGreaterThan(allClose.multiply(BigDecimal.valueOf(5)));

        // ── 다종목 페이징 실동작 (가이드 §9가 🔴 미검증으로 남긴 항목) ──────
        System.out.println("\n----- 다종목 페이징 -----");
        String token = null;
        int pages = 0;
        Map<String, Integer> perSymbol = new TreeMap<>();
        do {
            final String pageToken = token;
            JsonNode page = MAPPER.readTree(data.get()
                    .uri(uri -> {
                        uri.path("/v2/stocks/bars")
                                .queryParam("symbols", "AAPL,MSFT,NVDA")
                                .queryParam("timeframe", "1Day")
                                .queryParam("start", "2024-01-02").queryParam("end", "2024-01-31")
                                .queryParam("feed", "sip").queryParam("adjustment", "raw")
                                .queryParam("limit", 5).queryParam("sort", "asc");
                        if (pageToken != null) {
                            uri.queryParam("page_token", pageToken);
                        }
                        return uri.build();
                    })
                    .retrieve().body(String.class));
            pages++;
            for (Iterator<Map.Entry<String, JsonNode>> it = page.path("bars").properties().iterator();
                 it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                perSymbol.merge(e.getKey(), e.getValue().size(), Integer::sum);
            }
            JsonNode next = page.path("next_page_token");
            token = next.isNull() || next.asString("").isBlank() ? null : next.asString("");
            System.out.printf("  page %d: 심볼 %s 누적=%s next=%s%n",
                    pages, page.path("bars").propertyNames(), perSymbol, token == null ? "null" : "있음");
        } while (token != null && pages < 40);
        System.out.printf("limit=5로 3종목 1월치를 받으니 %d페이지, 종목별 누적=%s%n", pages, perSymbol);
        assertThat(pages).as("limit이 전체 기준이면 여러 페이지가 나온다").isGreaterThan(1);
        assertThat(perSymbol.keySet()).containsExactlyInAnyOrder("AAPL", "MSFT", "NVDA");

        // ── 모르는 심볼 ────────────────────────────────────────────────
        System.out.println("\n----- 모르는 심볼 -----");
        ResponseEntity<String> unknown = data.get()
                .uri(uri -> uri.path("/v2/stocks/bars")
                        .queryParam("symbols", "ZZZZNOTREAL,AAPL")
                        .queryParam("timeframe", "1Day")
                        .queryParam("start", "2024-01-02").queryParam("end", "2024-01-05")
                        .queryParam("feed", "sip").queryParam("adjustment", "raw")
                        .queryParam("limit", 10000).build())
                .retrieve().toEntity(String.class);
        JsonNode unknownRoot = MAPPER.readTree(unknown.getBody() == null ? "{}" : unknown.getBody());
        System.out.printf("http=%s bars의 키=%s 본문=%s%n", unknown.getStatusCode(),
                unknownRoot.path("bars").propertyNames(),
                unknown.getBody() == null ? "" : unknown.getBody().substring(0,
                        Math.min(200, unknown.getBody().length())));
        System.out.println("  → 「봉이 없다」와 「종목을 모른다」를 이 응답이 가르는가가 포트 계약의 입력이다");

        // ── end를 날짜만 줬을 때 오늘이 포함되는가 ─────────────────────────
        System.out.println("\n----- end=오늘 (15분 규칙) -----");
        String today = LocalDate.now().toString();
        ResponseEntity<String> endToday = data.get()
                .uri(uri -> uri.path("/v2/stocks/bars")
                        .queryParam("symbols", "AAPL").queryParam("timeframe", "1Day")
                        .queryParam("start", today).queryParam("end", today)
                        .queryParam("feed", "sip").queryParam("adjustment", "raw")
                        .queryParam("limit", 10000).build())
                .retrieve().toEntity(String.class);
        System.out.printf("start=end=%s → http=%s 본문=%s%n", today, endToday.getStatusCode(),
                endToday.getBody() == null ? "" : endToday.getBody().substring(0,
                        Math.min(300, endToday.getBody().length())));
        System.out.println("  → 403 + subscription 문구면 15분 규칙이다. 200이면 날짜 end가 안전하다");
    }

    @Test
    @DisplayName("[live][probe] end의 15분 규칙 경계를 잰다 — 어댑터가 무엇을 보내야 하는지의 입력이다")
    void probeEndBoundary() {
        RestClient data = client(DATA_URL);
        System.out.printf("%n===== end 경계 =====%n지금(UTC) = %s%n", Instant.now());

        // 날짜만 주는 end. 오늘·어제가 어디서 막히는지가 요점이다.
        for (int back = 0; back <= 3; back++) {
            String end = LocalDate.now().minusDays(back).toString();
            ResponseEntity<String> r = data.get()
                    .uri(uri -> uri.path("/v2/stocks/bars")
                            .queryParam("symbols", "AAPL").queryParam("timeframe", "1Day")
                            .queryParam("start", LocalDate.now().minusDays(10).toString())
                            .queryParam("end", end)
                            .queryParam("feed", "sip").queryParam("adjustment", "raw")
                            .queryParam("limit", 10000).build())
                    .retrieve().toEntity(String.class);
            System.out.printf("  end=%s (날짜만, 오늘-%d) -> HTTP %s%n", end, back, r.getStatusCode());
        }

        // RFC-3339 시각. 어댑터가 실제로 보낼 형태다.
        for (int minutes : new int[] {5, 16}) {
            String end = Instant.now().minusSeconds(minutes * 60L).toString();
            ResponseEntity<String> r = data.get()
                    .uri(uri -> uri.path("/v2/stocks/bars")
                            .queryParam("symbols", "AAPL").queryParam("timeframe", "1Day")
                            .queryParam("start", LocalDate.now().minusDays(10).toString())
                            .queryParam("end", end)
                            .queryParam("feed", "sip").queryParam("adjustment", "raw")
                            .queryParam("limit", 10000).build())
                    .retrieve().toEntity(String.class);
            System.out.printf("  end=%s (now-%d분, RFC3339) -> HTTP %s%n", end, minutes, r.getStatusCode());
        }
        System.out.println("  → 403은 AlpacaResponse.kindOf가 AUTH로 접고 AUTH는 fatal()이다.");
        System.out.println("     즉 이 경계를 넘기면 종목 하나가 아니라 배치 전체가 죽는다.");
    }

    @Test
    @DisplayName("[live][probe] 유니버스 전량으로 거래 비율·최저가·자릿수를 잰다")
    void probeUniverseCoverage() throws InterruptedException {
        RestClient trading = client(TRADING_URL);
        RestClient data = client(DATA_URL);

        JsonNode assets = MAPPER.readTree(trading.get()
                .uri("/v2/assets?status=active&asset_class=us_equity&exchange=NYSE,NASDAQ")
                .retrieve().body(String.class));
        List<String> symbols = new ArrayList<>();
        assets.forEach(a -> symbols.add(a.path("symbol").asString("")));
        System.out.printf("%n===== 유니버스 %d종목 커버리지 =====%n", symbols.size());

        for (String date : new String[] {"2024-06-03", "2024-11-01"}) {
            int traded = 0;
            int calls = 0;
            BigDecimal minLow = null;
            int maxScale = 0;
            String deepestSymbol = "";
            for (int i = 0; i < symbols.size(); i += 200) {
                String chunk = String.join(",", symbols.subList(i, Math.min(i + 200, symbols.size())));
                ResponseEntity<String> chunkResponse = data.get()
                        .uri(uri -> uri.path("/v2/stocks/bars")
                                .queryParam("symbols", chunk).queryParam("timeframe", "1Day")
                                .queryParam("start", date).queryParam("end", date)
                                .queryParam("feed", "sip").queryParam("adjustment", "raw")
                                .queryParam("limit", 10000).build())
                        .retrieve().toEntity(String.class);
                assertThat(chunkResponse.getStatusCode().is2xxSuccessful())
                        .as("HTTP %s — %s", chunkResponse.getStatusCode(), chunkResponse.getBody())
                        .isTrue();
                JsonNode bars = MAPPER.readTree(chunkResponse.getBody()).path("bars");
                calls++;
                for (Iterator<Map.Entry<String, JsonNode>> it = bars.properties().iterator();
                     it.hasNext(); ) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (e.getValue().isEmpty()) {
                        continue;
                    }
                    traded++;
                    for (String field : new String[] {"o", "h", "l", "c"}) {
                        BigDecimal v = new BigDecimal(e.getValue().get(0).path(field).asString("0"));
                        if (v.scale() > maxScale) {
                            maxScale = v.scale();
                            deepestSymbol = e.getKey() + "." + field + "=" + v;
                        }
                        if (field.equals("l") && (minLow == null || v.compareTo(minLow) < 0)) {
                            minLow = v;
                        }
                    }
                }
                Thread.sleep(350);   // 공표 200/분에 여유를 둔다
            }
            System.out.printf("%s: 그날 거래된 종목 %d/%d (%.1f%%) · %d콜 · 최저가=%s · 최대 소수 자릿수=%d (%s)%n",
                    date, traded, symbols.size(), 100.0 * traded / symbols.size(), calls,
                    minLow, maxScale, deepestSymbol);
        }
        System.out.println("  → 「그날 거래된 종목 비율」이다. 「봉이 오는 비율」이 아니다 —");
        System.out.println("     거래가 없으면 봉이 생성되지 않으므로 하루 표본은 결측과 무거래를 못 가른다");
    }

    @Test
    @DisplayName("[live][probe] 국내 휴장·미국 개장일을 센다 — 전역 「거래일」이 밀리는 크기다")
    void probeKrClosedButUsOpen() {
        JsonNode bars = barsOf(client(DATA_URL), "AAPL", "2025-12-01", SAFE_END, "raw");
        List<String> usDates = new ArrayList<>();
        bars.forEach(b -> usDates.add(b.path("t").asString("").substring(0, 10)));

        // MUST: 모집단을 먼저 신고한다. 0을 "미국도 휴장"으로 읽는 것과 "호출이 빈 응답"으로
        // 읽는 것은 정반대이고, 이 수 위에 파일럿 창 선택이 서 있다.
        System.out.printf("%n===== 보존창 안 =====%n"
                        + "AAPL 2025-12-01~" + SAFE_END + " 봉 %d개 (첫 %s / 마지막 %s)%n",
                usDates.size(),
                usDates.isEmpty() ? "없음" : usDates.get(0),
                usDates.isEmpty() ? "없음" : usDates.get(usDates.size() - 1));
        assertThat(usDates)
                .as("봉이 없으면 아래 수는 「미국도 휴장」이 아니라 「잴 것이 없었다」다")
                .isNotEmpty();

        List<String> open = KR_CLOSED_WEEKDAYS.stream().filter(usDates::contains).toList();
        List<String> closed = KR_CLOSED_WEEKDAYS.stream().filter(d -> !usDates.contains(d)).toList();
        System.out.printf("국내 휴장 평일 %d일 중 — 미국 개장 %d일: %s%n              미국도 휴장 %d일: %s%n",
                KR_CLOSED_WEEKDAYS.size(), open.size(), open, closed.size(), closed);
        System.out.println("  → 앞의 수만큼이 전역 「거래일」에 더해지면 보존 경계가 그만큼 뒤로 밀린다");
    }

    /**
     * 한 종목의 봉. <b>상태코드를 반드시 본다.</b>
     *
     * <p>안 보면 403 본문{@code {"message":"subscription does not permit …"}}이
     * {@code bars} 키가 없는 JSON이라 <b>「봉 0개」로 접힌다</b> — 이 탐침을 쓰면서 실제로
     * 두 번 그렇게 읽었고, 한 번은 "미국도 휴장"이라는 정반대 결론이 나왔다.
     */
    /**
     * 심볼마다 <b>봉이 어디서부터 오는가</b>를 피드·조정 조합으로 찍고 자산 정보를 함께 찍는다 —
     * 「과거를 안 준다」를 한 조합만 묻고 적지 않으려는 탐침이다.
     * {@code -Dprobe.symbols=TEVA,AAPL -Dprobe.from=2025-01-01 -Dprobe.to=2026-09-15}. 심볼당 약 5콜.
     */
    @Test
    @DisplayName("[live][probe] 심볼별 봉 커버리지를 피드·조정 조합으로 찍는다")
    void probeSymbolCoverage() {
        String symbols = System.getProperty("probe.symbols", "TEVA,AAPL");
        String from = System.getProperty("probe.from", "2025-01-01");
        String to = System.getProperty("probe.to", SAFE_END);
        RestClient data = client(DATA_URL);
        RestClient trading = client(TRADING_URL);
        for (String symbol : symbols.split(",")) {
            String sym = symbol.trim();
            ResponseEntity<String> asset = trading.get().uri("/v2/assets/{s}", sym).retrieve().toEntity(String.class);
            System.out.printf("%n--- %s asset http=%s %s%n", sym, asset.getStatusCode(),
                    asset.getBody() == null ? "" : asset.getBody().replaceAll("\\s+", " "));
            // asof는 심볼이 가리키는 실체를 고르는 날짜다(비우면 오늘). CUSIP이 바뀐 종목은 오늘의 실체로만 답한다.
            String asof = System.getProperty("probe.asof", "");
            for (String feed : List.of("sip", "iex")) {
                for (String adjustment : List.of("raw", "all")) {
                    ResponseEntity<String> response = data.get()
                            .uri(uri -> {
                                uri.path("/v2/stocks/bars")
                                        .queryParam("symbols", sym).queryParam("timeframe", "1Day")
                                        .queryParam("start", from).queryParam("end", to)
                                        .queryParam("feed", feed).queryParam("adjustment", adjustment)
                                        .queryParam("limit", 10000).queryParam("sort", "asc");
                                if (!asof.isBlank()) {
                                    uri.queryParam("asof", asof);
                                }
                                return uri.build();
                            })
                            .retrieve().toEntity(String.class);
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        System.out.printf("  feed=%s adj=%s http=%s %s%n", feed, adjustment,
                                response.getStatusCode(), response.getBody());
                        continue;
                    }
                    JsonNode root = MAPPER.readTree(response.getBody());
                    JsonNode bars = root.path("bars").path(sym);
                    System.out.printf("  feed=%s adj=%s http=%s 봉=%d 첫=%s 끝=%s next=%s%n", feed, adjustment,
                            response.getStatusCode(), bars.size(),
                            bars.isEmpty() ? "-" : bars.get(0).path("t").asString(""),
                            bars.isEmpty() ? "-" : bars.get(bars.size() - 1).path("t").asString(""),
                            root.path("next_page_token").isNull() ? "null" : root.path("next_page_token").asString(""));
                }
            }
        }
    }

    private static JsonNode barsOf(RestClient data, String symbol, String from, String to,
                                   String adjustment) {
        ResponseEntity<String> response = data.get()
                .uri(uri -> uri.path("/v2/stocks/bars")
                        .queryParam("symbols", symbol).queryParam("timeframe", "1Day")
                        .queryParam("start", from).queryParam("end", to)
                        .queryParam("feed", "sip").queryParam("adjustment", adjustment)
                        .queryParam("limit", 10000).queryParam("sort", "asc").build())
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("HTTP %s — %s. 상태를 안 보면 이 본문이 「봉 0개」로 접힌다",
                        response.getStatusCode(), response.getBody())
                .isTrue();
        return MAPPER.readTree(response.getBody()).path("bars").path(symbol);
    }
}
