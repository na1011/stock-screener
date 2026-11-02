package com.stockscreener.indicator;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
import com.stockscreener.pipeline.collector.CorporateActionLedger;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판독기가 중단영업 두 컬럼을 <b>그 행 시장의 관문</b>으로만 읽는지 본다(F2b · ADR-0184).
 *
 * <p>한쪽 관문을 연 상태에서 다른 시장 행이 이 축을 타면 그 시장의 PER · EPS가 관문 없이 바뀐다 — 「us 켬 · kr 끔에서 국내 행
 * 무변화」(탐색 {@code GATE-SETTINGS-PLAN.md} v3)가 이 시험이다. 두 관문을 다 끄면 질의 자체가 두 컬럼을 안 싣는다(운영 DB에는
 * 아직 컬럼이 없다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class DiscontinuedGateTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 30);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CorporateActionLedger corporateActions;
    @Autowired
    private ShareClassStore shareClasses;

    @Test
    @DisplayName("미국 행과 국내 행이 모두 중단영업을 싣는다")
    void eachMarketReadsOnlyBehindItsOwnGate() {
        long us = insertStock("US", "ZZD1", "미국 중단영업");
        long kr = insertStock("KOSPI", "999981", "국내 중단영업");
        insertAnnual(us, "USD");
        insertAnnual(kr, "KRW");

        IndicatorDataReader reader = reader();

        assertThat(discontinued(reader, us)).isEqualByComparingTo("120");
        assertThat(discontinued(reader, kr)).isEqualByComparingTo("120");
        assertThat(reader.readAnnualFigures(us, AS_OF, 5, FinancialVisibility.KNOWN_ON).get(0)
                .discontinuedIncomeControlling()).as("시점 경로도 같은 컬럼을 싣는다").isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("국내 — 공시일은 공시 목록의 접수일이다: 금요일 밤에 낸 사업보고서는 월요일 봉부터 보인다")
    void domesticPublicationIsTheReceiptDate() {
        long kr = insertStock("KOSPI", "999982", "국내 접수일");
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at, revision_no, is_latest,
                     currency_code, report_code, net_income)
                VALUES (?, 2025, 'FY', '2025-01-01', '2025-12-31', '2026-03-13', 0, true, 'KRW', '11011', 1000)
                """, kr);
        jdbcTemplate.update("""
                INSERT INTO kr_report_xbrl (rcept_no, stock_id, corp_code, detail_type, report_name, rcept_date, bsns_year, reprt_code)
                VALUES ('20260313900123', ?, '00000001', 'A001', '사업보고서 (2025.12)', '2026-03-16', 2025, '11011')
                """, kr);

        IndicatorDataReader reader = reader();
        assertThat(reader.readAnnualFigures(kr, LocalDate.of(2026, 3, 14), 5, FinancialVisibility.KNOWN_ON))
                .as("토요일 봉은 아직 공개 전").isEmpty();
        FinancialFigures monday = reader.readAnnualFigures(kr, LocalDate.of(2026, 3, 16), 5, FinancialVisibility.KNOWN_ON).get(0);
        assertThat(monday.visibleOn()).as("보이는 날 = 접수일(게시 시각 모름 → 공시일 다음 날보다 접수일이 늦다)").isEqualTo(LocalDate.of(2026, 3, 16));
        assertThat(monday.publishedAt()).as("앵커는 저장 공시일(설계 v3)").isEqualTo(LocalDate.of(2026, 3, 13));
    }

    @Test
    @DisplayName("국내 — 거버넌스 사실도 공시 목록의 접수일부터 보인다(배당성향 분자 · 분모의 공개일을 맞춘다)")
    void domesticGovernanceIsTheReceiptDate() {
        long kr = insertStock("KOSPI", "999983", "국내 거버넌스 접수일");
        jdbcTemplate.update("""
                INSERT INTO governance_fact (stock_id, fiscal_year, metric_key, value, published_at, receipt_no)
                VALUES (?, 2025, 'DPS_COMMON', 500, '2026-03-13', '20260313900124')
                """, kr);
        jdbcTemplate.update("""
                INSERT INTO kr_report_xbrl (rcept_no, stock_id, corp_code, detail_type, report_name, rcept_date, bsns_year, reprt_code)
                VALUES ('20260313900124', ?, '00000002', 'A001', '사업보고서 (2025.12)', '2026-03-16', 2025, '11011')
                """, kr);
        // 보이는 날은 값이 싣고(GovernanceMath가 봉마다 거른다) 저장 공시일은 앵커로 남는다(설계 v3).
        GovernanceValue gated = reader().readGovernanceFacts(kr, LocalDate.of(2026, 3, 14), 3, FinancialVisibility.KNOWN_ON)
                .get(0).get(GovernanceMetric.DPS_COMMON);
        assertThat(gated.visibleOn()).as("공개 시각을 모르면 접수일(월)보다 이르지 않다").isEqualTo(LocalDate.of(2026, 3, 16));
        assertThat(gated.publishedAt()).as("앵커는 저장 공시일").isEqualTo(LocalDate.of(2026, 3, 13));

        // 게시 시각을 알면 그 시각 뒤 처음 마감하는 세션 — 금 15:40 KST 게시는 그날 종가 뒤라 다음 날(접수일 월보다 이르면 접수일).
        jdbcTemplate.update("""
                INSERT INTO kr_filing_time (rcept_no, corp_code, corp_name, report_name, listed_on, opened_at)
                VALUES ('20260313900124', '00000002', '국내 거버넌스 접수일', '사업보고서 (2025.12)', '2026-03-13', '2026-03-13 15:40:59+09')
                """);
        assertThat(reader().readGovernanceFacts(kr, LocalDate.of(2026, 3, 16), 3, FinancialVisibility.KNOWN_ON)
                .get(0).get(GovernanceMetric.DPS_COMMON).visibleOn()).isEqualTo(LocalDate.of(2026, 3, 16));
    }

    @Test
    @DisplayName("미국 — 재무 판은 접수 시각 뒤 처음 마감하는 세션부터 보인다 · 앵커는 저장 공시일 · 시각을 모르면 다음 날")
    void usFinancialsAreVisibleAfterTheFirstCloseFollowingAcceptance() {
        long us = insertStock("US", "ZZA1", "미국 접수 시각");
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at, revision_no, is_latest,
                     currency_code, net_income, accepted_at)
                VALUES (?, 2024, 'FY', '2023-07-01', '2024-06-30', '2024-07-30', 0, true, 'USD', 1000, '2024-07-30 20:06:00+00'),
                       (?, 2023, 'FY', '2022-07-01', '2023-06-30', '2023-07-27', 0, true, 'USD', 900, NULL)
                """, us, us);
        List<FinancialFigures> on = reader().readAnnualFigures(us, LocalDate.of(2024, 7, 30), 5, FinancialVisibility.KNOWN_ON);
        assertThat(on.get(0).visibleOn()).as("16:06 ET 접수 → 다음 날(달력에 없으면 13:00 마감으로)").isEqualTo(LocalDate.of(2024, 7, 31));
        assertThat(on.get(0).publishedAt()).as("앵커").isEqualTo(LocalDate.of(2024, 7, 30));
        assertThat(on.get(1).visibleOn()).as("시각 모름 → 공시일 다음 날").isEqualTo(LocalDate.of(2023, 7, 28));
    }

    @Test
    @DisplayName("국내 — 기저 대체일은 뒤 보고서가 보이는 날로 옮긴다(16:10 게시 → 다음 날 · ADR-0204)")
    void domesticSupersessionMovesToTheReportsVisibleDay() {
        long kr = insertStock("KOSPI", "999981", "국내 기저 대체");
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at, revision_no, is_latest,
                     currency_code, report_code, net_income, flow_superseded)
                VALUES (?, 2024, 'FY', '2024-01-01', '2024-12-31', '2025-03-14', 0, true, 'KRW', '11011', 1000,
                        '{"revenue": "2026-03-13"}'::jsonb)
                """, kr);
        jdbcTemplate.update("""
                INSERT INTO kr_report_xbrl (rcept_no, stock_id, corp_code, detail_type, report_name, rcept_date, bsns_year, reprt_code)
                VALUES ('20260313000777', ?, '00000003', 'A001', '사업보고서 (2025.12)', '2026-03-13', 2025, '11011')
                """, kr);
        jdbcTemplate.update("""
                INSERT INTO kr_filing_time (rcept_no, corp_code, corp_name, report_name, listed_on, opened_at)
                VALUES ('20260313000777', '00000003', '국내 기저 대체', '사업보고서 (2025.12)', '2026-03-13', '2026-03-13 16:10:59+09')
                """);
        FinancialFigures row = reader().readAnnualFigures(kr, LocalDate.of(2026, 3, 20), 5, FinancialVisibility.KNOWN_ON).get(0);
        assertThat(row.flowsSupersededOn().get("revenue").since()).isEqualTo(LocalDate.of(2026, 3, 14));
    }

    @Test
    @DisplayName("국내 — 실적 발표는 DART 게시 시각으로 막는 날을 정한다(16:05 → 다음 날 · 모르면 접수일)")
    void domesticReleaseBlocksFromItsPostingTime() {
        long kr = insertStock("KOSPI", "999980", "국내 잠정실적");
        jdbcTemplate.update("UPDATE stock SET accounting_month = 12 WHERE id = ?", kr);
        jdbcTemplate.update("""
                INSERT INTO earnings_release (market, filing_no, stock_id, form, report_name, filed_on, effective_on)
                VALUES ('KR', '20260130800501', ?, 'I', '연결재무제표기준영업(잠정)실적(공정공시)', '2026-01-30', '2026-01-30'),
                       ('KR', '20260430800502', ?, 'I', '연결재무제표기준영업(잠정)실적(공정공시)', '2026-04-30', '2026-04-30')
                """, kr, kr);
        jdbcTemplate.update("""
                INSERT INTO kr_filing_time (rcept_no, corp_code, corp_name, report_name, listed_on, opened_at)
                VALUES ('20260130800501', '00000004', '국내 잠정실적', '연결재무제표기준영업(잠정)실적(공정공시)', '2026-01-30',
                        '2026-01-30 16:05:59+09')
                """);
        assertThat(reader().readEarningsReleaseDates(kr, LocalDate.of(2026, 5, 1)))
                .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("국내 — 잇는 접수번호가 없는 판은 그 회사가 그 공시일 목록에 올린 정기 보고서의 게시 시각으로 본다")
    void domesticUnlinkedVintageFallsBackToTheCompanysListing() {
        long kr = insertStock("KOSPI", "999979", "국내 접수번호 없음");
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at, revision_no, is_latest,
                     currency_code, report_code, net_income)
                VALUES (?, 2025, 'Q1', '2025-01-01', '2025-03-31', '2025-05-14', 0, true, 'KRW', '11013', 100)
                """, kr);
        // 같은 종목의 다른 해 장부 줄 — 회사 고유번호만 준다.
        jdbcTemplate.update("""
                INSERT INTO kr_report_xbrl (rcept_no, stock_id, corp_code, detail_type, report_name, rcept_date, bsns_year, reprt_code)
                VALUES ('20240515000111', ?, '00000005', 'A003', '분기보고서 (2024.03)', '2024-05-15', 2024, '11013')
                """, kr);
        jdbcTemplate.update("""
                INSERT INTO kr_filing_time (rcept_no, corp_code, corp_name, report_name, listed_on, opened_at)
                VALUES ('20250514000999', '00000005', '국내 접수번호 없음', '분기보고서 (2025.03)', '2025-05-14', '2025-05-14 17:02:59+09')
                """);
        assertThat(reader().readQuarterlyFigures(kr, LocalDate.of(2025, 6, 1), 4, FinancialVisibility.KNOWN_ON).get(0).visibleOn())
                .isEqualTo(LocalDate.of(2025, 5, 15));
    }

    private IndicatorDataReader reader() {
        return new IndicatorDataReader(jdbcTemplate, corporateActions, shareClasses);
    }

    private static BigDecimal discontinued(IndicatorDataReader reader, long stockId) {
        return reader.readAnnualFigures(stockId, AS_OF, 5, FinancialVisibility.LATEST).get(0).discontinuedIncome();
    }

    private long insertStock(String marketCode, String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id
                """, Long.class, marketCode, ticker, name);
    }

    private void insertAnnual(long stockId, String currencyCode) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end,
                     published_at, revision_no, is_latest, currency_code, net_income, net_income_controlling,
                     discontinued_income, discontinued_income_controlling, discontinued_rule)
                VALUES (?, 2024, 'FY', '2024-01-01', '2024-12-31', '2025-03-31', 0, true, ?, 1000, 900, 120, 100, 1)
                """, stockId, currencyCode);
    }
}
