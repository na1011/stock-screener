package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 하네스가 도는 <b>조회 대상</b>. 화면의 조회 대상 콤보와 <b>같은 술어</b>다(ADR-0108).
 *
 * <p>대상 하나가 시장·통화·달력·모집단 넷을 한꺼번에 정한다 — 그것이 이 하네스를 두 시장에서
 * 돌게 하는 유일한 축이다. 프리셋의 {@code universeCode}로는 정할 수 없다:
 * <b>활성 프리셋 여덟이 전부 {@code null}</b>이라(실측) 「전체」가 되고, 그러면 통화가 갈려
 * 임계를 해석할 수 없다.
 *
 * <p><b>편입이 있는 것만 센다.</b> 「전체」는 {@code inAnyActiveUniverse}로 도는데 그 조인이
 * 편입을 요구하므로 <b>편입 0건인 활성 유니버스는 결과에 한 종목도 못 넣는다.</b> 그런데도
 * 시장·통화 집합에 들어가면 기준일이 종목 없는 시장의 달력에서 나온다
 * ({@code MarketScope.ACTIVE_MARKETS}가 같은 이유로 같은 술어를 쓴다).
 *
 * <p><b>MUST: 이 질의의 사본을 테스트마다 만들지 않는다.</b> 갈리면 하네스마다 다른 모집단을
 * 보게 되고, 그 차이는 통과 수가 조금 다른 것으로만 드러난다. 실제로 이 질의가
 * {@code PresetAcrossUniversesTest} 안에만 있었고 나머지 셋은 대상 개념 자체가 없었다.
 */
@Component
public class ScreeningTargets {

    /**
     * @param asOf 그 시장 지표 달력의 최신 일자. <b>null이면 그 시장에 지표가 없다</b> —
     *             실행 불가가 아니라 「아직 준비 안 됨」이고 라이브에서는 503이다
     */
    public record Target(String code, String name, String marketCode, String currency,
                         LocalDate asOf, int members) {

        /** {@link AsOfCalendar}·{@link ForwardReturns}에 넘길 시장 집합. 대상 하나면 시장 하나다. */
        public Set<String> markets() {
            return Set.of(marketCode);
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public ScreeningTargets(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Target> all() {
        return jdbcTemplate.query("""
                SELECT u.code, u.name, u.market_code, mk.currency_code,
                       (SELECT max(d.trade_date) FROM indicator_trade_date d
                         WHERE d.market_code = u.market_code) AS as_of,
                       count(m.stock_id) AS members
                FROM universe u
                JOIN market mk ON mk.code = u.market_code
                JOIN universe_membership m ON m.universe_code = u.code AND m.effective_to IS NULL
                WHERE u.is_active
                GROUP BY u.code, u.name, u.market_code, mk.currency_code
                ORDER BY u.code
                """, (rs, n) -> new Target(
                rs.getString("code"), rs.getString("name"), rs.getString("market_code"),
                rs.getString("currency_code"), rs.getObject("as_of", LocalDate.class),
                rs.getInt("members")));
    }
}
