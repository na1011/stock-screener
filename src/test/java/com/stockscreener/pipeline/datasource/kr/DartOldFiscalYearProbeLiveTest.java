package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FY2015~2018을 <b>받을 수 있는가</b>, 그리고 <b>계정명이 지금 후보군과 같은가</b>를 묻는다
 * (P17 탐침 ②, ADR-0071).
 *
 * <p>이 phase의 백테스트 창은 {@code PROFIT_STREAK_Y >= 7}이 여는데, 지금 저장된 연간 재무가
 * FY2019부터라 7개년이 <b>2026-03-09에야</b> 갖춰진다. 창을 2022-04로 열려면 FY2015~2018이
 * 필요하고 그 적재가 <b>약 17,000콜</b>이다. <b>그 연도의 IFRS 태그를 재본 적이 한 번도 없다</b> —
 * 계정명이 다르면 17,000콜을 태우고 전 컬럼이 NULL로 들어온다.
 *
 * <p>묻는 것이 둘이다.
 * <ol>
 *   <li><b>데이터가 오는가</b> — {@code status}가 {@code 013}(데이터 없음)이면 창은 안 열린다.
 *       {@code bsns_year} 2015가 소스의 바닥이라는 것이 ADR-0071의 전제다.
 *   <li><b>{@link DartAccountMapper}의 후보군으로 잡히는가</b> — 후보군을 여기 베끼지 않고
 *       <b>그 상수를 그대로 읽는다.</b> 베끼면 매퍼를 고칠 때 탐침만 옛 이름을 재면서
 *       "괜찮다"고 답한다.
 * </ol>
 *
 * <p>보고서 둘을 보는 이유는 {@code financial-refill}이 연도마다 <b>보고서 4종</b>을 돌기
 * 때문이다. 사업보고서만 재고 분기 응답이 다르면 그 결손은 적재 뒤에 드러난다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DART_API_KEY", matches = ".+",
        disabledReason = "DART_API_KEY가 없어 건너뛴다")
class DartOldFiscalYearProbeLiveTest {

    /** 저장된 행이 없어 대조 상대가 없는 구간. 여기가 열려야 창이 2022-04로 간다. */
    private static final List<Integer> YEARS = List.of(2015, 2016, 2017, 2018);

    /** 사업보고서와 1분기보고서. 연간·분기 응답의 계정명이 같은지를 함께 본다. */
    private static final Map<String, String> REPORTS =
            new LinkedHashMap<>(Map.of("11011", "사업보고서(FY)", "11013", "1분기보고서(Q1)"));

    private static final int BATCH = 100;

    /** 후보군 이름 → 그 후보군. {@link DartAccountMapper}의 상수를 그대로 쓴다. */
    private static final Map<String, Set<String>> TARGETS = new LinkedHashMap<>();

    static {
        TARGETS.put("revenue", DartAccountMapper.REVENUE);
        TARGETS.put("operatingIncome", DartAccountMapper.OPERATING_INCOME);
        TARGETS.put("netIncome", DartAccountMapper.NET_INCOME);
        TARGETS.put("totalAssets", DartAccountMapper.TOTAL_ASSETS);
        TARGETS.put("totalLiabilities", DartAccountMapper.TOTAL_LIABILITIES);
        TARGETS.put("totalEquity", DartAccountMapper.TOTAL_EQUITY);
        TARGETS.put("currentAssets", DartAccountMapper.CURRENT_ASSETS);
        TARGETS.put("currentLiabilities", DartAccountMapper.CURRENT_LIABILITIES);
        TARGETS.put("retainedEarnings", DartAccountMapper.RETAINED_EARNINGS);
    }

