package com.stockscreener.stock;

import com.stockscreener.stock.FinancialBasisReader.Basis;
import com.stockscreener.stock.FinancialBasisReader.Status;
import com.stockscreener.stock.FinancialBasisReader.Untracked;
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
 * 재무 기준 · 새 실적 발표 판정(ADR-0195). 모양은 2026-10-30 실측에서 옮겼다 — GOOGL은 10-28(ET 16:01) 실적 8-K · 10-29 10-Q이고,
 * 국내 잠정실적은 분기보고서보다 한 달쯤 앞선다.
 *
 * <p>기준일을 2030년대로 둔다 — 공유 시험 DB의 공시 목록 커서({@code filing_listing_day})가 그보다 앞이어야 「목록을 아직 안 받음」
 * 갈래를 가를 수 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class FinancialBasisReaderTest {

    private static final LocalDate Q3_END = LocalDate.of(2031, 9, 30);

    @Autowired
    private FinancialBasisReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("다음 기말 뒤의 실적 8-K가 있으면 발표 대기다 — 지표는 직전 분기 기준(GOOGL 10-28 모양)")
    void pendingWhenAResultsReleaseFollowsTheNextPeriodEnd() {
        long id = usStockWithFourQuarters("PEND");
        release(id, "US", "0000000001-31-000090", "8-K", null, "2.02,9.01", LocalDate.of(2031, 10, 28));
        sweep(id, LocalDate.of(2031, 10, 29));

        Basis basis = reader.read(List.of(id), LocalDate.of(2031, 10, 29)).get(id);

        assertThat(basis.flowLabel()).isEqualTo("2031 Q2");
        assertThat(basis.balanceLabel()).isNull();
        assertThat(basis.status()).isEqualTo(Status.PENDING);
        assertThat(basis.releaseFiledOn()).isEqualTo(LocalDate.of(2031, 10, 28));
        assertThat(basis.notice()).contains("10월 28일").contains("2031 Q2").contains("계산하지 않았습니다");
    }

    @Test
    @DisplayName("정식 공시가 실리면 대기가 닫힌다 — 같은 분기 발표는 그 분기 기준의 다음 기말 앞이다")
    void closesOnceTheFilingIsReflected() {
        long id = usStockWithFourQuarters("DONE");
        release(id, "US", "0000000001-31-000090", "8-K", null, "2.02,9.01", LocalDate.of(2031, 10, 28));
        quarter(id, 2031, "Q3", Q3_END, LocalDate.of(2031, 10, 29), null);
        sweep(id, LocalDate.of(2031, 10, 29));

        Basis basis = reader.read(List.of(id), LocalDate.of(2031, 10, 29)).get(id);

        assertThat(basis.flowLabel()).isEqualTo("2031 Q3");
        assertThat(basis.status()).isEqualTo(Status.NONE);
        assertThat(basis.notice()).isNull();
    }

    @Test
    @DisplayName("정식 공시 뒤에 나온 같은 분기 보도자료 · 실적이 아닌 8-K는 대기가 아니다")
    void lateReleaseAndOtherItemsAreNotPending() {
        long id = usStockWithFourQuarters("LATE");
        // Q2(06-30) 10-Q가 08-09 — 그 뒤 08-10 보도자료는 Q2 것이다(다음 기말 09-30 앞).
        release(id, "US", "0000000001-31-000070", "8-K", null, "2.02,9.01", LocalDate.of(2031, 8, 10));
        release(id, "US", "0000000001-31-000091", "8-K", null, "5.02,9.01", LocalDate.of(2031, 10, 15));
        sweep(id, LocalDate.of(2031, 10, 29));

        assertThat(reader.read(List.of(id), LocalDate.of(2031, 10, 29)).get(id).status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("항목을 아직 모르는 8-K가 그 구간에 있으면 모른다 — 「없음」이라 하지 않는다")
    void unresolvedIsUntracked() {
        long id = usStockWithFourQuarters("UNRS");
        release(id, "US", "0000000001-31-000092", "8-K", null, null, LocalDate.of(2031, 10, 28));
        sweep(id, LocalDate.of(2031, 10, 29));

        Basis basis = reader.read(List.of(id), LocalDate.of(2031, 10, 29)).get(id);

        assertThat(basis.status()).isEqualTo(Status.UNTRACKED);
        assertThat(basis.untracked()).isEqualTo(Untracked.UNRESOLVED);
    }

    @Test
    @DisplayName("과거를 훑지 않은 종목 · 기준일까지 목록이 안 닫힌 종목은 모른다")
    void notSweptAndNotYetListedAreUntracked() {
        long unswept = usStockWithFourQuarters("NOSW");
        long behind = usStockWithFourQuarters("BHND");
        sweep(behind, LocalDate.of(2031, 10, 20));

        var bases = reader.read(List.of(unswept, behind), LocalDate.of(2031, 10, 29));

        assertThat(bases.get(unswept).untracked()).isEqualTo(Untracked.NOT_SWEPT);
        assertThat(bases.get(behind).untracked()).isEqualTo(Untracked.NOT_YET_LISTED);
    }

    @Test
    @DisplayName("외국 발행사(20-F · 6-K)와 국내 비12월 결산은 감지 대상이 아니다")
    void foreignIssuersAndNonDecemberYearsAreUntracked() {
        long foreign = insertStock("US", "FPIX", null);
        annual(foreign, 2030, LocalDate.of(2030, 12, 31), LocalDate.of(2031, 4, 20), "20-F:0000000002-31-000001");
        sweep(foreign, LocalDate.of(2031, 10, 29));
        long march = insertStock("KOSPI", "900001", 3);
        quarter(march, 2031, "Q1", LocalDate.of(2031, 3, 31), LocalDate.of(2031, 5, 15), "11013");
        sweep(march, LocalDate.of(2031, 10, 29));

        var bases = reader.read(List.of(foreign, march), LocalDate.of(2031, 10, 29));

        assertThat(bases.get(foreign).untracked()).isEqualTo(Untracked.FOREIGN_ISSUER);
        assertThat(bases.get(foreign).flowLabel()).isEqualTo("2030년");
        assertThat(bases.get(march).untracked()).isEqualTo(Untracked.NON_DECEMBER_FISCAL_YEAR);
    }

    @Test
    @DisplayName("IMPORTANT — 유량 기준은 지표와 같다: 분기가 하나 빠지면 FY로 돌고 잔액은 최신 분기 — 국내 잠정실적이 그 FY 뒤면 대기다")
    void flowBasisFollowsTheIndicatorSeries() {
        long id = insertStock("KOSPI", "900002", 12);
        quarter(id, 2030, "Q1", LocalDate.of(2030, 3, 31), LocalDate.of(2030, 5, 15), "11013");
        quarter(id, 2030, "Q2", LocalDate.of(2030, 6, 30), LocalDate.of(2030, 8, 14), "11012");
        annual(id, 2030, LocalDate.of(2030, 12, 31), LocalDate.of(2031, 3, 13), "11011");
        // 2030 Q3 · Q4가 없다 — 연속 네 분기가 안 서 지표는 FY2030으로 돈다.
        quarter(id, 2031, "Q1", LocalDate.of(2031, 3, 31), LocalDate.of(2031, 5, 15), "11013");
        release(id, "KR", "20310707800001", "I002", "연결재무제표기준영업(잠정)실적(공정공시)", null, LocalDate.of(2031, 7, 7));
        sweep(id, LocalDate.of(2031, 7, 20));

        Basis basis = reader.read(List.of(id), LocalDate.of(2031, 7, 20)).get(id);

        assertThat(basis.flowLabel()).isEqualTo("2030년");
        assertThat(basis.balanceLabel()).isEqualTo("2031 Q1");
        assertThat(basis.status()).as("FY2030의 다음 기말(2031-03-31) 뒤 발표").isEqualTo(Status.PENDING);
    }

    @Test
    @DisplayName("지표를 쓴 실행이 시작한 뒤 생긴 재무 행은 못 본 것이다 — 화해 전 라벨이 새 분기를 말하지 않는다")
    void rowsCreatedAfterTheIndicatorWriteAreNotTheBasis() {
        long id = usStockWithFourQuarters("LAGX");
        LocalDate asOf = LocalDate.of(2031, 10, 29);
        jdbc.update("""
                INSERT INTO batch_execution (job_name, target_date, status, triggered_by, scope, started_at, finished_at)
                VALUES ('daily-update', ?, 'SUCCESS', 'SCHEDULER', 'FULL', now() - interval '1 hour', now() - interval '50 minutes')
                """, asOf);
        quarter(id, 2031, "Q3", Q3_END, asOf, null);
        jdbc.update("UPDATE financial_statement SET created_at = now() WHERE stock_id = ? AND fiscal_period = 'Q3' AND fiscal_year = 2031", id);
        jdbc.update("UPDATE financial_statement SET created_at = now() - interval '2 hours' WHERE stock_id = ? AND NOT (fiscal_period = 'Q3' AND fiscal_year = 2031)", id);
        release(id, "US", "0000000001-31-000090", "8-K", null, "2.02,9.01", LocalDate.of(2031, 10, 28));
        sweep(id, asOf);

        Basis basis = reader.read(List.of(id), asOf).get(id);

        assertThat(basis.flowLabel()).isEqualTo("2031 Q2");
        assertThat(basis.status()).isEqualTo(Status.PENDING);
    }

    @Test
    @DisplayName("지표를 쓴 실행 뒤에 알게 된 발표는 아직 지표를 막지 않았다 — 대기라 말하지 않는다")
    void releasesLearnedAfterTheIndicatorWriteAreNotPendingYet() {
        long id = usStockWithFourQuarters("LATR");
        LocalDate asOf = LocalDate.of(2031, 10, 29);
        jdbc.update("""
                INSERT INTO batch_execution (job_name, target_date, status, triggered_by, scope, started_at, finished_at)
                VALUES ('daily-update', ?, 'SUCCESS', 'SCHEDULER', 'FULL', now() - interval '1 hour', now() - interval '50 minutes')
                """, asOf);
        jdbc.update("UPDATE financial_statement SET created_at = now() - interval '2 hours' WHERE stock_id = ?", id);
        release(id, "US", "0000000001-31-000090", "8-K", null, "2.02,9.01", LocalDate.of(2031, 10, 28));
        jdbc.update("UPDATE earnings_release SET first_seen_at = now(), resolved_at = now() WHERE stock_id = ?", id);
        sweep(id, asOf);

        assertThat(reader.read(List.of(id), asOf).get(id).status()).isEqualTo(Status.NONE);
    }

    @Test
    @DisplayName("재무가 하나도 안 보이는 종목은 판정이 없다")
    void noFinancialsNoBasis() {
        long id = insertStock("US", "NOFN", null);

        assertThat(reader.read(List.of(id), LocalDate.of(2031, 10, 29))).doesNotContainKey(id);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** 2030 Q3 ~ 2031 Q2 연속 네 분기 · 공시일은 기말 + 40일. */
    private long usStockWithFourQuarters(String ticker) {
        long id = insertStock("US", ticker, null);
        quarter(id, 2030, "Q3", LocalDate.of(2030, 9, 30), LocalDate.of(2030, 11, 9), null);
        quarter(id, 2030, "Q4", LocalDate.of(2030, 12, 31), LocalDate.of(2031, 2, 9), null);
        quarter(id, 2031, "Q1", LocalDate.of(2031, 3, 31), LocalDate.of(2031, 5, 10), null);
        quarter(id, 2031, "Q2", LocalDate.of(2031, 6, 30), LocalDate.of(2031, 8, 9), null);
        return id;
    }

    private long insertStock(String market, String ticker, Integer accountingMonth) {
        return jdbc.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, accounting_month) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, market, ticker, ticker + " 시험", accountingMonth);
    }

    private void quarter(long stockId, int year, String period, LocalDate periodEnd, LocalDate publishedAt, String reportCode) {
        row(stockId, year, period, periodEnd.minusMonths(3).plusDays(1), periodEnd, publishedAt, reportCode);
    }

    private void annual(long stockId, int year, LocalDate periodEnd, LocalDate publishedAt, String reportCode) {
        row(stockId, year, "FY", periodEnd.minusYears(1).plusDays(1), periodEnd, publishedAt, reportCode);
    }

    private void row(long stockId, int year, String period, LocalDate start, LocalDate end, LocalDate publishedAt,
                     String reportCode) {
        String currency = jdbc.queryForObject("""
                SELECT m.currency_code FROM stock s JOIN market m ON m.code = s.market_code WHERE s.id = ?
                """, String.class, stockId);
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                    revision_no, is_latest, currency_code, net_income, report_code)
                VALUES (?, ?, ?, ?, ?, ?, 0, true, ?, ?, ?)
                """, stockId, year, period, start, end, publishedAt, currency, new BigDecimal("100"), reportCode);
    }

    private void release(long stockId, String market, String filingNo, String form, String reportName, String items,
                         LocalDate filedOn) {
        jdbc.update("""
                INSERT INTO earnings_release (market, filing_no, stock_id, form, report_name, items, filed_on, effective_on,
                    resolved_at, first_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN CAST(? AS varchar) IS NULL THEN NULL ELSE now() - interval '3 hours' END,
                    now() - interval '3 hours')
                """, market, filingNo, stockId, form, reportName, items, filedOn, filedOn, items);
    }

    private void sweep(long stockId, LocalDate through) {
        jdbc.update("INSERT INTO earnings_release_sweep (stock_id, swept_from, swept_through) VALUES (?, DATE '2025-07-01', ?)",
                stockId, through);
    }
}
