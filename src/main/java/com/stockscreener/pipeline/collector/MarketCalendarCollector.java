package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.MarketCalendarDataSource;
import com.stockscreener.pipeline.datasource.MarketSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * 거래소 세션 달력을 받아 저장한다(ADR-0091).
 *
 * <p>{@link MarketIndexCollector}와 같은 자리다 — 대상이 종목이 아니라 <b>거래소</b>이고,
 * 실패하면 그 거래소 전 종목의 결측 판정이 함께 판정불가가 된다.
 *
 * <p><b>MUST: 창 안에서 소스가 안 준 세션 행을 지운다.</b> UPSERT만 두면 표가 append-only가
 * 되어, 소스가 나중에 거둬들인 세션이 <b>영구히 개장일로 남는다.</b> 그 행은 「그날 장이 섰다」로
 * 읽히므로 봉이 없는 것이 결측으로 뒤집힌다. 창 <b>밖</b>은 건드리지 않는다 — 이번에 안 물어본
 * 구간을 지우면 적재 창이 조용히 좁아진다.
 */
@Component
public class MarketCalendarCollector {

    private static final Logger log = LoggerFactory.getLogger(MarketCalendarCollector.class);

    /**
     * IMPORTANT — {@code ON CONFLICT}의 컬럼 순서는 PK 순서 {@code (exchange_code, session_date)}와
     * 같아야 한다. 그리고 {@code IS DISTINCT FROM} 가드를 지우지 않는다 — <b>그 가드가 있어야
     * 「두 번째 실행이 아무것도 바꾸지 않는다」가 갱신 행 0으로 측정된다.</b> 없으면 재실행이
     * 매번 전량을 신고해 멱등이 성립하는지 알 수 없게 된다({@code MarketIndexCollector}와 같다).
     */
    private static final String UPSERT = """
            INSERT INTO market_calendar
                (exchange_code, session_date, core_start, core_end, updated_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (exchange_code, session_date) DO UPDATE SET
                core_start = EXCLUDED.core_start,
                core_end   = EXCLUDED.core_end,
                updated_at = now()
            WHERE market_calendar.core_start IS DISTINCT FROM EXCLUDED.core_start
               OR market_calendar.core_end   IS DISTINCT FROM EXCLUDED.core_end
            """;

    private static final int BATCH_SIZE = 500;

    private final DataSourceRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    public MarketCalendarCollector(DataSourceRegistry registry, JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 한 거래소의 {@code [from, to]} 구간을 받아 저장한다.
     *
     * @return 값이 실제로 달라진 행 수 + 지운 행 수. 같은 답을 다시 받으면 0이며 그것이 멱등의 증거다
     */
    @Transactional(rollbackFor = Exception.class)
    public long collect(String exchangeCode, LocalDate from, LocalDate to)
            throws DataSourceException {
        MarketCalendarDataSource source = registry.marketCalendarSource(exchangeCode);
        List<MarketSession> sessions = source.fetchSessions(exchangeCode, from, to);

        long written = upsert(exchangeCode, sessions);
        long removed = removeWithdrawn(exchangeCode, from, to, sessions);

        log.info("세션 달력을 적재했다. exchange={} {}~{} 수신={}일 반영={}행 철회={}행",
                exchangeCode, from, to, sessions.size(), written, removed);
        return written + removed;
    }

    private long upsert(String exchangeCode, List<MarketSession> sessions) {
        if (sessions.isEmpty()) {
            // 휴일만 든 구간이 실재한다. 0건은 오류가 아니다 — 아래 철회 정리는 그래도 돈다.
            return 0L;
        }
        int[][] results = jdbcTemplate.batchUpdate(UPSERT, sessions, BATCH_SIZE, (ps, session) -> {
            ps.setString(1, session.exchangeCode());
            ps.setObject(2, session.sessionDate());
            ps.setTimestamp(3, Timestamp.from(session.coreStart()));
            ps.setTimestamp(4, Timestamp.from(session.coreEnd()));
        });

        long written = 0;
        for (int[] batch : results) {
            for (int updated : batch) {
                // 드라이버가 SUCCESS_NO_INFO(-2)를 주는 경우가 있어 음수를 1행으로 센다.
                written += updated < 0 ? 1 : updated;
            }
        }
        return written;
    }

    /**
     * 이번에 받은 창 안에서 소스가 더 이상 세션이라 하지 않는 날을 지운다.
     *
     * <p>MUST: 창을 인자로 좁힌다. {@code session_date}만 보고 지우면 이번에 안 물어본 구간까지
     * 사라져 <b>적재 창이 조용히 좁아지고</b>, 그 좁아짐은 판정불가로만 드러난다.
     */
    private long removeWithdrawn(String exchangeCode, LocalDate from, LocalDate to,
                                 List<MarketSession> sessions) {
        List<LocalDate> kept = sessions.stream().map(MarketSession::sessionDate).toList();
        if (kept.isEmpty()) {
            return jdbcTemplate.update("""
                    DELETE FROM market_calendar
                     WHERE exchange_code = ? AND session_date BETWEEN ? AND ?
                    """, exchangeCode, from, to);
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(kept.size(), "?"));
        Object[] args = new Object[kept.size() + 3];
        args[0] = exchangeCode;
        args[1] = from;
        args[2] = to;
        for (int i = 0; i < kept.size(); i++) {
            args[i + 3] = kept.get(i);
        }
        return jdbcTemplate.update("""
                DELETE FROM market_calendar
                 WHERE exchange_code = ? AND session_date BETWEEN ? AND ?
                   AND session_date NOT IN (%s)
                """.formatted(placeholders), args);
    }
}
