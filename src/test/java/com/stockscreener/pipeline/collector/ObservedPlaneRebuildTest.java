package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.collector.UniverseLedgerRestorer.DatedMembers;
import com.stockscreener.pipeline.collector.UniverseLedgerRestorer.Plan;
import com.stockscreener.pipeline.collector.UniverseLedgerRestorer.RebuildResult;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관측 평면 다시 짓기(탐색 {@code explore/universe/OBSERVED-PLANE-PLAN-1023.md} v1.1)의 계약.
 *
 * <p>요점 셋: 열린 행은 하나도 안 지운다({@code created_at}이 공시 소비 지연 · 따라잡기 시계다) · L에 처음 든 종목이 {@code [L, L)}을
 * 만들지 않는다 · 다시 지은 뒤 같은 기준일 리밸런싱이 빠지는 행을 닫지 않고 지운다.
 */
@JpaSliceTest
@Import({UniverseLedgerRestorer.class, UniverseReconciler.class})
class ObservedPlaneRebuildTest {

    private static final String UNIVERSE = "KOSPI200";
    private static final String OTHER = "KOSDAQ150";
    private static final LocalDate OLD_FLOOR = LocalDate.of(2026, 8, 31);
    private static final LocalDate W1 = LocalDate.of(2026, 9, 4);
    private static final LocalDate W2 = LocalDate.of(2026, 9, 11);
    private static final LocalDate W3 = LocalDate.of(2026, 9, 18);
    private static final LocalDate L = LocalDate.of(2026, 9, 25);
    private static final LocalDate OLD_CREATED = LocalDate.of(2026, 8, 31);

    @Autowired
    private UniverseLedgerRestorer restorer;

    @Autowired
    private UniverseReconciler reconciler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> ids = new ArrayList<>();

    @BeforeEach
    void insertStocks() {
        for (int i = 0; i < 8; i++) {
            ids.add(jdbcTemplate.queryForObject("""
                    INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?) RETURNING id
                    """, Long.class, "10000" + i, "종목" + i));
        }
    }

