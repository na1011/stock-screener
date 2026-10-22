package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬의 companyfacts 틀(ADR-0182). 꼴마다 하나씩 — 각 꼴은 탐색 {@code explore/universe/ifrs/IFRS-PLAN-1022.md}가 원장에서
 * 잰 실제 모양을 줄인 것이다.
 */
class TaxonomyFrameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 사실 하나를 {@code facts[taxonomy][concept].units[unit]}에 더한다. */
    private static void fact(ObjectNode facts, String taxonomy, String concept, String unit, String accn, String form,
                             String filed, String start, String end, String val) {
        ObjectNode t = facts.has(taxonomy) ? (ObjectNode) facts.get(taxonomy) : facts.putObject(taxonomy);
        ObjectNode c = t.has(concept) ? (ObjectNode) t.get(concept) : t.putObject(concept);
        ObjectNode units = c.has("units") ? (ObjectNode) c.get("units") : c.putObject("units");
        ArrayNode array = units.has(unit) ? (ArrayNode) units.get(unit) : units.putArray(unit);
        ObjectNode x = array.addObject();
        if (start != null) {
            x.put("start", start);
        }
        x.put("end", end).put("val", val).put("accn", accn).put("fy", 2025).put("fp", "FY").put("form", form)
                .put("filed", filed);
    }

    private static List<String> values(JsonNode facts, String taxonomy, String concept, String unit) {
        List<String> out = new ArrayList<>();
        facts.path(taxonomy).path(concept).path("units").path(unit).forEach(x -> out.add(x.path("val").asString()));
        return out;
    }

    private static ObjectNode facts() {
        return JsonNodeFactory.instance.objectNode();
    }

    @Test
    @DisplayName("공시 하나는 팩트가 많은 택소노미 하나다 — IFRS 공시에 섞인 us-gaap 태그는 버린다(LTM 꼴)")
    void keepsTheMajorityTaxonomyPerFiling() {
        ObjectNode f = facts();
        String a = "0001-26-000001";
        fact(f, "ifrs-full", "Assets", "USD", a, "20-F", "2026-03-01", null, "2025-12-31", "20080");
        fact(f, "ifrs-full", "Equity", "USD", a, "20-F", "2026-03-01", null, "2025-12-31", "5000");
        fact(f, "ifrs-full", "Liabilities", "USD", a, "20-F", "2026-03-01", null, "2025-12-31", "15080");
        fact(f, "us-gaap", "Assets", "USD", a, "20-F", "2026-03-01", null, "2025-12-31", "2700");

        JsonNode out = TaxonomyFrame.of(f).facts();

        assertThat(values(out, "us-gaap", "Assets", "USD")).containsExactly("20080");
        assertThat(out.has("ifrs-full")).isFalse();
    }

    @Test
    @DisplayName("공시마다 표시 통화 하나 — 편의환산 USD와 USD 주당 값을 버린다(TSM 꼴)")
    void keepsTheMajorityCurrencyPerFiling() {
        ObjectNode f = facts();
        String a = "0002-26-000001";
        fact(f, "ifrs-full", "Revenue", "TWD", a, "20-F", "2026-04-16", "2025-01-01", "2025-12-31", "3800");
        fact(f, "ifrs-full", "Assets", "TWD", a, "20-F", "2026-04-16", null, "2025-12-31", "7000");
        fact(f, "ifrs-full", "Revenue", "USD", a, "20-F", "2026-04-16", "2025-01-01", "2025-12-31", "120");
        fact(f, "ifrs-full", "BasicEarningsLossPerShare", "TWD/shares", a, "20-F", "2026-04-16", "2025-01-01",
                "2025-12-31", "45");
        fact(f, "ifrs-full", "BasicEarningsLossPerShare", "USD/shares", a, "20-F", "2026-04-16", "2025-01-01",
                "2025-12-31", "1.4");

        TaxonomyFrame.Result result = TaxonomyFrame.of(f);

        assertThat(values(result.facts(), "us-gaap", "Revenues", "TWD")).containsExactly("3800");
        assertThat(values(result.facts(), "us-gaap", "Revenues", "USD")).isEmpty();
        assertThat(values(result.facts(), "us-gaap", "EarningsPerShareBasic", "TWD/shares")).containsExactly("45");
        assertThat(values(result.facts(), "us-gaap", "EarningsPerShareBasic", "USD/shares")).isEmpty();
        assertThat(result.latestAnnualCurrency()).isEqualTo("TWD");
    }

    @Test
    @DisplayName("전환의 다리가 끊기면 옛 기준 공시를 버린다(FLUT 꼴) · 재작성 비교값이 닿으면 둘 다 남긴다(CRH 꼴)")
    void dropsTheOlderEraOnlyWhenTheBridgeIsBroken() {
        ObjectNode broken = facts();
        fact(broken, "ifrs-full", "Assets", "USD", "0003-20-000001", "40-F", "2020-02-27", null, "2019-12-31", "11");
        fact(broken, "us-gaap", "Assets", "USD", "0003-25-000001", "10-K", "2025-03-04", null, "2023-12-31", "45");
        fact(broken, "us-gaap", "Assets", "USD", "0003-25-000001", "10-K", "2025-03-04", null, "2024-12-31", "46");
        fact(broken, "dei", "EntityCommonStockSharesOutstanding", "shares", "0003-20-000001", "40-F", "2020-02-27",
                null, "2020-02-20", "280");

        TaxonomyFrame.Result cut = TaxonomyFrame.of(broken);

        assertThat(cut.droppedAccessions()).containsExactly("0003-20-000001");
        assertThat(values(cut.facts(), "us-gaap", "Assets", "USD")).containsExactly("45", "46");
        assertThat(values(cut.facts(), "dei", "EntityCommonStockSharesOutstanding", "shares")).isEmpty();

        ObjectNode bridged = facts();
        fact(bridged, "ifrs-full", "Assets", "USD", "0004-23-000001", "20-F", "2023-03-01", null, "2022-12-31", "40");
        fact(bridged, "us-gaap", "Assets", "USD", "0004-24-000001", "10-K", "2024-02-28", null, "2022-12-31", "41");
        fact(bridged, "us-gaap", "Assets", "USD", "0004-24-000001", "10-K", "2024-02-28", null, "2023-12-31", "43");

        TaxonomyFrame.Result kept = TaxonomyFrame.of(bridged);

        assertThat(kept.droppedAccessions()).isEmpty();
        assertThat(values(kept.facts(), "us-gaap", "Assets", "USD")).containsExactlyInAnyOrder("41", "43", "40");
    }

    @Test
    @DisplayName("비지배가 없는 공시만 총순이익·총자본을 지배 귀속으로도 싣는다(AEM 꼴 대 비지배 있는 꼴)")
    void infersControllingOnlyWithoutNoncontrollingInterest() {
        ObjectNode without = facts();
        String a = "0005-26-000001";
        fact(without, "ifrs-full", "ProfitLoss", "USD", a, "40-F", "2026-03-19", "2025-01-01", "2025-12-31", "4400");
        fact(without, "ifrs-full", "Equity", "USD", a, "40-F", "2026-03-19", null, "2025-12-31", "25000");

        JsonNode plain = TaxonomyFrame.of(without).facts();

        assertThat(values(plain, "us-gaap", "NetIncomeLoss", "USD")).containsExactly("4400");
        assertThat(values(plain, "us-gaap", "ProfitLoss", "USD")).containsExactly("4400");
        assertThat(values(plain, "us-gaap", "StockholdersEquity", "USD")).containsExactly("25000");

        ObjectNode with = facts();
        String b = "0006-26-000001";
        fact(with, "ifrs-full", "ProfitLoss", "USD", b, "20-F", "2026-03-26", "2025-01-01", "2025-12-31", "1000");
        fact(with, "ifrs-full", "ProfitLossAttributableToNoncontrollingInterests", "USD", b, "20-F", "2026-03-26",
                "2025-01-01", "2025-12-31", "30");
        fact(with, "ifrs-full", "Equity", "USD", b, "20-F", "2026-03-26", null, "2025-12-31", "9000");

        JsonNode nci = TaxonomyFrame.of(with).facts();

        assertThat(values(nci, "us-gaap", "NetIncomeLoss", "USD")).isEmpty();
        assertThat(values(nci, "us-gaap", "StockholdersEquity", "USD")).isEmpty();
        assertThat(values(nci, "us-gaap", "NetIncomeLossAttributableToNoncontrollingInterest", "USD"))
                .containsExactly("30");
    }

    @Test
    @DisplayName("계약 매출은 Revenue가 없는 공시에서만 매출이다 — 광산사 잠정가격 조정(BHP 꼴)")
    void contractRevenueIsOnlyAFallback() {
        ObjectNode f = facts();
        String a = "0007-25-000001";
        fact(f, "ifrs-full", "Revenue", "USD", a, "20-F", "2025-09-01", "2024-07-01", "2025-06-30", "53817");
        fact(f, "ifrs-full", "RevenueFromContractsWithCustomers", "USD", a, "20-F", "2025-09-01", "2024-07-01",
                "2025-06-30", "53910");
        String b = "0008-25-000001";
        fact(f, "ifrs-full", "RevenueFromContractsWithCustomers", "USD", b, "20-F", "2025-04-01", "2024-01-01",
                "2024-12-31", "700");

        JsonNode out = TaxonomyFrame.of(f).facts();

        assertThat(values(out, "us-gaap", "Revenues", "USD")).containsExactly("53817");
        assertThat(values(out, "us-gaap", "RevenueFromContractWithCustomerExcludingAssessedTax", "USD"))
                .containsExactly("700");
    }

    @Test
    @DisplayName("IFRS 주식수 개념은 별칭으로 싣지 않는다 — 표지와 10배 넘게 갈리는 쌍이 있다")
    void doesNotAliasIfrsShareCounts() {
        ObjectNode f = facts();
        String a = "0009-24-000001";
        fact(f, "ifrs-full", "WeightedAverageShares", "shares", a, "20-F", "2024-04-01", "2023-01-01", "2023-12-31",
                "1000");
        fact(f, "ifrs-full", "Assets", "USD", a, "20-F", "2024-04-01", null, "2023-12-31", "1");

        JsonNode out = TaxonomyFrame.of(f).facts();

        assertThat(out.path("us-gaap").has("WeightedAverageNumberOfSharesOutstandingBasic")).isFalse();
        assertThat(out.path("us-gaap").has("WeightedAverageNumberOfDilutedSharesOutstanding")).isFalse();
    }

    private static JsonNode aem() throws Exception {
        Path path = Path.of("src/test/resources/fixtures/sec/companyfacts-aem-ifrs.json");
        return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8)).path("facts");
    }

    @Test
    @DisplayName("관문 켬이면 IFRS 발행사(AEM 40-F)가 행을 얻고 꺼짐은 지금처럼 0행이다")
    void ifrsIssuerGetsRowsOnlyBehindTheGate() throws Exception {
        List<RawFinancialReport> off = SecEdgarFacts.build(aem(), 2022, false).reports();
        List<RawFinancialReport> on = SecEdgarFacts.build(aem(), 2022, true).reports();

        assertThat(off).isEmpty();
        RawFinancialReport fy2025 = on.stream()
                .filter(r -> r.fiscalYear() == 2025 && "FY".equals(r.fiscalPeriod()))
                .max(java.util.Comparator.comparing(RawFinancialReport::publishedAt))
                .orElseThrow();
        assertThat(fy2025.currencyCode()).isEqualTo("USD");
        assertThat(fy2025.revenue()).isPositive();
        assertThat(fy2025.totalAssets()).isEqualByComparingTo(
                fy2025.totalLiabilities().add(fy2025.totalEquity()));
        // AEM은 비지배가 없다 — 지배 귀속이 총과 같다.
        assertThat(fy2025.netIncomeControlling()).isEqualByComparingTo(fy2025.netIncome());
        assertThat(fy2025.sharesOutstanding()).isEqualByComparingTo(new BigDecimal("500046600"));
    }
}