    @Test
    @DisplayName("[live][probe] FY2015~2018이 오는가 · 계정명이 지금 후보군과 같은가 — 32콜")
    void probeOldFiscalYears() throws Exception {
        List<String> tickers = readActiveTickers();
        assertThat(tickers).as("편입 종목이 없으면 잴 것이 없다").isNotEmpty();

        DartCorpCodeCache cache = DartProbeSupport.corpCodeCache();
        RestClient client = DartProbeSupport.client();

        Map<String, String> corpToTicker = new LinkedHashMap<>();
        for (String ticker : tickers) {
            cache.findCorpCode(ticker).ifPresent(corp -> corpToTicker.put(corp, ticker));
        }
        List<List<String>> batches = partition(new ArrayList<>(corpToTicker.keySet()));

        System.out.println("\n===== P17 탐침 ② FY2015~2018 =====");
        System.out.printf("편입 %d종목 · corp_code %d · 묶음 %d · 예상 호출 %d회%n",
                tickers.size(), corpToTicker.size(), batches.size(),
                batches.size() * YEARS.size() * REPORTS.size());

        boolean anyYearOpened = false;

        for (Map.Entry<String, String> report : REPORTS.entrySet()) {
            System.out.println("\n----- " + report.getValue() + " -----");
            for (int year : YEARS) {
                Set<String> stockCodes = new TreeSet<>();
                // 후보군 이름 → 그 후보군으로 실제 잡힌 회사 수.
                Map<String, Integer> matched = new LinkedHashMap<>();
                TARGETS.keySet().forEach(name -> matched.put(name, 0));
                // 응답에 있었지만 어느 후보군에도 안 걸린 계정명.
                Map<String, Integer> unmatchedNames = new TreeMap<>();
                Map<String, Integer> statusCounts = new TreeMap<>();

                for (List<String> batch : batches) {
                    JsonNode body = call(client, batch, year, report.getKey());
                    statusCounts.merge(DartProbeSupport.describeStatus(body), 1, Integer::sum);
                    if (body == null) {
                        continue;
                    }
                    // 회사별로 모아야 "이 회사에서 이 계정이 잡혔는가"를 셀 수 있다.
                    Map<String, List<JsonNode>> rowsByStockCode = new LinkedHashMap<>();
                    for (JsonNode row : body.path("list")) {
                        String stockCode = row.path("stock_code").asString("");
                        if (stockCode.isBlank()) {
                            continue;
                        }
                        rowsByStockCode.computeIfAbsent(stockCode, c -> new ArrayList<>()).add(row);
                    }
                    stockCodes.addAll(rowsByStockCode.keySet());

                    for (List<JsonNode> rows : rowsByStockCode.values()) {
                        Set<String> hitNames = new LinkedHashSet<>();
                        for (Map.Entry<String, Set<String>> target : TARGETS.entrySet()) {
                            // 매퍼와 같은 판정을 쓴다 — 누적/당기 구분은 여기서 중요하지 않다.
                            if (DartAccountMapper.find(rows, target.getValue(), false) != null
                                    || DartAccountMapper.find(rows, target.getValue(), true) != null) {
                                matched.merge(target.getKey(), 1, Integer::sum);
                                hitNames.addAll(target.getValue());
                            }
                        }
                        for (JsonNode row : rows) {
                            String name = row.path("account_nm").asString("");
                            if (!name.isBlank() && !hitNames.contains(name)) {
                                unmatchedNames.merge(name, 1, Integer::sum);
                            }
                        }
                    }
                }

                int companies = stockCodes.size();
                anyYearOpened |= companies > 0;
                System.out.printf("%d: 회사 %d개 · status %s%n", year, companies, statusCounts);
                if (companies == 0) {
                    System.out.println("    !! 이 연도는 열리지 않는다."
                            + " status가 013이면 소스에 없는 것이고, 100이면 요청이 틀린 것이다.");
                    continue;
                }
                matched.forEach((name, hits) -> System.out.printf(
                        "    %-20s %4d/%d (%.0f%%)%n",
                        name, hits, companies, 100.0 * hits / companies));
                List<String> topUnmatched = unmatchedNames.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(10)
                        .map(e -> e.getKey() + "×" + e.getValue())
                        .toList();
                System.out.println("    후보군에 안 걸린 계정 상위: " + topUnmatched);
            }
        }

        System.out.println("\n----- 판정 -----");
        System.out.println("MUST: currentLiabilities가 낮으면 ROIC_PRETAX의 분기 TTM이 그 연도에"
                + " 안 나온다 — 다만 as-of 2022-04의 TTM은 FY2021 분기라 이 연도와 무관하다.");
        System.out.println("MUST: operatingIncome이 낮으면 PROFIT_STREAK_Y가 그만큼 짧아지고"
                + " 창이 안 열린다. 그것이 이 탐침의 본래 물음이다.");
        System.out.println("MUST NOT: 「후보군에 안 걸린 계정」을 곧바로 매핑 결손으로 읽지 않는다"
                + " — 주요계정 응답에는 우리가 안 쓰는 계정(비유동자산·자본금 등)이 원래 함께 온다.");

        assertThat(anyYearOpened)
                .as("FY2015~2018이 한 해도 안 열리면 창은 2022-04로 안 가고,"
                        + " 17,000콜 적재의 근거가 사라진다 — 멈추고 보고할 자리다")
                .isTrue();
    }

    private static List<String> readActiveTickers() throws Exception {
        String sql = """
                SELECT s.ticker
                FROM stock s
                JOIN universe_membership m ON m.stock_id = s.id AND m.effective_to IS NULL
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE s.is_active
                ORDER BY s.ticker
                """;
        List<String> tickers = new ArrayList<>();
        try (Connection connection = DartRestatementProbeLiveTest.openReadOnly();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                tickers.add(rs.getString("ticker"));
            }
        }
        return tickers;
    }

    private static JsonNode call(RestClient client, List<String> corpCodes,
                                 int year, String reportCode) {
        return DartProbeSupport.get(client, uri -> uri
                .path("/fnlttMultiAcnt.json")
                .queryParam("crtfc_key", DartProbeSupport.apiKey())
                .queryParam("corp_code", String.join(",", corpCodes))
                .queryParam("bsns_year", year)
                .queryParam("reprt_code", reportCode)
                .build());
    }

    private static List<List<String>> partition(List<String> source) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < source.size(); i += BATCH) {
            batches.add(source.subList(i, Math.min(source.size(), i + BATCH)));
        }
        return batches;
    }
}
