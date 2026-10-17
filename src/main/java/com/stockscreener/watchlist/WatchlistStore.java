package com.stockscreener.watchlist;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code watchlist_item}을 읽고 쓴다.
 *
 * <p>JPA 엔티티를 두지 않는 것은 {@code ScreenStore}·{@code AllowedEmailService}와 같은
 * 판단이다 — 동적 쿼리가 없고, 고정 형태 문장 몇 개뿐이며, 도메인 타입({@link WatchlistItem})이
 * 이미 있다. 엔티티를 만들면 한 개념에 객체 모델이 둘이 되고 그 사이 변환이 새 버그 표면이 된다.
 *
 * <p>목록 조회만 종목·화면 이름을 조인해 가져온다. 항목마다 종목을 따로 물으면 N+1이고,
 * 워치리스트는 그 화면의 전부라 반드시 함께 필요하다.
 */
@Repository
public class WatchlistStore {

    private final JdbcTemplate jdbcTemplate;

    public WatchlistStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────

    /**
     * 화면에 그릴 목록. 종목명과 알림 조건 화면 이름이 함께 온다.
     *
     * <p>{@code LEFT JOIN screen}인 이유는 {@code screen_id}가 nullable이기 때문이다.
     * INNER로 두면 <b>알림 없이 추적만 하는 항목이 목록에서 통째로 사라진다.</b>
     */
    @Transactional(readOnly = true)
    public List<WatchlistRow> listRows(long userId) {
        return jdbcTemplate.query("""
                SELECT w.id, w.user_id, w.stock_id, w.screen_id, w.memo,
                       w.last_satisfied, w.last_notified_at, w.created_at,
                       %s AS ticker, s.name AS stock_name, s.market_code,
                       sc.name AS screen_name, sc.screen_type
                FROM watchlist_item w
                JOIN stock s ON s.id = w.stock_id
                LEFT JOIN screen sc ON sc.id = w.screen_id
                WHERE w.user_id = ?
                ORDER BY w.created_at DESC, w.id DESC
                """.formatted(com.stockscreener.stock.StockReader.DISPLAY_TICKER), ROW_MAPPER, userId);
    }

