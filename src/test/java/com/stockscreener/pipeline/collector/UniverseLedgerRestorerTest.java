package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.collector.UniverseLedgerRestorer.DatedMembers;
import com.stockscreener.pipeline.collector.UniverseLedgerRestorer.Interval;
import com.stockscreener.pipeline.collector.UniverseLedgerRestorer.Plan;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 과거 편입 복원의 계약을 잰다(ADR-0135).
 *
 * <p><b>런렝스 접기가 틀리면 없던 편입을 지어낸다</b> — 그것은 결측이 아니라 <b>그럴듯한 구간</b>으로
 * 남아 화면과 대조의 정확도 수를 함께 오염시킨다. 그래서 구간의 개수·끝점·하한 닫힘을 값으로 못박는다.
 *
 * <p>{@code plan()}이 순수 함수인 것이 이 시험의 절반을 DB 없이 성립하게 한다 — 그리고 같은 성질이
 * 운영에서 {@code --apply} 없는 대조를 공짜로 만든다.
 */
@JpaSliceTest
@Import(UniverseLedgerRestorer.class)
class UniverseLedgerRestorerTest {

    private static final String UNIVERSE = "KOSPI200";
    private static final LocalDate W1 = LocalDate.of(2026, 6, 5);
    private static final LocalDate W2 = LocalDate.of(2026, 6, 12);
    private static final LocalDate W3 = LocalDate.of(2026, 6, 19);
    private static final LocalDate FLOOR = LocalDate.of(2026, 8, 5);

    @Autowired
    private UniverseLedgerRestorer restorer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> ids = new ArrayList<>();

    @BeforeEach
    void insertStocks() {
        for (int i = 0; i < 8; i++) {
            ids.add(jdbcTemplate.queryForObject("""
                    INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?) RETURNING id
                    """, Long.class, "00000" + i, "종목" + i));
        }
    }

    @Test
    @DisplayName("계속 편입이면 구간 하나로 접히고 관측 하한에서 닫힌다")
    void foldsContinuousMembershipIntoOneIntervalClosedAtFloor() {
        Plan plan = restorer.plan(UNIVERSE, FLOOR, List.of(
                point(W1, 0), point(W2, 0), point(W3, 0)));

        assertThat(plan.intervals()).containsExactly(new Interval(ids.get(0), W1, FLOOR));
    }

    @Test
    @DisplayName("중간에 빠졌다 돌아오면 구간이 둘이고 그 사이가 공백이다")
    void splitsIntoTwoIntervalsAcrossAGap() {
        Plan plan = restorer.plan(UNIVERSE, FLOOR, List.of(
                point(W1, 0), point(W2), point(W3, 0)));

        assertThat(plan.intervals()).containsExactly(
                new Interval(ids.get(0), W1, W2),
                new Interval(ids.get(0), W3, FLOOR));
        assertThat(plan.membersOn(W2)).as("공백이 정답이다 — 그날은 비회원이었다").isEmpty();
    }

    @Test
    @DisplayName("마지막 격자일 전에 빠지면 그 격자일로 닫힌다 — 하한까지 늘리지 않는다")
    void closesAtTheGridPointNotAtTheFloor() {
        Plan plan = restorer.plan(UNIVERSE, FLOOR, List.of(
                point(W1, 0), point(W2, 0), point(W3)));

        assertThat(plan.intervals()).containsExactly(new Interval(ids.get(0), W1, W3));
    }

    @Test
    @DisplayName("격자가 비면 구간도 비고 던지지 않는다 — 창이 미끄러져 대상이 없는 날이 온다")
    void emptyGridYieldsNoIntervals() {
        assertThat(restorer.plan(UNIVERSE, FLOOR, List.of()).intervals()).isEmpty();
    }

