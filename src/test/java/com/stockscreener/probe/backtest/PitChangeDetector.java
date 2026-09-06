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
     * @param stockSuspects  종목 → <b>그 종목이 의심스러워지는 가장 이른 기준일.</b> 저장 날짜
     *                       {@code D}는 그 값이 {@code D} 이하일 때만 의심이다 — {@code D} 뒤에
     *                       붙은 행은 그날의 값을 바꾸지 않는다
     * @param sharedFrom     종목에 안 걸리는 입력(시장 지수)이 흔들린 가장 이른 기준일.
     *                       {@code null}이면 안 흔들렸다
     * @param inPlaceDigests 적재 시점에 이미 있던 행들의 <b>종목별</b> 내용 해시. 적재 때 적어 둔
     *                       것과 다른 종목만 의심한다
     */
    record Changes(Map<Long, LocalDate> stockSuspects, LocalDate sharedFrom,
                   Map<Long, String> inPlaceDigests) {

        /**
         * 그 날짜에서 값을 다시 만들어야 하는 종목.
         *
         * <p><b>모집단이 바뀌었다는 것만으로는 아무 종목도 의심하지 않는다.</b> 저장되는 125개
         * 코드는 전부 종목 단위이고({@code IndicatorRunner.computeAt}이 <b>종목 하나 + 시장
         * 지수</b>만 받는다), 교차단면({@code _PCTL}·{@code _SECTOR_*})은 저장하지 않고
         * {@code PointInTimeScreener}가 <b>읽는 시점에</b> 만든다. 그래서 편입·편출은 살아남은
         * 종목의 저장값을 틀리게 만들 수 없다 — 실측으로 2026-09-04 리밸런싱을 사이에 두고
         * <b>양쪽 모집단에 다 있는 1,310종목 중 해시가 움직인 것이 0</b>이었다.
         *
         * <p>새로 편입된 종목은 저장된 해시가 <b>없으므로</b> 의심이 된다. 다만 그 종목은 값도
         * 없어서 {@code PitSweep.repair}의 완전성 검사가 <b>먼저</b> 던지고,
         * {@code PitLoadTest}에서는 {@code fp:} 부재로 낡음이 되어 계산된다.
         *
         * @param digestsAtLoad 매니페스트에 적힌 종목별 해시. <b>비어 있으면 전 종목이 의심</b>이다
         *                      — 옛 판으로 찍힌 매니페스트가 그렇고, 한 번의 스윕이 스스로 낫는다
         */
        Set<Long> suspectsOn(LocalDate asOf, Collection<Long> population,
                             Map<Long, String> digestsAtLoad) {
            if (sharedFrom != null && !sharedFrom.isAfter(asOf)) {
                return new LinkedHashSet<>(population);
            }
            Set<Long> out = new LinkedHashSet<>();
            for (Long stockId : population) {
                String atLoad = digestsAtLoad.get(stockId);
                if (atLoad == null || !atLoad.equals(inPlaceDigests.get(stockId))) {
                    out.add(stockId);
                    continue;
                }
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
        // 아래 inPlaceDigests가 맡는다. published_at은 과거를 가리킬 수 있어 자르는 기준이 된다.
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
        return new Changes(suspects, sharedFrom, inPlaceDigests(since));
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
     * <b>적재 시점에 이미 있던 행</b>들의 <b>종목별</b> 내용 해시. 뒤에 붙은 행을 빼는 것이
     * 요점이다 — 안 빼면 매일의 적재가 해시를 움직여 저장소가 매일 통째로 무효가 된다.
     *
     * <p><b>왜 종목별인가.</b> 전역 문자열 하나였을 때는 무엇이 움직이든 <b>전 종목</b>이
     * 의심으로 올라갔고, 그 확인이 512날짜에 <b>약 535초</b>였다(KRW 0.57초 · USD 1.53초 ×
     * 256날짜, 적재와 스윕이 <b>각각</b> 문다). 그리고 그 「무엇」의 대부분이 <b>모집단 변경</b>
     * 이었다 — 네 항이 전부 {@code JOIN pop}을 지나므로 리밸런싱 한 번이 저장본 전체를 무효로
     * 읽혔다. 같은 저장소가 {@code PitStore.freshnessOf}의 javadoc(<b>「전역 지문을 쓰지 않는
     * 이유」</b>)에서 이미 종목 축을 골랐고, 여기만 그 예외로 남아 있었다.
     *
     * <p><b>모집단으로 좁힌다.</b> 모집단 밖은 어떤 일상 잡도 안 건드리는데
     * (전 잡이 {@code activeUniverseMemberIds()} = 1,350을 돈다) digest만 그것을 보고 있었다 —
     * 실측 노출면이 {@code financial_statement} 190,696행 중 <b>130,196행(68.3%)</b>,
     * {@code stock} 8,872 중 7,522, {@code universe_membership} 9,911 중 7,554다.
     *
     * <p><b>모집단은 성분 안에 안 들어간다.</b> 좁히기는 <b>어느 종목을 낼지</b>만 정하고 각
     * 종목의 해시는 자기 행들만 본다. 그래서 편입·편출이 살아남은 종목의 해시를 못 움직인다 —
     * 그것이 이 변경의 전부이고, {@code PitDigestScopeTest}가 그 성질을 지킨다.
     *
     * <p><b>마이그레이션이 필요 없다.</b> 이 불일치는 조용한 오답이 아니라 <b>비용</b>이다 —
     * 1단계가 의심을 올리고 2단계가 내용으로 확정한 뒤 {@code restamp}이 새 해시를 찍는다.
     * <b>한 번의 스윕이 스스로 낫는다.</b>
     *
     * <p><b>시세·기업행위는 건수만 본다.</b> 그 둘은 1단계가 {@code updated_at}으로 훑으므로
     * 삽입·수정은 이미 잡히는데 <b>삭제만 못 본다</b> — 시각은 사라진 행을 못 말한다. 건수를
     * {@code created_at <= since}로 자르면 <b>일상 적재에 안 움직이고 삭제에만 준다</b>:
     * {@code PriceWriter}의 UPSERT는 {@code DO UPDATE SET}에 {@code created_at}이 없고
     * {@code AdjustmentRecalculator}는 {@code updated_at}만 손대며, 기업행위도 INSERT 1 ·
     * UPDATE 5가 전부 {@code updated_at}만 건드린다(전수 확인). 실측: 최근 24시간에 갱신된
     * 2,115행 중 <b>472행이 옛 {@code created_at}</b>을 그대로 들고 있다.
     *
     * <p><b>{@code since} 시점에 이미 있던 행의 삭제만 잡는다.</b> 적재 실행은 기준 시각을 한 번
     * 찍고 그 뒤 몇 시간을 도므로, <b>그 사이에 생겨 값에 실린 뒤 지워진 행</b>은 건수도
     * {@code updated_at}도 안 움직여 못 본다. {@code PitStore.Stamp.writtenAt}의 「과검출이지
     * 미검출이 아니다」는 <b>이 축에 한해 예외가 있다.</b> 도달 0건이다(아래).
     *
     * <p>지금 그 삭제가 <b>운영 코드에 한 번도 없었고</b>({@code src/main}에 0건, `git log -S`로
     * 저장소 전 이력 0건) 지문 대상 표로 오는 FK <b>14개가 전부 {@code NO ACTION}</b>이라 부모
     * 삭제로도 못 사라진다. <b>그래도 여기 넣는 이유는 그 사실이 보장이 아니라 우연이기
     * 때문이다</b> — 이 저장소는 다른 표에 보존 삭제를 쓴다({@code IndicatorRetentionPolicy} 등).
     *
     * <p>제자리 수정을 하는 표만 본다. 실측 종목별 <b>0.93초</b>(건수 둘을 넣기 전 0.71~0.75초,
     * 옛 전역 식 0.76~0.86초). 스윕당 한 번이다.
     */
    Map<Long, String> inPlaceDigests(Instant since) {
        Timestamp t = Timestamp.from(since);
        Map<Long, String> out = new LinkedHashMap<>();
        jdbcTemplate.query(IN_PLACE_DIGEST_SQL,
                rs -> {
                    out.put(rs.getLong(1), rs.getString(2));
                }, t, t, t, t);
        return out;
    }

    /**
     * 펜스({@code PitDigestScopeTest})가 보는 <b>실제 SQL</b>. 요약본을 따로 적지 않는다 —
     * 사본을 두면 두 곳이 갈릴 때까지 드러나지 않고, 그때 펜스는 <b>거짓 초록</b>이 된다.
     */
    static String inPlaceDigestSql() {
        return IN_PLACE_DIGEST_SQL;
    }

    /**
     * <b>MUST: 네 성분이 전부 {@code pop.id}로 한정된다.</b> 성분 안에서 {@code pop}을 집합으로
     * 쓰면(예: {@code JOIN pop}) 모집단 변경이 다시 전 종목의 해시를 움직여, 리밸런싱 한 번이
     * 저장본 전체를 비싼 길로 보내던 옛 동작으로 돌아간다. 펜스가 그것을 본다.
     */
    private static final String IN_PLACE_DIGEST_SQL = """
            WITH pop AS (
                SELECT s.id FROM stock s
                WHERE s.is_active AND EXISTS (
                    SELECT 1 FROM universe_membership m
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE m.stock_id = s.id AND m.effective_to IS NULL))
            SELECT pop.id, md5(concat_ws('|',
              coalesce((SELECT md5(string_agg((to_jsonb(f.*) - 'created_at')::text,
                                             ',' ORDER BY f.id))
                        FROM financial_statement f
                        WHERE f.stock_id = pop.id AND f.created_at <= ?), ''),
              coalesce((SELECT md5(string_agg((to_jsonb(g.*) - 'created_at')::text,
                                   ',' ORDER BY g.fiscal_year, g.metric_key))
                        FROM governance_fact g
                        WHERE g.stock_id = pop.id AND g.created_at <= ?), ''),
              coalesce((SELECT (to_jsonb(s.*) - 'created_at' - 'updated_at')::text
                        FROM stock s WHERE s.id = pop.id), ''),
              coalesce((SELECT string_agg(m.universe_code || ':'
                                          || coalesce(m.effective_to::text, ''),
                                          ',' ORDER BY m.universe_code)
                        FROM universe_membership m WHERE m.stock_id = pop.id), ''),
              (SELECT count(*) FROM price_daily p
                WHERE p.stock_id = pop.id AND p.created_at <= ?),
              (SELECT count(*) FROM corporate_action ca
                WHERE ca.stock_id = pop.id AND ca.created_at <= ?)
            ))
            FROM pop
            ORDER BY pop.id
            """;
}
