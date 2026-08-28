package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.CollectedMarkets;
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
                                   listing_exchange, is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, true, now(), now())
                ON CONFLICT (market_code, ticker) DO NOTHING
                """,
                marketCode, ticker, descriptor.name(), descriptor.nameEn(),
                descriptor.sector(), descriptor.listedOn(), descriptor.listingExchange());

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
        return new SecurityDescriptor(marketCode, ticker, ticker, null, null, null, null, null);
    }

    /**
     * {@link CollectedMarkets#ALL}을 정렬해 배열로. 정렬하는 이유는 <b>질의 문자열을 안정시켜</b>
     * 실행 계획 캐시와 로그 대조가 실행마다 흔들리지 않게 하려는 것이다.
     */
    private static Object[] collectedMarkets() {
        return CollectedMarkets.ALL.stream().sorted().toArray();
    }

    private static String collectedMarketPlaceholders() {
        return String.join(",", java.util.Collections.nCopies(CollectedMarkets.ALL.size(), "?"));
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
     *
     * <p><b>MUST: {@code universe.is_active}를 조인한다</b>(ADR-0087). 편입 행은 유니버스의
     * 활성 여부와 무관하게 남으므로, 조인하지 않으면 <b>비활성 유니버스에만 편입된 종목이
     * 영구히 "전 종목"에 남는다</b> — 유니버스를 다시 내려도 빠지지 않고, 되돌리려 해도
     * {@code UniverseReconciler}의 대량 제외 가드가 정확히 그때 거부한다.
     *
     * <p>그 상태가 무엇을 하는지는 소스가 없는 시장을 켜 보면 드러난다. 수집은 종목마다
     * {@code NOT_FOUND}로 실패해 <b>부분 실패 알림이 상시화되고 진짜 장애가 묻히며</b>,
     * {@code CorporateActionJob}처럼 <b>루프 앞에서 시장별 소스를 먼저 해석하는 잡</b>은
     * 다른 시장까지 통째로 멈춘다.
     *
     * <p>{@code PercentileCalculator}와 {@code StockReader.selectableUniverses}는 처음부터
     * 이 조인을 갖고 있었다. 여기 없던 것이 비대칭이었다.
     *
     * <p><b>MUST: {@link CollectedMarkets}로도 좁힌다.</b> 유니버스에 편입하는 것과 데이터를
     * 모으는 것은 다르다 — 미국은 편입까지만 열려 있고 수집 어댑터가 하나도 없어서, 대상에
     * 넣으면 종목마다 실패한다. 활성 유니버스 조인만으로는 이것을 막지 못한다: <b>적재하려면
     * 그 유니버스를 켜야 하고, 켜는 순간 편입 종목이 전부 여기 들어온다.</b>
     */
    public List<Long> activeUniverseMemberIds() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE m.effective_to IS NULL AND s.is_active
                  AND s.market_code IN (%s)
                ORDER BY m.stock_id
                """.formatted(collectedMarketPlaceholders()), Long.class, collectedMarkets());
    }

    /**
     * 업종이 아직 비어 있는 편입 종목.
     *
     * <p>{@link #activeUniverseMemberIds()} 옆에 두는 이유는 그 javadoc이 적은 것과 같다 —
     * "편입 중인 활성 종목"의 정의가 잡마다 갈리면 <b>어느 쪽도 에러를 내지 않는다.</b>
     * 여기는 그 정의에 결손 조건 하나만 더한 것이며, 정의 자체는 위와 같은 자리에서 온다.
     */
    public List<Long> activeUniverseMemberIdsWithoutIndustry() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE m.effective_to IS NULL AND s.is_active AND s.industry_code IS NULL
                  AND s.market_code IN (%s)
                ORDER BY m.stock_id
                """.formatted(collectedMarketPlaceholders()), Long.class, collectedMarkets());
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
        Object[] args = java.util.stream.Stream
                .concat(tickers.stream(), java.util.Arrays.stream(collectedMarkets()))
                .toArray();
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE m.effective_to IS NULL AND s.is_active AND s.ticker IN (%s)
                  AND s.market_code IN (%s)
                ORDER BY m.stock_id
                """.formatted(placeholders, collectedMarketPlaceholders()), Long.class, args);
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
