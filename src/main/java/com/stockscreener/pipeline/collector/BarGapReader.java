package com.stockscreener.pipeline.collector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 봉이 없는 날을 판정한다(ADR-0091).
 *
 * <p><b>이 축이 닫는 것은 넷 중 「휴장」 하나다.</b> 개장일인데 봉이 없는 것이 무거래·비활성·
 * 거래정지·수집 실패 중 무엇인지는 <b>여전히 갈리지 않는다</b> — 거래정지 목록이 없고
 * {@code stock.is_active}·{@code delisted_on}·{@code listed_on}이 미국 전 종목에서 비어 있다.
 * 그래서 {@code INACTIVE} 같은 판정값을 만들지 않는다. 상장폐지를 유니버스 이탈로 대신하는 것은
 * P20 축④가 금지한다 — 둘은 다르고 지금 소스로는 안 갈린다.
 *
 * <p><b>MUST: 판정 구간을 종목마다 좁힌다.</b>
 * {@code [그 종목의 첫 봉, 마지막 봉] ∩ [그 거래소 달력의 min, max]}이다. 안 좁히면 마지막 봉
 * 이후의 모든 세션이 결측이 되어 <b>첫 실행이 오탐만 낸다</b> — 파일럿 창(봉 2024년, 달력 오늘까지)
 * 에서 산술로 종목당 400세션 남짓이다. 그리고 같은 좁힘이 <b>공개 지연도 함께 닫는다</b>:
 * 오늘·어제 세션은 어느 종목의 마지막 봉보다 뒤라 애초에 안 든다.
 *
 * <p><b>반대 방향을 함께 잰다.</b> 「비세션인데 봉이 있다」는 달력이 세션을 빠뜨렸다는 뜻이고,
 * 그것이 <b>치명적 방향의 유일한 값싼 신호</b>다 — 열린 날을 닫혔다고 하면 수집 실패가 휴장으로
 * 접혀 영구히 침묵하는데, 적재 창의 <b>가운데 구멍</b>은 {@code min/max} 논증으로 안 막힌다.
 */
@Component
public class BarGapReader {

    /** 한 (종목, 일자)의 판정. 「모른다」와 「정상」과 「결함」이 각각 다른 값이다. */
    public enum Verdict {
        /** 세션인데 봉이 없다. <b>무거래·비활성·거래정지·수집 실패가 아직 뭉쳐 있다.</b> */
        SESSION_WITHOUT_BAR,
        /** 달력이 세션이 아니라는데 봉이 있다. <b>달력 쪽이 틀렸다는 신호다.</b> */
        BAR_ON_NON_SESSION
    }

    /** 한 건. */
    public record Gap(String ticker, LocalDate date, Verdict verdict) {
    }

    /**
     * 판정 결과.
     *
     * @param stocksInScope        판정 구간이 실제로 잡힌 종목 수
     * @param stocksWithoutBars    달력이 있는 거래소에 상장돼 있는데 봉이 <b>한 행도</b> 없는 종목 수.
     *                             결측이 아니라 <b>미적재</b>이며, 조용히 빼면 가장 큰 수집 실패가
     *                             구조적으로 안 보인다. 「시도했는데 0건」과 「아직 안 시켰다」는
     *                             <b>가를 기록이 없다.</b>
     * @param stocksWithoutCalendar 봉은 있는데 달력이 없어 판정불가인 종목 수(국내가 여기다)
     * @param gaps                 판정된 건. 많으면 잘릴 수 있으므로 {@code truncated}를 함께 본다
     * @param truncated            상한에 걸려 잘렸는가
     */
    public record Report(long stocksInScope, long stocksWithoutBars, long stocksWithoutCalendar,
                         List<Gap> gaps, boolean truncated) {

        public long count(Verdict verdict) {
            return gaps.stream().filter(g -> g.verdict() == verdict).count();
        }
    }

    /** 한 번에 들고 오는 건수 상한. 넘으면 잘렸다고 신고한다 — 조용히 자르지 않는다. */
    private static final int MAX_GAPS = 500;

    private final JdbcTemplate jdbcTemplate;

