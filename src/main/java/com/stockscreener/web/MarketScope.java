package com.stockscreener.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 요청이 무엇을 물었는지에서 <b>어느 시장의 달력을 쓸지</b>를 유도한다(ADR-0095).
 *
 * <p>{@link AsOfResolver}가 시장을 받게 되면서 생긴 자리다. 화면은 시장을 직접 말하지 않고
 * <b>유니버스</b>나 <b>종목</b>을 말하므로, 그 둘에서 시장을 얻는다 — {@code universe.market_code}와
 * {@code stock.market_code}가 이미 있어 새 저장 구조가 필요 없다.
 *
 * <p><b>MUST NOT: 시장 코드를 코드에 박지 않는다.</b> 「국내는 KOSPI·KOSDAQ」을 상수로 두면
 * 시장이 늘 때 이 파일이 조용히 낡는다 — 값은 나오고 새 시장만 빠진다.
 */
@Component
public class MarketScope {

    /**
     * 「전체」가 실제로 훑는 유니버스.
     *
     * <p><b>편입이 있는 것만 센다.</b> 대상을 안 고른 요청은 {@code inAnyActiveUniverse}로 도는데
     * 그 조인이 편입을 요구하므로, <b>편입 0건인 활성 유니버스는 결과에 한 종목도 못 넣는다.</b>
     * 그런데도 시장·통화 집합에는 들어가면 실행 자체가 달라진다 — 기준일이 종목 없는 시장의
     * 달력에서 나오고, 통화가 둘이 되어 <b>요청이 거절된다.</b> 실제로 테스트 DB가 그 형태다
     * (시드 유니버스가 활성이고 편입은 0건이며 시장이 셋이다).
     *
     * <p>{@code StockReader.selectableUniverses}가 화면에 제시하는 목록과 <b>같은 술어</b>다.
     * 갈리면 화면이 못 고르는 대상이 기준일과 통화를 정하게 된다.
     */
    private static final String ACTIVE_MARKETS = """
            SELECT DISTINCT u.market_code
            FROM universe u
            JOIN universe_membership m ON m.universe_code = u.code AND m.effective_to IS NULL
            WHERE u.is_active
            """;

    /** {@link #ACTIVE_MARKETS}의 통화 짝. 같은 술어를 쓰는 이유가 거기 있다. */
    private static final String ACTIVE_CURRENCIES = """
            SELECT DISTINCT mk.currency_code
            FROM universe u
            JOIN market mk ON mk.code = u.market_code
            JOIN universe_membership m ON m.universe_code = u.code AND m.effective_to IS NULL
            WHERE u.is_active
            """;

    private final JdbcTemplate jdbcTemplate;

    public MarketScope(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 스크리닝 요청의 시장 범위.
     *
     * <p><b>유니버스를 지정하면 그 유니버스의 시장 하나</b>다. 지정하지 않으면 요청이
     * {@code inAnyActiveUniverse}로 도므로 <b>활성 유니버스 전체의 시장 집합</b>이 답이다.
     *
     * <p><b>미국을 켤 때 이 갈래를 다시 본다.</b> 지금 활성 유니버스가 국내 둘뿐이라 미지정
     * 요청의 기준일이 국내 달력에서 나오지만, 미국 유니버스가 활성이 되면 이 집합에 US가 들어가
     * {@code latestIndicatorDate}의 <b>최댓값이 다시 미국 날짜가 될 수 있다.</b> 그때 필요한 것은
     * 시장별 기준일로 판정을 가르는 것이고(ADR-0093이 워치리스트에 세운 형태),
     * <b>그 결정은 미국을 켜는 단계에 속한다.</b>
     *
     * @param universeCode 요청이 고른 유니버스. {@code null}이면 활성 유니버스 전체
     * @return 시장 코드 집합. 비어 있으면 활성 유니버스가 하나도 없다는 뜻이고,
     *         {@link AsOfResolver}가 그것을 「준비되지 않음」으로 접는다
     */
    @Transactional(readOnly = true)
    public Set<String> forScreening(String universeCode) {
        List<String> codes = universeCode == null
                ? jdbcTemplate.queryForList(ACTIVE_MARKETS, String.class)
                : jdbcTemplate.queryForList(
                        "SELECT market_code FROM universe WHERE code = ?", String.class, universeCode);
        return new LinkedHashSet<>(codes);
    }

    /**
     * 그 범위의 <b>통화</b> 집합. 조건의 임계를 어느 시장 분포 기준으로 해석할지가 여기서 나온다.
     *
     * <p>축을 통화로 잡는 이유는 {@code KRW} 하나가 코스피·코스닥 둘을 덮기 때문이다 —
     * 시장 코드로 두면 시장이 늘 때마다 조건마다 목록을 고쳐야 하고, 고치지 않은 조건이
     * 새 시장까지 조용히 판정한다(ADR-0099).
     *
     * <p>둘 이상이 나오면 「전체」가 통화를 넘나든다는 뜻이고, 그때는 호출부가 대상을 고르게
     * 한다 — 한 결과에 두 시장을 섞으면 임계가 어느 쪽 기준인지 정해지지 않는다.
     *
     * @return 통화 코드 집합. 유니버스를 지정했으면 하나, 안 했으면 활성 유니버스 전체의 것
     */
    @Transactional(readOnly = true)
    public Set<String> currenciesFor(String universeCode) {
        List<String> codes = universeCode == null
                ? jdbcTemplate.queryForList(ACTIVE_CURRENCIES, String.class)
                : jdbcTemplate.queryForList("""
                        SELECT m.currency_code
                        FROM universe u JOIN market m ON m.code = u.market_code
                        WHERE u.code = ?
                        """, String.class, universeCode);
        return new LinkedHashSet<>(codes);
    }

    /**
     * 종목 하나를 보는 요청의 시장 범위.
     *
     * <p>상세·지지저항·재무 화면이 이 갈래다. <b>종목이 곧 시장이라</b> 애매함이 없다.
     *
     * <p><b>없는 종목이면 여기서 404를 던진다.</b> 처음에는 「호출부가 이미
     * {@code StockNotFoundException}으로 끝낸다」고 적었는데 <b>순서가 반대라 거짓이었다</b> —
     * 컨트롤러가 기준일을 먼저 해석하고 그 결과로 종목을 찾으므로, 빈 집합을 그대로 돌려주면
     * {@link AsOfResolver}가 <b>404가 나야 할 자리에 503</b>을 낸다("배치가 아직 안 돌았다").
     * 그 둘은 사용자가 할 수 있는 일이 다르다 — 하나는 기다리는 것이고 하나는 없는 것이다.
     * {@code ReadApiTest}가 이것을 잡았다.
     *
     * @return 그 종목의 시장 하나
     * @throws ApiExceptions.StockNotFoundException 그 id의 종목이 없다
     */
    @Transactional(readOnly = true)
    public Set<String> forStock(long stockId) {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT market_code FROM stock WHERE id = ?", String.class, stockId);
        if (codes.isEmpty()) {
            throw new ApiExceptions.StockNotFoundException(stockId);
        }
        return new LinkedHashSet<>(codes);
    }
}
