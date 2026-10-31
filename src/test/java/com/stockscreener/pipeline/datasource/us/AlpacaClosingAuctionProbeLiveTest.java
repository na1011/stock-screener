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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종가 경매가 봉에 들어가는가 — 30분 봉 정규장 합 계획의 반증이 연 질문(2026-10-31).
 *
 * <p>정규장을 [09:30, 16:00) ET 봉으로 자르면 16:00:0x에 찍히는 종가 경매 체결은 16:00 이후 봉에 떨어져 시간외와 섞인다.
 * 그래서 종목일마다 세 수를 나란히 낸다 — ① 경매 엔드포인트({@code /v2/stocks/auctions})의 종가 경매 체결 수량·가격
 * ② 1분 봉 15:59 · 16:00 · 16:01의 {@code v} ③ 하루 1분 봉 {@code v} 합 대 일봉 {@code v}(봉이 일봉 거래량을 다 담는가 —
 * 단주는 봉을 안 만든다는 가이드 서술). 고가주(BKNG · AZO · NVR · FICO)를 표본에 더한다(단주 비중이 크다). <b>판정하지 않는다.</b>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaClosingAuctionProbeLiveTest {

    private static final String DATA_URL = "https://data.alpaca.markets";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final List<String> SYMBOLS =
            List.of("AAPL", "MSFT", "NVDA", "INTC", "JPM", "KO", "PFE", "T", "F", "BKNG", "AZO", "NVR", "FICO");
    private static final String FROM = "2026-08-24";
    private static final String TO = "2026-08-27";

    private static RestClient client() {
        return RestClient.builder()
                .baseUrl(DATA_URL)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    @Test
    @DisplayName("[live][probe] 종가 경매 체결 대 16:00 언저리 1분 봉 · 하루 1분 봉 거래량 합 대 일봉 거래량")
    void probeClosingAuction() {
        RestClient data = client();

        JsonNode auctions = get(data, "/v2/stocks/auctions", null, null);
        System.out.println("\n===== 경매 응답 머리(모양 확인) =====");
        String raw = auctions.toString();
        System.out.println(raw.substring(0, Math.min(1500, raw.length())));

        for (String symbol : SYMBOLS) {
            Map<LocalDate, Double> dayBarVolume = new LinkedHashMap<>();
            for (JsonNode bar : pages(data, symbol, "1Day")) {
                dayBarVolume.put(LocalDate.parse(bar.path("t").asString("").substring(0, 10)), bar.path("v").asDouble(0));
            }
            Map<LocalDate, double[]> minute = new LinkedHashMap<>(); // [하루 합, 15:59, 16:00, 16:01, 정규장 합]
            for (JsonNode bar : pages(data, symbol, "1Min")) {
                ZonedDateTime at = OffsetDateTime.parse(bar.path("t").asString("")).atZoneSameInstant(NEW_YORK);
                double v = bar.path("v").asDouble(0);
                double[] row = minute.computeIfAbsent(at.toLocalDate(), d -> new double[5]);
                row[0] += v;
                LocalTime t = at.toLocalTime();
                if (t.equals(LocalTime.of(15, 59))) row[1] = v;
                if (t.equals(LocalTime.of(16, 0))) row[2] = v;
                if (t.equals(LocalTime.of(16, 1))) row[3] = v;
                if (!t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0))) row[4] += v;
            }
            System.out.printf("%n----- %s -----%n%-12s %12s %12s %10s %10s %10s %12s%n", symbol,
                    "일자", "일봉 v", "1분 합 v", "15:59 v", "16:00 v", "16:01 v", "정규 1분 합");
            for (Map.Entry<LocalDate, double[]> e : minute.entrySet()) {
                double[] r = e.getValue();
                System.out.printf("%-12s %12.0f %12.0f %10.0f %10.0f %10.0f %12.0f%n", e.getKey(),
                        dayBarVolume.getOrDefault(e.getKey(), -1.0), r[0], r[1], r[2], r[3], r[4]);
            }
            JsonNode mine = auctions.path("auctions").path(symbol);
            System.out.println("경매(" + symbol + "): " + (mine.isMissingNode() ? "없음" : mine.toString().substring(0,
                    Math.min(1200, mine.toString().length()))));
        }
    }

    private static List<JsonNode> pages(RestClient data, String symbol, String timeframe) {
        List<JsonNode> out = new java.util.ArrayList<>();
        String token = null;
        do {
            JsonNode body = get(data, "/v2/stocks/bars", symbol + "|" + timeframe, token);
            for (JsonNode bar : body.path("bars").path(symbol)) {
                out.add(bar);
            }
            token = body.path("next_page_token").asString(null);
        } while (token != null && !token.isBlank());
        return out;
    }

    private static JsonNode get(RestClient data, String path, String symbolAndFrame, String pageToken) {
        ResponseEntity<JsonNode> response = data.get()
                .uri(uri -> {
                    var b = uri.path(path)
                            .queryParam("start", FROM)
                            .queryParam("end", TO + "T23:59:00Z")
                            .queryParam("feed", "sip")
                            .queryParam("limit", 10000);
                    if (symbolAndFrame == null) {
                        b = b.queryParam("symbols", String.join(",", SYMBOLS));
                    } else {
                        String[] parts = symbolAndFrame.split("\\|");
                        b = b.queryParam("symbols", parts[0]).queryParam("timeframe", parts[1]).queryParam("adjustment", "raw");
                    }
                    if (pageToken != null) {
                        b = b.queryParam("page_token", pageToken);
                    }
                    return b.build();
                })
                .retrieve()
                .toEntity(JsonNode.class);
        // 상태코드를 먼저 본다 — 403·404 본문은 「0건」으로 접힌다(.claude/rules/data-pipeline.md).
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("%s 호출이 %s다: %s", path, response.getStatusCode(), response.getBody()).isTrue();
        return response.getBody();
    }
}