    public BarGapReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 달력이 있는 거래소에 상장돼 있고 봉이 하나라도 있는 종목을 판정한다.
     *
     * <p>유니버스를 보지 않는다 — 읽기만 하고 적재 대상을 고르지 않으므로 「전 종목」의 정의를
     * 하나 더 만드는 것이 아니다. 「봉이 있는가」는 {@link PriceCalendar#hasBars}가 답하는 것과
     * 같은 질문이고, 여기서는 집합으로 필요해 한 질의 안에서 푼다.
     *
     * @param tickers 비면 전량. 준 티커 중 대상에 없는 것은 그냥 빠진다
     * @param from    이 일자 이후만 본다. {@code null}이면 종목별 첫 봉부터
     */
    @Transactional(readOnly = true)
    public Report read(List<String> tickers, LocalDate from) {
        String tickerFilter = "";
        List<String> narrowed = tickers == null ? List.of() : tickers;
        if (!narrowed.isEmpty()) {
            tickerFilter = " AND s.ticker IN (%s)"
                    .formatted(String.join(",", Collections.nCopies(narrowed.size(), "?")));
        }

        // 판정 구간 = [종목의 첫 봉, 마지막 봉] ∩ [그 거래소 달력의 min, max] ∩ [from, ∞)
        // 조인 키가 s.listing_exchange라 국내 종목은 여기서 원리적으로 빠진다 — 그 값이 NULL이다.
        String scoped = """
                WITH cov AS (
                    SELECT exchange_code, min(session_date) AS lo, max(session_date) AS hi
                      FROM market_calendar GROUP BY exchange_code),
                scoped AS (
                    SELECT s.id, s.ticker, s.listing_exchange AS ex,
                           greatest(min(p.trade_date), c.lo,
                                    coalesce(?::date, min(p.trade_date))) AS lo,
                           least(max(p.trade_date), c.hi) AS hi
                      FROM stock s
                      JOIN cov c ON c.exchange_code = s.listing_exchange
                      JOIN price_daily p ON p.stock_id = s.id
                     WHERE true%s
                     GROUP BY s.id, s.ticker, s.listing_exchange, c.lo, c.hi
                    HAVING greatest(min(p.trade_date), c.lo,
                                    coalesce(?::date, min(p.trade_date)))
                           <= least(max(p.trade_date), c.hi))
                """.formatted(tickerFilter);

        // from 이 SELECT와 HAVING에 한 번씩, 티커 인자가 그 사이다.
        List<Object> scopeArgs = new ArrayList<>();
        scopeArgs.add(from);
        scopeArgs.addAll(narrowed);
        scopeArgs.add(from);

        Long inScope = jdbcTemplate.queryForObject(
                scoped + "SELECT count(*) FROM scoped", Long.class, scopeArgs.toArray());

        List<Object> gapArgs = new ArrayList<>(scopeArgs);
        gapArgs.add(MAX_GAPS + 1);
        List<Gap> found = jdbcTemplate.query(scoped + """
                SELECT sc.ticker, mc.session_date AS d, 'SESSION_WITHOUT_BAR' AS verdict
                  FROM scoped sc
                  JOIN market_calendar mc
                    ON mc.exchange_code = sc.ex AND mc.session_date BETWEEN sc.lo AND sc.hi
                  LEFT JOIN price_daily p
                    ON p.stock_id = sc.id AND p.trade_date = mc.session_date
                 WHERE p.stock_id IS NULL
                UNION ALL
                SELECT sc.ticker, p.trade_date AS d, 'BAR_ON_NON_SESSION' AS verdict
                  FROM scoped sc
                  JOIN price_daily p
                    ON p.stock_id = sc.id AND p.trade_date BETWEEN sc.lo AND sc.hi
                  LEFT JOIN market_calendar mc
                    ON mc.exchange_code = sc.ex AND mc.session_date = p.trade_date
                 WHERE mc.exchange_code IS NULL
                 ORDER BY 3, 1, 2
                 LIMIT ?
                """,
                (rs, rowNum) -> new Gap(rs.getString(1), rs.getObject(2, LocalDate.class),
                        Verdict.valueOf(rs.getString(3))),
                gapArgs.toArray());

        boolean truncated = found.size() > MAX_GAPS;
        List<Gap> gaps = truncated ? List.copyOf(found.subList(0, MAX_GAPS)) : List.copyOf(found);

        // 봉이 한 행도 없는 종목은 결측이 아니라 미적재다. 그래도 수는 신고한다 —
        // 조용히 빼면 가장 큰 수집 실패가 구조적으로 안 보인다.
        Long withoutBars = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM stock s
                 WHERE s.listing_exchange IS NOT NULL
                   AND EXISTS (SELECT 1 FROM market_calendar mc
                                WHERE mc.exchange_code = s.listing_exchange)
                   AND NOT EXISTS (SELECT 1 FROM price_daily p WHERE p.stock_id = s.id)
                """, Long.class);

        // 봉은 있는데 달력이 없어 판정불가인 종목. 국내 전량이 여기이며 그것이 정상이다.
        Long withoutCalendar = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM stock s
                 WHERE EXISTS (SELECT 1 FROM price_daily p WHERE p.stock_id = s.id)
                   AND NOT EXISTS (SELECT 1 FROM market_calendar mc
                                    WHERE mc.exchange_code = s.listing_exchange)
                """, Long.class);

        return new Report(inScope == null ? 0L : inScope,
                withoutBars == null ? 0L : withoutBars,
                withoutCalendar == null ? 0L : withoutCalendar,
                gaps, truncated);
    }
}
