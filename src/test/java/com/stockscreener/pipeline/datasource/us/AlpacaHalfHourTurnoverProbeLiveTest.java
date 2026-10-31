package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미국 정규장 거래대금을 <b>30분 봉</b>으로 낼 수 있는가 — 정규장 1분 봉 체결금액 합과 대조한다(ADR-0112의 남은 문).
 *
 * <p><b>가설</b>: 봉은 시계에 정렬되고(:00 · :30) 정규장 09:30~16:00 ET는 30분 경계에서 시작하고 끝난다(조기 폐장 13:00도).
 * 봉마다 {@code vw × v}는 그 봉의 체결금액이고 합은 더해지므로, 정규장 30분 봉 13개의 합은 같은 창 1분 봉 390개의 합과
 * 같아야 한다. 서머타임과 무관하다 — 경계를 UTC가 아니라 그날 뉴욕 시각으로 잡는다.
 *
 * <p>표본은 ADR-0112 실측과 같은 9종목이고 창을 셋 둔다 — 여름(EDT) 5일 · 겨울(EST) 5일 · 조기 폐장 2일. 페이지당 봉 수도
 * 찍는다(다중 심볼 페이징 크기 — 착수 전에 잴 값으로 ADR-0112가 남긴 것). <b>판정하지 않는다</b> — 수만 낸다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaHalfHourTurnoverProbeLiveTest {

    private static final String DATA_URL = "https://data.alpaca.markets";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final List<String> SYMBOLS =
            List.of("AAPL", "MSFT", "NVDA", "INTC", "JPM", "KO", "PFE", "T", "F");
    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final LocalTime CLOSE = LocalTime.of(16, 0);
    private static final LocalTime EARLY_CLOSE = LocalTime.of(13, 0);
    /** 이 창 안의 조기 폐장일(NYSE 공시). */
    private static final Set<LocalDate> EARLY_CLOSES = Set.of(LocalDate.of(2025, 11, 28), LocalDate.of(2025, 12, 24));

    private record Window(String label, String from, String to) {
    }

    private static final List<Window> WINDOWS = List.of(
            new Window("여름 EDT", "2026-08-24", "2026-08-29"),
            new Window("겨울 EST", "2026-01-12", "2026-01-17"),
            new Window("조기 폐장 11-28", "2025-11-28", "2025-11-29"),
            new Window("조기 폐장 12-24", "2025-12-24", "2025-12-25"));

    private static RestClient client() {
        return RestClient.builder()
                .baseUrl(DATA_URL)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    /** 심볼·일자별 정규장 합과 그 합에 든 봉 수. */
    private record SessionSum(double value, int bars) {
        SessionSum plus(double v) {
            return new SessionSum(value + v, bars + 1);
        }
    }

    @Test
    @DisplayName("[live][probe] 정규장 30분 봉 vw×v 합이 1분 봉 합과 같은가 · 30분 봉 페이지 크기")
    void probeHalfHourAgainstMinute() {
        RestClient data = client();
        List<Double> ratios = new ArrayList<>();
        int countMismatch = 0;
        for (Window window : WINDOWS) {
            Map<String, Map<LocalDate, SessionSum>> minute = fetch(data, "1Min", window, false);
            Map<String, Map<LocalDate, SessionSum>> half = fetch(data, "30Min", window, true);
            System.out.printf("%n===== %s %s~%s =====%n%-6s %-12s %16s %16s %8s %5s%n", window.label(), window.from(), window.to(),
                    "종목", "일자", "1분 합", "30분 합", "비", "봉");
            for (String symbol : SYMBOLS) {
                for (Map.Entry<LocalDate, SessionSum> e : minute.getOrDefault(symbol, Map.of()).entrySet()) {
                    SessionSum h = half.getOrDefault(symbol, Map.of()).get(e.getKey());
                    if (h == null || e.getValue().value() <= 0) {
                        System.out.printf("%-6s %-12s 30분 봉 없음%n", symbol, e.getKey());
                        continue;
                    }
                    double ratio = h.value() / e.getValue().value();
                    ratios.add(ratio);
                    int expectedBars = EARLY_CLOSES.contains(e.getKey()) ? 7 : 13;
                    if (h.bars() != expectedBars) {
                        countMismatch++;
                    }
                    System.out.printf("%-6s %-12s %16.0f %16.0f %8.6f %5d%n", symbol, e.getKey(),
                            e.getValue().value(), h.value(), ratio, h.bars());
                }
            }
        }
        assertThat(ratios).as("관측이 없다 — 잴 것이 없었다").isNotEmpty();
        ratios.sort(Double::compare);
        System.out.printf("%n30분/1분 비 n=%d · 최소 %.6f · 최대 %.6f · 중앙 %.6f · 봉 수가 13(조기 7)이 아닌 종목일 %d%n",
                ratios.size(), ratios.getFirst(), ratios.getLast(), ratios.get(ratios.size() / 2), countMismatch);
    }

    /**
     * 정규장 [09:30, 폐장) ET 안에서 시작하는 봉만 더한다. {@code logPages}면 페이지마다 봉 수를 찍는다.
     */
    private static Map<String, Map<LocalDate, SessionSum>> fetch(RestClient data, String timeframe, Window window,
                                                                 boolean logPages) {
        Map<String, Map<LocalDate, SessionSum>> out = new LinkedHashMap<>();
        String token = null;
        int page = 0;
        do {
            String pageToken = token;
            ResponseEntity<JsonNode> response = data.get()
                    .uri(uri -> {
                        var b = uri.path("/v2/stocks/bars")
                                .queryParam("symbols", String.join(",", SYMBOLS))
                                .queryParam("timeframe", timeframe)
                                .queryParam("start", window.from())
                                .queryParam("end", window.to())
                                .queryParam("feed", "sip")
                                .queryParam("adjustment", "raw")
                                .queryParam("limit", 10000);
                        if (pageToken != null) {
                            b = b.queryParam("page_token", pageToken);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .toEntity(JsonNode.class);
            // 상태코드를 먼저 본다 — 403 본문에는 bars가 없어 「봉 0개」로 접힌다(.claude/rules/data-pipeline.md).
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("%s 호출이 %s다 — 잴 것이 없다", timeframe, response.getStatusCode()).isTrue();
            JsonNode body = response.getBody();
            int barsOnPage = 0;
            for (Map.Entry<String, JsonNode> symbolBars : body.path("bars").properties()) {
                for (JsonNode bar : symbolBars.getValue()) {
                    barsOnPage++;
                    ZonedDateTime at = OffsetDateTime.parse(bar.path("t").asString("")).atZoneSameInstant(NEW_YORK);
                    LocalDate day = at.toLocalDate();
                    LocalTime close = EARLY_CLOSES.contains(day) ? EARLY_CLOSE : CLOSE;
                    LocalTime t = at.toLocalTime();
                    if (t.isBefore(OPEN) || !t.isBefore(close)) {
                        continue;
                    }
                    double value = bar.path("vw").asDouble(0) * bar.path("v").asDouble(0);
                    out.computeIfAbsent(symbolBars.getKey(), k -> new LinkedHashMap<>())
                            .merge(day, new SessionSum(value, 1), (a, b) -> a.plus(b.value()));
                }
            }
            page++;
            if (logPages) {
                System.out.printf("[%s %s] 페이지 %d · 봉 %d%n", timeframe, window.label(), page, barsOnPage);
            }
            token = body.path("next_page_token").asString(null);
        } while (token != null && !token.isBlank());
        return out;
    }
}
