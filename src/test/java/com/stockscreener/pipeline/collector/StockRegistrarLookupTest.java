package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치가 "전 종목"을 말할 때의 대상 선정을 검증한다.
 *
 * <p>가장 중요한 것은 <b>티커 조회가 종목을 만들지 않는다</b>는 것이다.
 * {@link StockRegistrar#resolveOrCreate}는 유니버스 편입이 근거라 모르는 티커를 최소 정보로
 * 등록하는 것이 옳지만, <b>손으로 준 인자에 그 동작이 붙으면 오타 하나가 가짜 종목을 만든다.</b>
 * 그 행은 아무 에러도 내지 않고 남아 이후의 유니버스 판정에 섞인다.
 *
 * <p>두 번째는 <b>비활성 유니버스가 실제로 대상을 좁히는가</b>다(ADR-0087). 편입 행은 유니버스를
 * 꺼도 남으므로 {@code universe}를 조인하지 않으면 그 종목이 영구히 "전 종목"에 남는데,
 * 어떤 에러도 나지 않는다. <b>이 테스트가 그 조인을 실제로 건드리는 유일한 수단이다.</b>
 *
 * <p>여기서 쓰는 {@code KOSPI200}은 <b>테스트 DB에서 활성</b>이고 {@code KOSPI_TOP200}은
 * 비활성이다 — 운영과 정반대이며 그것이 정상이다(ADR-0012, {@code .claude/rules/test.md}).
 */
@JpaSliceTest
@Import({StockRegistrar.class, DataSourceRegistry.class})
class StockRegistrarLookupTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 2);

    @Autowired
    private StockRegistrar registrar;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("편입 중인 활성 종목만 낸다 — 편입 종료·비활성은 빠진다")
    void listsOnlyActiveCurrentMembers() {
        long current = member("005930", "삼성전자", true, null);
        long ended = member("035420", "NAVER", true, LocalDate.of(2026, 6, 30));
        long inactive = member("000660", "SK하이닉스", false, null);

        List<Long> ids = registrar.activeUniverseMemberIds();

        assertThat(ids).contains(current).doesNotContain(ended, inactive);
    }

    @Test
    @DisplayName("한 종목이 두 지수에 편입돼 있어도 한 번만 낸다 — 중복은 곧 쿼터 낭비다")
    void deduplicatesAcrossUniverses() {
        long stockId = member("005930", "삼성전자", true, null);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('KOSDAQ150', ?, ?)
                """, stockId, FROM);

        assertThat(registrar.activeUniverseMemberIds()).filteredOn(id -> id == stockId).hasSize(1);
    }

    @Test
    @DisplayName("티커로 고르면 편입 종목과의 교집합만 낸다")
    void narrowsToIntersection() {
        long member = member("005930", "삼성전자", true, null);
        long notMember = insertStock("035420", "NAVER", true);

        List<Long> ids = registrar.activeUniverseMemberIdsByTicker(List.of("005930", "035420"));

        assertThat(ids).containsExactly(member);
        assertThat(ids).doesNotContain(notMember);
    }

    @Test
    @DisplayName("IMPORTANT — 비활성 유니버스에만 편입된 종목은 대상이 아니다(ADR-0087)")
    void excludesMembersOfInactiveUniversesOnly() {
        long onlyInactive = insertStock("100001", "비활성유니버스전용", true);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('KOSPI_TOP200', ?, ?)
                """, onlyInactive, FROM);
        long alsoActive = member("005930", "삼성전자", true, null);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('KOSPI_TOP200', ?, ?)
                """, alsoActive, FROM);

        List<Long> ids = registrar.activeUniverseMemberIds();

        // 비활성 유니버스에만 있는 종목은 빠지고, 활성에도 걸린 종목은 남는다.
        assertThat(ids).contains(alsoActive).doesNotContain(onlyInactive);
        assertThat(registrar.activeUniverseMemberIdsByTicker(List.of("100001", "005930")))
                .containsExactly(alsoActive);
    }

    @Test
    @DisplayName("IMPORTANT — 수집하기로 하지 않은 시장은 활성 유니버스에 있어도 대상이 아니다")
    void excludesMarketsWeDoNotCollect() {
        long krStock = member("005930", "삼성전자", true, null);
        long outsideStock = memberOfUncollectedMarket();

        assertThat(registrar.activeUniverseMemberIds())
                .as("수집 어댑터가 하나도 없는 시장이라 종목마다 실패하게 된다")
                .contains(krStock).doesNotContain(outsideStock);
        assertThat(registrar.activeUniverseMemberIdsByTicker(List.of("005930", "XX0001")))
                .containsExactly(krStock);
    }

    @Test
    @DisplayName("IMPORTANT — 이력이 없어도 두 조건 밖의 종목은 초기 적재 대상이 아니다(ADR-0087)")
    void needingHistoryAppliesBothConditions() {
        // 셋 다 price_daily가 비어 있다. 갈리는 것은 유니버스 활성 여부와 시장뿐이다.
        long krMember = member("005930", "삼성전자", true, null);

        long onlyInactiveUniverse = insertStock("100002", "비활성유니버스전용", true);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('KOSPI_TOP200', ?, ?)
                """, onlyInactiveUniverse, FROM);

        long outsideStock = memberOfUncollectedMarket();

        List<Long> ids = registrar.activeUniverseMemberIdsNeedingHistory();

        assertThat(ids).contains(krMember);
        assertThat(ids)
                .as("비활성 유니버스에만 편입된 종목은 유니버스를 다시 내려도 영구히 남았다")
                .doesNotContain(onlyInactiveUniverse);
        assertThat(ids)
                .as("수집 어댑터가 없는 시장은 종목마다 NOT_FOUND로 실패한다")
                .doesNotContain(outsideStock);
    }

    @Test
    @DisplayName("봉이 하나라도 있으면 초기 적재 대상에서 빠진다 — 재개 가능성의 근거다")
    void needingHistorySkipsStocksThatAlreadyHaveBars() {
        long loaded = member("005930", "삼성전자", true, null);
        long empty = member("035420", "NAVER", true, null);
        jdbcTemplate.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close,
                                         adj_close, volume)
                VALUES (?, ?, 1, 1, 1, 1, 1, 0)
                """, loaded, FROM);

        assertThat(registrar.activeUniverseMemberIdsNeedingHistory())
                .contains(empty).doesNotContain(loaded);
    }

    @Test
    @DisplayName("모르는 티커로 조회해도 종목을 만들지 않는다 — resolveOrCreate와 갈라놓은 이유다")
    void neverCreatesStockRows() {
        int before = stockCount();

        assertThat(registrar.activeUniverseMemberIdsByTicker(List.of("999999"))).isEmpty();

        assertThat(stockCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("IMPORTANT — 손으로 고른 유니버스는 비활성이어도 낸다. 다만 시장 게이트는 남는다")
    void oneUniverseIgnoresActivenessButNotTheMarketGate() {
        long inactiveMember = insertStock("100003", "비활성유니버스종목", true);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('KOSPI_TOP200', ?, ?)
                """, inactiveMember, FROM);
        long outsideStock = memberOfUncollectedMarket();

        assertThat(registrar.oneUniverseMemberIds("KOSPI_TOP200", List.of()))
                .as("이 경로가 없으면 비활성 유니버스는 적재할 방법이 없다")
                .contains(inactiveMember);
        assertThat(registrar.oneUniverseMemberIds("XX_ALL", List.of()))
                .as("CollectedMarkets가 이 경로의 유일한 게이트다 — 없는 시장이면 비어야 한다")
                .doesNotContain(outsideStock)
                .isEmpty();
        assertThat(registrar.oneUniverseMemberIds("KOSPI_TOP200", List.of("100003")))
                .containsExactly(inactiveMember);
        assertThat(registrar.oneUniverseMemberIds("KOSPI_TOP200", List.of("999999")))
                .as("교집합이 비면 호출부가 예외로 끝낸다").isEmpty();
    }

    @Test
    @DisplayName("빈 목록은 빈 결과다 — IN () 조립으로 문법 오류가 나면 안 된다")
    void handlesEmptyTickerList() {
        assertThat(registrar.activeUniverseMemberIdsByTicker(List.of())).isEmpty();
    }

    /**
     * <b>수집하기로 하지 않은 시장</b>의 활성 유니버스 편입 종목.
     *
     * <p>시장을 테스트가 직접 만드는 이유는, {@code CollectedMarkets.ALL}이 지금 존재하는
     * 시장 코드를 <b>전부</b> 담고 있어 실재하는 시장으로는 이 갈래를 만들 수 없기 때문이다.
     * 여기서 실재 시장을 쓰면 목록이 자랄 때마다 이 테스트가 <b>의도한 동작에 붉어지고</b>,
     * 그러면 가드가 지워진다 — 실제로 미국을 더하면서 그렇게 될 뻔했다.
     */
    private long memberOfUncollectedMarket() {
        jdbcTemplate.update("""
                INSERT INTO market (code, country_code, currency_code, timezone)
                VALUES ('XX', 'XX', 'XXX', 'UTC') ON CONFLICT (code) DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO universe (code, name, market_code, is_active)
                VALUES ('XX_ALL', '수집 대상 밖', 'XX', true) ON CONFLICT (code) DO NOTHING
                """);
        long stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('XX', 'XX0001', '범위 밖 종목')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('XX_ALL', ?, ?)
                """, stockId, FROM);
        return stockId;
    }

    private long member(String ticker, String name, boolean active, LocalDate effectiveTo) {
        long stockId = insertStock(ticker, name, active);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to)
                VALUES ('KOSPI200', ?, ?, ?)
                """, stockId, FROM, effectiveTo);
        return stockId;
    }

    private long insertStock(String ticker, String name, boolean active) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, is_active)
                VALUES ('KOSPI', ?, ?, ?) RETURNING id
                """, Long.class, ticker, name, active);
    }

    private int stockCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM stock", Integer.class);
    }
}
