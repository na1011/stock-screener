package com.stockscreener.indicator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code indicator_trade_date}를 원본({@code indicator_value})에서 다시 짓는다.
 *
 * <p><b>왜 색인이 따로 있는가</b>: 화면 기준일이 시장을 갈라야 하는데, {@code indicator_value}에
 * {@code stock}을 조인하면 {@code max(trade_date)}가 <b>0.099ms → 1,333ms</b>다(실측 7,179,721행).
 * 그것이 <b>요청마다 도는 질의</b>라 쓸 수 없어 ADR-0093이 미뤄 두었고, 이 색인이 그 자리를 닫는다.
 * 색인은 시장 수 × 보존 일수라 수백 행이다.
 *
 * <p><b>MUST: 지표를 쓰거나 지우는 잡의 끝에서 부른다.</b> 지금 그 잡은 둘이다 —
 * {@code IndicatorBackfillJob}과 <b>{@code DailyUpdateJob}</b>. <b>매일 도는 것은 후자이므로
 * 백필에만 달면 기준일이 마지막 백필 시점에 언 채 매일 하루씩 벌어진다.</b> 값은 전부 옳고
 * 어긋남은 조용하다.
 *
 * <p><b>MUST NOT: 잡 전체를 한 트랜잭션으로 묶어 이것을 그 안에 넣지 않는다.</b> 지표 계층에
 * 잡 단위 트랜잭션이 없고({@code IndicatorRunner}·{@code IndicatorValueWriter}·
 * {@code IndicatorRetentionPolicy} 전부), 만들면 <b>실패 격리의 단위가 종목이 아니게 된다</b>.
 * 이 클래스만 자기 트랜잭션을 갖고, 원본에서 유도하므로 <b>몇 번을 불러도 결과가 같다.</b>
 *
 * <p>비용은 원본을 두 번 훑는 것이다(약 2.7초). 배치 끝에 한 번이라 백필 실측(2026-08 · 350종목 ×
 * 180일자 = 1시간 51분) 옆에서 무시할 수 있다.
 */
@Component
public class IndicatorTradeDateIndex {

    /**
     * 원본에 있는데 색인에 없는 (시장, 거래일)을 더한다.
     *
     * <p>{@code ON CONFLICT DO NOTHING}이라 동시에 두 잡이 불러도 안전하다.
     */
    private static final String INSERT_MISSING = """
            INSERT INTO indicator_trade_date (market_code, trade_date)
            SELECT DISTINCT s.market_code, v.trade_date
            FROM indicator_value v
            JOIN stock s ON s.id = v.stock_id
            ON CONFLICT DO NOTHING
            """;

    /**
     * 원본에서 사라진 (시장, 거래일)을 색인에서 지운다.
     *
     * <p><b>이 문장이 없으면 보존 정리·무효화가 지운 날짜가 색인에 남는다.</b> 그러면
     * {@code earliestIndicatorDate}가 이미 없는 날을 하한으로 신고하고, 화면의 날짜 선택기가
     * <b>조회하면 비는 범위</b>를 연다.
     */
    private static final String DELETE_STALE = """
            DELETE FROM indicator_trade_date d
            WHERE NOT EXISTS (
                SELECT 1
                FROM indicator_value v
                JOIN stock s ON s.id = v.stock_id
                WHERE s.market_code = d.market_code AND v.trade_date = d.trade_date)
            """;

    private final JdbcTemplate jdbcTemplate;

    public IndicatorTradeDateIndex(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 색인을 원본에 맞춘다.
     *
     * <p>더하기와 지우기를 <b>한 트랜잭션</b>에 둔다 — 갈라 두면 그 사이에 읽는 요청이
     * 하한과 상한이 어긋난 색인을 본다.
     *
     * @return 색인에 남은 (시장, 거래일) 수. 로그에 찍어 어긋남을 눈으로 볼 수 있게 한다
     */
    @Transactional
    public int rebuild() {
        jdbcTemplate.update(INSERT_MISSING);
        jdbcTemplate.update(DELETE_STALE);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM indicator_trade_date", Integer.class);
        return count == null ? 0 : count;
    }
}
