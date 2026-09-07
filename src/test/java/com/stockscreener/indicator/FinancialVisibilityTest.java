package com.stockscreener.indicator;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시점 재현이 <b>실제로</b> 미래를 막는지 본다(ADR-0071).
 *
 * <p>이 테스트가 없으면 규칙이 주석으로만 남는다 — 그리고 어겨도 <b>결과가 나온다.</b>
 * 실측으로 공시 지연 중앙값이 분기 45일·연간 80일이고 p90이 152일이라,
 * {@code period_end}로만 자르면 몇 달치 미래를 보고도 값이 그럴듯하게 나온다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class FinancialVisibilityTest {

    /** FY2024는 기말이 지났지만 공시는 2025-03-20이다. 그 사이가 이 테스트의 전장이다. */
    private static final LocalDate PERIOD_END = LocalDate.of(2024, 12, 31);
    private static final LocalDate PUBLISHED = LocalDate.of(2025, 3, 20);
    private static final LocalDate BEFORE_PUBLICATION = LocalDate.of(2025, 1, 15);

    @Autowired
    private IndicatorDataReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("KNOWN_ON은 아직 공시되지 않은 재무를 주지 않는다")
    void knownOnBlocksUnpublishedFinancials() {
        insertAnnual(2024, PERIOD_END, PUBLISHED, 0, true, new BigDecimal("1000"));

        assertThat(reader.readAnnualFigures(stockId, BEFORE_PUBLICATION, 5, FinancialVisibility.KNOWN_ON))
                .isEmpty();
    }

    @Test
    @DisplayName("LATEST는 같은 자리에서 미래 공시를 준다 — 그것이 이 모드를 저장 경로에서 뺀 이유다")
    void latestStillSeesTheFuture() {
        insertAnnual(2024, PERIOD_END, PUBLISHED, 0, true, new BigDecimal("1000"));

        // **저장 경로는 2026-09-07에 KNOWN_ON으로 옮겼다**(ADR-0128). 이 단정이 남아 있는 것은
        // 두 모드의 차이를 못박기 위해서다 — 지우면 KNOWN_ON이 무엇을 막는지 말하는 자리가
        // 사라진다. 운영 코드에서 이 모드를 고르는 곳은 이제 없다.
        assertThat(reader.readAnnualFigures(stockId, BEFORE_PUBLICATION, 5, FinancialVisibility.LATEST))
                .hasSize(1);
    }

    @Test
    @DisplayName("KNOWN_ON은 그 시점에 유효했던 판을 고른다 — 정정 후 값이 아니다")
    void knownOnPicksTheRevisionInForceThen() {
        insertAnnual(2024, PERIOD_END, PUBLISHED, 0, false, new BigDecimal("1000"));
        insertAnnual(2024, PERIOD_END, LocalDate.of(2026, 5, 11), 1, true, new BigDecimal("777"));

        // 리더는 판 이력을 통째로 주고, 어느 판을 볼지는 **봉이 정한다**(ADR-0128).
        // 그래서 판정을 컨텍스트에 물어야 한다 — 리더에 물으면 둘 다 온다.
        LocalDate betweenRevisions = LocalDate.of(2025, 6, 30);

        // is_latest는 **오늘의** 최신이라 정정 후 777을 준다. 그 시점의 시장은 1000을 봤다.
        assertThat(visibleAnnual(betweenRevisions))
                .singleElement()
                .extracting(FinancialFigures::revenue)
                .isEqualTo(new BigDecimal("1000.00"));

        // 정정이 공시된 뒤로 오면 정정본을 준다.
        assertThat(visibleAnnual(LocalDate.of(2026, 8, 13)))
                .singleElement()
                .extracting(FinancialFigures::revenue)
                .isEqualTo(new BigDecimal("777.00"));
    }

    @Test
    @DisplayName("리더는 판 이력을 통째로 준다 — 접는 자리가 봉으로 내려갔다는 것의 실동작")
    void knownOnReturnsEveryRevision() {
        insertAnnual(2024, PERIOD_END, PUBLISHED, 0, false, new BigDecimal("1000"));
        insertAnnual(2024, PERIOD_END, LocalDate.of(2026, 5, 11), 1, true, new BigDecimal("777"));

        // 접어서 주면 봉마다 다른 판을 고를 수 없다. 이 단정이 그 성질을 못박는다.
        assertThat(reader.readAnnualFigures(stockId, LocalDate.of(2026, 8, 13), 5,
                FinancialVisibility.KNOWN_ON))
                .hasSize(2);
    }

    @Test
    @DisplayName("KNOWN_ON도 기말이 지나지 않은 기(期)는 주지 않는다")
    void knownOnStillBlocksUnfinishedPeriods() {
        // 실측으로 published_at < period_end인 행이 5개 있다. 공시됐다고 해서 완결된 실적이
        // 아니므로 period_end 조건을 함께 유지한다.
        insertAnnual(2026, LocalDate.of(2026, 12, 31), LocalDate.of(2026, 6, 11), 0, true,
                new BigDecimal("2000"));

        assertThat(reader.readAnnualFigures(stockId, LocalDate.of(2026, 8, 13), 5, FinancialVisibility.KNOWN_ON))
                .isEmpty();
    }

    @Test
    @DisplayName("계산기가 보는 것은 기(期)마다 한 판이다 — TTM이 중복 합산되지 않는다")
    void oneRevisionPerPeriod() {
        insertQuarter(2025, "Q1", LocalDate.of(2025, 3, 31), LocalDate.of(2025, 5, 15), 0, false);
        insertQuarter(2025, "Q1", LocalDate.of(2025, 3, 31), LocalDate.of(2025, 8, 20), 1, true);

        LocalDate asOf = LocalDate.of(2026, 8, 13);
        List<FinancialFigures> revisions =
                reader.readQuarterlyFigures(stockId, asOf, 5, FinancialVisibility.KNOWN_ON);

        // 리더는 둘을 준다(위 knownOnReturnsEveryRevision). 접는 것은 컨텍스트이고,
        // 그 접기가 없으면 TTM이 같은 분기를 두 번 더한다.
        assertThat(revisions).hasSize(2);
        assertThat(new IndicatorContext(stockId, List.of(), List.of(), revisions, Map.of())
                .quarterlyFigures())
                .hasSize(1);
    }

    /** 그 봉 시점에 계산기가 실제로 보는 연간 재무. */
    private List<FinancialFigures> visibleAnnual(LocalDate tradeDate) {
        List<FinancialFigures> revisions =
                reader.readAnnualFigures(stockId, tradeDate, 5, FinancialVisibility.KNOWN_ON);
        return new IndicatorContext(stockId, List.of(), revisions, List.of(), Map.of())
                .visibleAt(tradeDate).annualFigures();
    }

    private void insertAnnual(int year, LocalDate periodEnd, LocalDate publishedAt,
                              int revisionNo, boolean isLatest, BigDecimal revenue) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end,
                     published_at, revision_no, is_latest, currency_code, revenue)
                VALUES (?, ?, 'FY', ?, ?, ?, ?, ?, 'KRW', ?)
                """, stockId, year, periodEnd.withDayOfYear(1), periodEnd, publishedAt,
                revisionNo, isLatest, revenue);
    }

    private void insertQuarter(int year, String period, LocalDate periodEnd, LocalDate publishedAt,
                               int revisionNo, boolean isLatest) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end,
                     published_at, revision_no, is_latest, currency_code, revenue)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'KRW', 100)
                """, stockId, year, period, periodEnd.minusMonths(3), periodEnd, publishedAt,
                revisionNo, isLatest);
    }
}
