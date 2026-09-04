package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 결과 표가 <b>무엇 위에서 나왔는가</b>. 저장본은 안 낡았는데 표가 바뀌는 축들이다.
 *
 * <p><b>왜 필요한가.</b> 저장본은 값을 담고, 판정은 <b>읽는 시점의 DB 상태</b>가 한다.
 * 그 상태에 이력 표가 없어서 <b>어제의 표를 다시 만들 수 없다.</b> 실측:
 * <ul>
 *   <li>전략 정의는 사용자가 화면에서 고친다 — v12는 만든 뒤 <b>2026-09-02 20:26</b>에,
 *       v13은 다음 날 <b>09:07</b>에 고쳐졌다.</li>
 *   <li>v12·v13 <b>둘 다</b> {@code PER_SECTOR_PCTL}을 <b>가중 2.000</b>(만점 11 중 18%)으로
 *       쓰는데, 그 코드는 <b>저장본에 없고</b> 읽기 시점에 {@code stock}의 업종 키로 만들어진다.
 *       일일 잡이 그 원본을 쓰고, 소분류 크기가 <b>4 또는 5</b>인 그룹이 USD 31개(134종목) ·
 *       KRW 10개(43종목)라 <b>종목 하나가 그룹 전체를 소분류↔중분류로 넘긴다.</b></li>
 *   <li>조회 대상의 편입도 읽기 시점이다 — 한 종목이 코스피200↔코스닥150을 옮기면
 *       통화 모집단은 그대로인데 <b>두 대상의 표가 동시에 바뀐다.</b></li>
 * </ul>
 *
 * <p><b>MUST: 이것은 「재현」이 아니라 「무효화 신고」다.</b> 해시는 달라졌음을 알려줄 뿐
 * 원본을 복원하지 못한다. <b>전략만은 값으로 찍어</b> 실제로 재현할 수 있게 한다 — 조건이
 * 스무 줄 남짓이라 비용이 거의 같다.
 *
 * <p><b>MUST NOT: {@code screen.updated_at}으로 대신하지 않는다.</b> 셋이 어긋난다 —
 * 그 시각의 정의를 복원할 길이 없고(재현 불가), 이름만 고쳐도 움직이며(과검출),
 * psql이 조건을 직접 고치면 안 움직인다(미검출). {@code screen_condition_threshold}에는
 * <b>시각 컬럼이 아예 없다.</b>
 */
final class JudgementProvenance {

    private final JdbcTemplate jdbcTemplate;

    JudgementProvenance(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param sectorKeyFingerprint 모집단의 업종 키. {@code _SECTOR_*}의 모집단을 정한다
     * @param membershipFingerprint 조회 대상별 편입. 판정 대상과 {@code _PCTL} 모집단을 정한다
     * @param fallbackStocks       소분류 표본이 모자라 중분류로 넘어간 종목 수.
     *                             <b>구조적 업종 크기 기준</b>이지 지표별 표본이 아니다 —
     *                             업종에 8종목이 있어도 그중 셋만 PER이 있으면 PER의 표본은 3이다
     */
    record Snapshot(String sectorKeyFingerprint, String membershipFingerprint,
                    Map<String, Integer> fallbackStocks) {
    }

    Snapshot capture() {
        return new Snapshot(digest("""
                SELECT coalesce(md5(string_agg(s.id || ':' || coalesce(s.industry_group_key, '')
                                    || ':' || coalesce(s.industry_division_key, ''),
                                    ',' ORDER BY s.id)), '')
                FROM stock s WHERE s.is_active AND EXISTS (
                    SELECT 1 FROM universe_membership m
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE m.stock_id = s.id AND m.effective_to IS NULL)
                """), digest("""
                SELECT coalesce(md5(string_agg(m.universe_code || ':' || m.stock_id,
                                    ',' ORDER BY m.universe_code, m.stock_id)), '')
                FROM universe_membership m
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE m.effective_to IS NULL
                """), fallbackCounts());
    }

    /** 소분류가 {@code MIN_PEERS} 미만이라 중분류로 넘어가는 종목 수(통화별). */
    private Map<String, Integer> fallbackCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbcTemplate.query("""
                WITH pop AS (
                    SELECT s.id, s.industry_group_key g, mk.currency_code c
                    FROM stock s JOIN market mk ON mk.code = s.market_code
                    WHERE s.is_active AND EXISTS (
                        SELECT 1 FROM universe_membership m
                        JOIN universe u ON u.code = m.universe_code AND u.is_active
                        WHERE m.stock_id = s.id AND m.effective_to IS NULL)),
                sized AS (SELECT c, g, count(*) n FROM pop WHERE g IS NOT NULL GROUP BY 1, 2)
                SELECT c, coalesce(sum(n) FILTER (WHERE n < ?), 0)
                FROM sized GROUP BY 1
                """, rs -> {
            out.put(rs.getString(1), rs.getInt(2));
        }, PointInTimeScreener.MIN_PEERS);
        return out;
    }

    /**
     * 전략을 <b>값으로</b> 적는다 — 해시가 아니다. 해시는 「달라졌다」만 말하고, 이 표를 다시
     * 만들려면 조건 자체가 있어야 한다.
     *
     * <p>{@code id}·{@code created_at}은 뺀다: {@code ScreenStore.update}가 DELETE+INSERT라
     * <b>내용이 같아도 id가 매번 바뀐다.</b>
     */
    List<String> describe(long screenId) {
        List<String> lines = new ArrayList<>();
        jdbcTemplate.query("""
                SELECT sc.indicator_code, sc.operator, sc.operand_type,
                       sc.literal_value, sc.literal_value_2, sc.operand_indicator_code,
                       sc.operand_multiplier, sc.weight, sc.is_required, sc.threshold_currency,
                       (SELECT string_agg(t.currency_code || '=' || coalesce(t.literal_value::text, '')
                                          || coalesce('~' || t.literal_value_2::text, ''),
                                          ' ' ORDER BY t.currency_code)
                        FROM screen_condition_threshold t WHERE t.condition_id = sc.id) AS thresholds
                FROM screen_condition sc
                JOIN condition_group g ON g.id = sc.group_id
                WHERE g.screen_id = ?
                ORDER BY sc.sort_order, sc.id
                """, rs -> {
            lines.add(String.format("%s %s %s%s w=%s%s%s",
                    rs.getString("indicator_code"), rs.getString("operator"),
                    rs.getString("operand_type"),
                    rs.getString("literal_value") == null ? ""
                            : " " + rs.getString("literal_value"),
                    rs.getString("weight"),
                    rs.getBoolean("is_required") ? " 필수" : "",
                    rs.getString("thresholds") == null ? ""
                            : " [" + rs.getString("thresholds") + "]"));
        }, screenId);
        return lines;
    }

    private String digest(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