    /**
     * <b>격자에 구멍을 내고 런렝스가 그 위를 이으면 없던 편입을 지어낸다.</b> 그래서 소스가 부분
     * 목록을 준 격자점을 만나면 그 유니버스를 <b>통째로 중단</b>한다 — 「그 날짜만 빼고 진행」이 없다.
     * 복원은 매주 수렴하는 잡이 아니라 1회성이라 자가 치유 갈래가 없기 때문이다.
     */
    @Test
    @DisplayName("격자점 사이 제외가 임계를 넘으면 그 유니버스를 통째로 중단한다")
    void abortsWhenAStepRemovesTooMany() {
        assertThatThrownBy(() -> restorer.plan(UNIVERSE, FLOOR, List.of(
                point(W1, 0, 1, 2, 3, 4, 5, 6, 7), point(W2, 0, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("제외가 지나치게 많다");
    }

    @Test
    @DisplayName("제외가 임계 미만이면 통과한다 — 가드가 정상 교체를 막지 않는다")
    void allowsNormalTurnover() {
        Plan plan = restorer.plan(UNIVERSE, FLOOR, List.of(
                point(W1, 0, 1, 2, 3, 4, 5, 6, 7), point(W2, 0, 1, 2, 3, 4, 5)));

        assertThat(plan.steps()).singleElement()
                .satisfies(step -> assertThat(step.removed()).isEqualTo(2));
    }

    @Test
    @DisplayName("복원 구간이 관측 구간의 시작에 붙어도 DB가 받는다 — 이 결정이 서 있는 자리다")
    void restoredIntervalsAbutObservedOnesInTheRealTable() {
        openObserved(ids.get(0), FLOOR);

        restorer.apply(restorer.plan(UNIVERSE, FLOOR, List.of(point(W1, 0), point(W2, 0))), false);

        assertThat(intervalsOf(ids.get(0)))
                .containsExactly(W1 + "~" + FLOOR, FLOOR + "~열림");
    }

    @Test
    @DisplayName("replace 없이 두 번 돌려도 행이 안 늘어난다")
    void isIdempotentWithoutReplace() {
        Plan plan = restorer.plan(UNIVERSE, FLOOR, List.of(point(W1, 0), point(W2, 0)));
        restorer.apply(plan, false);
        restorer.apply(plan, false);

        assertThat(restoredRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("replace는 옛 복원분을 지우고 다시 넣는다 — 구간이 달라진 경우가 그 자리다")
    void replaceRewritesChangedIntervals() {
        restorer.apply(restorer.plan(UNIVERSE, FLOOR, List.of(point(W1, 0), point(W2, 0))), false);
        restorer.apply(restorer.plan(UNIVERSE, FLOOR, List.of(point(W1, 0), point(W2))), true);

        assertThat(intervalsOf(ids.get(0))).containsExactly(W1 + "~" + W2);
    }

    @Test
    @DisplayName("replace는 관측 행을 안 건드린다 — 지우는 것은 복원 평면뿐이다")
    void replaceDoesNotTouchObservedRows() {
        openObserved(ids.get(0), FLOOR);
        restorer.apply(restorer.plan(UNIVERSE, FLOOR, List.of(point(W1, 0))), true);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM universe_membership WHERE origin = 'OBSERVED'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("관측 하한을 origin으로 좁힌다 — 안 좁히면 재실행이 자기 복원분을 하한으로 읽는다")
    void observedFloorIgnoresRestoredRows() {
        openObserved(ids.get(0), FLOOR);
        restorer.apply(restorer.plan(UNIVERSE, FLOOR, List.of(point(W1, 0))), false);

        assertThat(restorer.observedFloor(UNIVERSE)).contains(FLOOR);
    }

    @Test
    @DisplayName("관측이 없으면 하한이 없다 — 복원이 무엇을 상한으로 삼을지 스스로 못 정한다")
    void observedFloorIsEmptyWithoutObservedRows() {
        assertThat(restorer.observedFloor(UNIVERSE)).isEmpty();
    }

    private DatedMembers point(LocalDate date, int... members) {
        Set<Long> set = new LinkedHashSet<>();
        for (int m : members) {
            set.add(ids.get(m));
        }
        return new DatedMembers(date, set);
    }

    private void openObserved(long stockId, LocalDate from) {
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, UNIVERSE, stockId, from);
    }

    private List<String> intervalsOf(long stockId) {
        return jdbcTemplate.query("""
                SELECT effective_from, effective_to FROM universe_membership
                WHERE stock_id = ? ORDER BY effective_from
                """, (rs, n) -> {
            LocalDate to = rs.getObject("effective_to", LocalDate.class);
            return rs.getObject("effective_from", LocalDate.class) + "~" + (to == null ? "열림" : to);
        }, stockId);
    }

    private int restoredRowCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM universe_membership WHERE origin = 'RESTORED'
                """, Integer.class);
    }
}
