package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alpaca <b>{@code /v1/corporate-actions}</b>가 실제로 무엇을 주는지 잰다.
 * <b>P23의 착수 판단이 여기서 나온다.</b>
 *
 * <p>어댑터를 거치지 않고 {@link RestClient}를 직접 세운다 — 이 시점에는 어댑터가 없고, 재는
 * 대상도 우리 코드가 아니라 <b>소스</b>이기 때문이다({@link AlpacaBarsProbeLiveTest}와 같은
 * 이유·같은 형태다).
 *
 * <p><b>Market Data API다.</b> 자산 마스터·캘린더({@code paper-api.alpaca.markets})와 base URL이
 * 다르고, 인증 헤더는 두 계열이 같아 섞으면 <b>404가 「키 문제」로 보이지 않는다.</b>
 *
 * <p><b>개발가이드가 이미 답한 것은 다시 재지 않는다.</b> {@code us-market-api-dev-guide.md} §5가
 * 파라미터 표({@code start}/{@code end} 기본값이 <b>당일</b> · {@code limit} 기본 100이
 * <b>전체 기준</b> · 정렬이 {@code ex_date}가 아니라 {@code process_date}) · 타입 16종 ·
 * {@code data_quality}의 의미 · 「생성 시점을 보장하지 않는다」는 공식 경고를 이미 싣고 있다.
 * 다시 재면 콜을 태우면서 <b>두 번째 정본</b>을 만든다.
 *
 * <p>여기서 재는 것은 그 문서가 <b>답하지 않은 것</b>과 <b>이 저장소 고유의 제약</b>이다:
 * <ul>
 *   <li><b>응답 봉투의 모양과 필드명</b> — 가이드에 <b>응답 예시가 없다</b>(파라미터 표와 curl
 *       한 줄뿐). 파서를 짤 근거가 없으므로 이것이 첫 항목이다.</li>
 *   <li><b>NVDA 2024-06-10의 {@code old_rate}·{@code new_rate}·{@code ex_date}</b> — 비율 방향과
 *       효력일의 정본. {@code AdjustmentRecalculator}가 {@code trade_date < 효력일}인 봉을
 *       나누므로 <b>효력일이 하루만 밀려도 이미 분할된 봉이 한 번 더 나뉜다.</b></li>
 *   <li><b>무료 계정이 403을 내는가</b> — {@code AlpacaResponse.kindOf}가 403을 {@code AUTH}로
 *       접고 {@code AUTH}는 {@code fatal()}이다. <b>배치 전체가 죽는 자리를 모르는 채 짜지
 *       않는다.</b></li>
 *   <li><b>적재 창 전체의 타입 분포</b> — 무엇을 담을지가 여기서 나온다. {@code types}를 안 좁혀
 *       부르고 <b>담지 않을 것도 세어 남긴다</b>(「없다」와 「안 담는다」는 다르다).</li>
 *   <li><b>점이 든 심볼과 우선주 표기</b>({@code BRK.A}·{@code KIM.PRL}) — 안 받으면 조용히 빠진다.</li>
 *   <li><b>페이징 실동작</b>과 <b>모르는 심볼의 응답 형태</b> — 봉에서 둘 다 함정이었다.</li>
 * </ul>
 *
 * <p><b>탐침의 창을 실제 적재 창과 같게 둔다.</b> 좁은 창으로 재고 넓은 창으로 적재하면
 * 「없는 것에 매핑을 짜지 않는다」의 근거가 표본 밖에서 무너진다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaCorporateActionProbeLiveTest {

    private static final String DATA_URL = "https://data.alpaca.markets";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 파일럿 11종목 — {@code price_daily}에 미국 봉이 있는 전부다(2026-08-29 실측 2,772행). */
    private static final String PILOT =
            "AAPL,BRK.A,BRK.B,F,KIM.PRL,META,MSFT,NVDA,RH,T,VTEX";

    /**
     * 적재 창. 저장된 미국 봉이 2024년뿐이라 그 앞뒤로만 넓힌다.
     *
     * <p>기본값({@code min(price_daily.trade_date)} = 2020-01-02)을 쓰지 않는 이유는 그것이
     * <b>국내 데이터가 정한 전역값</b>이고, 봉이 없는 구간에서는 원장의 근접중복 창이
     * 퇴화하기 때문이다({@code findNearDuplicate}가 두 날짜 사이의 <b>봉 수</b>로 5거래일을 센다).
     */
    private static final String LOAD_FROM = "2023-12-01";

    private static final String LOAD_TO = LocalDate.now().toString();

    /**
     * 우리 봉에서 관측한 <b>유일한</b> 불연속(2026-08-29 실측: 2,772행 중 1건).
     * 종가 1208.88 → 121.79. 소스의 {@code ex_date}가 이 날이어야 계수가 맞는다.
     */
    private static final String NVDA_SPLIT_EX_DATE = "2024-06-10";

    private static RestClient client() {
        return RestClient.builder()
                .baseUrl(DATA_URL)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                // 4xx에 예외를 던지지 않게 한다 — 상태코드와 본문을 그대로 봐야 한다.
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private static ResponseEntity<String> call(RestClient client, Consumer<UriParams> params) {
        return client.get()
                .uri(uri -> {
                    uri.path("/v1/corporate-actions");
                    params.accept(new UriParams(uri::queryParam));
                    return uri.build();
                })
                .retrieve().toEntity(String.class);
    }

    /** {@code queryParam}만 노출하는 얇은 껍데기. 람다 안에서 빌더 타입을 안 드러내려는 것이다. */
    private record UriParams(java.util.function.BiConsumer<String, Object[]> sink) {
        void put(String name, Object value) {
            sink.accept(name, new Object[] {value});
        }
    }

    /**
     * 봉투가 어떤 모양이든 사건 하나하나를 꺼낸다.
     *
     * <p><b>모양을 모르는 것이 이 탐침의 첫 항목이므로</b> 셋을 다 받는다 —
     * {@code {"corporate_actions": {"forward_splits": [...]}}}(타입별 키) ·
     * {@code {"corporate_actions": [...]}}(평평한 배열) · 최상위 배열.
     * 꺼낸 사건에는 그것이 어느 키 아래 있었는지를 {@code __bucket}으로 붙여 돌려준다.
     */
    private static List<Map.Entry<String, JsonNode>> flatten(JsonNode root) {
        JsonNode container = root.has("corporate_actions") ? root.path("corporate_actions") : root;
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        if (container.isArray()) {
            container.forEach(node -> out.add(Map.entry("(배열)", node)));
            return out;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = container.properties().iterator();
             it.hasNext(); ) {
            Map.Entry<String, JsonNode> bucket = it.next();
            if (bucket.getValue().isArray()) {
                bucket.getValue().forEach(node -> out.add(Map.entry(bucket.getKey(), node)));
            }
        }
        return out;
    }

    private static String pageToken(JsonNode root) {
        JsonNode next = root.path("next_page_token");
        return next.isNull() || next.asString("").isBlank() ? null : next.asString("");
    }

    private static String head(String body, int limit) {
        if (body == null) {
            return "(null)";
        }
        return body.length() <= limit ? body : body.substring(0, limit) + " …";
    }

    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[live][probe] 봉투 모양·타입 분포·NVDA 분할의 비율과 효력일을 기록한다")
    void probeEnvelopeAndTypes() {
        RestClient data = client();
        System.out.println("\n===== /v1/corporate-actions 실측 =====");
        System.out.printf("창 %s ~ %s (적재 창과 같다) · 심볼 %s%n", LOAD_FROM, LOAD_TO, PILOT);

        // types를 좁히지 않는다 — 담지 않을 것도 세어 남기는 것이 이 phase 기준 축 ③이다.
        List<Map.Entry<String, JsonNode>> all = new ArrayList<>();
        String token = null;
        int pages = 0;
        String firstBody = null;
        do {
            final String pageToken = token;
            ResponseEntity<String> response = call(data, p -> {
                p.put("symbols", PILOT);
                p.put("start", LOAD_FROM);
                p.put("end", LOAD_TO);
                p.put("limit", 1000);
                p.put("sort", "asc");
                if (pageToken != null) {
                    p.put("page_token", pageToken);
                }
            });
            if (pages == 0) {
                firstBody = response.getBody();
                System.out.printf("HTTP %s%n", response.getStatusCode());
                System.out.printf("본문 앞머리: %s%n", head(firstBody, 900));
                assertThat(response.getStatusCode().is2xxSuccessful())
                        .as("403이면 이 계정에 이 엔드포인트 권한이 없다는 뜻이고, "
                                + "403은 AlpacaResponse.kindOf가 AUTH로 접어 fatal()이 된다. "
                                + "그러면 이 phase의 계획을 통째로 다시 세워야 한다. 본문=%s",
                                head(response.getBody(), 300))
                        .isTrue();
            }
            JsonNode root = MAPPER.readTree(response.getBody() == null ? "{}" : response.getBody());
            if (pages == 0) {
                System.out.printf("최상위 키: %s%n", root.propertyNames());
                JsonNode container = root.has("corporate_actions")
                        ? root.path("corporate_actions") : root;
                System.out.printf("컨테이너 모양: %s · 키=%s%n",
                        container.isArray() ? "배열" : "객체", container.propertyNames());
            }
            all.addAll(flatten(root));
            token = pageToken(root);
            pages++;
        } while (token != null && pages < 40);

        System.out.printf("%n페이지 %d · 사건 %d건%n", pages, all.size());

        // ── 타입 분포 ────────────────────────────────────────────────────
        Map<String, Integer> byBucket = new TreeMap<>();
        Map<String, Integer> bySymbol = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : all) {
            byBucket.merge(e.getKey(), 1, Integer::sum);
            bySymbol.merge(symbolOf(e.getValue()), 1, Integer::sum);
        }
        System.out.println("\n----- 타입(봉투 키) 분포 -----");
        byBucket.forEach((k, v) -> System.out.printf("  %-32s %d%n", k, v));
        System.out.println("----- 심볼 분포 -----");
        bySymbol.forEach((k, v) -> System.out.printf("  %-10s %d%n", k, v));

        // ── 점이 든 심볼과 우선주가 실제로 오는가 ─────────────────────────
        System.out.println("\n----- 점이 든 심볼·우선주 -----");
        for (String symbol : new String[] {"BRK.A", "BRK.B", "KIM.PRL"}) {
            System.out.printf("  %-8s 사건 %d건 — 0건이 「사건이 없다」인지 「표기를 못 알아듣는다」인지는"
                            + " 이 응답이 안 갈라 준다%n",
                    symbol, bySymbol.getOrDefault(symbol, 0));
        }

        // ── 사건 하나의 전체 필드 (파서를 여기서 짠다) ─────────────────────
        if (!all.isEmpty()) {
            Map.Entry<String, JsonNode> sample = all.get(0);
            System.out.printf("%n----- 사건 하나의 전체 필드 (버킷=%s) -----%n%s%n",
                    sample.getKey(), sample.getValue().toPrettyString());
        }

        // ── NVDA 분할: 비율 방향과 효력일의 정본 ──────────────────────────
        System.out.println("\n----- NVDA 분할 (우리 봉의 유일한 불연속) -----");
        List<JsonNode> nvdaSplits = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : all) {
            if (!"NVDA".equals(symbolOf(e.getValue()))) {
                continue;
            }
            System.out.printf("  [%s] %s%n", e.getKey(), e.getValue());
            if (e.getKey().contains("split") || e.getValue().toString().contains("rate")) {
                nvdaSplits.add(e.getValue());
            }
        }
        assertThat(nvdaSplits)
                .as("NVDA 2024-06-10 10:1 분할이 우리가 가진 유일한 실물 부채다. "
                        + "소스가 그것을 안 주면 담을 것이 무엇인지부터 다시 물어야 한다.")
                .isNotEmpty();

        boolean exDateMatches = nvdaSplits.stream()
                .anyMatch(n -> NVDA_SPLIT_EX_DATE.equals(n.path("ex_date").asString("")));
        System.out.printf("  ex_date가 우리 봉의 점프일(%s)과 같은가: %s%n",
                NVDA_SPLIT_EX_DATE, exDateMatches);
        System.out.println("  → AdjustmentRecalculator는 trade_date < 효력일인 봉을 나눈다.");
        System.out.println("     하루만 밀리면 이미 분할된 봉이 한 번 더 나뉘는데,");
        System.out.println("     adj_close = close × adj_factor는 그 어긋남을 보지 않는다(ADR-0050).");
        assertThat(exDateMatches)
                .as("ex_date가 %s가 아니면 효력일 해석부터 다시 정해야 한다 — 계수가 조용히 틀린다",
                        NVDA_SPLIT_EX_DATE)
                .isTrue();
    }

    /**
     * 사건이 붙는 종목. <b>필드 이름이 타입마다 다르다</b>(실측) — 배당·분할은 {@code symbol}
     * 하나인데 분사는 {@code source_symbol}(발행사)과 {@code new_symbol}(분사된 회사) 둘이다.
     *
     * <p>MUST: <b>발행사를 먼저 본다.</b> 가격이 조정되는 쪽이 그쪽이고, 뒤쪽을 집으면
     * 우리가 묻지도 않은 종목이 응답에 있는 것처럼 보인다(실제로 그렇게 오독했다).
     */
    private static String symbolOf(JsonNode node) {
        for (String field : new String[] {"symbol", "source_symbol", "target_symbol", "new_symbol"}) {
            String value = node.path(field).asString("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "(심볼 필드 미상)";
    }

    @Test
    @DisplayName("[live][probe] start·end를 안 주면 정말 당일만 오는가 — 0건과 「없다」가 같은 모양이 되는 자리")
    void probeDefaultWindow() {
        RestClient data = client();
        System.out.println("\n===== 창을 안 줬을 때 =====");

        ResponseEntity<String> bare = call(data, p -> p.put("symbols", PILOT));
        JsonNode bareRoot = MAPPER.readTree(bare.getBody() == null ? "{}" : bare.getBody());
        int bareCount = flatten(bareRoot).size();
        System.out.printf("start·end 없음 -> HTTP %s · 사건 %d건 · 본문=%s%n",
                bare.getStatusCode(), bareCount, head(bare.getBody(), 300));

        ResponseEntity<String> windowed = call(data, p -> {
            p.put("symbols", PILOT);
            p.put("start", LOAD_FROM);
            p.put("end", LOAD_TO);
            p.put("limit", 1000);
        });
        int windowedCount = flatten(
                MAPPER.readTree(windowed.getBody() == null ? "{}" : windowed.getBody())).size();
        System.out.printf("창을 준 같은 요청 -> 사건 %d건%n", windowedCount);
        System.out.println("  → 두 수가 다르면 「창을 안 준 0건」과 「그 기간에 사건이 없다」가 같은 모양이라는 뜻이다.");
        System.out.println("     어댑터는 start·end를 언제나 명시한다.");
    }

    @Test
    @DisplayName("[live][probe] limit 기본 100과 페이징 실동작 — 중간에 멈추면 뒤가 통째로 빈다")
    void probePaging() {
        RestClient data = client();
        System.out.println("\n===== 페이징 =====");

        // 작은 limit으로 순회를 강제한다. 봉에서는 정렬이 심볼 우선이라 중간에 멈추면
        // 뒤쪽 심볼이 통째로 비었다. 여기 정렬은 process_date라고 문서가 적었다 — 실제를 본다.
        String token = null;
        int pages = 0;
        Map<String, Integer> bySymbol = new TreeMap<>();
        List<String> firstOfPage = new ArrayList<>();
        do {
            final String pageToken = token;
            ResponseEntity<String> response = call(data, p -> {
                p.put("symbols", PILOT);
                p.put("start", LOAD_FROM);
                p.put("end", LOAD_TO);
                p.put("limit", 2);
                p.put("sort", "asc");
                if (pageToken != null) {
                    p.put("page_token", pageToken);
                }
            });
            JsonNode root = MAPPER.readTree(response.getBody() == null ? "{}" : response.getBody());
            List<Map.Entry<String, JsonNode>> page = flatten(root);
            page.forEach(e -> bySymbol.merge(symbolOf(e.getValue()), 1, Integer::sum));
            if (!page.isEmpty()) {
                firstOfPage.add(symbolOf(page.get(0).getValue()) + "/"
                        + page.get(0).getValue().path("process_date").asString("?"));
            }
            token = pageToken(root);
            pages++;
        } while (token != null && pages < 60);

        System.out.printf("limit=2로 순회하니 %d페이지 · 종목별 누적=%s%n", pages, bySymbol);
        System.out.printf("페이지별 첫 사건(심볼/process_date): %s%n", firstOfPage);
        System.out.println("  → 정렬 축이 process_date인지 심볼인지가 여기서 드러난다.");
        System.out.println("     어댑터는 next_page_token이 null이 될 때까지 돌고 부분 결과를 반환하지 않는다.");
    }

    @Test
    @DisplayName("[live][probe] data_quality=complete와 all의 건수 차 · 모르는 심볼의 응답 형태")
    void probeDataQualityAndUnknownSymbol() {
        RestClient data = client();
        System.out.println("\n===== data_quality =====");
        for (String quality : new String[] {"complete", "all"}) {
            ResponseEntity<String> response = call(data, p -> {
                p.put("symbols", PILOT);
                p.put("start", LOAD_FROM);
                p.put("end", LOAD_TO);
                p.put("limit", 1000);
                p.put("data_quality", quality);
            });
            JsonNode root = MAPPER.readTree(response.getBody() == null ? "{}" : response.getBody());
            System.out.printf("  data_quality=%-9s HTTP %s · 사건 %d건 · next=%s%n",
                    quality, response.getStatusCode(), flatten(root).size(),
                    pageToken(root) == null ? "null" : "있음");
        }
        System.out.println("  → 차이가 0이면 파일럿 범위에서는 어느 쪽을 명시해도 같다는 뜻이다.");
        System.out.println("     그래도 명시한다 — 기본값에 기대면 소스가 바꾸는 날 조용히 갈린다.");

        System.out.println("\n===== 모르는 심볼 =====");
        ResponseEntity<String> unknown = call(data, p -> {
            p.put("symbols", "ZZZZNOTREAL,NVDA");
            p.put("start", LOAD_FROM);
            p.put("end", LOAD_TO);
            p.put("limit", 1000);
        });
        System.out.printf("HTTP %s · 본문=%s%n", unknown.getStatusCode(), head(unknown.getBody(), 400));
        System.out.println("  → 봉은 200 응답에서 모르는 심볼이 조용히 빠졌다(에러도 빈 배열도 아니었다).");
        System.out.println("     포트는 「사건이 없다」와 「종목을 모른다」를 다르게 정의하므로 이 답이 필요하다.");
    }

    @Test
    @DisplayName("[live][probe] 모집단의 분할 건수 — 전량 적재(다른 phase)의 부채 크기다")
    void probePopulationSplits() {
        RestClient data = client();
        System.out.println("\n===== 모집단 분할 건수 (symbols 없이) =====");
        System.out.println("이것은 이 phase의 선행 조건이 아니라 전량 적재의 판단 재료다.");

        String token = null;
        int pages = 0;
        int count = 0;
        Map<String, Integer> byBucket = new TreeMap<>();
        do {
            final String pageToken = token;
            ResponseEntity<String> response = call(data, p -> {
                p.put("types", "forward_split,reverse_split");
                p.put("start", "2024-01-01");
                p.put("end", "2024-12-31");
                p.put("limit", 1000);
                p.put("sort", "asc");
                if (pageToken != null) {
                    p.put("page_token", pageToken);
                }
            });
            if (pages == 0 && !response.getStatusCode().is2xxSuccessful()) {
                System.out.printf("HTTP %s — symbols 없는 조회를 거절한다: %s%n",
                        response.getStatusCode(), head(response.getBody(), 300));
                return;
            }
            JsonNode root = MAPPER.readTree(response.getBody() == null ? "{}" : response.getBody());
            List<Map.Entry<String, JsonNode>> page = flatten(root);
            page.forEach(e -> byBucket.merge(e.getKey(), 1, Integer::sum));
            count += page.size();
            token = pageToken(root);
            pages++;
        } while (token != null && pages < 40);

        System.out.printf("2024년 미국 전체 분할·병합 %d건 · %d페이지 · 버킷=%s%n",
                count, pages, byBucket);
        System.out.println("  → 「1콜이면 답이 나온다」는 안 잰 값이었다. limit 최대가 1000이다.");
    }

    @Test
    @DisplayName("[live][probe] 타입마다 심볼 필드 이름이 다르다 · 병합의 부호 · limit 기본값")
    void probeSpinOffAndReverseSplitFields() {
        RestClient data = client();
        System.out.println("\n===== 분사(spin_off)의 필드 =====");
        System.out.println("배당·분할은 symbol 하나인데 분사는 source_symbol과 new_symbol 둘이다.");
        System.out.println("뒤쪽을 집으면 묻지도 않은 종목이 응답에 있는 것처럼 보인다 — 실제로 그렇게 오독했다.");

        ResponseEntity<String> spin = call(data, p -> {
            p.put("symbols", PILOT);
            p.put("types", "spin_off");
            p.put("start", LOAD_FROM);
            p.put("end", LOAD_TO);
            p.put("limit", 1000);
        });
        JsonNode spinRoot = MAPPER.readTree(spin.getBody() == null ? "{}" : spin.getBody());
        for (Map.Entry<String, JsonNode> e : flatten(spinRoot)) {
            System.out.printf("  [%s]%n%s%n", e.getKey(), e.getValue().toPrettyString());
        }
        System.out.println("  → 우리는 SecurityRef 하나에 사건을 붙인다. 필드를 잘못 고르면 분사가");
        System.out.println("     분사된 회사 쪽 원장에 들어가고, 그 계수는 아무 에러 없이 틀린다.");
        System.out.println("  → process_date와 ex_date가 3년 넘게 벌어진 사건이 여기 있다.");
        System.out.println("     요청 창은 process_date 기준이므로, ex_date로 창을 좁게 잡으면 못 받는다.");

        System.out.println("\n===== 병합(reverse_split)의 필드 — 파일럿에 없어 모집단에서 하나 뽑는다 =====");
        ResponseEntity<String> reverse = call(data, p -> {
            p.put("types", "reverse_split");
            p.put("start", "2024-06-01");
            p.put("end", "2024-06-30");
            p.put("limit", 2);
        });
        JsonNode reverseRoot = MAPPER.readTree(reverse.getBody() == null ? "{}" : reverse.getBody());
        for (Map.Entry<String, JsonNode> e : flatten(reverseRoot)) {
            System.out.printf("  [%s] %s%n", e.getKey(), e.getValue());
        }
        System.out.println("  → old_rate/new_rate의 방향이 분할과 대칭인지가 부호 계약의 입력이다.");

        System.out.println("\n===== limit 기본값 =====");
        ResponseEntity<String> noLimit = call(data, p -> {
            p.put("types", "forward_split,reverse_split");
            p.put("start", "2024-01-01");
            p.put("end", "2024-12-31");
        });
        JsonNode noLimitRoot = MAPPER.readTree(noLimit.getBody() == null ? "{}" : noLimit.getBody());
        System.out.printf("  limit 없이 2024년 전체 분할·병합 -> %d건 · next_page_token=%s%n",
                flatten(noLimitRoot).size(), pageToken(noLimitRoot) == null ? "null" : "있음");
        System.out.println("  → limit=1000으로는 773건이 한 페이지였다. 여기서 잘리면 기본값이 그만큼이라는 뜻이다.");
    }
}
