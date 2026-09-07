package com.stockscreener.pipeline.collector;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 유니버스 리밸런싱을 검증한다.
 *
 * <p>이 테스트가 {@code ex_membership_no_overlap}(EXCLUDE 제약)을 실제로 건드리는 유일한
 * 수단이다. 제약이 없으면 배치 버그가 스크리닝 결과의 행을 조용히 부풀리는데,
 * 그것은 일반 테스트에서 거의 보이지 않는다.
 */
@JpaSliceTest
@Import(UniverseReconciler.class)
class UniverseReconcilerTest {

    private static final String UNIVERSE = "KOSPI200";
    private static final LocalDate JUNE = LocalDate.of(2026, 6, 12);
    private static final LocalDate DECEMBER = LocalDate.of(2026, 12, 11);

    @Autowired
    private UniverseReconciler reconciler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> stockIds = new ArrayList<>();

    @BeforeEach
    void insertStocks() {
        for (int i = 0; i < 5; i++) {
            stockIds.add(jdbcTemplate.queryForObject("""
                    INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?)
                    RETURNING id
                    """, Long.class, "00000" + i, "종목" + i));
        }
    }

    @Test
    @DisplayName("최초 반영 시 전 종목을 편입한다")
    void addsAllOnFirstRun() {
        UniverseReconciler.Result result = reconciler.reconcile(
                snapshot(JUNE, "000000", "000001", "000002"), ids(0, 1, 2));

        assertThat(result.addedCount()).isEqualTo(3);
        assertThat(result.removedCount()).isZero();
        assertThat(reconciler.currentMembers(UNIVERSE)).containsExactlyElementsOf(ids(0, 1, 2));
    }

