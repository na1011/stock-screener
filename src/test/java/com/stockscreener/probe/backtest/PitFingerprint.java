package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장본이 <b>아직 유효한가</b>를 판정할 재료. 종목 하나의 시점 재현 입력을 해시 하나로 접는다.
 *
 * <p><b>감사 타임스탬프를 절대 넣지 않는다.</b> UPSERT가 값과 무관하게 {@code updated_at}을
 * 올리기 때문이다 — 실측으로 24시간 동안 {@code updated_at}은 <b>1,350종목 전부</b>에서
 * 움직였는데 값이 실제로 바뀐 종목은 <b>1개</b>(APH, 기업행위 조정 재작성)였다. 타임스탬프를
 * 넣었다면 저장소가 매일 통째로 무효가 됐을 것이고, 그러면 저장하는 의미가 없다.
 *
 * <p><b>컬럼을 손으로 나열하지 않는다.</b> {@code information_schema}에서 만든다 — 나열하면
 * 나중에 더해진 컬럼이 조용히 지문 밖으로 빠지고, 그때 저장본은 <b>낡았는데 유효해 보인다.</b>
 * 앞선 판(재무 3컬럼만 나열)이 실제로 그랬다: {@code capex}를 지워 보는 대조군에서 옛 식은
 * <b>0종목</b>, 지금 식은 <b>4,362종목</b>(= capex가 채워진 종목 수)이 움직였다.
 *
 * <p><b>기준일로 자른다.</b> {@code asOf} 뒤에 붙은 봉은 그날의 값에 영향을 주지 않으므로
 * 낡음이 아니다. 안 자르면 매일의 시세 적재가 전 날짜를 무효로 만든다.
 *
 * <p>비용은 실측 날짜당 KRW 0.56초 · USD 1.43초다(전 이력 기준). 값 계산이 14.5초·37.0초이므로
 * 약 4%이고, 1년치 적재 전체에 약 15분을 더한다.
 */
final class PitFingerprint {

    /** 시세는 행이 220만이라 전 컬럼 jsonb가 3.6배 비쌌다(실측 5.81초 대 1.43초). */
    private static final String PRICE_COLUMNS = """
            SELECT string_agg('coalesce(p.' || quote_ident(column_name) || '::text,'''')',
                              ' || '':'' || ' ORDER BY ordinal_position)
            FROM information_schema.columns
            WHERE table_name = 'price_daily' AND column_name NOT IN ('created_at', 'updated_at')
            """;

    private final JdbcTemplate jdbcTemplate;
    private final String priceExpression;

    PitFingerprint(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.priceExpression = jdbcTemplate.queryForObject(PRICE_COLUMNS, String.class);
        if (priceExpression == null || priceExpression.isBlank()) {
            throw new IllegalStateException(
                    "price_daily 컬럼 목록이 비었다 — 지문이 시세를 아예 안 덮는다");
        }
    }

    /**
     * 한 통화 모집단의 종목별 지문. 모집단 선정은 {@code PointInTimeScreener}가 값을 만들 때
     * 쓰는 것과 <b>같은 조건</b>이어야 한다 — 어긋나면 매니페스트에 지문 없는 종목이 생기고,
     * 그 종목은 영원히 「낡음」으로 읽혀 날짜가 완료되지 않는다.
     */
    Map<Long, String> forCurrency(String currencyCode, LocalDate asOf) {
        String sql = """
                SELECT s.id, md5(concat_ws('|',
                  coalesce((SELECT md5(string_agg(%s, ',' ORDER BY p.trade_date))
                            FROM price_daily p
                            WHERE p.stock_id = s.id AND p.trade_date <= ?), ''),
                  coalesce((SELECT md5(string_agg((to_jsonb(f.*) - 'created_at')::text,
                                                 ',' ORDER BY f.id))
                            FROM financial_statement f
                            WHERE f.stock_id = s.id AND f.published_at <= ?), ''),
                  coalesce((SELECT md5(string_agg((to_jsonb(g.*) - 'created_at')::text,
                                                 ',' ORDER BY g.fiscal_year, g.metric_key))
                            FROM governance_fact g
                            WHERE g.stock_id = s.id AND g.published_at <= ?), ''),
                  coalesce((SELECT md5(string_agg(
                                (to_jsonb(ca.*) - 'created_at' - 'updated_at')::text,
                                ',' ORDER BY ca.id))
                            FROM corporate_action ca WHERE ca.stock_id = s.id), ''),
                  (to_jsonb(s.*) - 'created_at' - 'updated_at')::text,
                  coalesce((SELECT string_agg(m.universe_code, ',' ORDER BY m.universe_code)
                            FROM universe_membership m
                            WHERE m.stock_id = s.id AND m.effective_to IS NULL), '')
                ))
                FROM stock s
                JOIN market mk ON mk.code = s.market_code
                WHERE s.is_active AND mk.currency_code = ? AND EXISTS (
                    SELECT 1 FROM universe_membership m
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE m.stock_id = s.id AND m.effective_to IS NULL)
                ORDER BY s.id
                """.formatted(priceExpression);

        Map<Long, String> out = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            out.put(rs.getLong(1), rs.getString(2));
        }, asOf, asOf, asOf, currencyCode);
        return out;
    }

    /**
     * 종목에 안 걸리는 입력. {@code IndicatorRunner.marketIndexesFor}가 읽는 지수는 모집단
     * 전체가 함께 쓰므로 <b>종목 지문에 넣을 자리가 없다</b> — 넣으면 지수가 한 번 바뀔 때마다
     * 전 종목이 낡는 것으로 읽혀 종목 단위 무효화가 무의미해진다. 매니페스트에 따로 적는다.
     */
    String forMarketIndexes(LocalDate asOf) {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(md5(string_agg((to_jsonb(i.*) - 'created_at' - 'updated_at')::text,
                                               ',' ORDER BY i.index_code, i.trade_date)), '')
                FROM market_index_daily i WHERE i.trade_date <= ?
                """, String.class, asOf);
    }

    /** 통화별 모집단. 값 생산과 같은 조건을 쓰는지 시험이 확인할 수 있게 노출한다. */
    List<Long> populationOf(String currencyCode) {
        return jdbcTemplate.queryForList("""
                SELECT s.id FROM stock s
                JOIN market mk ON mk.code = s.market_code
                WHERE s.is_active AND mk.currency_code = ? AND EXISTS (
                    SELECT 1 FROM universe_membership m
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE m.stock_id = s.id AND m.effective_to IS NULL)
                ORDER BY s.id
                """, Long.class, currencyCode);
    }
}
