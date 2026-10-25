package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.collector.CorporateActionLedger;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
import com.stockscreener.indicator.IndicatorDataReader;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Basis;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Fact;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Member;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Source;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 국내 보통주 몫 유도와 판독(ADR-0192 · 관문 마이그레이션 V70_5 · V70_6 — 시험 프로파일만 만든다). 재료 → 판정 → 저장 → 지표 판독기.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class KrCommonIncomeDeriverTest {

    private static final Member ORDINARY = new Member("ClassesOfShareCapitalAxis", "OrdinarySharesMember", Source.IFRS);
    private static final long[] QUARTER_NI = {1_000_000_000L, 2_000_000_000L, 3_000_000_000L};
    private static final long[] QUARTER_EPS = {90, 180, 270};

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KrXbrlStore store;

    @Autowired
    private KrCommonIncomeDeriver deriver;

    @Autowired
    private CorporateActionLedger ledger;

    @Autowired
    private ShareClassStore shareClasses;

    private long stockId;

    @BeforeEach
    void seed() {
        stockId = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', 'XB0002', '유도 시험') RETURNING id",
                Long.class);
        for (int q = 1; q <= 3; q++) {
            LocalDate start = LocalDate.of(2025, 3 * (q - 1) + 1, 1);
            LocalDate end = start.plusMonths(3).minusDays(1);
            LocalDate published = end.plusDays(45);
            row(2025, "Q" + q, start, end, published, QUARTER_NI[q - 1]);
            filing("2025" + q + "0000000" + q, published, q == 2 ? "A002" : "A003", q == 1 ? "분기보고서 (2025.03)"
                    : q == 2 ? "반기보고서 (2025.06)" : "분기보고서 (2025.09)", start, end, QUARTER_NI[q - 1], QUARTER_EPS[q - 1]);
        }
        LocalDate fyPublished = LocalDate.of(2026, 3, 10);
        row(2025, "FY", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), fyPublished, 10_000_000_000L);
        row(2025, "Q4", LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31), fyPublished, 4_000_000_000L);
        filing("20260310000004", fyPublished, "A001", "사업보고서 (2025.12)", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                10_000_000_000L, 900);
        ObjectMapper m = new ObjectMapper();
        List<JsonNode> register = List.of(
                m.readTree("{\"se\":\"보통주\",\"istc_totqy\":\"10,000,000\",\"tesstk_co\":\"-\",\"rcept_no\":\"20250310000001\",\"stlm_dt\":\"2024-12-31\"}"),
                m.readTree("{\"se\":\"합계\",\"istc_totqy\":\"10,000,000\",\"tesstk_co\":\"-\",\"rcept_no\":\"20250310000001\",\"stlm_dt\":\"2024-12-31\"}"),
                m.readTree("{\"se\":\"비고\",\"istc_totqy\":\"-\",\"tesstk_co\":\"(주1) 자기주식 없음\",\"rcept_no\":\"20250310000001\",\"stlm_dt\":\"2024-12-31\"}"));
        store.recordRegister(stockId, 2024, register);
    }

    @Test
    @DisplayName("판마다 E × W · Q4는 FY − ΣQ · 배당성향 분모(신종자본증권만 · 우선주 없음)는 보통주 몫 + 0")
    void derivesEveryRow() {
        KrCommonIncomeDeriver.Derived d = deriver.derive();
        assertThat(d.failedStocks()).isZero();

        Map<String, Map<String, Object>> byPeriod = stored();
        assertThat(byPeriod.get("Q1").get("branch")).isEqualTo("exw");
        assertThat((BigDecimal) byPeriod.get("Q1").get("common")).isEqualByComparingTo("900000000");
        assertThat((BigDecimal) byPeriod.get("FY").get("common")).isEqualByComparingTo("9000000000");
        assertThat((BigDecimal) byPeriod.get("FY").get("payout_base")).isEqualByComparingTo("9000000000");
        // FY 90억 − (9억 + 18억 + 27억) = 36억.
        assertThat(byPeriod.get("Q4").get("branch")).isEqualTo("q4");
        assertThat((BigDecimal) byPeriod.get("Q4").get("common")).isEqualByComparingTo("3600000000");
        assertThat(byPeriod.get("Q1").get("statement_basis")).isEqualTo("C");
    }

    @Test
    @DisplayName("판독기 — 국내 관문 켬은 보통주 몫을 읽고 · 행이 바뀌면(스냅숏 갈림) 붙은 것 없음 · 관문 꺼짐은 이 규칙으로 안 읽는다")
    void readerHonoursTheGateAndTheSnapshot() {
        deriver.derive();
        IndicatorDataReader on = new IndicatorDataReader(jdbc, ledger, shareClasses, false, true);
        IndicatorDataReader off = new IndicatorDataReader(jdbc, ledger, shareClasses, false, false);
        LocalDate asOf = LocalDate.of(2026, 4, 1);

        List<FinancialFigures> read = on.readQuarterlyFigures(stockId, asOf, 1, FinancialVisibility.LATEST);
        FinancialFigures q4 = read.stream().filter(f -> f.fiscalPeriod() == FinancialFigures.FiscalPeriod.Q4).findFirst().orElseThrow();
        assertThat(q4.readsCommonIncome()).isTrue();
        assertThat(q4.commonContinuingIncome()).isEqualByComparingTo("3600000000");

        assertThat(off.readQuarterlyFigures(stockId, asOf, 1, FinancialVisibility.LATEST))
                .allSatisfy(f -> assertThat(f.readsCommonIncome()).isFalse());

        // 같은 행의 지배 NI를 매퍼 교정이 바꿨는데 유도가 아직 안 돌았다 — 옛 몫을 안 읽는다.
        jdbc.update("UPDATE financial_statement SET net_income_controlling = 3100000000 WHERE stock_id = ? AND fiscal_period = 'Q3'",
                stockId);
        FinancialFigures q3 = on.readQuarterlyFigures(stockId, asOf, 1, FinancialVisibility.LATEST).stream()
                .filter(f -> f.fiscalPeriod() == FinancialFigures.FiscalPeriod.Q3).findFirst().orElseThrow();
        assertThat(q3.readsCommonIncome()).isTrue();
        assertThat(q3.commonContinuingIncome()).isNull();
    }

    private Map<String, Map<String, Object>> stored() {
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        jdbc.queryForList("""
                SELECT f.fiscal_period, ci.branch, ci.common, ci.payout_base, ci.statement_basis
                  FROM financial_common_income ci JOIN financial_statement f ON f.id = ci.financial_statement_id
                 WHERE f.stock_id = ?
                """, stockId).forEach(r -> out.put((String) r.get("fiscal_period"), r));
        return out;
    }

    private void row(int year, String period, LocalDate start, LocalDate end, LocalDate published, long controlling) {
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                                                 currency_code, report_code, net_income_controlling, net_income, shares_outstanding)
                VALUES (?, ?, ?, ?, ?, ?, 'KRW', ?, ?, ?, 10000000)
                """, stockId, year, period, start, end, published,
                switch (period) {
                    case "Q1" -> "11013";
                    case "Q2" -> "11012";
                    case "Q3" -> "11014";
                    default -> "11011";
                }, controlling, controlling);
    }

    /** 판 하나와 그 재료 — 지배 귀속 · 보통주 EPS · W 1,000만 · 신종자본증권 잔액(지분상품 있음). */
    private void filing(String rceptNo, LocalDate published, String detail, String name, LocalDate start, LocalDate end,
                        long income, long eps) {
        com.stockscreener.pipeline.datasource.kr.DartReportKey key =
                com.stockscreener.pipeline.datasource.kr.DartReportKey.of(detail, name, 12);
        store.upsertListed(stockId, "00000002", detail, name, rceptNo, published, key.bsnsYear(), key.reprtCode(), key.reason());
        store.recordOk(stockId, rceptNo, List.of(
                fact("ProfitLossAttributableToOwnersOfParent", start, end, List.of(), income),
                fact("BasicEarningsLossPerShare", start, end, List.of(ORDINARY), eps),
                fact("WeightedAverageShares", start, end, List.of(ORDINARY), 10_000_000),
                new Fact(Source.DART, "HybridBonds", null, end, Basis.CONSOLIDATED, List.of(), BigDecimal.valueOf(500_000_000L))), 0);
    }

    private static Fact fact(String concept, LocalDate start, LocalDate end, List<Member> members, long value) {
        return new Fact(Source.IFRS, concept, start, end, Basis.CONSOLIDATED, members, BigDecimal.valueOf(value));
    }
}
