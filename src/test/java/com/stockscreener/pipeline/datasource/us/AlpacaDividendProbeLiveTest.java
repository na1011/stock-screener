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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alpaca {@code /v1/corporate-actions}의 <b>{@code cash_dividends}만</b> 잰다. P28의 착수 판단이
 * 여기서 나온다.
 *
 * <p>{@link AlpacaCorporateActionProbeLiveTest}가 이미 잰 것은 다시 재지 않는다 — 봉투 모양 ·
 * 타입 분포 · 페이징 정렬 · 모르는 심볼의 응답은 그 문서와 {@code alpaca-corporate-action.md}가
 * 정본이다. 여기서 재는 것은 <b>배당에만 걸리고, 그것을 안 재면 값이 조용히 틀리는</b> 셋이다.
 *
 * <ol>
 *   <li><b>{@code process_date − ex_date}의 분포.</b> 조회 창이 <b>처리 시각</b> 기준인데 우리가
 *       묻는 것은 <b>효력일</b>이라, 늦게 처리된 배당은 「그 해에 없다」와 같은 모양이 된다.
 *       P28 축①이 「무배당 0」을 그 지연 위에 세우므로 <b>이 수가 0을 낼지 말지를 정한다.</b>
 *       분할·분사에서 3년 넘게 벌어진 사건이 실재했지만(T→WBD) <b>배당에 대해서는 재본 적이 없다.</b>
 *   <li><b>{@code special}·{@code foreign}의 건수.</b> 합계에 넣을지가 갈리는데, 갈리면
 *       <b>「같은 이름이 시장마다 다른 것을 잰다」</b>가 된다(P27 축①). 국내 {@code DPS_COMMON}은
 *       사업보고서 배당표의 주당 현금배당금이다.
 *   <li><b>연도 경계에서 몇 건이 걸리는가.</b> 회계연도 창으로 자를 때 경계에 사건이 몰려 있으면
 *       하루 차이로 합이 갈린다.
 * </ol>
 *
 * <p><b>표본은 실제 조회 대상에서 뽑았다</b> — {@code US_TOP1000} 편입을 티커 순으로 20에 하나씩
 * (2026-09-01 실측 50종목). 파일럿 11종목으로 재면 <b>대형 배당주에 치우친 표본</b>이라
 * 지연 분포가 낙관적으로 나온다.
 *
 * <p><b>MUST: 페이지를 끝까지 돈다.</b> 정렬이 심볼 우선이라 중간에 멈추면 <b>뒤쪽 심볼이 통째로
 * 비고</b>, 그것은 「그 종목은 배당이 없었다」와 구별되지 않는다 — 이 탐침이 재려는 바로 그 구분이다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "APCA_API_KEY_ID", matches = ".+",
        disabledReason = "APCA_API_KEY_ID가 없어 건너뛴다")
class AlpacaDividendProbeLiveTest {

