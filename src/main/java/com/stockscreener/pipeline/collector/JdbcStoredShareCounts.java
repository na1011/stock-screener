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
     * 보고 종료일 <b>이후</b>에 반영된 분할·병합만큼 주식수를 옮긴다.
     *
     * <p>주식수는 보고 종료일 기준이고 종가는 오늘이라, 그 사이에 분할이 있으면
     * <b>시총이 배수만큼 틀린다.</b> 결측이 아니라 <b>그럴듯한 숫자</b>로 나온다 —
     * 실측(2026-08-31)으로 편입 1,000 중 <b>5종목</b>이 그 상태였고 그중 {@code BYND}는
     * 1:30 병합 전 주식수라 시총이 <b>7.15B로 30배</b> 잡혀 상위 1,000에 <b>잘못 들어왔다</b>
     * (실제 약 0.24B). 나머지 넷({@code IESC}·{@code MLI}·{@code MNST}·{@code SNEX})은 반대
     * 방향이라 편입 자체는 옳았고 순위만 낮게 잡혔다.
     *
     * <p>ADR-0096이 지표 계층에 세운 것과 <b>같은 원리이고 자리만 다르다</b> —
     * {@code shiftToBar}는 이 조인을 타지 않는다(ADR-0099 §결과가 적어 둔 자리다).
     *
     * <p><b>MUST: {@code APPLIED}만 쓴다.</b> 그것이 사람·소스가 확정한 계수라는 뜻이고,
     * {@code OBSERVED}는 <i>"있었는데 아직 못 고쳤다"</i>라 비율이 계수가 아니다(ADR-0049).
     *
     * <p><b>순환이 하나 있다 — 원장이 유니버스보다 먼저다.</b> 원장은 편입 종목을 대상으로
     * 채우므로, 새로 편입될 종목은 <b>그 시점에 원장이 비어</b> 이 보정을 못 받는다.
     * 창이 「최신 보고 종료일 이후」라 최대 한 분기이고, 다음 리밸런싱이 수렴시킨다.
     * <b>MUST NOT: 그것을 이 질의에서 풀려고 하지 않는다</b> — 유니버스를 정하려고 유니버스
     * 밖 전 종목의 원장을 채우는 것이 그 대가다.
     *
     * <p>{@code exp(sum(ln(...)))}인 이유는 PostgreSQL에 곱 집계가 없어서다. 비율은
     * {@code ck_ca_ratio_sign}이 양수로 못 박으므로 {@code ln}의 정의역을 벗어나지 않는다.
     */
    private static final String SHIFT_TO_TODAY = """
            coalesce((SELECT exp(sum(ln(ca.ratio))) FROM corporate_action ca
                       WHERE ca.stock_id = latest.stock_id
                         AND ca.status = 'APPLIED'
                         AND ca.effective_date > latest.period_end), 1)
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
            SELECT s.ticker,
                   latest.shares_outstanding * %s AS shares_outstanding,
                   s.sic_code, latest.period_end,
                   %s AS currency_mismatched
            FROM latest
            JOIN stock s ON s.id = latest.stock_id
            ORDER BY s.ticker
            """.formatted(SHIFT_TO_TODAY, CURRENCY_MISMATCHED);

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
