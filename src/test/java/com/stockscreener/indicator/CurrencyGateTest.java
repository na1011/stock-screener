package com.stockscreener.indicator;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재무 통화가 시장 통화와 다른 종목의 재무를 <b>리더가 통째로 막는지</b> 본다.
 *
 * <p>막지 않으면 값이 <b>나온다</b> — 그것이 이 결함의 성질이다. 실측된 두산밥캣의 저장값이
 * {@code PER 17,386} · {@code PBR 1,163} · {@code EPS_CAGR_5Y −75.2%}였고, 화면에서는
 * 「조건 미달」과 구별되지 않았다. 계약의 정본은 {@code IndicatorDataReader}의 클래스 javadoc과
 * {@code CURRENCY_GATE} 상수 javadoc이다.
 *
 * <p><b>단정을 「게이트 SQL이 있는가」로 세우지 않는다</b> — 그것은 문자열을 보는 것이라
 * 정의상 언제나 참이다. 재는 것은 <b>섞인 종목의 재무가 실제로 안 나오는가</b>이고,
 * 게이트를 지우면 아래 넷 중 둘이 붉는다(통과 갈래 둘은 그대로 초록이라 대조가 된다).
 *
 * <p><b>NULL 통화 갈래는 테스트하지 않는다</b> — {@code currency_code}가 NOT NULL이라
 * 그 상황이 원리적으로 없다(실측 15,695행 전부 KRW 아니면 USD).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class CurrencyGateTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 30);

    @Autowired
    private IndicatorDataReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("시장 통화와 같으면 그대로 준다 — 국내 374종목이 이 갈래다")
    void matchingCurrencyPasses() {
        long stockId = insertStock("KOSPI", "005930", "삼성전자");
        insertAnnual(stockId, 2024, "KRW");
        insertAnnual(stockId, 2023, "KRW");

        assertThat(reader.readAnnualFigures(stockId, AS_OF, 5, FinancialVisibility.LATEST))
                .hasSize(2);
    }

    @Test
    @DisplayName("한 행만 통화가 달라도 그 종목의 재무가 통째로 막힌다 — 행 단위가 아니라 종목 단위다")
    void oneMismatchedRowBlocksTheWholeStock() {
        long stockId = insertStock("KOSPI", "241560", "두산밥캣");
        insertAnnual(stockId, 2022, "KRW");
        insertAnnual(stockId, 2023, "KRW");
        insertAnnual(stockId, 2024, "USD");

        // 행 단위로 걸렀다면 KRW 2행이 남는다. 그러면 5년 창이 2년치가 되고
        // 「최신 재무가 2023년」인 척하는데, 그 상태가 정상값 보존이 아니다.
        assertThat(reader.readAnnualFigures(stockId, AS_OF, 5, FinancialVisibility.LATEST))
                .isEmpty();
        assertThat(reader.readQuarterlyFigures(stockId, AS_OF, 5, FinancialVisibility.LATEST))
                .isEmpty();
    }

    @Test
    @DisplayName("시점 재현 경로에도 같은 게이트가 걸린다 — 두 경로가 갈리지 않는다")
    void knownOnIsGatedToo() {
        long stockId = insertStock("KOSPI", "950160", "코오롱티슈진");
        insertAnnual(stockId, 2024, "USD");

        assertThat(reader.readAnnualFigures(stockId, AS_OF, 5, FinancialVisibility.KNOWN_ON))
                .isEmpty();
    }

    @Test
    @DisplayName("미국 종목의 USD 재무는 통과한다 — 판정 축은 「USD인가」가 아니라 「시장 통화와 같은가」다")
    void usdIsNormalInUsMarket() {
        long stockId = insertStock("US", "AAPL", "Apple Inc.");
        insertAnnual(stockId, 2024, "USD");
        insertAnnual(stockId, 2023, "USD");

        // 이 단정이 깨지면 게이트가 통화 리터럴로 굳은 것이고, 그러면 미국을 켜는 순간
        // 전 종목의 재무가 사라진다.
        assertThat(reader.readAnnualFigures(stockId, AS_OF, 5, FinancialVisibility.LATEST))
                .hasSize(2);
    }

    private long insertStock(String marketCode, String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id
                """, Long.class, marketCode, ticker, name);
    }

    private void insertAnnual(long stockId, int year, String currencyCode) {
        LocalDate periodEnd = LocalDate.of(year, 12, 31);
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end,
                     published_at, revision_no, is_latest, currency_code, revenue)
                VALUES (?, ?, 'FY', ?, ?, ?, 0, true, ?, 1000)
                """, stockId, year, periodEnd.withDayOfYear(1), periodEnd,
                periodEnd.plusMonths(3), currencyCode);
    }
}
