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
 *
 * <h2>시점성 — 세 축이 함께 움직여야 한다 (ADR-0135)</h2>
 * <p>{@code asOf}를 받기 전에는 이 질의가 <b>세 자리에서 미래를 봤다.</b> ①{@code is_latest}가
 * 읽는 시점의 최신 개정본이고 ②기업행위 배수에 <b>상한이 없어</b> {@code asOf} 이후 사건까지
 * 곱했으며 ③그 배수의 <b>하한 앵커가 기말</b>이었다. 셋을 따로 고치면 안 된다 —
 * ①만 고치면 그 시점 판을 골라 놓고 미래 분할을 곱하고, ②만 고치면 미래 판에 옛 배수를 건다.
 *
 * <p><b>ADR-0132·0133이 이 클래스를 명시적으로 범위 밖에 뒀던 자리다</b>
 * (<i>"유니버스 순위가 움직이는 것으로 읽지 않는다 — {@code sharesOf}를 안 탄다"</i>).
 * 그 서술은 이 결정으로 <b>거짓이 됐고 두 문서를 함께 고쳤다.</b>
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
     *
     * <p><b>MUST NOT: 이 술어에 {@code asOf}를 걸지 않는다.</b> 통화 불일치는 「그 종목의 재무를
     * 우리가 쓸 수 있는가」에 대한 판정이지 시점 값이 아니다. 시점으로 좁히면 과거 as-of에서
     * 불일치 행이 아직 안 들어온 종목이 <b>통과했다가</b> 오늘 막히는데, 그것은 시점 재현이
     * 아니라 <b>수집 시점의 우연</b>이다.
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
     * 고른 판의 <b>공시일 이후 {@code asOf}까지</b> 반영된 분할·병합만큼 주식수를 옮긴다.
     *
     * <p>주식수는 그 판의 기준일이고 종가는 {@code asOf}라, 그 사이에 분할이 있으면
     * <b>시총이 배수만큼 틀린다.</b> 결측이 아니라 <b>그럴듯한 숫자</b>로 나온다 —
     * 실측(2026-08-31)으로 편입 1,000 중 <b>5종목</b>이 그 상태였고 그중 {@code BYND}는
     * 1:30 병합 전 주식수라 시총이 <b>7.15B로 30배</b> 잡혀 상위 1,000에 <b>잘못 들어왔다</b>
     * (실제 약 0.24B).
     *
     * <p><b>MUST: 하한 앵커가 {@code published_at}이다 — 기말이 아니다</b>(ADR-0133 승계).
     * {@code dei:EntityCommonStockSharesOutstanding}은 <b>보고서 표지일</b> 기준이라 기말 이후
     * 공시 전에 일어난 기업행위가 그 수에 <b>이미 반영돼 있다.</b> 앵커가 기말이면 그 구간을
     * <b>한 번 더</b> 곱한다. 실측(2026-09-09 · 선택 규칙에 맞춰 셈)으로 편입 중 <b>두 종목</b>이
     * 그 상태였다 — {@code MLI} 2.0배 · {@code SNEX} 1.5배.
     *
     * <p><b>MUST NOT: 이 발화를 「원장에 {@code (기말, 공시일]} 사건이 있는 판」으로 세지 않는다.</b>
     * 그렇게 세면 수백 판이 나오는데 <b>대부분 안 틀렸다</b> — 같은 공시가 싣는 전년 비교열은
     * {@code DISTINCT ON}이 안 고르므로 값에 안 닿는다(ADR-0133 §범위).
     *
     * <p><b>MUST: 상한이 있다.</b> 없으면 {@code asOf} 이후의 분할까지 곱해 <b>과거 기준일의
     * 시총이 미래를 본다.</b> 상수 이름이 {@code SHIFT_TO_TODAY}였던 것이 그 상태였다.
     *
     * <p><b>MUST: {@code APPLIED}만 쓴다.</b> 그것이 사람·소스가 확정한 계수라는 뜻이고,
     * {@code OBSERVED}는 <i>"있었는데 아직 못 고쳤다"</i>라 비율이 계수가 아니다(ADR-0049).
     *
     * <p><b>순환이 하나 있다 — 원장이 유니버스보다 먼저다.</b> 원장은 편입 종목을 대상으로
     * 채우므로, 새로 편입될 종목은 <b>그 시점에 원장이 비어</b> 이 보정을 못 받는다.
     * 창이 「공시일 이후」라 최대 한 분기이고, 다음 리밸런싱이 수렴시킨다.
     * <b>MUST NOT: 그것을 이 질의에서 풀려고 하지 않는다</b> — 유니버스를 정하려고 유니버스
     * 밖 전 종목의 원장을 채우는 것이 그 대가다.
     *
     * <p>{@code exp(sum(ln(...)))}인 이유는 PostgreSQL에 곱 집계가 없어서다. 비율은
     * {@code ck_ca_ratio_sign}이 양수로 못 박으므로 {@code ln}의 정의역을 벗어나지 않는다.
     */
    private static final String SHIFT_TO_ASOF = """
            coalesce((SELECT exp(sum(ln(ca.ratio))) FROM corporate_action ca
                       WHERE ca.stock_id = latest.stock_id
                         AND ca.status = 'APPLIED'
                         AND ca.effective_date > latest.published_at
                         AND ca.effective_date <= ?), 1)
            """;

    /**
     * {@code DISTINCT ON}으로 기(期)를 고른다.
     *
     * <p><b>MUST: 정렬 첫 키는 {@code period_end DESC}다.</b> 순위에 맞는 것은 <b>「그 시점에
     * 공표된 것 중 가장 최근 기」</b>이지 「가장 늦게 공시된 것」이 아니다. 첫 키를
     * {@code published_at}으로 옮기면 <b>뒤늦게 공시된 옛 기가 이긴다</b> — 실측으로 6종목이
     * 그렇게 움직이고 {@code NCRA}는 시총이 <b>7.16배</b>가 된다(2026-06-30의 2.1M주 대신
     * 2026-03-31의 15.3M주를 집는다). ADR-0099 §규칙의 <i>"기를 {@code period_end}로 고르고
     * 개정본을 {@code revision_no}로 고른다"</i>가 그대로 지켜지는 자리다.
     *
     * <p><b>{@code is_latest}를 안 본다.</b> 그것은 <b>읽는 시점의</b> 최신 개정본이라 시간축이
     * 없다. {@code published_at <= asOf}로 자르면 그 시점에 보이던 개정본이 자동으로 골라지므로
     * {@code is_latest}는 더할 것이 없고, 걸면 <b>미래의 정정본만 남은 기가 통째로 사라진다.</b>
     * 정렬 둘째 키가 {@code published_at DESC}인 것이 같은 기 안에서 그 시점의 최신 판을 고른다.
     *
     * <p><b>{@code published_at}에 NULL 갈래를 두지 않는다.</b> 그 컬럼은 스키마가 {@code NOT NULL}로
     * 막으므로(V1) {@code coalesce}나 {@code IS NOT NULL} 가드는 <b>떨어질 수 없는 펜스</b>가 된다
     * (ADR-0074가 금지한 형태).
     *
     * <p>{@code shares_outstanding > 0}을 거는 이유는 0이 「작은 회사」와 구별되지 않기
     * 때문이다({@code MarketCapUniverseAdapter.toCandidate}가 같은 판단을 한다).
     */
    private static final String SQL = """
            WITH latest AS (
                SELECT DISTINCT ON (f.stock_id)
                       f.stock_id, f.shares_outstanding, f.period_end, f.published_at
                FROM financial_statement f
                JOIN stock s ON s.id = f.stock_id
                WHERE s.market_code = ?
                  AND s.is_active
                  AND f.published_at <= ?
                  AND f.shares_outstanding IS NOT NULL
                  AND f.shares_outstanding > 0
                  AND f.period_end >= ?
                ORDER BY f.stock_id, f.period_end DESC, f.published_at DESC, f.revision_no DESC
            )
            SELECT s.ticker,
                   latest.shares_outstanding * %s AS shares_outstanding,
                   s.sic_code, latest.period_end, latest.published_at,
                   %s AS currency_mismatched
            FROM latest
            JOIN stock s ON s.id = latest.stock_id
            ORDER BY s.ticker
            """.formatted(SHIFT_TO_ASOF, CURRENCY_MISMATCHED);

    private final JdbcTemplate jdbcTemplate;

    public JdbcStoredShareCounts(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShareCount> activeByMarket(String marketCode, LocalDate oldestPeriodEnd, LocalDate asOf) {
        return jdbcTemplate.query(SQL,
                (rs, rowNum) -> new ShareCount(
                        rs.getString("ticker"),
                        rs.getBigDecimal("shares_outstanding"),
                        rs.getString("sic_code"),
                        rs.getObject("period_end", LocalDate.class),
                        rs.getObject("published_at", LocalDate.class),
                        rs.getBoolean("currency_mismatched")),
                marketCode, asOf, oldestPeriodEnd, asOf);
    }
}
