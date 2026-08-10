package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.SecurityDescriptor;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 티커를 {@code stock.id}로 해석하고, 없으면 종목 행을 만든다.
 *
 * <p>유니버스에 새로 편입된 종목은 티커만 알려진 상태다. {@code universe_membership}이
 * {@code stock_id}를 FK로 참조하므로 리밸런싱보다 먼저 이 해석이 일어나야 한다.
 *
 * <p>{@link UniverseReconciler}와 분리한 이유는 책임이 다르기 때문이다. 이쪽은 종목 마스터를
 * 다루고 저쪽은 편입 구간을 다룬다. 합치면 마스터 소스의 실패가 유니버스 갱신을 되돌린다.
 */
@Component
public class StockRegistrar {

    private static final Logger log = LoggerFactory.getLogger(StockRegistrar.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSourceRegistry registry;

    public StockRegistrar(JdbcTemplate jdbcTemplate, DataSourceRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
    }

    /**
     * 티커 목록을 stock_id 목록으로 해석한다. 없는 종목은 만든다.
     *
     * <p>마스터 소스가 없거나 티커를 모르면 <b>최소 정보로 행을 만든다.</b> 이름을 티커로
     * 대신하고 로그를 남긴다. 종목을 건너뛰지 않는 이유는, 그러면 그 종목이 유니버스에서
     * 조용히 빠져 스크리닝 결과에서 사라지기 때문이다 — 이름이 없는 것보다 나쁘다.
     */
    @Transactional
    public List<Long> resolveOrCreate(String marketCode, List<String> tickers) {
        return tickers.stream()
                .map(ticker -> resolveOne(marketCode, ticker))
                .toList();
    }

    private long resolveOne(String marketCode, String ticker) {
        Long existing = findId(marketCode, ticker);
        if (existing != null) {
            return existing;
        }

        SecurityDescriptor descriptor = describe(marketCode, ticker);
        jdbcTemplate.update("""
                INSERT INTO stock (market_code, ticker, name, name_en, sector, listed_on,
                                   is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, true, now(), now())
                ON CONFLICT (market_code, ticker) DO NOTHING
                """,
                marketCode, ticker, descriptor.name(), descriptor.nameEn(),
                descriptor.sector(), descriptor.listedOn());

        Long created = findId(marketCode, ticker);
        if (created == null) {
            throw new IllegalStateException(
                    "종목을 만들지도 찾지도 못했다: " + marketCode + '/' + ticker);
        }
        log.info("신규 종목을 등록했다. {}/{} name={}", marketCode, ticker, descriptor.name());
        return created;
    }

    /**
     * 마스터 소스에서 종목 정보를 가져온다. 실패해도 예외를 던지지 않는다.
     *
     * <p>MUST NOT: 조용히 넘기지 않는다. 최소 정보로 대체하되 반드시 로그를 남긴다 —
     * 이름이 티커와 같은 종목이 쌓이면 마스터 소스가 동작하지 않는다는 신호다.
     */
    private SecurityDescriptor describe(String marketCode, String ticker) {
        try {
            Optional<SecurityDescriptor> found =
                    registry.securityMasterSource(marketCode).findByTicker(marketCode, ticker);
            if (found.isPresent()) {
                return found.get();
            }
            log.warn("마스터 소스가 종목을 모른다. {}/{} — 최소 정보로 등록한다.", marketCode, ticker);
        } catch (DataSourceException e) {
            log.warn("종목 마스터 조회에 실패했다. {}/{} kind={} — 최소 정보로 등록한다. {}",
                    marketCode, ticker, e.kind(), e.getMessage());
        }
        return new SecurityDescriptor(marketCode, ticker, ticker, null, null, null, null);
    }

    private Long findId(String marketCode, String ticker) {
        List<Long> found = jdbcTemplate.queryForList(
                "SELECT id FROM stock WHERE market_code = ? AND ticker = ?",
                Long.class, marketCode, ticker);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 지금 편입 중인 활성 종목.
     *
     * <p>배치가 "전 종목"을 말할 때의 정본이다. {@code DISTINCT}인 이유는 한 종목이 여러 지수에
     * 동시에 편입될 수 있기 때문이며, 중복을 두면 같은 종목을 두 번 처리해 쿼터를 낭비한다.
     *
     * <p>MUST NOT: 이 질의를 잡마다 다시 적지 않는다. 실제로 사본이 둘이었고, 하나만 고치면
     * 두 잡이 서로 다른 "전 종목"을 처리하는데 <b>어느 쪽도 에러를 내지 않는다.</b>
     */
    public List<Long> activeUniverseMemberIds() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                WHERE m.effective_to IS NULL AND s.is_active
                ORDER BY m.stock_id
                """, Long.class);
    }

    /**
     * 티커로 편입 종목을 골라낸다. <b>없는 종목을 만들지 않는다.</b>
     *
     * <p>{@link #resolveOrCreate}와 갈라놓은 이유가 그것이다 — 저쪽은 유니버스 편입이 근거라
     * 모르는 티커를 최소 정보로 등록하는 것이 옳지만, <b>손으로 준 인자에 그 동작을 붙이면
     * 오타 하나가 가짜 {@code stock} 행을 만들고 마스터 소스까지 호출한다.</b>
     *
     * <p>시장 코드를 요구하지 않는다. 국내 티커는 6자리 숫자로 시장 간에 겹치지 않으며,
     * 손으로 고르는 경로에서 시장까지 적게 하면 실수만 는다.
     *
     * @return 편입 중인 활성 종목과의 <b>교집합</b>. 호출부가 개수 차이로 오타를 판별한다.
     */
    public List<Long> activeUniverseMemberIdsByTicker(List<String> tickers) {
        if (tickers.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(tickers.size(), "?"));
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                WHERE m.effective_to IS NULL AND s.is_active AND s.ticker IN (%s)
                ORDER BY m.stock_id
                """.formatted(placeholders), Long.class, tickers.toArray());
    }

    /**
     * 배치가 처리할 종목의 참조를 만든다.
     *
     * <p>{@code IN} 절을 문자열로 조립하는 이유는 {@code setArray}가 드라이버별 타입 이름에
     * 의존해 이식성이 낮기 때문이다. 값은 바인드 파라미터로 나가므로 주입 위험은 없다 —
     * 자리표시자만 개수만큼 만든다.
     */
    public List<SecurityRef> toSecurityRefs(List<Long> stockIds) {
        if (stockIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(stockIds.size(), "?"));
        return jdbcTemplate.query(
                "SELECT id, market_code, ticker FROM stock WHERE id IN (" + placeholders + ") ORDER BY id",
                (rs, rowNum) -> new SecurityRef(
                        rs.getLong("id"), rs.getString("market_code"), rs.getString("ticker")),
                stockIds.toArray());
    }
}
