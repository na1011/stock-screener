package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alpaca <b>{@code /v3/calendar/{market}}</b>가 실제로 무엇을 주는지 잰다. P24의 착수 판단이
 * 여기서 나온다.
 *
 * <p>어댑터를 거치지 않고 {@code RestClient}를 직접 세운다. 이 시점에는 어댑터가 없고, 재는
 * 대상도 우리 코드가 아니라 <b>소스</b>이기 때문이다.
 *
 * <p><b>Trading API이고 경로가 {@code v3}다.</b> 시세·기업행위({@code data.alpaca.markets})와
 * base URL이 다르다. 인증 헤더는 두 계열이 같아 <b>키가 틀린 것처럼 보이지 않는다.</b>
 *
 * <p>개발가이드 §6이 이미 적어 둔 것은 <b>다시 재지 않는다</b>: 엔드포인트 모양, 인증 방식.
 * 여기서 재는 것은 <b>그 문서가 답하지 않았거나, 답했더라도 실측이 필요한 것</b>이다 —
 * 그 문서는 기업행위의 정렬 기준을 실측과 다르게 적은 전례가 있다(ADR-0090).
 *
 * <p>안 재면 계획이 통째로 어긋나는 것 넷:
 * <ul>
 *   <li><b>2024년 세션 집합</b> — 우리 미국 봉이 2024년 252일이다. <b>개수가 아니라 집합</b>을
 *       양방향으로 대조해야 한다. 개수만 같고 서로 어긋난 날이 상쇄될 수 있다.</li>
 *   <li><b>두 거래소가 갈리는 날</b> — 갈리면 표가 거래소 단위여야 하는 실증이고, 안 갈리면
 *       "같더라"를 실측으로 적을 수 있다. 개발가이드 §9가 미검증으로 올려 둔 항목이다.</li>
 *   <li><b>{@code {market}} 경로가 받는 값</b> — {@code NYSE}인지 {@code XNYS}인지.
 *       {@code stock.listing_exchange}를 그대로 못 쓰면 어댑터에 매핑이 하나 붙는다.</li>
 *   <li><b>모르는 값·인증 실패의 상태코드</b> — 403은 {@code AlpacaResponse.kindOf}가
 *       {@code AUTH}로 접고 {@code AUTH}는 {@code fatal()}이라 <b>배치 전체를 죽인다.</b></li>
 * </ul>
 *
 * <p>여기서 나온 수는 그대로 {@code docs/api-spec/alpaca-calendar.md}로 옮긴다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaCalendarProbeLiveTest {

    private static final String BASE_URL = "https://paper-api.alpaca.markets";
    private static final String MARKET_DATA_BASE_URL = "https://data.alpaca.markets";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RestClient client(String baseUrl, String keyId, String secret) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("APCA-API-KEY-ID", keyId)
                .defaultHeader("APCA-API-SECRET-KEY", secret)
                // 4xx에 예외를 던지지 않게 한다 — 상태코드와 본문을 그대로 봐야 한다.
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private static RestClient client() {
        return client(BASE_URL, System.getenv("APCA_API_KEY_ID"), System.getenv("APCA_API_SECRET_KEY"));
    }

    /** {@code start}/{@code end}를 준 조회. 둘 중 null이면 그 파라미터를 아예 안 보낸다. */
    private static ResponseEntity<String> fetch(RestClient client, String market,
                                                String start, String end) {
        return client.get()
                .uri(uri -> {
                    uri.path("/v3/calendar/" + market);
                    if (start != null) {
                        uri.queryParam("start", start);
                    }
                    if (end != null) {
                        uri.queryParam("end", end);
                    }
                    return uri.build();
                })
                .retrieve().toEntity(String.class);
    }

    private static JsonNode parse(ResponseEntity<String> raw) {
        String body = raw.getBody();
        if (body == null || body.isBlank()) {
            return null;
        }
        return MAPPER.readTree(body);
    }

    /** 응답에서 세션 날짜만 뽑는다. 봉투 모양이 다르면 빈 집합이다(그 사실도 신고한다). */
    private static Set<String> sessionDates(JsonNode root) {
        Set<String> dates = new TreeSet<>();
        if (root == null) {
            return dates;
        }
        JsonNode calendar = root.path("calendar");
        if (!calendar.isArray()) {
            return dates;
        }
        for (JsonNode day : calendar) {
            String date = day.path("date").asString("");
            if (!date.isBlank()) {
                dates.add(date);
            }
        }
        return dates;
    }

    private static String head(String body, int limit) {
        if (body == null) {
            return "(본문 없음)";
        }
        String flat = body.replace("\n", " ");
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    @Test
    @DisplayName("[live][probe] /v3/calendar의 봉투·기본값·거래소 차이·경로 값·오류를 기록한다")
    void probeCalendar() {
        RestClient client = client();

        System.out.println("\n===== /v3/calendar 실측 =====");
        System.out.println("base URL: " + BASE_URL);

        // ── 1. 봉투와 필드 ───────────────────────────────────────────────────
        ResponseEntity<String> sample = fetch(client, "NYSE", "2024-01-02", "2024-01-05");
        System.out.println("\n[1] 봉투 — NYSE 2024-01-02~2024-01-05");
        System.out.println("  HTTP " + sample.getStatusCode().value());
        System.out.println("  body: " + head(sample.getBody(), 900));
        JsonNode sampleRoot = parse(sample);
        if (sampleRoot != null) {
            List<String> topKeys = new ArrayList<>();
            sampleRoot.propertyNames().forEach(topKeys::add);
            System.out.println("  최상위 키: " + topKeys);
            System.out.println("  calendar가 배열인가: " + sampleRoot.path("calendar").isArray());
            System.out.println("  market 노드: " + sampleRoot.path("market"));
            JsonNode first = sampleRoot.path("calendar").path(0);
            List<String> dayKeys = new ArrayList<>();
            first.propertyNames().forEach(dayKeys::add);
            System.out.println("  하루 노드의 키: " + dayKeys);
            System.out.println("  next_page_token 존재: "
                    + !sampleRoot.path("next_page_token").isMissingNode()
                    + " 값=" + sampleRoot.path("next_page_token"));
        }

        // ── 2. 기본값 — end를 빼면 어디까지 오는가 / 둘 다 빼면 무엇이 오는가 ──
        System.out.println("\n[2] 기본값");
        ResponseEntity<String> noEnd = fetch(client, "NYSE", "2024-01-02", null);
        Set<String> noEndDates = sessionDates(parse(noEnd));
        System.out.println("  end 생략 (start=2024-01-02): HTTP " + noEnd.getStatusCode().value()
                + " · 세션 " + noEndDates.size() + "일 · 범위 "
                + (noEndDates.isEmpty() ? "-" : noEndDates.iterator().next() + "~"
                        + new TreeSet<>(noEndDates).last()));
        ResponseEntity<String> noParams = fetch(client, "NYSE", null, null);
        Set<String> noParamDates = sessionDates(parse(noParams));
        System.out.println("  start·end 둘 다 생략: HTTP " + noParams.getStatusCode().value()
                + " · 세션 " + noParamDates.size() + "일 · body=" + head(noParams.getBody(), 300));

        // ── 3. 2024년 전체 — 거래소별 세션 집합과 그 차이 ────────────────────
        System.out.println("\n[3] 2024년 전체 세션");
        Map<String, Set<String>> byExchange = new LinkedHashMap<>();
        for (String market : new String[] {"NYSE", "NASDAQ"}) {
            ResponseEntity<String> year = fetch(client, market, "2024-01-01", "2024-12-31");
            JsonNode root = parse(year);
            Set<String> dates = sessionDates(root);
            byExchange.put(market, dates);
            System.out.println("  " + market + ": HTTP " + year.getStatusCode().value()
                    + " · 세션 " + dates.size() + "일"
                    + " · next_page_token=" + (root == null ? "?" : root.path("next_page_token")));
        }
        Set<String> nyse = byExchange.getOrDefault("NYSE", Set.of());
        Set<String> nasdaq = byExchange.getOrDefault("NASDAQ", Set.of());
        Set<String> nyseOnly = new TreeSet<>(nyse);
        nyseOnly.removeAll(nasdaq);
        Set<String> nasdaqOnly = new TreeSet<>(nasdaq);
        nasdaqOnly.removeAll(nyse);
        System.out.println("  NYSE에만 있는 날: " + nyseOnly.size() + " " + nyseOnly);
        System.out.println("  NASDAQ에만 있는 날: " + nasdaqOnly.size() + " " + nasdaqOnly);
        // 우리 봉과 대조할 수 있게 그대로 뱉는다. psql로 양방향 차집합을 낸다.
        System.out.println("  NYSE 2024 세션 전량(우리 봉 252일과 대조용):");
        System.out.println("  " + String.join(",", nyse));

        // ── 4. 조기폐장 — core_end의 벽시계 시각 분포 ────────────────────────
        System.out.println("\n[4] 조기폐장과 시각 표기 (NYSE 2024)");
        ResponseEntity<String> year2024 = fetch(client, "NYSE", "2024-01-01", "2024-12-31");
        JsonNode cal = parse(year2024);
        Map<String, Integer> closeTimes = new LinkedHashMap<>();
        List<String> earlyCloses = new ArrayList<>();
        Set<String> offsets = new LinkedHashSet<>();
        if (cal != null) {
            for (JsonNode day : cal.path("calendar")) {
                String end = day.path("core_end").asString("");
                if (end.isBlank()) {
                    // 필드명이 다를 수 있다. 첫 하루의 키 목록이 [1]에 찍혀 있다.
                    continue;
                }
                // "2024-07-03T13:00:00-04:00" → 시각과 오프셋을 갈라 센다.
                int t = end.indexOf('T');
                String clock = t < 0 ? end : end.substring(t + 1);
                String hhmm = clock.length() >= 5 ? clock.substring(0, 5) : clock;
                closeTimes.merge(hhmm, 1, Integer::sum);
                if (clock.length() > 8) {
                    offsets.add(clock.substring(8));
                }
                if (!hhmm.equals("16:00")) {
                    earlyCloses.add(day.path("date").asString("?") + " " + end);
                }
            }
        }
        System.out.println("  core_end 벽시계 분포: " + closeTimes);
        System.out.println("  오프셋 종류: " + offsets);
        System.out.println("  16:00이 아닌 날 " + earlyCloses.size() + "건: " + earlyCloses);

        // ── 5. 서머타임 경계 원문 ────────────────────────────────────────────
        System.out.println("\n[5] 서머타임 경계 원문 (2024-03-08~03-12, 2024-11-01~11-05)");
        for (String[] window : new String[][] {{"2024-03-08", "2024-03-12"}, {"2024-11-01", "2024-11-05"}}) {
            JsonNode dst = parse(fetch(client, "NYSE", window[0], window[1]));
            if (dst != null) {
                for (JsonNode day : dst.path("calendar")) {
                    System.out.println("    " + day);
                }
            }
        }

        // ── 6. {market} 경로가 받는 값 ───────────────────────────────────────
        System.out.println("\n[6] {market} 경로 값");
        for (String market : new String[] {"NYSE", "NASDAQ", "XNYS", "XNAS", "nyse", "ZZZNOTREAL"}) {
            ResponseEntity<String> r = fetch(client, market, "2024-06-03", "2024-06-07");
            Set<String> d = sessionDates(parse(r));
            System.out.println("  " + market + ": HTTP " + r.getStatusCode().value()
                    + " · 세션 " + d.size() + "일 · body=" + head(r.getBody(), 200));
        }

        // ── 7. 이력의 시작과 끝 ──────────────────────────────────────────────
        System.out.println("\n[7] 이력의 시작·끝");
        for (String[] window : new String[][] {
                {"1990-01-01", "1990-01-31"}, {"2000-01-01", "2000-01-31"},
                {"2010-01-01", "2010-01-31"}, {"2016-01-01", "2016-01-31"},
                {"2027-01-01", "2027-12-31"}, {"2030-01-01", "2030-12-31"}}) {
            ResponseEntity<String> r = fetch(client, "NYSE", window[0], window[1]);
            Set<String> d = sessionDates(parse(r));
            System.out.println("  " + window[0] + "~" + window[1] + ": HTTP "
                    + r.getStatusCode().value() + " · 세션 " + d.size() + "일"
                    + (d.isEmpty() ? " · body=" + head(r.getBody(), 160) : ""));
        }

        // ── 8. 긴 창에서 절단·페이징이 있는가 ────────────────────────────────
        System.out.println("\n[8] 긴 창 (2016-01-01~2026-12-31)");
        ResponseEntity<String> longWindow = fetch(client, "NYSE", "2016-01-01", "2026-12-31");
        JsonNode longRoot = parse(longWindow);
        Set<String> longDates = sessionDates(longRoot);
        System.out.println("  HTTP " + longWindow.getStatusCode().value()
                + " · 세션 " + longDates.size() + "일"
                + " · 범위 " + (longDates.isEmpty() ? "-"
                        : new TreeSet<>(longDates).first() + "~" + new TreeSet<>(longDates).last())
                + " · next_page_token=" + (longRoot == null ? "?" : longRoot.path("next_page_token"))
                + " · 본문 " + (longWindow.getBody() == null ? 0 : longWindow.getBody().length()) + "자");

        // ── 9. 오류 — 인증 실패와 뒤집힌 창 ──────────────────────────────────
        System.out.println("\n[9] 오류");
        RestClient badKey = client(BASE_URL, "NOT_A_REAL_KEY", "NOT_A_REAL_SECRET");
        ResponseEntity<String> unauthorized = fetch(badKey, "NYSE", "2024-06-03", "2024-06-07");
        System.out.println("  틀린 키: HTTP " + unauthorized.getStatusCode().value()
                + " · body=" + head(unauthorized.getBody(), 200));
        ResponseEntity<String> inverted = fetch(client, "NYSE", "2024-06-07", "2024-06-03");
        System.out.println("  뒤집힌 창: HTTP " + inverted.getStatusCode().value()
                + " · 세션 " + sessionDates(parse(inverted)).size() + "일"
                + " · body=" + head(inverted.getBody(), 200));

        // ── 10. base URL을 섞으면 무엇이 오는가 ──────────────────────────────
        // 404면 오타가 NOT_FOUND, 403이면 AUTH → fatal()이라 배치 전체가 죽는다. 가르는 값이다.
        System.out.println("\n[10] Market Data base URL로 같은 경로를 부르면");
        ResponseEntity<String> wrongBase = fetch(
                client(MARKET_DATA_BASE_URL, System.getenv("APCA_API_KEY_ID"),
                        System.getenv("APCA_API_SECRET_KEY")),
                "NYSE", "2024-06-03", "2024-06-07");
        System.out.println("  HTTP " + wrongBase.getStatusCode().value()
                + " · body=" + head(wrongBase.getBody(), 200));


        // ── 11. 미래 연도에 휴장일 지식이 어디까지 있는가 ───────────────────
        // 평일 수와 세션 수의 차이가 그 해에 알려진 휴장일이다. 어느 해부터 0에 가까워지면
        // 그 뒤 세션은 "달력을 편 것"이지 "일정을 아는 것"이 아니다.
        System.out.println("\n[11] 연도별 세션 수와 알려진 휴장일");
        for (int y = 2023; y <= 2031; y++) {
            Set<String> d = sessionDates(parse(fetch(client, "NYSE", y + "-01-01", y + "-12-31")));
            long weekdays = java.time.LocalDate.of(y, 1, 1)
                    .datesUntil(java.time.LocalDate.of(y + 1, 1, 1))
                    .filter(x -> x.getDayOfWeek().getValue() <= 5)
                    .count();
            System.out.println("  " + y + ": 세션 " + d.size() + " · 평일 " + weekdays
                    + " · 알려진 휴장 " + (weekdays - d.size()));
        }

        // ── 12. 인증 실패 본문이 우리 어댑터 경로에서 무엇이 되는가 ──────────
        // 어댑터는 toEntity(JsonNode.class)로 받는다. 본문이 JSON이 아니면 상태코드를 보기
        // 전에 변환이 터지고, 그러면 AUTH가 아니라 TRANSIENT로 접힌다. 그 여부를 직접 잰다.
        System.out.println("\n[12] 인증 실패 본문 — 계열별로 다른가");
        for (String[] target : new String[][] {
                {"Trading /v3/calendar", BASE_URL, "/v3/calendar/NYSE?start=2024-06-03&end=2024-06-07"},
                {"Trading /v2/assets", BASE_URL, "/v2/assets?status=active&asset_class=us_equity&exchange=NYSE"},
                {"MarketData /v1/corporate-actions", MARKET_DATA_BASE_URL,
                        "/v1/corporate-actions?symbols=AAPL&start=2024-01-01&end=2024-01-31"}}) {
            RestClient bad = client(target[1], "NOT_A_REAL_KEY", "NOT_A_REAL_SECRET");
            ResponseEntity<String> asText = bad.get().uri(target[2]).retrieve().toEntity(String.class);
            String contentType = String.valueOf(asText.getHeaders().getContentType());
            String asJson;
            try {
                bad.get().uri(target[2]).retrieve().toEntity(JsonNode.class);
                asJson = "JsonNode로 변환됨";
            } catch (RuntimeException e) {
                asJson = "변환 실패 → " + e.getClass().getSimpleName();
            }
            System.out.println("  " + target[0] + ": HTTP " + asText.getStatusCode().value()
                    + " · Content-Type=" + contentType
                    + " · " + asJson
                    + " · body=" + head(asText.getBody(), 120));
        }

        System.out.println("\n===== 끝 =====\n");

        // 탐침의 유일한 단언 — 이 엔드포인트에 우리 키가 통한다는 것.
        // 나머지는 값을 기록하는 것이 목적이라 단언하지 않는다.
        assertThat(sample.getStatusCode().value()).isEqualTo(200);
    }
}