    private static final String DATA_URL = "https://data.alpaca.markets";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code US_TOP1000} 편입을 티커 오름차순으로 20에 하나씩 뽑은 50종목(2026-09-01). */
    private static final String SAMPLE =
            "A,ADM,ALAB,AMRN,ARES,AXP,BIIB,BSX,CB,CHRD,CMS,CPB,CSL,DDOG,DOW,EFX,ERAS,EXPE,"
            + "FISV,FTAI,GL,HAL,HQY,IDXX,IT,KEYS,LAD,LNTH,MANH,MHK,MPLX,MTSI,NKE,NXST,OSK,"
            + "PCOR,PM,PTC,RDNT,ROAD,SAIL,SMCI,SRRK,T,TOL,TW,UNM,VOYA,WDC,WTW";

    /** 적재 창과 같게 둔다 — 좁게 재고 넓게 적재하면 결론이 표본 밖에서 무너진다. */
    private static final String FROM = "2018-01-01";

    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_PAGES = 50;

    @Test
    @DisplayName("cash_dividends의 처리 지연·플래그·연도 경계를 잰다")
    void probeCashDividends() {
        List<JsonNode> rows = fetchAll();

        assertThat(rows)
                .as("표본 50종목 × %s~오늘에서 배당이 한 건도 안 왔다 — 잴 것이 없었다", FROM)
                .isNotEmpty();

        List<Long> lags = new ArrayList<>();
        TreeMap<String, Integer> byFlag = new TreeMap<>();
        TreeMap<Integer, Integer> byYear = new TreeMap<>();
        TreeSet<String> symbols = new TreeSet<>();
        TreeMap<String, Integer> missing = new TreeMap<>();
        int boundary = 0;
        int negativeLag = 0;

        for (JsonNode row : rows) {
            String symbol = row.path("symbol").asString("");
            symbols.add(symbol);
            LocalDate ex = dateOf(row, "ex_date");
            LocalDate process = dateOf(row, "process_date");
            if (ex == null) {
                missing.merge("ex_date", 1, Integer::sum);
                continue;
            }
            byYear.merge(ex.getYear(), 1, Integer::sum);
            // 연도 경계 ±3일. 회계연도 창으로 자를 때 하루 차이로 합이 갈리는 자리다.
            if (ex.getDayOfYear() <= 3 || ex.getDayOfYear() >= ex.lengthOfYear() - 2) {
                boundary++;
            }
            if (process == null) {
                missing.merge("process_date", 1, Integer::sum);
            } else {
                long lag = ChronoUnit.DAYS.between(ex, process);
                lags.add(lag);
                if (lag < 0) {
                    negativeLag++;
                }
            }
            if (row.path("rate").isMissingNode()) {
                missing.merge("rate", 1, Integer::sum);
            }
            if (row.path("special").asBoolean(false)) {
                byFlag.merge("special", 1, Integer::sum);
            }
            if (row.path("foreign").asBoolean(false)) {
                byFlag.merge("foreign", 1, Integer::sum);
            }
        }

        lags.sort(Long::compareTo);
        System.out.printf("%n===== cash_dividends 탐침 (표본 50종목 · %s ~ 오늘) =====%n", FROM);
        System.out.printf("사건 %d건 · 배당이 온 종목 %d/50%n", rows.size(), symbols.size());
        System.out.printf("연도별: %s%n", byYear);
        System.out.printf("플래그: %s (없으면 빈 맵)%n", byFlag);
        System.out.printf("필드 결손: %s (없으면 빈 맵)%n", missing);
        System.out.printf("연도 경계 ±3일에 걸린 사건: %d건%n", boundary);

        if (lags.isEmpty()) {
            System.out.println("process_date가 한 건도 없어 지연을 못 쟀다.");
        } else {
            System.out.printf("process_date − ex_date (일): 최소 %d · p50 %d · p90 %d · p99 %d · 최대 %d%n",
                    lags.getFirst(), at(lags, 0.50), at(lags, 0.90), at(lags, 0.99), lags.getLast());
            System.out.printf("  음수(효력일보다 먼저 처리) %d건 · 30일 초과 %d건 · 365일 초과 %d건%n",
                    negativeLag,
                    lags.stream().filter(v -> v > 30).count(),
                    lags.stream().filter(v -> v > 365).count());
        }

        // foreign 플래그가 붙은 종목의 연 합계를 낸다. rate가 USD인지 종가로 대조하려는 것이고,
        // 아니면 통화가 섞인 DPS가 되는데 값이 그럴듯해 안 드러난다.
        TreeMap<String, double[]> perSymbol = new TreeMap<>();
        int lastFullYear = LocalDate.now().getYear() - 1;
        for (JsonNode row : rows) {
            LocalDate ex = dateOf(row, "ex_date");
            if (ex == null || ex.getYear() != lastFullYear) {
                continue;
            }
            double[] acc = perSymbol.computeIfAbsent(
                    row.path("symbol").asString(""), k -> new double[2]);
            acc[0] += row.path("rate").asDouble(0);
            if (row.path("foreign").asBoolean(false)) {
                acc[1]++;
            }
        }
        System.out.printf("%n----- %d년 종목별 연 합계 (foreign 건수) -----%n", lastFullYear);
        perSymbol.forEach((sym, acc) ->
                System.out.printf("%-8s %10.4f  foreign %.0f%n", sym, acc[0], acc[1]));

        System.out.println("\n읽는 법 — 이 수가 정하는 것은 하나다: 회계연도 연간 재무의 공시일"
                + "(기말 대비 최소 18일 · 중앙 53일, 실측)이 위 지연을 덮는가."
                + " 안 덮으면 「무배당 0」을 내지 않는다. 덮어도 꼬리(최대)를 함께 적는다.");
        System.out.println("MUST: 이 탐침은 판정하지 않는다. 수만 내고 무엇을 저장할지는 설계의 몫이다.");
    }

    private static long at(List<Long> sorted, double q) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * (sorted.size() - 1))));
    }

    private static LocalDate dateOf(JsonNode row, String field) {
        String raw = row.path(field).asString("");
        try {
            return raw.isBlank() ? null : LocalDate.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 페이지를 끝까지 돈다. 중간에 멈추면 뒤쪽 심볼이 통째로 빈다. */
    @Test
    @DisplayName("[live][probe] 역년 하나짜리 요청 — 잡이 실제로 보낼 모양으로 그 해가 오는지 본다")
    void probeOneCalendarYearTheWayTheJobAsksForIt() {
        // 왜 묻는가: 「역년 2023을 더하면 +114종목」은 **우리 DB가 예측한 수**이지 소스가 그 해를
        // 준다는 증거가 아니다. 적재를 켜기 전에 그 전제를 읽기만 해서 확인한다.
        // 표본은 그 114 중 열둘이고 밖에서 갈아끼울 수 있다.
        String symbols = System.getProperty("probe.symbols",
                "A,AAPL,ACM,ACN,ADBE,ADI,AMAT,AMD,AOS,APD,ARMK,ARWR");
        String year = System.getProperty("probe.year", "2023");
        System.out.printf("%n===== 역년 하나 · symbols=%s year=%s =====%n", symbols, year);

        RestClient client = RestClient.builder()
                .baseUrl(DATA_URL)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
        ResponseEntity<String> response = client.get()
                .uri(uri -> uri.path("/v1/corporate-actions")
                        .queryParam("symbols", symbols)
                        .queryParam("types", "cash_dividend")
                        .queryParam("start", year + "-01-01")
                        .queryParam("end", year + "-12-31")
                        .queryParam("data_quality", "complete")
                        .queryParam("region", "us")
                        .queryParam("limit", PAGE_LIMIT)
                        .queryParam("sort", "asc")
                        .build())
                .retrieve().toEntity(String.class);
        System.out.printf("http=%s%n", response.getStatusCode());
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("상태코드를 안 보고 본문을 파싱하면 오류가 「0건」이 된다: %s", response.getBody())
                .isTrue();

        // MUST: 버킷 이름은 **복수**다. 요청의 types는 cash_dividend(단수)인데 응답 키는
        // cash_dividends다 — 단수로 읽으면 200에 0건이 되어 「소스가 그 해를 안 준다」로 읽힌다.
        // 정본은 AlpacaDividendAdapter.BUCKET이다.
        JsonNode dividends = MAPPER.readTree(response.getBody())
                .path("corporate_actions").path("cash_dividends");
        TreeMap<String, Integer> bySymbol = new TreeMap<>();
        for (JsonNode row : dividends) {
            bySymbol.merge(row.path("symbol").asString(""), 1, Integer::sum);
        }
        System.out.printf("사건 %d건 · 배당이 온 종목 %d/%d%n", dividends.size(), bySymbol.size(),
                symbols.split(",").length);
        bySymbol.forEach((symbol, count) -> System.out.printf("    %-6s %d건%n", symbol, count));
        // 「없다」와 「잴 것이 없었다」를 가른다.
        assertThat(dividends.size())
                .as("그 역년에 한 건도 안 오면 역년을 더하는 처방 자체가 무너진다")
                .isPositive();
    }

    private static List<JsonNode> fetchAll() {
        RestClient client = RestClient.builder()
                .baseUrl(DATA_URL)
                .defaultHeader("APCA-API-KEY-ID", System.getenv("APCA_API_KEY_ID"))
                .defaultHeader("APCA-API-SECRET-KEY", System.getenv("APCA_API_SECRET_KEY"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        List<JsonNode> out = new ArrayList<>();
        String token = null;
        int page = 0;
        do {
            final String pageToken = token;
            ResponseEntity<String> response = client.get()
                    .uri(uri -> {
                        uri.path("/v1/corporate-actions")
                                .queryParam("symbols", SAMPLE)
                                // 배당만 부른다. 타입 분포는 이미 잰 것이라 다시 안 태운다.
                                .queryParam("types", "cash_dividend")
                                .queryParam("start", FROM)
                                .queryParam("end", LocalDate.now().toString())
                                .queryParam("data_quality", "complete")
                                .queryParam("region", "us")
                                .queryParam("limit", PAGE_LIMIT)
                                .queryParam("sort", "asc");
                        if (pageToken != null) {
                            uri.queryParam("page_token", pageToken);
                        }
                        return uri.build();
                    })
                    .retrieve().toEntity(String.class);

            assertThat(response.getStatusCode().value())
                    .as("HTTP가 200이 아니다. 본문=%s", abbreviate(response.getBody()))
                    .isEqualTo(200);

            JsonNode root = MAPPER.readTree(response.getBody());
            JsonNode bucket = root.path("corporate_actions").path("cash_dividends");
            bucket.forEach(out::add);
            JsonNode next = root.path("next_page_token");
            token = next.isNull() || next.isMissingNode() ? null : next.asString();
            page++;
            assertThat(page)
                    .as("페이지가 %d를 넘는다 — 부분 결과는 「사건이 없다」와 구별되지 않는다", MAX_PAGES)
                    .isLessThanOrEqualTo(MAX_PAGES);
        } while (token != null);

        System.out.printf("페이지 %d개를 돌았다.%n", page);
        return out;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "(없음)";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
