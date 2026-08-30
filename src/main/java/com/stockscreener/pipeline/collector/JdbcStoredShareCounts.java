package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredShareCounts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * {@link StoredShareCounts}를 {@code financial_statement}에서 읽는다.
 *
 * <p>구현이 이 패키지에 있는 이유는 <b>DB를 만지는 클래스가 여기 모여 있기</b> 때문이다
 * ({@link JdbcStoredPriceSeries}와 같은 자리). 포트만 저쪽에 두면 어댑터가
 * {@code JdbcTemplate}을 몰라도 되고, 어댑터 테스트가 DB 없이 성립한다.
 */
@Component
public class JdbcStoredShareCounts implements StoredShareCounts {

    /**
     * <b>MUST: 이 서브쿼리를 {@code IndicatorDataReader.CURRENCY_GATE}와 같게 유지한다.</b>
     * 그쪽은 {@code NOT EXISTS}이고 여기는 판정을 컬럼으로 내므로 부호가 반대일 뿐, 술어는
     * 글자까지 같아야 한다 — {@code is_latest}를 보지 않고 그 종목의 <b>어느 행이든</b>
     * 통화가 시장 통화와 다르면 참이다.
     *
     * <p>갈리면 <b>유니버스는 담는데 지표는 안 나는 종목</b>이 생긴다. 실측으로 최신 기의
     * 행만 보는 술어를 쓰면 미국에서 134종목이 그 상태가 된다(2026-08-31).
     * {@code UsShareCountCurrencyGateTest}가 이 자리를 잠근다.
     *
     * <p><b>MUST NOT: 통화 리터럴(`'USD'`)로 바꾸지 않는다.</b> 판정 축은 「USD인가」가 아니라
     * 「그 종목이 상장된 시장의 통화와 같은가」다(ADR-0094 §규칙).
     */
    private static final String CURRENCY_MISMATCHED = """
            EXISTS (
                SELECT 1
                FROM financial_statement mismatched
                JOIN stock gated ON gated.id = mismatched.stock_id
                JOIN market gate_market ON gate_market.code = gated.market_code
                WHERE mismatched.stock_id = latest.stock_id
                  AND mismatched.currency_code <> gate_market.currency_code)
            """;

    /**
     * {@code DISTINCT ON}으로 기(期)를 고른다. 정렬의 둘째 키가 {@code revision_no}인 것이
     * 계약이다 — 같은 기에 개정본이 여럿이면 나중 판을 쓴다.
     *
     * <p>{@code shares_outstanding > 0}을 거는 이유는 0이 「작은 회사」와 구별되지 않기
     * 때문이다({@code MarketCapUniverseAdapter.toCandidate}가 같은 판단을 한다).
     */
    private static final String SQL = """
            WITH latest AS (
                SELECT DISTINCT ON (f.stock_id)
                       f.stock_id, f.shares_outstanding, f.period_end
                FROM financial_statement f
                JOIN stock s ON s.id = f.stock_id
                WHERE s.market_code = ?
                  AND s.is_active
                  AND f.is_latest
                  AND f.shares_outstanding IS NOT NULL
                  AND f.shares_outstanding > 0
                  AND f.period_end >= ?
                ORDER BY f.stock_id, f.period_end DESC, f.revision_no DESC
            )
            SELECT s.ticker, latest.shares_outstanding, s.sic_code, latest.period_end,
                   %s AS currency_mismatched
            FROM latest
            JOIN stock s ON s.id = latest.stock_id
            ORDER BY s.ticker
            """.formatted(CURRENCY_MISMATCHED);

    private final JdbcTemplate jdbcTemplate;

    public JdbcStoredShareCounts(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShareCount> activeByMarket(String marketCode, LocalDate oldestPeriodEnd) {
        return jdbcTemplate.query(SQL,
                (rs, rowNum) -> new ShareCount(
                        rs.getString("ticker"),
                        rs.getBigDecimal("shares_outstanding"),
                        rs.getString("sic_code"),
                        rs.getObject("period_end", LocalDate.class),
                        rs.getBoolean("currency_mismatched")),
                marketCode, oldestPeriodEnd);
    }
}