    /**
     * 항목 하나.
     *
     * <p>{@code userId}를 함께 받는 이유는 소유권 검사를 <b>쿼리에서</b> 하기 위해서다.
     * 조회 후 자바에서 비교하면 어느 호출 경로가 그 비교를 빠뜨렸는지 드러나지 않는다.
     * 남의 항목은 여기서 비어 있음으로 나오고, 호출부가 그것을 404로 옮긴다.
     */
    @Transactional(readOnly = true)
    public Optional<WatchlistItem> findOwned(long id, long userId) {
        List<WatchlistItem> rows = jdbcTemplate.query(
                SELECT_ITEM + " WHERE id = ? AND user_id = ?", ITEM_MAPPER, id, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 이 사용자가 이 종목을 이미 담았는가. 화면의 담기 버튼 상태 판정이 쓴다. */
    @Transactional(readOnly = true)
    public Optional<WatchlistItem> findByStock(long userId, long stockId) {
        List<WatchlistItem> rows = jdbcTemplate.query(
                SELECT_ITEM + " WHERE user_id = ? AND stock_id = ?", ITEM_MAPPER, userId, stockId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 알림 조건이 걸린 항목 전체. 일일 배치가 판정 대상으로 읽는다.
     *
     * <p>{@code screen_id IS NOT NULL}로 좁히는 것이 {@code idx_watchlist_alerting}
     * 부분 인덱스를 직격한다. 조건이 없는 항목까지 읽어 자바에서 거르면 그 인덱스가
     * 있으나 마나가 된다.
     *
     * <p>사용자 순으로 정렬하는 이유는 <b>사용자당 알림 한 통</b> 때문이다. 호출부가
     * 사용자별로 묶어야 하는데, 정렬되어 오면 그 묶기가 한 번 훑는 것으로 끝난다.
     */
    @Transactional(readOnly = true)
    public List<WatchlistItem> findAlerting() {
        return jdbcTemplate.query(
                SELECT_ITEM + " WHERE screen_id IS NOT NULL ORDER BY user_id, id", ITEM_MAPPER);
    }

    /**
     * 종목 → 시장 코드.
     *
     * <p>판정 기준일이 <b>시장마다 다르기 때문에</b> 필요하다({@code DailyUpdateJob}).
     * {@link WatchlistItem}에 시장을 싣지 않는 이유는 그것이 워치리스트의 사실이 아니라
     * 종목의 사실이어서다 — 실으면 종목이 바뀔 때 두 곳이 갈린다.
     */
    public Map<Long, String> marketsOf(List<Long> stockIds) {
        if (stockIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(stockIds.size(), "?"));
        Map<Long, String> markets = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, market_code FROM stock WHERE id IN (" + placeholders + ')',
                rs -> { markets.put(rs.getLong("id"), rs.getString("market_code")); },
                stockIds.toArray());
        return markets;
    }

    /**
     * 시장 코드 → 통화. {@code market}은 3행 규모라 통째로 읽는다.
     *
     * <p>{@link #marketsOf}와 짝이다 — 그쪽이 종목의 시장을, 이쪽이 그 시장의 통화를 준다.
     * 조건의 임계가 어느 시장 분포에서 나왔는지를 통화로 표현하므로(ADR-0099) 판정 전에
     * 이것이 필요하다.
     */
    public Map<String, String> currencyByMarket() {
        Map<String, String> currencies = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT code, currency_code FROM market",
                rs -> { currencies.put(rs.getString("code"), rs.getString("currency_code")); });
        return currencies;
    }

    // ── 쓰기 ──────────────────────────────────────────────────────────────

    /**
     * 항목을 담는다.
     *
     * @throws AlreadyWatchedException {@code uq_watchlist_user_stock} 위반. 같은 종목을
     *                                 두 번 담는 것은 사용자 입력 문제이지 서버 버그가 아니다.
     */
    @Transactional
    public WatchlistItem create(long userId, long stockId, Long screenId, String memo) {
        try {
            long id = jdbcTemplate.queryForObject("""
                    INSERT INTO watchlist_item (user_id, stock_id, screen_id, memo, created_at)
                    VALUES (?, ?, ?, ?, now())
                    RETURNING id
                    """, Long.class, userId, stockId, screenId, memo);
            return new WatchlistItem(id, userId, stockId, screenId, memo, null, null);
        } catch (DuplicateKeyException e) {
            throw new AlreadyWatchedException(stockId, e);
        }
    }

    /**
     * 알림 조건과 메모를 바꾼다.
     *
     * <p>IMPORTANT — 조건이 바뀌면 {@code last_satisfied}를 NULL로 되돌린다. 되돌리지 않으면
     * <b>이전 조건의 판정 결과가 새 조건의 직전 상태로 읽힌다.</b> 참이던 조건을 다른 조건으로
     * 바꿨는데 새 조건도 참이면, 엣지 트리거는 "참→참"으로 보고 침묵한다 — 사용자가 방금
     * 설정한 알림이 오지 않는다.
     *
     * @return 소유자가 아니거나 없으면 {@code false}
     */
    @Transactional
    public boolean update(long id, long userId, Long screenId, String memo) {
        return jdbcTemplate.update("""
                UPDATE watchlist_item
                   SET screen_id = ?,
                       memo = ?,
                       last_satisfied = CASE WHEN screen_id IS DISTINCT FROM ? THEN NULL
                                             ELSE last_satisfied END
                 WHERE id = ? AND user_id = ?
                """, screenId, memo, screenId, id, userId) > 0;
    }

    /** @return 소유자가 아니거나 없으면 {@code false} */
    @Transactional
    public boolean delete(long id, long userId) {
        return jdbcTemplate.update(
                "DELETE FROM watchlist_item WHERE id = ? AND user_id = ?", id, userId) > 0;
    }

    /**
     * 판정 결과를 기록한다. 알린 경우에만 {@code last_notified_at}을 갱신한다.
     *
     * <p>둘을 한 문장으로 쓰는 이유는 <b>따로 쓰면 어긋날 수 있기 때문</b>이다. 알림은 보냈는데
     * 상태 기록이 실패하면 다음 배치가 같은 알림을 또 보내고, 반대면 알림 없이 상태만 참이
     * 되어 그 사건은 영영 통지되지 않는다.
     */
    @Transactional
    public void recordEvaluation(long id, boolean satisfied, boolean notified) {
        jdbcTemplate.update("""
                UPDATE watchlist_item
                   SET last_satisfied = ?,
                       last_notified_at = CASE WHEN ? THEN now() ELSE last_notified_at END
                 WHERE id = ?
                """, satisfied, notified, id);
    }

    /** 이 화면을 알림 조건으로 쓰는 항목 수. 화면 삭제가 막혔을 때 사용자에게 이유를 준다. */
    @Transactional(readOnly = true)
    public int countByScreen(long screenId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM watchlist_item WHERE screen_id = ?", Integer.class, screenId);
        return count == null ? 0 : count;
    }

    // ── 매핑 ──────────────────────────────────────────────────────────────

    private static final String SELECT_ITEM = """
            SELECT id, user_id, stock_id, screen_id, memo, last_satisfied, last_notified_at
            FROM watchlist_item
            """;

    private static final RowMapper<WatchlistItem> ITEM_MAPPER = (rs, rowNum) -> mapItem(rs);

    private static final RowMapper<WatchlistRow> ROW_MAPPER = (rs, rowNum) -> new WatchlistRow(
            mapItem(rs),
            rs.getString("ticker"),
            rs.getString("stock_name"),
            rs.getString("market_code"),
            rs.getString("screen_name"),
            rs.getString("screen_type"),
            instantOf(rs, "created_at"));

    private static WatchlistItem mapItem(ResultSet rs) throws SQLException {
        // getObject(…, Class)를 쓴다. getLong + wasNull()은 wasNull이 "직전에 읽은 컬럼"을
        // 가리키므로 생성자 인자 안에서 쓰면 그 사이 읽은 다른 컬럼을 보게 되고,
        // 알림 조건 없는 항목의 screenId가 null이 아니라 0이 된다 — 0은 아무 화면도 아니다.
        return new WatchlistItem(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("stock_id"),
                rs.getObject("screen_id", Long.class),
                rs.getString("memo"),
                rs.getObject("last_satisfied", Boolean.class),
                instantOf(rs, "last_notified_at"));
    }

    private static java.time.Instant instantOf(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 목록 한 줄. 항목에 표시용 정보를 얹은 것이다.
     *
     * @param screenType {@code null}이면 알림 조건이 없다. 프리셋인지 내 화면인지 화면이
     *                   구분해 보여줘야 "이건 왜 못 고치지"가 생기지 않는다.
     */
    public record WatchlistRow(
            WatchlistItem item,
            String ticker,
            String stockName,
            String marketCode,
            String screenName,
            String screenType,
            java.time.Instant createdAt
    ) {
    }

    /** {@code uq_watchlist_user_stock} 위반. HTTP 계층이 409로 옮긴다. */
    public static class AlreadyWatchedException extends RuntimeException {
        private final long stockId;

        public AlreadyWatchedException(long stockId, Throwable cause) {
            super("이미 워치리스트에 있는 종목이다: stockId=" + stockId, cause);
            this.stockId = stockId;
        }

        public long stockId() {
            return stockId;
        }
    }
}