    @Test
    @DisplayName("이어지는 열린 행은 id · created_at을 그대로 두고 시작일만 L로 옮긴다 — 복원 구간이 L에서 붙는다")
    void continuingOpenRowKeepsIdentityAndAbutsRestored() {
        long rowId = observed(UNIVERSE, ids.get(0), OLD_FLOOR, null);

        rebuild(List.of(point(W1, 0), point(W2, 0)), point(L, 0));

        assertThat(intervalsOf(UNIVERSE, ids.get(0))).containsExactly("RESTORED " + W1 + "~" + L, "OBSERVED " + L + "~열림");
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM universe_membership WHERE effective_to IS NULL AND stock_id = ?",
                Long.class, ids.get(0))).isEqualTo(rowId);
        assertThat(jdbcTemplate.queryForObject("SELECT created_at::date FROM universe_membership WHERE id = ?",
                LocalDate.class, rowId)).isEqualTo(OLD_CREATED);
    }

    @Test
    @DisplayName("L 명부에 없는 열린 행도 지우지 않는다 — 같은 기준일 리밸런싱이 고른다")
    void openRowOutsideRosterIsKeptForTheRebalance() {
        observed(UNIVERSE, ids.get(1), OLD_FLOOR, null);

        rebuild(List.of(point(W1, 0)), point(L, 0));

        assertThat(intervalsOf(UNIVERSE, ids.get(1))).containsExactly("OBSERVED " + L + "~열림");
    }

    @Test
    @DisplayName("닫힌 관측 행과 옛 복원 행은 지워지고 과거 순위 구간으로 바뀐다")
    void closedObservedAndOldRestoredAreReplaced() {
        observed(UNIVERSE, ids.get(2), OLD_FLOOR, W2);
        restored(UNIVERSE, ids.get(2), LocalDate.of(2026, 8, 7), OLD_FLOOR);

        rebuild(List.of(point(W1), point(W2, 2), point(W3)), point(L));

        assertThat(intervalsOf(UNIVERSE, ids.get(2))).containsExactly("RESTORED " + W2 + "~" + W3);
    }

    @Test
    @DisplayName("L에서 처음 든 종목은 복원 행 없이 [L, 열림) 하나다 — [L, L)이 생기면 트랜잭션째 되돌아간다")
    void firstEntryAtFloorOpensWithoutARestoredRow() {
        rebuild(List.of(point(W1, 0), point(W2, 0)), point(L, 0, 3));

        assertThat(intervalsOf(UNIVERSE, ids.get(3))).containsExactly("OBSERVED " + L + "~열림");
    }

    @Test
    @DisplayName("다시 지은 뒤 관측 하한은 L이다 · 다른 유니버스는 안 바뀐다")
    void floorMovesToLAndOtherUniversesStay() {
        observed(UNIVERSE, ids.get(0), OLD_FLOOR, null);
        observed(OTHER, ids.get(4), OLD_FLOOR, W2);
        restored(OTHER, ids.get(4), W1.minusDays(30), OLD_FLOOR);

        rebuild(List.of(point(W1, 0)), point(L, 0));

        assertThat(restorer.observedFloor(UNIVERSE)).contains(L);
        assertThat(intervalsOf(OTHER, ids.get(4)))
                .containsExactly("RESTORED " + W1.minusDays(30) + "~" + OLD_FLOOR, "OBSERVED " + OLD_FLOOR + "~" + W2);
    }

    @Test
    @DisplayName("관측 원장에 L보다 늦은 날짜가 있으면 한 행도 안 바꾸고 던진다")
    void refusesWhenObservationIsLaterThanFloor() {
        observed(UNIVERSE, ids.get(0), L.plusDays(7), null);
        Plan plan = restorer.planThrough(UNIVERSE, List.of(point(W1, 0)), point(L, 0), Map.of());

        assertThatThrownBy(() -> restorer.rebuildObserved(plan, Set.of(ids.get(0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("늦은 날짜");
        assertThat(intervalsOf(UNIVERSE, ids.get(0))).containsExactly("OBSERVED " + L.plusDays(7) + "~열림");
    }

    @Test
    @DisplayName("L 명부로의 교체도 비율 가드를 탄다")
    void stepIntoFloorIsGuarded() {
        assertThatThrownBy(() -> restorer.planThrough(UNIVERSE,
                List.of(point(W1, 0, 1, 2, 3, 4, 5, 6)), point(L, 7), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("제외가 지나치게 많다");
    }

    @Test
    @DisplayName("같은 L로 두 번 돌려도 행이 같다")
    void isIdempotentForTheSameFloor() {
        observed(UNIVERSE, ids.get(0), OLD_FLOOR, null);
        observed(UNIVERSE, ids.get(2), OLD_FLOOR, W2);
        List<DatedMembers> grid = List.of(point(W1, 0, 2), point(W2, 0));

        rebuild(grid, point(L, 0, 3));
        List<String> first = allRows();
        rebuild(grid, point(L, 0, 3));

        assertThat(allRows()).isEqualTo(first);
    }

    @Test
    @DisplayName("다시 지은 뒤 같은 기준일 리밸런싱은 빠지는 행을 지운다 — 시작일이 L이라 [L, L)을 안 만든다")
    void sameDayRebalanceAfterRebuildDeletesDroppedRows() {
        observed(UNIVERSE, ids.get(0), OLD_FLOOR, null);
        observed(UNIVERSE, ids.get(1), OLD_FLOOR, null);
        rebuild(List.of(point(W1, 0, 1)), point(L, 0, 1));

        reconciler.reconcile(new UniverseSnapshot(UNIVERSE, L, List.of("100000", "100005"), "test"),
                List.of(ids.get(0), ids.get(5)));

        assertThat(intervalsOf(UNIVERSE, ids.get(1))).containsExactly("RESTORED " + W1 + "~" + L);
        assertThat(intervalsOf(UNIVERSE, ids.get(5))).containsExactly("OBSERVED " + L + "~열림");
        assertThat(intervalsOf(UNIVERSE, ids.get(0))).containsExactly("RESTORED " + W1 + "~" + L, "OBSERVED " + L + "~열림");
    }

    private RebuildResult rebuild(List<DatedMembers> grid, DatedMembers atFloor) {
        Plan plan = restorer.planThrough(UNIVERSE, grid, atFloor, Map.of());
        return restorer.rebuildObserved(plan, atFloor.stockIds());
    }

    private DatedMembers point(LocalDate date, int... members) {
        Set<Long> set = new LinkedHashSet<>();
        for (int m : members) {
            set.add(ids.get(m));
        }
        return new DatedMembers(date, set);
    }

    private long observed(String universe, long stockId, LocalDate from, LocalDate to) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to, origin, created_at)
                VALUES (?, ?, ?, ?, 'OBSERVED', ?::timestamptz) RETURNING id
                """, Long.class, universe, stockId, from, to, OLD_CREATED.toString());
    }

    private void restored(String universe, long stockId, LocalDate from, LocalDate to) {
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to, origin)
                VALUES (?, ?, ?, ?, 'RESTORED')
                """, universe, stockId, from, to);
    }

    private List<String> intervalsOf(String universe, long stockId) {
        return jdbcTemplate.query("""
                SELECT origin, effective_from, effective_to FROM universe_membership
                WHERE universe_code = ? AND stock_id = ? ORDER BY effective_from
                """, (rs, n) -> {
            LocalDate to = rs.getObject("effective_to", LocalDate.class);
            return rs.getString("origin") + " " + rs.getObject("effective_from", LocalDate.class) + "~" + (to == null ? "열림" : to);
        }, universe, stockId);
    }

    private List<String> allRows() {
        return jdbcTemplate.query("""
                SELECT universe_code, stock_id, origin, effective_from, effective_to FROM universe_membership
                ORDER BY universe_code, stock_id, effective_from
                """, (rs, n) -> String.join(" ", rs.getString(1), String.valueOf(rs.getLong(2)), rs.getString(3),
                String.valueOf(rs.getObject(4)), String.valueOf(rs.getObject(5))));
    }
}
