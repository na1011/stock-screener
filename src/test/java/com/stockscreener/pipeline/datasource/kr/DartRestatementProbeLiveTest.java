package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.support.ReadOnlyDb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적재 전에 <b>정정공시가 어디에 있는지</b>를 먼저 묻는다(P17 탐침 ①, ADR-0071).
 *
 * <p>{@code financial-refill --financial-years=11}은 종목당 <b>12년 × 보고서 4종</b>을 다시
 * 받는다. 그 재수집에서 소스가 저장값보다 늦은 공시일을 주면 {@code FinancialWriter}가
 * 정정으로 판정하고 {@code invalidate}를 불러 <b>그 종목 지표를 보존 창 전체에서 지운다.</b>
 * 복구는 백필이고 350종목이면 몇 시간이다 — <b>되돌릴 수 없는 것 앞에 되돌릴 수 있는 것을
 * 둔다</b>는 것이 이 탐침이 존재하는 이유다.
 *
 * <p><b>MUST NOT: {@code batch-load} skill의 36콜 레시피를 그대로 쓰지 않는다.</b> 그 레시피의
 * 9기간(FY 5 + 최근 3분기 + 당해 1분기)은 <b>지표가 실제로 읽는 범위</b>용이고, 이 잡이 도는
 * 범위가 아니다. 실제로 저장된 행에 {@code fiscal_period='Q1'}·{@code fiscal_year=2019}인데
 * {@code published_at}이 2021-08-31인 것이 있다 — 레시피 범위 밖에서 정정이 실재한다.
 * 범위를 좁히면 이 탐침은 <b>다른 것을 재면서 잰 것처럼 보인다.</b>
 *
 * <p>싸게 묻는 근거는 {@code fnlttMultiAcnt}가 회사 100개를 한 번에 받는다는 것이다
 * ({@link DartBulkAndTaxonomyProbeLiveTest}가 실측했다). 응답의 {@code rcept_no} 앞 8자리가
 * 접수일이므로 저장된 {@code published_at}과 그대로 견준다.
 *
 * <p>DB는 <b>운영 DB를 읽기만 한다.</b> 스프링 컨텍스트를 띄우지 않는 이유는 이 탐침이
 * 외부 응답과 저장값의 대조일 뿐이고, 컨텍스트를 띄우면 {@code @ConditionalOnApiKey} 어댑터가
 * 함께 등록되어 <b>무엇이 호출되는지가 흐려지기 때문</b>이다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartRestatementProbeLiveTest {

    /** {@code --financial-years=11}이 실제로 도는 회계연도. {@code FinancialRefillJob:81}과 같다. */
    private static final int FROM_FISCAL_YEAR = 2019;

    /**
     * 저장된 행이 있는 마지막 회계연도까지. 그 앞(FY2015~2018)은 저장된 행이 없어
     * <b>대조할 상대가 없다</b> — 그쪽은 탐침 ②가 "받아지는가"를 따로 묻는다.
     */
    private static final int TO_FISCAL_YEAR = 2026;

    /** {@code reprt_code} → 우리 {@code fiscal_period}. {@code DartFinancialAdapter:41-44}와 같다. */
    private static final Map<String, String> REPORT_TO_PERIOD = new LinkedHashMap<>(Map.of(
            "11013", "Q1", "11012", "Q2", "11014", "Q3", "11011", "FY"));

    /** DART가 한 번에 받는 회사 수. {@code status 021}이 그 상한을 말한다. */
    private static final int BATCH = 100;

    @Test
    @DisplayName("[live][probe] 적재가 도는 전 범위에서 정정 후보를 찾는다 — 128콜")
    void findRestatementCandidates() throws Exception {
        Map<String, Map<String, LocalDate>> stored = readStoredPublishedAt();
        assertThat(stored)
                .as("저장된 재무가 없으면 이 탐침은 아무것도 대조하지 못한다")
                .isNotEmpty();

        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();

        // corp_code → ticker. 응답이 stock_code를 주므로 되돌릴 키는 그것이다.
        Map<String, String> corpToTicker = new LinkedHashMap<>();
        for (String ticker : new TreeSet<>(stored.keySet())) {
            cache.findCorpCode(ticker).ifPresent(corp -> corpToTicker.put(corp, ticker));
        }

        List<List<String>> batches = partition(new ArrayList<>(corpToTicker.keySet()), BATCH);
        int years = TO_FISCAL_YEAR - FROM_FISCAL_YEAR + 1;
        int planned = batches.size() * years * REPORT_TO_PERIOD.size();

        System.out.println("\n===== P17 탐침 ① 정정공시 =====");
        System.out.printf("저장 종목 %d · corp_code 확보 %d · 묶음 %d · 회계연도 %d~%d · 보고서 %d종%n",
                stored.size(), corpToTicker.size(), batches.size(),
                FROM_FISCAL_YEAR, TO_FISCAL_YEAR, REPORT_TO_PERIOD.size());
        System.out.println("예상 호출 " + planned + "회");

        // 종목 → 정정 후보 목록. 뒤에 --tickers로 넘길 것이 이 키다.
        Map<String, List<String>> candidates = new TreeMap<>();
        int calls = 0;
        int compared = 0;
        int noRow = 0;
        Map<String, Integer> statusCounts = new TreeMap<>();

        for (int fiscalYear = FROM_FISCAL_YEAR; fiscalYear <= TO_FISCAL_YEAR; fiscalYear++) {
            for (Map.Entry<String, String> report : REPORT_TO_PERIOD.entrySet()) {
                for (List<String> batch : batches) {
                    calls++;
                    JsonNode body = call(client, batch, fiscalYear, report.getKey());
                    String status = body == null ? "(본문 없음)" : body.path("status").asString("");
                    statusCounts.merge(status, 1, Integer::sum);
                    if (body == null) {
                        continue;
                    }

                    // 회사마다 여러 계정 행이 오지만 rcept_no는 보고서 하나의 것이라 같다.
                    Map<String, String> receiptByStockCode = new HashMap<>();
                    for (JsonNode row : body.path("list")) {
                        String stockCode = row.path("stock_code").asString("");
                        String receipt = row.path("rcept_no").asString("");
                        if (!stockCode.isBlank() && receipt.length() >= 8) {
                            receiptByStockCode.putIfAbsent(stockCode, receipt.substring(0, 8));
                        }
                    }

                    for (Map.Entry<String, String> entry : receiptByStockCode.entrySet()) {
                        String ticker = entry.getKey();
                        LocalDate storedAt = stored
                                .getOrDefault(ticker, Map.of())
                                .get(key(fiscalYear, report.getValue()));
                        if (storedAt == null) {
                            // 저장된 행이 없다. 정정이 아니라 결손이며 적재가 새로 넣을 자리다.
                            noRow++;
                            continue;
                        }
                        compared++;
                        LocalDate sourceAt = parseReceipt(entry.getValue());
                        if (sourceAt != null && sourceAt.isAfter(storedAt)) {
                            candidates.computeIfAbsent(ticker, t -> new ArrayList<>()).add(
                                    "%d %s: 저장 %s → 소스 %s"
                                            .formatted(fiscalYear, report.getValue(),
                                                    storedAt, sourceAt));
                        }
                    }
                }
            }
        }

        System.out.println("\n----- 결과 -----");
        System.out.println("실제 호출 " + calls + "회 · status " + statusCounts);
        System.out.println("대조한 (종목,기) " + compared + "건 · 저장 행 없어 건너뜀 " + noRow + "건");
        System.out.println("정정 후보 " + candidates.size() + "종목");
        candidates.forEach((ticker, hits) -> {
            System.out.println("  " + ticker + " (" + hits.size() + "건)");
            hits.forEach(hit -> System.out.println("      " + hit));
        });

        System.out.println("\n----- 이어서 돌릴 것 -----");
        System.out.println("MUST: 이 종목들의 지표가 적재 중 보존 창 전체에서 지워진다."
                + " 적재 뒤 backfill의 --tickers가 이 목록이다:");
        System.out.println("  --tickers=" + String.join(",", candidates.keySet()));
        System.out.println("MUST: 대조한 건수가 계획한 (종목 × 기)와 크게 다르면"
                + " corp_code 확보나 stock_code 되돌리기가 샌 것이다 — 0건을 '정정 없음'으로 읽지 않는다.");

        assertThat(compared)
                .as("한 건도 대조하지 못했으면 이 탐침은 '정정이 없다'가 아니라 '못 쟀다'이다")
                .isPositive();
    }

    /** {@code (ticker → (연도·기 → 저장된 공시일))}. 지금 저장된 <b>최신 판</b>과 견준다. */
    private static Map<String, Map<String, LocalDate>> readStoredPublishedAt() throws Exception {
        String sql = """
                SELECT s.ticker, f.fiscal_year, f.fiscal_period, max(f.published_at) AS published_at
                FROM financial_statement f
                JOIN stock s ON s.id = f.stock_id
                WHERE f.fiscal_period <> 'Q4' AND f.fiscal_year BETWEEN ? AND ?
                GROUP BY 1, 2, 3
                """;
        Map<String, Map<String, LocalDate>> stored = new LinkedHashMap<>();
        try (Connection connection = ReadOnlyDb.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, FROM_FISCAL_YEAR);
            statement.setInt(2, TO_FISCAL_YEAR);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    stored.computeIfAbsent(rs.getString("ticker"), t -> new LinkedHashMap<>())
                            .put(key(rs.getInt("fiscal_year"), rs.getString("fiscal_period")),
                                    rs.getObject("published_at", LocalDate.class));
                }
            }
        }
        return stored;
    }

    @Test
    @DisplayName("[live][probe] 옛 회계연도의 접수번호 — 소스가 원본 판을 주는가, 정정본만 주는가")
    void probeWhetherOldFiscalYearsStillReturnTheOriginalFiling() throws Exception {
        // 왜 묻는가: 연간 재무의 min(published_at)이 period_end + 180일보다 늦은 종목이 51개다
        // (000880 한화는 FY2023·FY2024가 둘 다 2025-12-18에 revision_no=0). 그것이 우리가
        // 원본을 못 받은 것인지 소스가 원본을 안 주는 것인지는 **소스에 물어야만** 갈린다.
        //
        // 거버넌스 쪽(alotMatter)에서는 정정본이 옛 접수번호를 덮는 것이 확인됐다. 재무 쪽
        // (fnlttMultiAcnt)이 같은지를 여기서 본다.
        String tickers = System.getProperty("probe.tickers", "000880,006360,010130");
        String years = System.getProperty("probe.years", "2022,2023,2024");
        System.out.printf("%n===== 옛 회계연도의 접수번호 · tickers=%s years=%s =====%n", tickers, years);

        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        Map<String, String> corpByTicker = new LinkedHashMap<>();
        for (String ticker : tickers.split(",")) {
            cache.findCorpCode(ticker.trim())
                    .ifPresent(code -> corpByTicker.put(ticker.trim(), code));
        }
        assertThat(corpByTicker).as("corp_code를 하나도 못 풀면 아래 출력은 아무것도 답하지 않는다")
                .isNotEmpty();

        RestClient client = DartProbeSupport.client();
        int answered = 0;
        for (Map.Entry<String, String> entry : corpByTicker.entrySet()) {
            System.out.printf("%n--- %s%n", entry.getKey());
            for (String year : years.split(",")) {
                JsonNode body = call(client, List.of(entry.getValue()),
                        Integer.parseInt(year.trim()), "11011");
                String status = body == null ? "(본문 없음)" : body.path("status").asString("");
                Set<String> receipts = new TreeSet<>();
                if (body != null) {
                    for (JsonNode row : body.path("list")) {
                        String receipt = row.path("rcept_no").asString("");
                        if (receipt.length() >= 8) {
                            receipts.add(receipt.substring(0, 8));
                        }
                    }
                }
                if (!receipts.isEmpty()) {
                    answered++;
                }
                System.out.printf("    bsns_year=%s status=%s 접수일=%s%n",
                        year.trim(), status, receipts.isEmpty() ? "(없음)" : receipts);
            }
        }
        assertThat(answered).as("하나도 못 받으면 위는 전부 「잴 것이 없었다」다").isPositive();
    }

    private static JsonNode call(RestClient client, List<String> corpCodes,
                                 int fiscalYear, String reportCode) {
        return DartProbeSupport.get(client, uri -> uri
                .path("/fnlttMultiAcnt.json")
                .queryParam("crtfc_key", DartProbeSupport.apiKey())
                .queryParam("corp_code", String.join(",", corpCodes))
                .queryParam("bsns_year", fiscalYear)
                .queryParam("reprt_code", reportCode)
                .build());
    }

    /** {@code rcept_no} 앞 8자리는 접수일 {@code yyyyMMdd}다. 형식이 아니면 null을 낸다. */
    private static LocalDate parseReceipt(String yyyymmdd) {
        try {
            return LocalDate.of(Integer.parseInt(yyyymmdd.substring(0, 4)),
                    Integer.parseInt(yyyymmdd.substring(4, 6)),
                    Integer.parseInt(yyyymmdd.substring(6, 8)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String key(int fiscalYear, String fiscalPeriod) {
        return fiscalYear + "-" + fiscalPeriod;
    }

    private static List<List<String>> partition(List<String> source, int size) {
        List<List<String>> batches = new ArrayList<>();
        Set<String> deduped = new LinkedHashSet<>(source);
        List<String> all = new ArrayList<>(deduped);
        for (int i = 0; i < all.size(); i += size) {
            batches.add(all.subList(i, Math.min(all.size(), i + size)));
        }
        return batches;
    }
}
