package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 저장본을 쓰기 전에 <b>그 사이 원자료가 움직였는지</b>를 싸게 훑는다.
 *
 * <p><b>왜 지문을 다시 안 재는가</b>: 지문은 날짜마다 다시 계산해야 하고 1년치면 약 15분이다
 * (실측 날짜당 KRW 0.56초 · USD 1.43초 × 873날짜). 저장소를 만든 목적이 <b>전략 변경당 수십
 * 초</b>인데 읽을 때마다 15분을 치르면 그 목적이 사라진다. 여기는 <b>한 번에 2초대</b>다.
 *
 * <p><b>표를 통째로 해시해 「바뀌었나」를 묻지 않는다.</b> 시세·지수·재무는 <b>매일 뒤에
 * 붙으므로</b> 자르지 않은 해시는 매일 달라지고, 그러면 저장소 전체가 매일 무효가 되어
 * 저장할 이유가 사라진다. 그래서 표마다 두 가지를 따로 본다.
 *
 * <ol>
 *   <li><b>뒤늦게 도착한 과거 행</b> — 언제 붙었든 {@code asOf} 이하를 가리키면 그 날짜를
 *       바꾼다. 「가장 이른 영향 날짜」로 접어 종목별로 들고 있는다.</li>
 *   <li><b>제자리 수정</b> — 적재 시점에 이미 있던 행이 값만 바뀐 것. 시각으로는 못 잡는다.
 *       {@code FinancialWriter}가 {@code UPDATE financial_statement SET … WHERE id = ?}를
 *       하는데 그 표에는 {@code updated_at}이 <b>없기 때문이다.</b> 이것만 내용 해시로 본다.</li>
 * </ol>
 *
 * <p><b>과검출은 하되 미검출은 안 한다.</b> 시세 UPSERT는 값과 무관하게 {@code updated_at}을
 * 올리므로(실측 24시간에 1,350종목 전부, 값이 바뀐 것은 1종목) 안 바뀐 것도 의심으로 잡힌다.
 * 의심은 값을 다시 만들어 해소하지, 저장본을 그대로 쓰지 않는다.
 */
final class PitChangeDetector {

    private final JdbcTemplate jdbcTemplate;

    PitChangeDetector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param stockSuspects 종목 → <b>그 종목이 의심스러워지는 가장 이른 기준일.</b> 저장 날짜
     *                      {@code D}는 그 값이 {@code D} 이하일 때만 의심이다 — {@code D} 뒤에
     *                      붙은 행은 그날의 값을 바꾸지 않는다
     * @param sharedFrom    종목에 안 걸리는 입력(시장 지수)이 흔들린 가장 이른 기준일.
     *                      {@code null}이면 안 흔들렸다
     * @param inPlaceDigest 적재 시점에 이미 있던 행들의 내용 해시. 적재 때 적어 둔 것과 다르면
     *                      <b>어느 행이 바뀌었는지 이 신호로는 알 수 없어</b> 전부 의심한다
     */
    record Changes(Map<Long, LocalDate> stockSuspects, LocalDate sharedFrom,
                   String inPlaceDigest) {

        /** 그 날짜에서 값을 다시 만들어야 하는 종목. */
        Set<Long> suspectsOn(LocalDate asOf, Collection<Long> population, String digestAtLoad) {
            if (digestAtLoad == null || !digestAtLoad.equals(inPlaceDigest)
                    || (sharedFrom != null && !sharedFrom.isAfter(asOf))) {
                return new LinkedHashSet<>(population);
            }
            Set<Long> out = new LinkedHashSet<>();
            for (Long stockId : population) {
                LocalDate earliest = stockSuspects.get(stockId);
                if (earliest != null && !earliest.isAfter(asOf)) {
                    out.add(stockId);
                }
            }
            return out;
        }
    }

