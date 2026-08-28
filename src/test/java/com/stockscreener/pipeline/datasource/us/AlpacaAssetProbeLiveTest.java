package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alpaca <b>{@code /v2/assets}</b>가 실제로 무엇을 주는지 잰다. <b>P20의 착수 판단이 여기서
 * 나온다</b> — 기획서의 추정치(~5,600)는 이 저장소가 확인한 적이 없다.
 *
 * <p>어댑터를 거치지 않고 {@code RestClient}를 직접 세운다. 이 시점에는 어댑터가 없고, 재는
 * 대상도 우리 코드가 아니라 <b>소스</b>이기 때문이다. 어댑터의 봉투 판별은 나중에
 * {@code AlpacaUniverseLiveTest}가 따로 지킨다.
 *
 * <p><b>Trading API다.</b> Base URL이 시세({@code data.alpaca.markets})와 다르고, 섞으면 404가
 * 난다. 캘린더만 {@code v3}라는 것도 같은 함정의 다른 얼굴이다.
 *
 * <p>여기서 나온 수는 그대로 {@code docs/api-spec/alpaca-universe.md}로 옮긴다. 특히 셋은
 * <b>안 재면 나중에 통째로 되돌아온다</b>:
 * <ul>
 *   <li><b>심볼·이름의 최대 길이</b> — {@code stock.ticker}가 {@code varchar(20)},
 *       {@code stock.name}이 {@code varchar(200)}이다. 넘기는 행 하나가 수천 행 트랜잭션을
 *       통째로 되돌린다.</li>
 *   <li><b>중복 심볼</b> — {@code universe_membership}의 EXCLUDE 제약에서 뒤늦게 터진다.
 *       {@code UniverseSnapshot} 생성자도 막지만 그쪽은 잡을 "갱신 거부"로 보고하게 만든다.</li>
 *   <li><b>{@code status}를 뺐을 때의 수</b> — 문서는 기본값이 "모든 상태 포함"이라고 한다.
 *       명시를 빠뜨리면 상장폐지 종목이 조용히 섞이므로, 그 주장이 참인지를 수로 확인한다.</li>
 * </ul>
 *
 * <p><b>유형 분포는 세기만 한다.</b> 응답의 {@code class}는 전부 {@code us_equity} 하나이고
 * 증권 유형 필드가 없다. 이름 접미로 가르는 것은 <b>커버리지를 재본 적이 없는 추측</b>이므로,
 * 여기서는 분포만 남기고 판정 규칙은 노출이 열리는 phase가 정한다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaAssetProbeLiveTest {

    private static final String BASE_URL = "https://paper-api.alpaca.markets";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 유형 단서 후보. 커버리지 미실측이라 판정에 쓰지 않고 분포만 센다. */
    private static final Map<String, String> NAME_HINTS = new LinkedHashMap<>(Map.of(
            "ETF", "(?i)\\bETF\\b",
            "Fund/Trust", "(?i)\\b(Fund|Trust)\\b",
            "Preferred", "(?i)\\bPreferred\\b",
            "Warrant", "(?i)\\bWarrant",
            "Unit", "(?i)\\bUnits?\\b",
            "Depositary", "(?i)\\bDepositary\\b",
            "Common Stock", "(?i)Common Stock"));

    private static RestClient client() {
        return RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                // 4xx에 예외를 던지지 않게 한다 — 인증 실패의 상태코드와 본문을 그대로 봐야 한다.
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    @Test
    @DisplayName("[live][probe] /v2/assets의 규모·스키마·유형 분포를 기록한다")
    void probeAssets() {
        RestClient client = client();

        System.out.println("\n===== /v2/assets 실측 =====");

        Map<String, JsonNode> byExchange = new LinkedHashMap<>();
        for (String exchange : new String[] {"NYSE", "NASDAQ"}) {
            ResponseEntity<String> raw = client.get()
                    .uri(uri -> uri.path("/v2/assets")
                            .queryParam("status", "active")
                            .queryParam("asset_class", "us_equity")
                            .queryParam("exchange", exchange)
                            .build())
                    .retrieve().toEntity(String.class);

            String body = raw.getBody() == null ? "" : raw.getBody();
            JsonNode array = MAPPER.readTree(body);
            byExchange.put(exchange, array);

            System.out.printf("%-7s http=%s 종목=%d 바이트=%d%n",
                    exchange, raw.getStatusCode(), array.size(),
                    body.getBytes(StandardCharsets.UTF_8).length);
            assertThat(raw.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(array.isArray()).isTrue();
        }

        int total = byExchange.values().stream().mapToInt(JsonNode::size).sum();
        System.out.printf("NYSE+NASDAQ 합계 = %d종목%n", total);

        // 콤마 목록을 받는가 — 콜 수가 1인지 2인지를 정한다.
        JsonNode combined = getArray(client, "/v2/assets?status=active&asset_class=us_equity&exchange=NYSE,NASDAQ");
        System.out.printf("exchange=NYSE,NASDAQ 한 번에 → %d종목 (합계와 %s)%n",
                combined.size(), combined.size() == total ? "일치 → 1콜로 끝난다" : "불일치 → 2콜이 필요하다");

        // status 기본값이 "모든 상태 포함"이라는 문서 주장의 실측.
        JsonNode noStatus = getArray(client, "/v2/assets?asset_class=us_equity&exchange=NYSE,NASDAQ");
        System.out.printf("status 생략 → %d종목 (active 명시보다 %+d)%n",
                noStatus.size(), noStatus.size() - combined.size());

        reportTypes(byExchange);
        reportSchema(byExchange);
    }

    /** 컬럼 폭·유일성 — 여기서 안 재면 적재가 통째로 롤백된다. */
    private void reportSchema(Map<String, JsonNode> byExchange) {
        int maxSymbol = 0;
        int maxName = 0;
        int tradableFalse = 0;
        int blankName = 0;
        int noCusip = 0;
        TreeSet<String> symbols = new TreeSet<>();
        TreeSet<String> duplicates = new TreeSet<>();
        TreeSet<String> exchanges = new TreeSet<>();
        TreeSet<String> classes = new TreeSet<>();
        TreeSet<String> attributes = new TreeSet<>();
        TreeMap<Integer, String> longestNames = new TreeMap<>();
        int rows = 0;

        for (JsonNode array : byExchange.values()) {
            for (JsonNode asset : array) {
                rows++;
                String symbol = asset.path("symbol").asString("");
                String name = asset.path("name").asString("");
                maxSymbol = Math.max(maxSymbol, symbol.length());
                maxName = Math.max(maxName, name.length());
                longestNames.put(name.length(), symbol + " — " + name);
                if (longestNames.size() > 5) {
                    longestNames.pollFirstEntry();
                }
                if (!symbols.add(symbol)) {
                    duplicates.add(symbol);
                }
                if (name.isBlank()) {
                    blankName++;
                }
                if (asset.path("cusip").isNull() || asset.path("cusip").asString("").isBlank()) {
                    noCusip++;
                }
                if (!asset.path("tradable").asBoolean(true)) {
                    tradableFalse++;
                }
                exchanges.add(asset.path("exchange").asString(""));
                classes.add(asset.path("class").asString(""));
                for (JsonNode attribute : asset.path("attributes")) {
                    attributes.add(attribute.asString(""));
                }
            }
        }

        System.out.println("\n----- 스키마 -----");
        System.out.printf("행 %d · 고유 심볼 %d · 중복 심볼 %d %s%n",
                rows, symbols.size(), duplicates.size(),
                duplicates.isEmpty() ? "" : duplicates);
        System.out.printf("심볼 최대 길이 %d (stock.ticker varchar(20))%n", maxSymbol);
        System.out.printf("이름 최대 길이 %d (stock.name varchar(200)) · 빈 이름 %d%n", maxName, blankName);
        System.out.printf("cusip 없음 %d · tradable=false %d%n", noCusip, tradableFalse);
        System.out.printf("exchange 값 %s · class 값 %s%n", exchanges, classes);
        System.out.printf("attributes 값 %s%n", attributes.isEmpty() ? "(전부 비어 있다)" : attributes);
        System.out.println("가장 긴 이름 5개:");
        longestNames.descendingMap().forEach((length, label) ->
                System.out.printf("  %3d자  %s%n", length, label));

        // 컬럼 폭은 적재의 전제다. 넘치면 여기서 실패해야 한다 — 적재 중에 알면 늦다.
        //
        // MUST: 이 상수는 마이그레이션이 실제로 만든 폭이다. 실측을 보고 상수를 늘리는 것이
        // 아니라, 상수를 늘렸으면 마이그레이션도 함께 늘린 것이어야 한다. 2026-08-29 실측에서
        // 최대 229자가 나와 stock.name을 varchar(200)에서 넓혔다.
        assertThat(maxSymbol).as("stock.ticker varchar(20)").isLessThanOrEqualTo(20);
        assertThat(maxName).as("stock.name의 폭").isLessThanOrEqualTo(400);
        assertThat(duplicates).as("중복 심볼은 EXCLUDE 제약에서 뒤늦게 터진다").isEmpty();
        assertThat(blankName).as("SecurityDescriptor가 빈 이름을 거부한다").isZero();
    }

    /** 유형 분포 — 세기만 한다. 이 표 위에 판정 규칙을 세우는 것은 다음 phase다. */
    private void reportTypes(Map<String, JsonNode> byExchange) {
        Map<String, Integer> hits = new TreeMap<>();
        int rows = 0;
        for (JsonNode array : byExchange.values()) {
            for (JsonNode asset : array) {
                rows++;
                String name = asset.path("name").asString("");
                for (Map.Entry<String, String> hint : NAME_HINTS.entrySet()) {
                    if (name.matches(".*" + hint.getValue() + ".*")) {
                        hits.merge(hint.getKey(), 1, Integer::sum);
                    }
                }
            }
        }
        int scanned = rows;
        System.out.println("\n----- 이름 접미 분포 (미검증 단서. 판정에 쓰지 않는다) -----");
        hits.forEach((label, count) ->
                System.out.printf("%-14s %5d (%.1f%%)%n", label, count, 100.0 * count / scanned));
        System.out.printf("어느 단서에도 안 걸린 종목이 있는지는 위 합계와 행 %d를 대조해 본다%n", rows);
    }

    @Test
    @DisplayName("[live][probe] 틀린 자격증명은 401이다 — 가이드의 403 서술이 틀렸다")
    void probeAuthFailure() {
        RestClient bogus = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("APCA-API-KEY-ID", "bogus")
                .defaultHeader("APCA-API-SECRET-KEY", "bogus")
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        ResponseEntity<String> response = bogus.get()
                .uri("/v2/assets?status=active&asset_class=us_equity&exchange=NYSE")
                .retrieve().toEntity(String.class);

        System.out.printf("%n틀린 키 → http=%s body=%s%n", response.getStatusCode(), response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    private JsonNode getArray(RestClient client, String uri) {
        ResponseEntity<String> raw = client.get().uri(uri).retrieve().toEntity(String.class);
        assertThat(raw.getStatusCode().is2xxSuccessful()).as(uri).isTrue();
        return MAPPER.readTree(raw.getBody() == null ? "[]" : raw.getBody());
    }
}
