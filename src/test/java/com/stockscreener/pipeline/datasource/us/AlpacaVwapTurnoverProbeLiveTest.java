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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미국 <b>거래대금</b>을 낼 수 있는가 — {@code vw × v}를 정규장 분봉 합과 대조한다.
 *
 * <p><b>왜 다시 재는가.</b> {@code alpaca-bars.md}가 거래대금을 「없다」로 닫으면서
 * <i>"`vw`(VWAP)도 대용이 아니다"</i>를 <b>한 줄로 단정</b>했는데, 그 절의 실측은 전부
 * {@code close × volume}에 대한 것이다. 둘은 성질이 다르다 — {@code close × volume}은
 * <b>합성</b>(정규장 종가 × 전 세션 거래량)이고 {@code vw × v}는 소스가 <b>같은 집계 창에서
 * 낸 두 값의 곱</b>이라 그 창의 실제 체결금액이다. <b>기각된 적이 없는 것을 기각된 것으로
 * 읽고 있었다.</b>
 *
 * <p><b>표본과 방법을 지난 실측과 같게 둔다</b>(9종목 × 5거래일 = 45 종목일). 그래야 두
 * 결과를 나란히 읽을 수 있다 — 다른 창·다른 종목으로 재면 「좋아졌다」가 방법이 바뀐 것인지
 * 지표가 다른 것인지 갈리지 않는다. {@code close × volume}도 함께 찍어 <b>대조군</b>으로
 * 삼는다: 그 값이 지난번의 13.62% 근처로 재현되지 않으면 <b>이 측정 자체를 믿지 않는다.</b>
 *
 * <p><b>이 탐침은 판정하지 않는다.</b> 무엇을 저장할지·임계를 얼마로 둘지는 설계 시점의
 * 몫이고, 여기서 내는 것은 <b>수</b>뿐이다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaVwapTurnoverProbeLiveTest {

    private static final String DATA_URL = "https://data.alpaca.markets";

    /** 지난 실측과 <b>같은 9종목</b>. 유동성 상·하위가 섞여 있다. */
    private static final List<String> SYMBOLS =
            List.of("AAPL", "MSFT", "NVDA", "INTC", "JPM", "KO", "PFE", "T", "F");

    /** 지난 실측과 <b>같은 창</b>(2026-08-24~28, 5거래일). */
    private static final String FROM = "2026-08-24";
    private static final String TO = "2026-08-28";

    /**
     * 정규장 구간(UTC). 8월은 EDT(UTC−4)라 09:30~16:00 ET = 13:30~20:00 UTC다.
     *
     * <p><b>MUST: 서머타임을 상수로 박은 것을 창 밖으로 옮겨 쓰지 않는다.</b> 11월~3월은
     * EST(UTC−5)라 14:30~21:00이 되고, 그대로 쓰면 30분이 통째로 빠지는데 <b>합이 조금
     * 작아질 뿐</b>이라 드러나지 않는다.
     */
    private static final LocalTime SESSION_OPEN = LocalTime.of(13, 30);
    private static final LocalTime SESSION_CLOSE = LocalTime.of(20, 0);

    private static RestClient client() {
        return RestClient.builder()
                .baseUrl(DATA_URL)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    /** 한 종목·한 날의 관측. 비율은 정규장 분봉 합을 1로 본 배수다. */
    private record Observation(String symbol, LocalDate date,
                               double closeTimesVolume, double vwapTimesVolume, double session) {
        double closeRatio() {
            return closeTimesVolume / session;
        }

        double vwapRatio() {
            return vwapTimesVolume / session;
        }
    }

    @Test
    @DisplayName("[live][probe] vw × v가 정규장 체결금액과 얼마나 벌어지는지 잰다 (대조군: close × v)")
    void probeVwapAsTurnover() {
        RestClient data = client();

        System.out.println("\n===== 미국 거래대금 — vw × v 실측 =====");
        System.out.printf("표본 %d종목 × %s~%s · 정규장 %s~%s UTC%n",
                SYMBOLS.size(), FROM, TO, SESSION_OPEN, SESSION_CLOSE);
        System.out.println("MUST: close × v 열이 지난 실측(중앙값 13.62% 과대)과 어긋나면"
                + " 이 측정을 믿지 않는다 — 방법이 재현되지 않은 것이다.");

        Map<String, Map<LocalDate, JsonNode>> daily = fetchDaily(data);
        List<Observation> observations = new ArrayList<>();

        for (String symbol : SYMBOLS) {
            Map<LocalDate, Double> sessionValue = fetchSessionValue(data, symbol);
            Map<LocalDate, JsonNode> bars = daily.getOrDefault(symbol, Map.of());
            for (Map.Entry<LocalDate, JsonNode> bar : bars.entrySet()) {
                Double session = sessionValue.get(bar.getKey());
                if (session == null || session <= 0) {
                    continue;
                }
                double volume = bar.getValue().path("v").asDouble(0);
                double close = bar.getValue().path("c").asDouble(0);
                double vwap = bar.getValue().path("vw").asDouble(0);
                if (volume <= 0 || close <= 0 || vwap <= 0) {
                    // vw가 없거나 0인 날은 세지 않는다 — 0을 섞으면 비율이 통째로 무너진다.
                    continue;
                }
                observations.add(new Observation(symbol, bar.getKey(),
                        close * volume, vwap * volume, session));
            }
        }

        assertThat(observations)
                .as("관측이 하나도 없다 — 창이 휴장이거나 응답 모양이 바뀐 것이다. 잰 것이 없다")
                .isNotEmpty();

        System.out.printf("%n%-6s %-12s %12s %12s%n", "종목", "일자", "close×v", "vw×v");
        System.out.println("(정규장 분봉 합을 1.000으로 본 배수)");
        for (Observation o : observations) {
            System.out.printf("%-6s %-12s %12.4f %12.4f%n",
                    o.symbol(), o.date(), o.closeRatio(), o.vwapRatio());
        }

        report("close × v (대조군)", observations.stream().map(Observation::closeRatio).toList());
        report("vw × v    (이번에 재는 것)",
                observations.stream().map(Observation::vwapRatio).toList());

        System.out.println("\nMUST: 이 표로 무엇을 저장할지·임계를 얼마로 둘지 정하지 않는다."
                + " 여기서 내는 것은 수뿐이고, 판단은 설계 시점의 몫이다.");
    }

    /**
     * 부호까지 함께 낸다. <b>「과대」가 한 방향인가</b>가 계통 오차와 노이즈를 가르는 축이고,
     * 지난 실측이 {@code close × v}를 기각한 근거가 정확히 그것이었다(45 종목일 전부 양수).
     */
    private static void report(String label, List<Double> ratios) {
        List<Double> sorted = new ArrayList<>(ratios);
        sorted.sort(Double::compare);
        int n = sorted.size();
        double median = n % 2 == 1 ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double sd = Math.sqrt(sorted.stream()
                .mapToDouble(v -> (v - mean) * (v - mean)).sum() / Math.max(1, n - 1));
        long above = sorted.stream().filter(v -> v > 1.0).count();

        System.out.printf("%n----- %s (n=%d) -----%n", label, n);
        System.out.printf("중앙값 %.4f (%+.2f%%) · 평균 %.4f · sd %.4f%n",
                median, (median - 1) * 100, mean, sd);
        System.out.printf("최소 %.4f · 최대 %.4f · 1.0 초과 %d/%d%n",
                sorted.getFirst(), sorted.getLast(), above, n);
        System.out.println(above == n || above == 0
                ? "→ 한 방향이다. 평균으로 사라지지 않는 계통 오차의 모양이다."
                : "→ 양쪽으로 흩어진다. 계통이 아니라 노이즈 쪽 모양이다.");
    }

    /** 일봉 한 번에 9종목 5일. 심볼 → 일자 → 봉. */
    private static Map<String, Map<LocalDate, JsonNode>> fetchDaily(RestClient data) {
        ResponseEntity<JsonNode> response = data.get()
                .uri(uri -> uri.path("/v2/stocks/bars")
                        .queryParam("symbols", String.join(",", SYMBOLS))
                        .queryParam("timeframe", "1Day")
                        .queryParam("start", FROM)
                        .queryParam("end", TO)
                        .queryParam("feed", "sip")
                        .queryParam("adjustment", "raw")
                        .queryParam("limit", 10000)
                        .build())
                .retrieve()
                .toEntity(JsonNode.class);

        // MUST: 상태코드를 먼저 본다. 403 본문에는 bars 키가 없어 「봉 0개」로 접히고,
        // 그 0에서 정반대 결론이 나온 전례가 있다(.claude/rules/data-pipeline.md).
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("일봉 호출이 %s다 — 잴 것이 없다", response.getStatusCode())
                .isTrue();

        Map<String, Map<LocalDate, JsonNode>> bySymbol = new LinkedHashMap<>();
        JsonNode bars = response.getBody().path("bars");
        for (String symbol : SYMBOLS) {
            Map<LocalDate, JsonNode> byDate = new LinkedHashMap<>();
            for (JsonNode bar : bars.path(symbol)) {
                byDate.put(dateOf(bar), bar);
            }
            bySymbol.put(symbol, byDate);
        }
        return bySymbol;
    }

    /**
     * 한 종목의 <b>정규장</b> 분봉 체결금액 합. 분봉의 {@code vw × v}를 더한다.
     *
     * <p>분봉의 {@code vw}는 그 1분 창의 체결가 가중평균이므로 {@code vw × v}가 그 분의
     * 체결금액이다 — 여기에는 합성이 없다. 이 합이 <b>기준</b>이고, 일봉 쪽 두 값이 이것에서
     * 얼마나 벌어지는지를 잰다.
     */
    private static Map<LocalDate, Double> fetchSessionValue(RestClient data, String symbol) {
        ResponseEntity<JsonNode> response = data.get()
                .uri(uri -> uri.path("/v2/stocks/bars")
                        .queryParam("symbols", symbol)
                        .queryParam("timeframe", "1Min")
                        .queryParam("start", FROM)
                        .queryParam("end", TO)
                        .queryParam("feed", "sip")
                        .queryParam("adjustment", "raw")
                        .queryParam("limit", 10000)
                        .build())
                .retrieve()
                .toEntity(JsonNode.class);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("%s 분봉 호출이 %s다", symbol, response.getStatusCode())
                .isTrue();

        // 페이징이 남으면 합이 조용히 작아진다 — 그러면 비율이 통째로 부풀어 「vw가 좋다」로
        // 읽힌다. 남았으면 그 사실을 찍고 그 종목을 버린다.
        String next = response.getBody().path("next_page_token").asString(null);
        if (next != null && !next.isBlank()) {
            System.out.printf("!! %s 분봉이 한 페이지에 안 들어왔다 — 이 종목을 버린다%n", symbol);
            return Map.of();
        }

        Map<LocalDate, Double> byDate = new LinkedHashMap<>();
        for (JsonNode bar : response.getBody().path("bars").path(symbol)) {
            OffsetDateTime at = OffsetDateTime.parse(bar.path("t").asString(""));
            LocalTime utc = at.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalTime();
            if (utc.isBefore(SESSION_OPEN) || utc.isAfter(SESSION_CLOSE)) {
                continue;
            }
            double value = bar.path("vw").asDouble(0) * bar.path("v").asDouble(0);
            byDate.merge(dateOf(bar), value, Double::sum);
        }
        return byDate;
    }

    private static LocalDate dateOf(JsonNode bar) {
        return OffsetDateTime.parse(bar.path("t").asString(""))
                .withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
    }
}