    /**
     * @param since 저장본을 쓴 시각. 이보다 뒤에 손댄 행만 의심한다
     */
    Changes since(Instant since) {
        Timestamp t = Timestamp.from(since);
        Map<Long, LocalDate> suspects = new LinkedHashMap<>();

        // 시세: UPSERT가 updated_at을 올린다. 과검출이지만 미검출은 없다.
        collectEarliest(suspects, """
                SELECT stock_id, min(trade_date) FROM price_daily
                WHERE updated_at > ? GROUP BY stock_id
                """, t);
        // 재무·거버넌스: updated_at이 없어 「새로 들어온 행」만 시각으로 잡힌다. 제자리 수정은
        // 아래 inPlaceDigest가 맡는다. published_at은 과거를 가리킬 수 있어 자르는 기준이 된다.
        collectEarliest(suspects, """
                SELECT stock_id, min(published_at) FROM financial_statement
                WHERE created_at > ? GROUP BY stock_id
                """, t);
        collectEarliest(suspects, """
                SELECT stock_id, min(published_at) FROM governance_fact
                WHERE created_at > ? GROUP BY stock_id
                """, t);
        // 기업행위: 하나가 승인되면 그 종목의 시세가 통째로 다시 쓰인다(실측 APH 1,760봉).
        // 효력일이 아니라 그 종목의 전 구간을 의심해야 해 가장 이른 날짜로 접는다.
        collectEarliest(suspects, """
                SELECT stock_id, DATE '0001-01-01' FROM corporate_action
                WHERE updated_at > ? GROUP BY stock_id
                """, t);

        LocalDate sharedFrom = jdbcTemplate.queryForObject("""
                SELECT min(trade_date) FROM market_index_daily WHERE updated_at > ?
                """, LocalDate.class, t);
        return new Changes(suspects, sharedFrom, inPlaceDigest(since));
    }

    private void collectEarliest(Map<Long, LocalDate> out, String sql, Timestamp t) {
        jdbcTemplate.query(sql, rs -> {
            java.sql.Date d = rs.getDate(2);
            if (d == null) {
                return;
            }
            out.merge(rs.getLong(1), d.toLocalDate(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }, t);
    }

    /**
     * <b>적재 시점에 이미 있던 행</b>들의 내용 해시. 뒤에 붙은 행을 빼는 것이 요점이다 —
     * 안 빼면 매일의 적재가 해시를 움직여 저장소가 매일 통째로 무효가 된다.
     *
     * <p><b>모집단으로 좁힌다.</b> 모집단 밖은 어떤 일상 잡도 안 건드리는데
     * (전 잡이 {@code activeUniverseMemberIds()} = 1,350을 돈다) digest만 그것을 보고 있었다 —
     * 실측 노출면이 {@code financial_statement} 190,696행 중 <b>130,196행(68.3%)</b>,
     * {@code stock} 8,872 중 7,522, {@code universe_membership} 9,911 중 7,554다.
     *
     * <p><b>통화별이 아니라 모집단 전체로 좁힌다.</b> {@code PitSweep.changeCache}가
     * {@code Map<Instant, Changes>}이고 캐시된 digest가 String 하나라 <b>통화를 모른다</b> —
     * 통화 의존으로 만들면 KRW 값이 USD 날짜에 재사용돼 USD가 영구 의심이 된다.
     *
     * <p><b>마이그레이션이 필요 없다.</b> {@code indexFp}와 달리 이 불일치는 조용한 오답이
     * 아니라 <b>비용</b>이다 — 1단계가 전 종목을 의심으로 올리고 2단계가 내용으로 확정한 뒤
     * {@code restamp}이 새 digest를 찍는다. <b>한 번의 스윕이 스스로 낫는다</b>(실측 8분대).
     *
     * <p>제자리 수정을 하는 표만 본다. 실측 2,031ms → <b>796ms</b>.
     */
    String inPlaceDigest(Instant since) {
        Timestamp t = Timestamp.from(since);
        return jdbcTemplate.queryForObject("""
                WITH pop AS (
                    SELECT s.id FROM stock s
                    WHERE s.is_active AND EXISTS (
                        SELECT 1 FROM universe_membership m
                        JOIN universe u ON u.code = m.universe_code AND u.is_active
                        WHERE m.stock_id = s.id AND m.effective_to IS NULL))
                SELECT md5(concat_ws('|',
                  coalesce((SELECT md5(string_agg((to_jsonb(f.*) - 'created_at')::text,
                                                 ',' ORDER BY f.id))
                            FROM financial_statement f JOIN pop ON pop.id = f.stock_id
                            WHERE f.created_at <= ?), ''),
                  coalesce((SELECT md5(string_agg((to_jsonb(g.*) - 'created_at')::text,
                                       ',' ORDER BY g.stock_id, g.fiscal_year, g.metric_key))
                            FROM governance_fact g JOIN pop ON pop.id = g.stock_id
                            WHERE g.created_at <= ?), ''),
                  coalesce((SELECT md5(string_agg((to_jsonb(s.*) - 'created_at' - 'updated_at')::text,
                                                 ',' ORDER BY s.id))
                            FROM stock s JOIN pop ON pop.id = s.id), ''),
                  coalesce((SELECT md5(string_agg(m.stock_id || ':' || m.universe_code || ':'
                                                 || coalesce(m.effective_to::text, ''),
                                                 ',' ORDER BY m.stock_id, m.universe_code))
                            FROM universe_membership m JOIN pop ON pop.id = m.stock_id), '')
                ))
                """, String.class, t, t);
    }
}
