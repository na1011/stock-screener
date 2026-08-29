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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC EDGAR {@code submissions}가 업종(SIC)을 실제로 어떻게 주는지 잰다.
 * <b>P25의 착수 판단이 여기서 나온다.</b>
 *
 * <p>{@link SecEdgarProbeLiveTest}와 같은 형태다 — 어댑터를 거치지 않고 {@code RestClient}를
 * 직접 세운다. 이 시점에는 어댑터가 없고, 재는 대상도 우리 코드가 아니라 <b>소스</b>다.
 * 호스트·User-Agent·쓰로틀 계약은 그 탐침의 javadoc이 정본이고 여기서 되풀이하지 않는다.
 *
 * <p><b>이 탐침이 답해야 계획이 서는 것 넷</b> — 하나라도 예상과 다르면 스키마가 바뀐다:
 * <ul>
 *   <li><b>{@code sic} 문자열의 실제 자릿수.</b> SIC는 정의상 4자리인데 소스가 앞자리 0을
 *       잘라 보내면 {@code left(_,3)}이 <b>중분류를 소분류라 부른다.</b> 계획의
 *       {@code ck_stock_sic_code}(4자리 고정)와 어댑터의 zero-padding이 이 측정에 걸려 있다.</li>
 *   <li><b>{@code sicDescription}·{@code ownerOrg}.</b> 금융업 제외 집합의 유일한 근거다 —
 *       SEC 공식 SIC 목록 페이지는 <b>신원 안 밝힌 클라이언트에 403</b>이라(2026-08-29 실측)
 *       정적 목록으로 범위를 확인할 수 없다.</li>
 *   <li><b>응답 크기.</b> {@code companyfacts}가 종목당 78KB~7.9MB였다. 이쪽이 얼마인지가
 *       「종목당 1콜로 전량」과 벌크를 가른다.</li>
 *   <li><b>빈 {@code sic}의 비율과 모양.</b> 빈 값은 예외가 아니라 「소스가 모른다」로 접는다.</li>
 * </ul>
 *
 * <p><b>MUST NOT: 이 탐침의 출력에 User-Agent 값을 찍지 않는다.</b> 이메일이 들어 있다.
 *
 * <p><b>MUST NOT: 차단을 유발하지 않는다.</b> 스로틀·차단 시의 코드는 <b>재지 않는다</b> —
 * 유발하면 IP가 일시 제한되어 남은 측정이 전부 막힌다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+",
        disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecEdgarSubmissionsProbeLiveTest {

    private static final String DATA_URL = "https://data.sec.gov";
    private static final String FILES_URL = "https://www.sec.gov";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 공표 초당 10회의 절반(`api-sources` skill의 「공표값의 절반」). */
    private static final long THROTTLE_MS = 200;

    /**
     * 표본. 파일럿(재무가 이미 들어온 것)에 <b>금융업 후보를 일부러 섞었다.</b>
     *
     * <p>금융 쪽이 두꺼운 이유는 이 탐침이 그 집합의 근거를 만들어야 하기 때문이다 —
     * 은행·보험·REIT·자산운용·지주가 각각 어느 2자리로 오는지가 갈려야 한다.
     */
    private static final List<String> SAMPLE = List.of(
            // 파일럿 (CIK에 붙는 것)
            "AAPL", "BRK.A", "F", "META", "MSFT", "NVDA", "RH", "T", "VTEX",
            // 금융업 후보 — 어느 2자리로 오는지가 제외 집합을 정한다
            "JPM", "BAC", "PGR", "ALL", "O", "SPG", "BX", "SCHW", "CME", "SPGI",
            // 비금융 대조군
            "XOM", "KO", "WMT", "MRNA",
            // 앞자리가 0인 SIC(농업 01xx~02xx)를 일부러 넣는다. 소스가 앞자리 0을 잘라
            // 보내는지는 **그런 코드를 받아 봐야만** 알 수 있고, 그 답이 ck_stock_sic_code와
            // 어댑터의 zero-padding을 정한다. 이것 없이 "항상 4자리"라 적으면 표본이
            // 안 덮은 자리를 덮었다고 말하는 것이다.
            "FDP", "CALM", "LMNR", "ALCO");

    @Test
    @DisplayName("[live][probe] submissions가 주는 SIC의 자릿수·설명·빈 비율과 응답 크기를 잰다")
    void probeSicShapeAndSize() throws Exception {
        Map<String, Long> cikByTicker = resolveCiks(SAMPLE);
        RestClient data = client(DATA_URL);

        Map<Integer, Integer> lengthHistogram = new TreeMap<>();
        Map<String, Integer> divisionCount = new TreeMap<>();
        List<String> rows = new ArrayList<>();
        long totalBytes = 0;
        long minBytes = Long.MAX_VALUE;
        long maxBytes = 0;
        int empty = 0;
        int withFiscalYearEnd = 0;

        for (String ticker : SAMPLE) {
            Long cik = cikByTicker.get(ticker);
            if (cik == null) {
                rows.add("%-6s CIK 미매칭 — 이 종목은 업종을 원리적으로 못 받는다".formatted(ticker));
                continue;
            }
            String raw = body(data, "/submissions/CIK%010d.json".formatted(cik));
            long bytes = raw.getBytes(StandardCharsets.UTF_8).length;
            totalBytes += bytes;
            minBytes = Math.min(minBytes, bytes);
            maxBytes = Math.max(maxBytes, bytes);

            JsonNode root = MAPPER.readTree(raw);
            String sic = root.path("sic").asString("").strip();
            String description = root.path("sicDescription").asString("").strip();
            String ownerOrg = root.path("ownerOrg").asString("").strip();
            String fiscalYearEnd = root.path("fiscalYearEnd").asString("").strip();
            if (!fiscalYearEnd.isEmpty()) {
                withFiscalYearEnd++;
            }
            if (sic.isEmpty()) {
                empty++;
            } else {
                lengthHistogram.merge(sic.length(), 1, Integer::sum);
                divisionCount.merge(sic.substring(0, Math.min(2, sic.length())), 1, Integer::sum);
            }
            rows.add("%-6s sic=%-6s len=%d  fyEnd=%-5s  %-12s %s".formatted(
                    ticker, sic.isEmpty() ? "(빈값)" : sic, sic.length(),
                    fiscalYearEnd.isEmpty() ? "(없음)" : fiscalYearEnd,
                    ownerOrg.isEmpty() ? "(없음)" : ownerOrg, description));
        }

        System.out.printf("%n===== submissions의 SIC (표본 %d) =====%n", SAMPLE.size());
        rows.forEach(r -> System.out.println("  " + r));

        System.out.printf("%n자릿수 분포: %s%n", lengthHistogram);
        System.out.println("  → 4자리 하나뿐이면 ck_stock_sic_code를 '^[0-9]{4}$'로 고정한다.");
        System.out.println("     3자리가 섞이면 앞자리 0이 잘린 것이고, 어댑터가 4자리로 채워야 한다");
        System.out.println("     — 안 채우면 left(_,3)이 중분류를 소분류라 부른다");

        System.out.printf("빈 sic: %d / %d%n", empty, SAMPLE.size());
        System.out.printf("fiscalYearEnd 있음: %d / %d%n", withFiscalYearEnd, SAMPLE.size());
        System.out.printf("앞 2자리 분포: %s%n", divisionCount);
        System.out.println("  → 금융업 제외 집합의 후보는 이 표와 위 sicDescription이 정한다.");
        System.out.println("     MUST NOT: 「60~67이 금융이다」를 기억에서 사실로 적지 않는다");

        int fetched = SAMPLE.size() - (int) rows.stream().filter(r -> r.contains("CIK 미매칭")).count();
        System.out.printf("%n응답 크기: 합계 %.1fMB · 최소 %.0fKB · 최대 %.1fMB (분모 %d)%n",
                totalBytes / 1e6, minBytes / 1e3, maxBytes / 1e6, fetched);
        if (fetched > 0) {
            System.out.printf("전량(6,689종목) 환산: 약 %.1fGB · 초당 5회면 약 %d분%n",
                    (totalBytes / (double) fetched) * 6689 / 1e9, 6689 / 300);
        }
        System.out.println("  → 아래 벌크 크기와 견준다. 크기만으로 정하지 않는다 —");
        System.out.println("     벌크는 하루 한 번 재발행이라 신선도가 다르고 zip 파서가 따로 필요하다");

        assertThat(fetched).as("하나도 못 받으면 아래 수는 전부 「잴 것이 없었다」다").isPositive();
    }

    @Test
    @DisplayName("[live][probe] 벌크 대안 둘의 크기를 잰다 — 종목당 1콜의 대조군이다")
    void probeBulkAlternatives() {
        head(FILES_URL, "/Archives/edgar/daily-index/bulkdata/submissions.zip", "submissions.zip");
        // DERA 재무제표 데이터셋의 sub.txt에도 발행사별 SIC가 있다. 분기 zip 하나가 그 분기에
        // 공시한 발행사를 덮으므로 커버리지가 「그 분기에 공시한 곳」으로 갈린다 — 크기만
        // 재고, 커버리지 차이는 ADR에 대가로 적는다.
        head(FILES_URL, "/files/dera/data/financial-statement-data-sets/2026q2.zip", "DERA 2026q2");
        head(FILES_URL, "/files/dera/data/financial-statement-data-sets/2026q1.zip", "DERA 2026q1");
        System.out.println("  → 404면 그 분기가 아직 안 나온 것이다. 「없다」로 읽지 않는다");
    }

    @Test
    @DisplayName("[live][probe] 모르는 CIK의 실패 계약이 companyfacts와 같은지 잰다")
    void probeFailureContract() {
        RestClient data = client(DATA_URL);
        throttle();
        ResponseEntity<String> unknown = data.get()
                .uri("/submissions/CIK9999999999.json").retrieve().toEntity(String.class);
        System.out.printf("%n===== 실패 계약 =====%n모르는 CIK: HTTP %s · %s%n",
                unknown.getStatusCode(), preview(unknown.getBody()));
        System.out.println("  → companyfacts는 404 + XML 본문이었다. 같으면 SecEdgarResponse를 그대로 쓴다.");
        System.out.println("     다르면 그 매핑이 갈리므로 어댑터를 쓰기 전에 여기서 잡는다");
        assertThat(unknown.getStatusCode().is2xxSuccessful())
                .as("모르는 CIK가 200이면 「없음」과 「있음」이 구별되지 않는다").isFalse();
    }

    /** 우리 US 종목 중 몇이 CIK에 붙는지는 {@link SecEdgarProbeLiveTest}가 이미 쟀다. 여기서는 표본만 푼다. */
    private Map<String, Long> resolveCiks(List<String> tickers) throws Exception {
        String raw = body(client(FILES_URL), "/files/company_tickers_exchange.json");
        JsonNode root = MAPPER.readTree(raw);
        List<String> fields = new ArrayList<>();
        root.path("fields").forEach(f -> fields.add(f.asString()));
        int cikIndex = fields.indexOf("cik");
        int tickerIndex = fields.indexOf("ticker");
        assertThat(cikIndex).as("매핑 파일의 fields에 cik이 없다 — 계약이 바뀌었다").isNotNegative();

        Map<String, Long> byTicker = new LinkedHashMap<>();
        for (JsonNode row : root.path("data")) {
            byTicker.putIfAbsent(row.get(tickerIndex).asString(), row.get(cikIndex).asLong());
        }
        Map<String, Long> resolved = new LinkedHashMap<>();
        for (String t : tickers) {
            Long cik = byTicker.get(t);
            if (cik == null) {
                // SecTickerCikCache와 같은 정규화 하나뿐이다(점 → 하이픈).
                cik = byTicker.get(t.replace('.', '-'));
            }
            if (cik != null) {
                resolved.put(t, cik);
            }
        }
        return resolved;
    }

    private static void head(String baseUrl, String path, String label) {
        throttle();
        ResponseEntity<Void> response =
                client(baseUrl).head().uri(path).retrieve().toBodilessEntity();
        long length = response.getHeaders().getContentLength();
        System.out.printf("%n===== %s =====%nHTTP %s · Content-Length=%s%n",
                label, response.getStatusCode(),
                length < 0 ? "(헤더 없음)" : "%.2fGB".formatted(length / 1e9));
    }

    private static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                // MUST NOT: 이 값을 로그·출력에 찍지 않는다. 이메일이 들어 있다.
                .defaultHeader("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT"))
                // 4xx에 예외를 던지지 않게 한다 — 상태코드와 본문을 그대로 봐야 한다.
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

    /** 상태코드를 반드시 보고 본문을 돌려준다 — 안 보면 오류 본문이 「0건」으로 접힌다. */
    private static String body(RestClient client, String path) {
        throttle();
        ResponseEntity<String> response =
                client.get().uri(path).retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("HTTP %s — %s", response.getStatusCode(), preview(response.getBody()))
                .isTrue();
        return response.getBody();
    }

    private static String preview(String body) {
        if (body == null) {
            return "(없음)";
        }
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }
}
