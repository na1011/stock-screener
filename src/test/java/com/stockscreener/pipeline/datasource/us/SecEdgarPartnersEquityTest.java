package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 자본 — LP · LLC 태그와 지배자본의 비지배 판정(ADR-0188 · 탐색 {@code LP-EQUITY-PLAN-1024.md} v1.1). 픽스처는 실제
 * companyfacts를 자본 · 비지배 · 자산 · 부채 · 매출 · 순이익 태그와 사례 기간으로 자른 것이다.
 *
 * <p>무너지는 방식이 조용하다 — 비지배를 다른 태그로 내는 회사(ARES · BX · TKO)의 지배자본이 총자본이 되어도 값은 그럴듯하고
 * (BX 21,062M 대 원장 9,015M), 판이 섞인 행은 한 행 안에서 자산 − 부채 ≠ 자본인 채로 저장된다.
 */
class SecEdgarPartnersEquityTest {

    private static List<RawFinancialReport> build(String name, int from, boolean usGate) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/sec", "companyfacts-" + name + "-partners-equity.json");
        JsonNode facts = new ObjectMapper().readTree(Files.readString(path, StandardCharsets.UTF_8)).path("facts");
        return SecEdgarFacts.build(facts, from, usGate).reports();
    }

    private static RawFinancialReport at(List<RawFinancialReport> reports, int fiscalYear, String period, String published) {
        LocalDate on = LocalDate.parse(published);
        return reports.stream()
                .filter(r -> r.fiscalYear() == fiscalYear && period.equals(r.fiscalPeriod()) && r.publishedAt().equals(on))
                .findFirst().orElseThrow(() -> new AssertionError(fiscalYear + " " + period + " @" + published + " 판이 없다"));
    }

    @Test
    @DisplayName("LP 총 · 지배 · 비지배가 같은 공시에서 닫히면 LP 지배 태그가 지배자본이다 — SE 계열 총자본이 있는 판에서도(ET 2026 Q1)")
    void partnersCapitalIsControllingWhenTheSumCloses() throws Exception {
        RawFinancialReport on = at(build("et", 2025, true), 2026, "Q1", "2026-05-07");
        RawFinancialReport off = at(build("et", 2025, false), 2026, "Q1", "2026-05-07");

        assertThat(on.totalEquity()).isEqualByComparingTo("49737000000");
        assertThat(on.totalEquityControlling()).as("PartnersCapital 34,462M + 비지배 15,275M = 총 49,737M")
                .isEqualByComparingTo("34462000000");
        assertThat(off.totalEquityControlling()).as("관문 꺼짐은 LP 태그를 안 읽는다").isNull();

        RawFinancialReport q1of2019 = at(build("et", 2019, true), 2019, "Q1", "2020-05-11");
        assertThat(q1of2019.totalEquityControlling()).as("원장 PartnersCapital — 「총 − 비지배」 유도는 20,759M")
                .isEqualByComparingTo("20654000000");
    }

    @Test
    @DisplayName("MinorityInterest를 안 내도 손익 비지배가 있고 원장이 두 자본을 갈라 실었으면 지배자본은 StockholdersEquity다(BX 2026 Q2)")
    void incomeNoncontrollingKeepsTheDirectControllingEquity() throws Exception {
        RawFinancialReport on = at(build("bx", 2025, true), 2026, "Q2", "2026-08-07");
        RawFinancialReport off = at(build("bx", 2025, false), 2026, "Q2", "2026-08-07");

        assertThat(on.totalEquityControlling()).isEqualByComparingTo("9014543000");
        assertThat(on.totalEquity()).isEqualByComparingTo("21062145000");
        assertThat(off.totalEquityControlling()).as("ADR-0159 옛 규칙 — 비지배 포함 총자본").isEqualByComparingTo("21062145000");
    }

    @Test
    @DisplayName("손익 비지배를 포괄손익 태그로만 내는 기간도 비지배 있음이다(TKO 2026 Q2)")
    void comprehensiveIncomeNoncontrollingCounts() throws Exception {
        RawFinancialReport on = at(build("tko", 2025, true), 2026, "Q2", "2026-08-03");

        assertThat(on.totalEquityControlling()).isEqualByComparingTo("3395932000");
    }

    @Test
    @DisplayName("비지배가 없는데 두 자본이 갈리면 자산 − 부채가 고른 쪽이다 — 비지배 포함 태그가 쓰레기(UNM 2026 Q2)")
    void balanceEvidenceRejectsAGarbageIncludingTag() throws Exception {
        RawFinancialReport on = at(build("unm", 2025, true), 2026, "Q2", "2026-07-29");
        RawFinancialReport off = at(build("unm", 2025, false), 2026, "Q2", "2026-07-29");

        assertThat(on.totalEquityControlling()).isEqualByComparingTo("10809500000");
        assertThat(on.totalEquity()).isEqualByComparingTo("10809500000");
        assertThat(off.totalEquityControlling()).as("옛 규칙은 쓰레기 −1,870M을 지배자본으로 고른다").isEqualByComparingTo("-1870000000");
    }

    @Test
    @DisplayName("같은 공시의 두 자본이 늘 같으면 이어받은 값끼리의 차이는 판 혼합이다 — 새 판정을 안 건다(UHAL 2021 FY 2023-06-02 판)")
    void mixedVintagesKeepTheLegacyBehaviour() throws Exception {
        RawFinancialReport on = at(build("uhal", 2020, true), 2021, "FY", "2023-06-02");
        RawFinancialReport off = at(build("uhal", 2020, false), 2021, "FY", "2023-06-02");

        assertThat(on.totalEquityControlling()).isEqualByComparingTo(off.totalEquityControlling());
        assertThat(on.totalEquity()).isEqualByComparingTo(off.totalEquity());
    }

    @Test
    @DisplayName("부채 유도는 옛 비지배 술어 그대로다 — 손익 비지배가 상환가능분(이미 메자닌)뿐인 회사의 부채를 안 지운다(ARMK 2026 Q3)")
    void liabilitiesKeepTheBalanceSheetPredicate() throws Exception {
        RawFinancialReport on = at(build("armk", 2025, true), 2026, "Q3", "2026-08-11");

        assertThat(on.totalLiabilities()).as("자산 13,973.9M − 자본 3,394.2M − 상환가능 비지배 69.9M")
                .isEqualByComparingTo("10509813000");
    }

    @Test
    @DisplayName("LP 지배 + 비지배가 총과 안 닫히면 지배자본은 값 없음이다 — 「총 − 비지배」도 막는다(BX 2017 · CG 2018)")
    void unconfirmedPartnersCapitalLeavesControllingEmpty() throws Exception {
        RawFinancialReport bx = at(build("bx", 2017, true), 2017, "FY", "2018-03-01");
        RawFinancialReport cg = at(build("cg", 2018, true), 2018, "FY", "2019-02-13");

        assertThat(bx.totalEquity()).as("LP 총 태그").isEqualByComparingTo("13518611000");
        assertThat(bx.totalEquityControlling()).as("지배 6,636M + 표준 비지배 3,253M ≠ 총 — 유도하면 10,265M").isNull();
        assertThat(cg.totalEquity()).as("PartnersCapital = 자산 − 부채(그 태그에 비지배를 실었다)").isEqualByComparingTo("2836300000");
        assertThat(cg.totalEquityControlling()).isNull();
    }

    @Test
    @DisplayName("뒤 해 10-Q의 비교 재무상태표는 LP 자본도 새 판으로 받는다 — 순이익이 없어도(WES 2018 FY 2019-05-01 판)")
    void comparativeBalanceSheetCarriesItsOwnPartnersCapital() throws Exception {
        RawFinancialReport restated = at(build("wes", 2018, true), 2018, "FY", "2019-05-01");

        assertThat(restated.totalAssets()).isEqualByComparingTo("11457205000");
        assertThat(restated.totalEquity()).as("원 10-K의 3,504.7M이 아니다").isEqualByComparingTo("4892683000");
        assertThat(restated.totalEquityControlling()).isEqualByComparingTo("2339906000");
    }

    @Test
    @DisplayName("자본변동표만 실은 뒤 공시의 LP 자본은 안 받는다 — 앞 판의 자산 · 부채와 섞인다(MPLX 2019 Q1)")
    void equityStatementOnlyFactsAreIgnored() throws Exception {
        List<RawFinancialReport> reports = build("mplx", 2019, true);

        for (RawFinancialReport q1 : reports.stream().filter(r -> r.fiscalYear() == 2019 && "Q1".equals(r.fiscalPeriod())).toList()) {
            assertThat(q1.totalEquity()).as("@%s — 원 10-Q 값", q1.publishedAt()).isEqualByComparingTo("6929000000");
        }
    }
}
