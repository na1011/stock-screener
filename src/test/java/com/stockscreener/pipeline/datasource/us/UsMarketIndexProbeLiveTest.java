package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 미국 <b>시장지수</b>로 쓸 시계열을 Alpaca에서 받을 수 있는가.
 *
 * <p><b>ADR-0099 §켜기 전에 닫아야 하는 것 3번의 착수 판단이 여기서 나온다.</b>
 * {@code BETA_104W}·{@code EXCESS_RETURN_26W}·{@code EXCESS_RETURN_52W} 셋이 지수를 읽는데
 * {@code market_index_daily}에 미국 코드가 없다. 못 구한다고 <b>실측된 것은 지수 구성종목
 * 명부</b>이고(ADR-0086) <b>종가 시계열은 조사한 기록이 없었다.</b>
 *
 * <p>재는 것 셋:
 * <ul>
 *   <li><b>지수 심볼 자체가 되는가</b> — 되면 ETF 대용을 쓸 이유가 없다. 이 엔드포인트는
 *       경로가 {@code /v2/stocks/}라 주식만 줄 가능성이 높지만 재보기 전에는 모른다.</li>
 *   <li><b>지수 추종 ETF의 봉이 오는가</b> — {@code SPY} 등은 <b>NYSE Arca 상장</b>이라
 *       우리 자산 목록({@code exchange=NYSE,NASDAQ})에 없어 {@code stock} 행이 없다.
 *       그러나 이 엔드포인트는 {@code stock} 행을 요구하지 않는다. <b>모르는 심볼은 200
 *       응답에서 조용히 빠지므로</b>(alpaca-bars.md) 빈 목록과 실패를 갈라 적는다.</li>
 *   <li><b>읽기 창을 덮는가</b> — 1,516봉(ADR-0079)을 못 덮으면 지수를 붙여도 값이 안 난다.</li>
 * </ul>
 *
 * <p>종가를 파일로 내보내 <b>FRED의 실제 S&amp;P 500 지수와 대조</b>할 수 있게 한다.
 * ETF는 배당락만큼 지수보다 낮게 흐르는데 <b>그 크기를 잰 적이 없다.</b>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class UsMarketIndexProbeLiveTest {

    private static final String DATA_URL = "https://data.alpaca.markets";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 우리 미국 봉 창과 같게 잡는다 — 덮는지를 그 창으로 물어야 뜻이 있다. */
    private static final String FROM = "2019-09-03";

    /** 15분 규칙을 피한다. 날짜만 주는 {@code end}는 어제도 403이다(alpaca-bars.md). */
    private static final String TO = LocalDate.now().minusDays(2).toString();

    private static RestClient client() {
        return RestClient.builder()
                .baseUrl(DATA_URL)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private JsonNode fetch(RestClient c, String symbol, String pageToken) {
        ResponseEntity<String> raw = c.get()
                .uri(uri -> {
                    uri.path("/v2/stocks/bars")
                            .queryParam("symbols", symbol)
                            .queryParam("timeframe", "1Day")
                            .queryParam("start", FROM)
                            .queryParam("end", TO)
                            .queryParam("feed", "sip")
                            .queryParam("adjustment", "raw")
                            .queryParam("limit", 10000)
                            .queryParam("sort", "asc");
                    if (pageToken != null) {
                        uri.queryParam("page_token", pageToken);
                    }
                    return uri.build();
                })
                .retrieve().toEntity(String.class);
        String body = raw.getBody() == null ? "" : raw.getBody();
        System.out.printf("  %-8s http=%s bytes=%d%n", symbol, raw.getStatusCode(), body.length());
        if (!raw.getStatusCode().is2xxSuccessful()) {
            System.out.printf("      본문: %s%n", body.length() > 220 ? body.substring(0, 220) : body);
            return null;
        }
        return MAPPER.readTree(body);
    }

    @Test
    @DisplayName("[live][probe] 미국 지수로 쓸 시계열이 Alpaca에서 오는가")
    void probeIndexSeries() {
        RestClient c = client();

        System.out.println("\n===== ① 지수 심볼 자체 =====");
        for (String s : List.of("SPX", "^SPX", "$SPX", "^GSPC", "SPXUSD", "US500")) {
            JsonNode root = fetch(c, s, null);
            if (root != null) {
                System.out.printf("      bars 키=%s%n", root.path("bars").isNull()
                        ? "null(그 심볼 없음)" : root.path("bars").propertyNames().toString());
            }
        }

        System.out.println("\n===== ② 지수 추종 ETF =====");
        for (String s : List.of("SPY", "VOO", "IVV", "QQQ", "DIA", "IWM", "VTI")) {
            JsonNode root = fetch(c, s, null);
            if (root == null) {
                continue;
            }
            JsonNode bars = root.path("bars").path(s);
            String first = bars.isEmpty() ? "-" : bars.get(0).path("t").asString("");
            String last = bars.isEmpty() ? "-" : bars.get(bars.size() - 1).path("t").asString("");
            System.out.printf("      봉=%d  첫=%s  끝=%s  next=%s%n",
                    bars.size(), first, last,
                    root.path("next_page_token").isNull() ? "null" : "있음");
        }

        System.out.println("\n===== ③ SPY 전 구간 종가를 파일로 =====");
        List<String> lines = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            JsonNode root = fetch(c, "SPY", token);
            if (root == null) {
                break;
            }
            for (JsonNode b : root.path("bars").path("SPY")) {
                lines.add(b.path("t").asString("").substring(0, 10) + "," + b.path("c").asString(""));
            }
            token = root.path("next_page_token").isNull() ? null
                    : root.path("next_page_token").asString(null);
            pages++;
        } while (token != null && pages < 20);

        Path out = Path.of(System.getProperty("java.io.tmpdir"), "spy-close.csv");
        try {
            Files.write(out, lines);
            System.out.printf("SPY 종가 %d행을 %s에 적었다(페이지 %d)%n", lines.size(), out, pages);
        } catch (Exception e) {
            System.out.printf("파일 쓰기 실패: %s%n", e.getMessage());
        }
        System.out.println("읽기 창 1,516봉 충족 = " + (lines.size() >= 1516));
    }
}
