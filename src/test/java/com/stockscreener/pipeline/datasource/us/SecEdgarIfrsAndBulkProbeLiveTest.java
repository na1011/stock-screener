package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.support.ReadOnlyDb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>progress.md가 미측정으로 남겨 둔 미국 항목 둘</b>을 잰다.
 *
 * <ol>
 *   <li><b>IFRS로만 보고하는 발행사가 모집단에서 몇인가.</b> {@code VTEX}가 176행 중 1행만 낸
 *       사례에서 나온 질문인데 <b>모집단 수를 안 쟀다.</b> 종목마다 {@code companyfacts}를
 *       부르면 6,266콜인데, <b>{@code frames}가 횡단면을 한 콜에 준다</b> — 다만 그 API가
 *       {@code ifrs-full} 택소노미에도 되는지는 <b>개발가이드가 적지 않았다.</b> 그것부터 잰다.</li>
 *   <li><b>벌크의 신선도.</b> progress.md가 「하루 한 번 재발행」이라 적었는데 그것은
 *       개발가이드의 서술이고 <b>재본 값이 아니다.</b> {@code Last-Modified}로 확인한다.</li>
 * </ol>
 *
 * <p><b>실측 (2026-08-29) — 이 파일이 그 답의 정본이다.</b>
 * <ul>
 *   <li><b>IFRS census</b>: CIK에 붙는 6,689 중 <b>IFRS를 내는 발행사 597</b>,
 *       그중 <b>{@code us-gaap}이 아예 없는 「전용」 494</b>, 병행 103. <b>52분 29초.</b></li>
 *   <li><b>{@code frames}는 {@code ifrs-full}을 안 준다</b> — 404. 대조군 {@code us-gaap}은
 *       같은 경로로 200(CY2024Q4I 6,259개 · CY2023Q4I 6,428개)이라 경로 문제가 아니다.
 *       <b>횡단면 한 콜로 세려던 길이 여기서 막혔다.</b></li>
 *   <li><b>{@code companyconcept}는 {@code ifrs-full}을 준다</b> — VTEX 200 · 1,199B.
 *       그래서 census가 종목당 <b>약 1.2KB</b>다({@code companyfacts}는 3.4MB).</li>
 *   <li><b>벌크</b>: {@code companyfacts.zip} 1.41GB · {@code Last-Modified}
 *       2026-08-29T03:49:41Z(11시간 19분 전) · {@code submissions.zip} 1.56GB · 03:57:30Z.
 *       개발가이드의 「약 03:00 <b>ET</b>」와 어긋난다 — 관측은 03:49 <b>UTC</b>다.</li>
 * </ul>
 *
 * <p><b>표본은 census를 대신하지 못했다.</b> 상한(us-gaap 프레임에 안 든 1,776) 안에서
 * 균등 간격 40종목을 뽑아 얻은 추정이 <b>266</b>이었는데 census는 <b>494</b>다 —
 * 신뢰구간 안이지만 점추정이 약 1.9배 빗나갔다. <b>그리고 앞선 표본은 목록 앞에서 25개를
 * 잘라낸 접두였다</b>(전부 A로 시작해서 드러났다). 균등 간격이 그 처방이다.
 *
 * <p><b>MUST NOT: VTEX를 「IFRS 전용」의 사례로 쓰지 않는다.</b> 그 종목은
 * {@code [ifrs-full, us-gaap]} 둘 다 갖고 {@code us-gaap}이 623B로 얇을 뿐이다.
 * 적재에서 176행 중 1행만 난 원인은 <b>전용이 아니라 얇음</b>이고,
 * <b>「얇은 발행사」가 몇인지는 안 쟀다</b> — 이 census는 {@code Assets} 존재만 봤다.
 *
 * <p><b>MUST NOT: 이 탐침의 출력에 User-Agent를 찍지 않는다</b>(이메일이 들어 있다).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+",
        disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecEdgarIfrsAndBulkProbeLiveTest {

    private static final String DATA_URL = "https://data.sec.gov";
    private static final String FILES_URL = "https://www.sec.gov";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long THROTTLE_MS = 200;

    /** 연간 재무상태표 프레임. {@code I}가 빠지면 빈 결과다(개발가이드 §2.6). */
    private static final List<String> FRAMES = List.of("CY2024Q4I", "CY2023Q4I");

    @Test
    @DisplayName("[live][probe] IFRS로만 보고하는 발행사가 우리 모집단에서 몇 종목인가")
    void probeIfrsOnlyIssuers() throws Exception {
        RestClient data = client(DATA_URL);

        Map<String, Set<Long>> ciksByTaxonomy = new LinkedHashMap<>();
        for (String taxonomy : List.of("ifrs-full", "us-gaap")) {
            Set<Long> ciks = new LinkedHashSet<>();
            for (String frame : FRAMES) {
                String path = "/api/xbrl/frames/%s/Assets/USD/%s.json".formatted(taxonomy, frame);
                throttle();
                ResponseEntity<String> r =
                        data.get().uri(path).retrieve().toEntity(String.class);
                if (!r.getStatusCode().is2xxSuccessful()) {
                    System.out.printf("  %s %s → HTTP %s%n", taxonomy, frame, r.getStatusCode());
                    continue;
                }
                JsonNode root = MAPPER.readTree(r.getBody());
                int n = root.path("data").size();
                root.path("data").forEach(d -> ciks.add(d.path("cik").asLong()));
                System.out.printf("  %-9s %s → HTTP 200 · %d개 발행사%n", taxonomy, frame, n);
            }
            ciksByTaxonomy.put(taxonomy, ciks);
        }

        Set<Long> ifrs = ciksByTaxonomy.getOrDefault("ifrs-full", Set.of());
        Set<Long> usgaap = ciksByTaxonomy.getOrDefault("us-gaap", Set.of());
        System.out.printf("%n프레임 합집합: ifrs-full %d · us-gaap %d · 둘 다 %d%n",
                ifrs.size(), usgaap.size(),
                ifrs.stream().filter(usgaap::contains).count());

        // frames가 ifrs-full을 안 준다는 것이 측정 결과다(404, 대조군 us-gaap은 200).
        // 그래서 **직접 세는 대신 상한으로 좁히고** 그 안을 표본으로 characterize한다.
        System.out.println(ifrs.isEmpty()
                ? "  → ifrs-full 프레임이 없다. 이 API로는 못 센다 — 아래는 상한과 표본이다"
                : "  → ifrs-full 프레임이 있다. 아래 교집합이 곧 답이다");

        // ---- 우리 모집단과 대조 ----
        Map<String, Long> cikByTicker = tickerToCik();
        List<String> ours = usTickers();
        assertThat(ours).as("US 종목이 없으면 아래 비율은 「잴 것이 없었다」다").isNotEmpty();

        List<String> inUsGaap = new ArrayList<>();
        List<String> notInUsGaap = new ArrayList<>();
        int mapped = 0;
        for (String t : ours) {
            Long cik = cikByTicker.get(t);
            if (cik == null) {
                cik = cikByTicker.get(t.replace('.', '-'));
            }
            if (cik == null) {
                continue;
            }
            mapped++;
            if (usgaap.contains(cik)) {
                inUsGaap.add(t);
            } else {
                notInUsGaap.add(t);
            }
        }

        System.out.printf("%n===== 우리 미국 %d종목 (CIK에 붙는 %d) =====%n", ours.size(), mapped);
        System.out.printf("us-gaap 프레임에 든 종목: %d%n", inUsGaap.size());
        System.out.printf("**안 든 종목(상한): %d**%n", notInUsGaap.size());
        System.out.println("  → 상한인 이유: 그 안에 IFRS 발행사 · 그 두 해에 공시 안 한 곳 ·");
        System.out.println("     XBRL을 아예 안 내는 자산(ETF·펀드·신탁)이 함께 들어 있다");

        // 상한 안을 표본으로 갈라 본다. 전량 census는 6,266콜인데 이 표본은 40콜이다.
        //
        // MUST: **균등 간격으로 뽑는다.** 목록이 티커 정렬이라 앞에서 잘라내면 표본이 아니라
        // 접두이고, 그 위에 세운 비율은 알파벳 편향을 그대로 물려받는다. 처음에 그렇게 짰다가
        // 표본이 전부 A로 시작해서 드러났다.
        int want = Math.min(40, notInUsGaap.size());
        List<String> sample = new ArrayList<>();
        for (int k = 0; k < want; k++) {
            sample.add(notInUsGaap.get((int) ((long) k * notInUsGaap.size() / want)));
        }
        int ifrsOnlyN = 0;
        int emptyN = 0;
        int usGaapN = 0;
        List<String> ifrsNames = new ArrayList<>();
        for (String t : sample) {
            Long cik = cikByTicker.get(t);
            if (cik == null) {
                cik = cikByTicker.get(t.replace('.', '-'));
            }
            throttle();
            ResponseEntity<String> r = data.get()
                    .uri("/api/xbrl/companyfacts/CIK{c}.json", "%010d".formatted(cik))
                    .retrieve().toEntity(String.class);
            if (!r.getStatusCode().is2xxSuccessful()) {
                emptyN++;
                continue;
            }
            JsonNode facts = MAPPER.readTree(r.getBody()).path("facts");
            boolean hasIfrs = facts.has("ifrs-full");
            boolean hasUsGaap = facts.has("us-gaap");
            if (hasIfrs && !hasUsGaap) {
                ifrsOnlyN++;
                ifrsNames.add(t);
            } else if (hasUsGaap) {
                usGaapN++;
            } else {
                emptyN++;
            }
        }
        System.out.printf("%n===== 상한 안 표본 %d종목 =====%n", sample.size());
        System.out.printf("IFRS로만: %d · us-gaap 있음(프레임만 못 든 것): %d · 둘 다 없음: %d%n",
                ifrsOnlyN, usGaapN, emptyN);
        System.out.printf("IFRS 표본: %s%n", ifrsNames);
        if (!sample.isEmpty()) {
            System.out.printf("→ 그 비율을 상한에 적용한 추정: 약 %d종목%n",
                    Math.round(ifrsOnlyN / (double) sample.size() * notInUsGaap.size()));
            System.out.println("   **추정이지 census가 아니다** — 아래 census 탐침이 그 수를 실제로 센다");
        }
    }

    @Test
    @DisplayName("[live][probe] companyconcept가 ifrs-full에도 되는가 — 되면 census가 싸진다")
    void probeIfrsViaCompanyConcept() throws Exception {
        RestClient data = client(DATA_URL);
        // MUST: CIK를 기억에서 박지 않는다. 매핑 파일에서 푼다 — 처음에 박았다가 그 CIK가
        // 정말 그 회사인지 확인하지 않은 채 "ifrs-full도 404"를 읽을 뻔했다.
        Map<String, Long> byTicker = tickerToCik();
        // VTEX는 api-spec이 ifrs-full 발행사로 기록한 종목이다. AAPL이 us-gaap 대조군이다.
        for (String ticker : List.of("VTEX", "AAPL")) {
            Long cik = byTicker.get(ticker);
            assertThat(cik).as("%s가 매핑 파일에 없다 — 이 대조가 성립하지 않는다", ticker).isNotNull();
            for (String taxonomy : List.of("ifrs-full", "us-gaap")) {
                throttle();
                ResponseEntity<String> r = data.get()
                        .uri("/api/xbrl/companyconcept/CIK{c}/{t}/Assets.json",
                                "%010d".formatted(cik), taxonomy)
                        .retrieve().toEntity(String.class);
                System.out.printf("  %-5s CIK%010d %-9s → HTTP %s · %dB%n",
                        ticker, cik, taxonomy, r.getStatusCode(),
                        r.getBody() == null ? 0 : r.getBody().length());
            }
            // companyfacts로도 물어 그 회사가 실제로 무엇을 내는지 대조한다.
            throttle();
            ResponseEntity<String> f = data.get()
                    .uri("/api/xbrl/companyfacts/CIK{c}.json", "%010d".formatted(cik))
                    .retrieve().toEntity(String.class);
            if (f.getStatusCode().is2xxSuccessful()) {
                JsonNode facts = MAPPER.readTree(f.getBody()).path("facts");
                List<String> ns = new ArrayList<>();
                facts.propertyNames().forEach(ns::add);
                System.out.printf("  %-5s companyfacts 네임스페이스: %s%n", ticker, ns);
            }
        }
        System.out.println("  → VTEX의 companyfacts에 ifrs-full이 있는데 companyconcept가 404면,");
        System.out.println("     그 API가 us-gaap·dei만 색인한다는 뜻이다 — census를 싸게 할 길이 없다");
    }

    @Test
    @DisplayName("[live][census] IFRS 발행사를 전량으로 센다 — companyconcept가 종목당 1.2KB다")
    void censusIfrsFilers() throws Exception {
        RestClient data = client(DATA_URL);
        Map<String, Long> byTicker = tickerToCik();
        List<String> ours = usTickers();

        List<String> ifrsOnly = new ArrayList<>();
        List<String> both = new ArrayList<>();
        int mapped = 0;
        int checked = 0;
        for (String t : ours) {
            Long cik = byTicker.get(t);
            if (cik == null) {
                cik = byTicker.get(t.replace('.', '-'));
            }
            if (cik == null) {
                continue;
            }
            mapped++;
            throttle();
            ResponseEntity<String> ifrs = data.get()
                    .uri("/api/xbrl/companyconcept/CIK{c}/ifrs-full/Assets.json",
                            "%010d".formatted(cik))
                    .retrieve().toEntity(String.class);
            checked++;
            if (!ifrs.getStatusCode().is2xxSuccessful()) {
                continue;
            }
            // IFRS를 내는 발행사만 한 번 더 물어 「전용인가 병행인가」를 가른다.
            throttle();
            ResponseEntity<String> gaap = data.get()
                    .uri("/api/xbrl/companyconcept/CIK{c}/us-gaap/Assets.json",
                            "%010d".formatted(cik))
                    .retrieve().toEntity(String.class);
            if (gaap.getStatusCode().is2xxSuccessful()) {
                both.add(t);
            } else {
                ifrsOnly.add(t);
            }
            if ((ifrsOnly.size() + both.size()) % 25 == 0) {
                System.out.printf("  … %d종목 확인 · IFRS %d(전용 %d)%n",
                        checked, ifrsOnly.size() + both.size(), ifrsOnly.size());
            }
        }

        System.out.printf("%n===== IFRS 발행사 census (미국 %d종목 · CIK에 붙는 %d) =====%n",
                ours.size(), mapped);
        System.out.printf("IFRS를 내는 발행사: **%d종목**%n", ifrsOnly.size() + both.size());
        System.out.printf("  그중 IFRS 전용(us-gaap 없음): **%d종목**%n", ifrsOnly.size());
        System.out.printf("  us-gaap과 병행: %d종목%n", both.size());
        System.out.printf("IFRS 전용 표본: %s%n",
                ifrsOnly.subList(0, Math.min(30, ifrsOnly.size())));
        System.out.println("  → 전용이 재무가 통째로 비는 자리다. 병행은 VTEX처럼 us-gaap이 얇을 수");
        System.out.println("     있는데, **얇은지는 이 census가 답하지 않는다**(Assets 존재만 봤다)");

        assertThat(mapped).as("CIK에 붙는 종목이 없으면 위 수는 「잴 것이 없었다」다").isPositive();
    }

    @Test
    @DisplayName("[live][probe] 벌크가 정말 하루 한 번 재발행되는가 — Last-Modified로 잰다")
    void probeBulkFreshness() {
        for (String path : List.of("/Archives/edgar/daily-index/xbrl/companyfacts.zip",
                "/Archives/edgar/daily-index/bulkdata/submissions.zip")) {
            throttle();
            ResponseEntity<Void> head =
                    client(FILES_URL).head().uri(path).retrieve().toBodilessEntity();
            long length = head.getHeaders().getContentLength();
            System.out.printf("%n%s%n  HTTP %s · %.2fGB · Last-Modified=%s%n",
                    path, head.getStatusCode(),
                    length < 0 ? Double.NaN : length / 1e9,
                    head.getHeaders().getFirstDate("Last-Modified") < 0
                            ? "(헤더 없음)"
                            : java.time.Instant.ofEpochMilli(
                                    head.getHeaders().getFirstDate("Last-Modified")));
        }
        System.out.println("\n  → progress.md의 「하루 한 번 재발행」이 참이면 이 값이 최근 24시간 안이다");
        System.out.println("     그 문장은 개발가이드 서술이었고 재본 값이 아니었다");
    }

    private static List<String> usTickers() throws Exception {
        List<String> ours = new ArrayList<>();
        try (Connection c = ReadOnlyDb.open();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ticker FROM stock WHERE market_code = 'US' ORDER BY ticker")) {
            while (rs.next()) {
                ours.add(rs.getString("ticker"));
            }
        }
        return ours;
    }

    private Map<String, Long> tickerToCik() throws Exception {
        throttle();
        ResponseEntity<String> r = client(FILES_URL).get()
                .uri("/files/company_tickers_exchange.json").retrieve().toEntity(String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("매핑 파일 HTTP %s", r.getStatusCode())
                .isTrue();
        JsonNode root = MAPPER.readTree(r.getBody());
        List<String> fields = new ArrayList<>();
        root.path("fields").forEach(f -> fields.add(f.asString()));
        int cikIndex = fields.indexOf("cik");
        int tickerIndex = fields.indexOf("ticker");
        Map<String, Long> byTicker = new LinkedHashMap<>();
        for (JsonNode row : root.path("data")) {
            byTicker.putIfAbsent(row.get(tickerIndex).asString(), row.get(cikIndex).asLong());
        }
        return byTicker;
    }

    private static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                // MUST NOT: 이 값을 출력에 찍지 않는다. 이메일이 들어 있다.
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private static void throttle() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("탐침이 인터럽트됐다", e);
        }
    }
}