    @Test
    @DisplayName("같은 스냅샷을 두 번 반영해도 행이 늘지 않는다 — 멱등해야 한다")
    void isIdempotent() {
        reconciler.reconcile(snapshot(JUNE, "000000", "000001"), ids(0, 1));
        UniverseReconciler.Result second = reconciler.reconcile(
                snapshot(JUNE, "000000", "000001"), ids(0, 1));

        assertThat(second.changed()).isFalse();
        assertThat(membershipRowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("편입과 제외가 시간 구간으로 기록된다 — 무엇이 언제 바뀌었는지 남아야 한다")
    void recordsChangesAsTemporalIntervals() {
        reconciler.reconcile(snapshot(JUNE, "000000", "000001", "000002"), ids(0, 1, 2));
        UniverseReconciler.Result result = reconciler.reconcile(
                snapshot(DECEMBER, "000000", "000001", "000003"), ids(0, 1, 3));

        assertThat(result.addedCount()).isEqualTo(1);
        assertThat(result.removedCount()).isEqualTo(1);
        assertThat(result.retainedCount()).isEqualTo(2);

        // 제외된 종목은 지워지지 않고 구간이 닫힌다 — 이력 조회가 여기 의존한다.
        Interval removed = intervalsOf(stockIds.get(2)).get(0);
        assertThat(removed.from()).isEqualTo(JUNE);
        assertThat(removed.to()).isEqualTo(DECEMBER);

        assertThat(reconciler.currentMembers(UNIVERSE)).containsExactlyElementsOf(ids(0, 1, 3));
    }

    @Test
    @DisplayName("같은 기준일에 목록이 바뀌면 그날 편입된 것을 지운다 — 닫으면 유니버스가 통째로 거부된다")
    void removesSameDayEntryInsteadOfClosingIt() {
        // 첫 실행이 000002를 편입시킨다. 원자료가 그 뒤 고쳐져 같은 기준일의 두 번째 실행이
        // 그것을 제외한다 — 그날로 닫으면 [JUNE, JUNE)이라 ck_membership_period가 막고
        // **그 예외가 트랜잭션 전체를 되돌려 편입까지 사라진다**(2026-09-04 실증).
        reconciler.reconcile(snapshot(JUNE, "000000", "000001", "000002"), ids(0, 1, 2));

        UniverseReconciler.Result second =
                reconciler.reconcile(snapshot(JUNE, "000000", "000001", "000003"), ids(0, 1, 3));

        assertThat(second.removedCount()).isEqualTo(1);
        assertThat(second.addedCount()).isEqualTo(1);
        assertThat(intervalsOf(stockIds.get(2)))
                .as("하루도 안 덮은 행은 닫을 이력이 아니라 취소된 편입이다")
                .isEmpty();
        assertThat(reconciler.currentMembers(UNIVERSE))
                .as("거부되면 000003 편입도 함께 되돌아간다 — 그것이 이 시험이 막는 것이다")
                .containsExactlyInAnyOrderElementsOf(ids(0, 1, 3));
    }

    @Test
    @DisplayName("제외 후 재편입이 가능하다 — 반열림 구간이라 EXCLUDE 제약을 위반하지 않는다")
    void supportsRemovalAndReentry() {
        reconciler.reconcile(snapshot(JUNE, "000000", "000001"), ids(0, 1));
        reconciler.reconcile(snapshot(DECEMBER, "000000"), ids(0));
        // 다시 편입 — [JUNE, DECEMBER)와 [LATER, NULL)은 겹치지 않는다.
        LocalDate later = LocalDate.of(2027, 6, 11);
        reconciler.reconcile(snapshot(later, "000000", "000001"), ids(0, 1));

        List<Interval> intervals = intervalsOf(stockIds.get(1));
        assertThat(intervals).hasSize(2);
        assertThat(intervals.get(0).to()).isEqualTo(DECEMBER);
        assertThat(intervals.get(1).from()).isEqualTo(later);
        assertThat(intervals.get(1).to()).isNull();
    }

    @Test
    @DisplayName("제외가 지나치게 많으면 거부한다 — 소스의 부분 목록이 유니버스를 비우면 안 된다")
    void rejectsSuspiciouslyLargeRemoval() {
        // 20종목을 편입해두고 소스가 1종목만 준 상황을 만든다. 파싱 실패의 전형적 형태다.
        List<Long> many = insertManyStocks(20);
        List<String> allTickers = tickersOf(many);
        reconciler.reconcile(
                new UniverseSnapshot(UNIVERSE, JUNE, allTickers, "test-seed"), many);

        assertThatThrownBy(() -> reconciler.reconcile(
                new UniverseSnapshot(UNIVERSE, DECEMBER, List.of(allTickers.get(0)), "test-seed"),
                List.of(many.get(0))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("제외 종목이 지나치게 많다");

        // 거부됐으므로 아무것도 바뀌지 않는다.
        assertThat(reconciler.currentMembers(UNIVERSE)).hasSize(20);
    }

    @Test
    @DisplayName("정상 규모의 교체는 통과한다 — 비율 검사가 실제 리밸런싱을 막으면 안 된다")
    void allowsNormalRebalancing() {
        List<Long> many = insertManyStocks(20);
        List<String> tickers = tickersOf(many);
        reconciler.reconcile(new UniverseSnapshot(UNIVERSE, JUNE, tickers, "test-seed"), many);

        // 20종목 중 3종목 교체(15%) — 실제 정기 리밸런싱의 규모다.
        List<Long> next = new ArrayList<>(many.subList(0, 17));
        next.addAll(ids(0, 1, 2));
        List<String> nextTickers = new ArrayList<>(tickers.subList(0, 17));
        nextTickers.addAll(List.of("000000", "000001", "000002"));

        UniverseReconciler.Result result = reconciler.reconcile(
                new UniverseSnapshot(UNIVERSE, DECEMBER, nextTickers, "test-seed"), next);

        assertThat(result.addedCount()).isEqualTo(3);
        assertThat(result.removedCount()).isEqualTo(3);
        assertThat(reconciler.currentMembers(UNIVERSE)).hasSize(20);
    }

    private List<Long> insertManyStocks(int count) {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String ticker = "1%04d".formatted(i);
            created.add(jdbcTemplate.queryForObject("""
                    INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?)
                    RETURNING id
                    """, Long.class, ticker, "대형종목" + i));
        }
        return created;
    }

    private List<String> tickersOf(List<Long> ids) {
        return ids.stream()
                .map(id -> jdbcTemplate.queryForObject(
                        "SELECT ticker FROM stock WHERE id = ?", String.class, id))
                .toList();
    }

    @Test
    @DisplayName("교집합은 건드리지 않는다 — 같은 날 제외·재편입은 0일 구간이 되어 거부된다")
    void doesNotTouchRetainedMembers() {
        reconciler.reconcile(snapshot(JUNE, "000000", "000001"), ids(0, 1));
        // 같은 날 같은 스냅샷을 다시 반영해도 구간이 쪼개지지 않아야 한다.
        reconciler.reconcile(snapshot(JUNE, "000000", "000001"), ids(0, 1));

        List<Interval> intervals = intervalsOf(stockIds.get(0));
        assertThat(intervals).hasSize(1);
        assertThat(intervals.get(0).to()).isNull();
    }

    /**
     * 구간을 {@code LocalDate}로 읽는다.
     *
     * <p>{@code queryForList}는 날짜를 {@code java.sql.Date}로 주므로 {@code LocalDate}와
     * 직접 비교하면 값이 같아도 실패한다. RowMapper로 타입을 고정한다.
     */
    private List<Interval> intervalsOf(long stockId) {
        return jdbcTemplate.query("""
                SELECT effective_from, effective_to FROM universe_membership
                WHERE universe_code = ? AND stock_id = ? ORDER BY effective_from
                """,
                (rs, rowNum) -> new Interval(
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class)),
                UNIVERSE, stockId);
    }

    private record Interval(LocalDate from, LocalDate to) {
    }

    private int membershipRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM universe_membership WHERE universe_code = ?",
                Integer.class, UNIVERSE);
    }

    private List<Long> ids(int... indexes) {
        List<Long> selected = new ArrayList<>();
        for (int index : indexes) {
            selected.add(stockIds.get(index));
        }
        return selected;
    }

    private static UniverseSnapshot snapshot(LocalDate asOf, String... tickers) {
        return new UniverseSnapshot(UNIVERSE, asOf, List.of(tickers), "test-seed");
    }
}
