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
                VALUES ('KOSDAQ_TOP150', ?, ?)
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
    @DisplayName("모르는 티커로 조회해도 종목을 만들지 않는다 — resolveOrCreate와 갈라놓은 이유다")
    void neverCreatesStockRows() {
        int before = stockCount();

        assertThat(registrar.activeUniverseMemberIdsByTicker(List.of("999999"))).isEmpty();

        assertThat(stockCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("빈 목록은 빈 결과다 — IN () 조립으로 문법 오류가 나면 안 된다")
    void handlesEmptyTickerList() {
        assertThat(registrar.activeUniverseMemberIdsByTicker(List.of())).isEmpty();
    }

    private long member(String ticker, String name, boolean active, LocalDate effectiveTo) {
        long stockId = insertStock(ticker, name, active);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to)
                VALUES ('KOSPI_TOP200', ?, ?, ?)
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
