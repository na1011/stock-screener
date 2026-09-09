package com.stockscreener.pipeline.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 과거 편입을 원장의 <b>둘째 평면</b>({@code origin = 'RESTORED'})으로 복원한다(ADR-0135).
 *
 * <h2>{@link UniverseReconciler}를 부르지 않는 이유</h2>
 * <p>저쪽 알고리즘의 어려운 부분(<i>"그날 열린 행은 닫지 말고 지운다"</i>)은 <b>같은 날 재실행</b>이
 * 만든 것인데, 복원은 격자 전체를 <b>미리 안다.</b> 그리고 {@code ck_restored_is_closed}가
 * 「열어 두고 나중에 닫기」를 금지하므로(PostgreSQL의 CHECK는 {@code DEFERRABLE}이 아니다)
 * 구간을 <b>메모리에서 완성해 닫힌 채로</b> 넣어야 한다. 제약이 이 클래스의 모양을 정했다.
 *
 * <p>대신 <b>제외 비율 가드는 같은 상수를 읽는다</b>({@link UniverseReconciler#MAX_REMOVAL_RATIO}).
 * 사본을 만들면 한쪽만 움직여도 아무도 모른다.
 *
 * <h2>왜 런렝스 인코딩인가</h2>
 * <p>격자점마다 「그날의 상위 N」이 오므로, 종목별로 <b>연속으로 편입돼 있던 구간</b>을
 * {@code [첫 격자일, 그 구간 다음 격자일)}로 접는다. 마지막 격자일까지 이어진 구간은
 * <b>관측 하한으로 닫는다</b> — 그래서 「마지막 열린 구간 처리」가 별도 UPDATE가 아니라
 * 알고리즘의 종결 조건이다.
 *
 * <p>그 결과 관측 구간과의 관계가 이렇게 닫힌다. 유지 중인 종목은 복원 {@code […, 하한)} +
 * 관측 {@code [하한, NULL)}로 <b>인접</b>하고, {@code daterange}가 반열림이라 겹치지 않는다.
 * 하한 뒤에 새로 들어온 종목은 그 사이가 <b>공백으로 남는데 그것이 정답이다</b> — 관측이
 * 그때 비회원이라고 말한다. 어느 경우에도 {@code ex_membership_no_overlap}에 안 걸리고,
 * 만에 하나 걸리면 <b>트랜잭션째 거절되므로 조용히 안 깨진다.</b>
 */
@Component
public class UniverseLedgerRestorer {

    private static final Logger log = LoggerFactory.getLogger(UniverseLedgerRestorer.class);

    private final JdbcTemplate jdbcTemplate;

    public UniverseLedgerRestorer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 관측 원장의 하한 — 복원 격자의 상한이다.
     *
     * <p><b>MUST: {@code origin = 'OBSERVED'}로 좁힌다.</b> 없으면 두 번째 실행이 <b>자기
     * 복원분을 하한으로 읽어</b> 격자가 통째로 빈다. 이것이 {@code origin} 컬럼의 두 번째
     * 존재 이유다.
     */
    public Optional<LocalDate> observedFloor(String universeCode) {
        return Optional.ofNullable(jdbcTemplate.queryForObject("""
                SELECT min(effective_from) FROM universe_membership
                WHERE universe_code = ? AND origin = 'OBSERVED'
                """, LocalDate.class, universeCode));
    }

    /**
     * 복원 격자 — <b>그 주의 마지막 거래일</b>과 보존 창의 첫날, {@code floor} 이전만.
     *
     * <p>격자를 {@code indicator_trade_date}에서 뽑는 이유가 셋이다. ① 휴장 계산을 안 한다
     * ② <b>화면이 물을 수 없는 날짜를 격자에 안 넣는다</b>(보존 창 밖은 {@code AsOfResolver}가
     * 400으로 접는다) ③ 창이 앞으로 미끄러지면 격자도 따라 줄어 <b>복원 대상이 저절로 닫힌다.</b>
     *
     * <p><b>MUST NOT: {@code market_calendar}를 쓰지 않는다.</b> 그 표는
     * {@code exchange_code IN ('NYSE','NASDAQ')}이라 <b>국내가 원리적으로 안 걸린다.</b>
     *
     * <p>보존 창 첫날을 따로 넣는 이유는 그날이 그 주의 마지막 거래일이 아닐 수 있어서다 —
     * 안 넣으면 복원 평면이 창보다 며칠 늦게 시작하고 그 며칠이 0건이 된다.
     */
    public List<LocalDate> weeklyGrid(String marketCode, LocalDate floor) {
        return jdbcTemplate.queryForList("""
                SELECT trade_date FROM (
                    SELECT trade_date,
                           row_number() OVER (PARTITION BY date_trunc('week', trade_date)
                                              ORDER BY trade_date DESC) AS rn,
                           min(trade_date) OVER () AS earliest
                    FROM indicator_trade_date WHERE market_code = ?
                ) t
                WHERE trade_date < ? AND (rn = 1 OR trade_date = earliest)
                ORDER BY trade_date
                """, LocalDate.class, marketCode, floor);
    }

    /**
     * 격자를 닫힌 구간 목록으로 접는다. <b>순수 함수다</b> — DB도 HTTP도 안 만진다.
     *
     * <p>그래서 {@code --apply} 없이 도는 대조가 <b>공짜로 나온다.</b> 페치(HTTP) → 이 접기 →
     * 보고에서 멈추면 그것이 dry-run이고, 쓰기 경로는 그 뒤에 한 단계 더 있을 뿐이다.
     *
     * @param grid 격자일 <b>오름차순</b>. 각 원소는 그날의 편입 종목 id 집합이다
     * @param floor 관측 하한. 마지막 격자일까지 이어진 구간이 여기서 닫힌다
     * @throws IllegalStateException 격자점 사이 제외 비율이 임계를 넘을 때. <b>그 유니버스를
     *         통째로 중단한다</b> — 격자에 구멍을 내고 런렝스가 그 위를 이으면 <b>없던 편입을
     *         지어내고</b>, 그 값은 그럴듯해서 대조의 정확도 수까지 오염시킨다
     */
    public Plan plan(String universeCode, LocalDate floor, List<DatedMembers> grid) {
        if (grid.isEmpty()) {
            return new Plan(universeCode, floor, List.of(), List.of());
        }
        List<StepDiff> steps = new ArrayList<>();
        Map<Long, LocalDate> openedAt = new HashMap<>();
        List<Interval> intervals = new ArrayList<>();
        Set<Long> previous = Set.of();
        LocalDate previousDate = null;

        for (DatedMembers point : grid) {
            Set<Long> members = point.stockIds();
            if (previousDate != null) {
                steps.add(diff(universeCode, previousDate, point.date(), previous, members));
            }
            for (Long gone : minus(previous, members)) {
                intervals.add(new Interval(gone, openedAt.remove(gone), point.date()));
            }
            for (Long fresh : minus(members, previous)) {
                openedAt.put(fresh, point.date());
            }
            previous = members;
            previousDate = point.date();
        }
        for (Map.Entry<Long, LocalDate> still : new TreeSet<>(openedAt.keySet()).stream()
                .map(id -> Map.entry(id, openedAt.get(id))).toList()) {
            intervals.add(new Interval(still.getKey(), still.getValue(), floor));
        }
        intervals.sort(Comparator.comparingLong(Interval::stockId).thenComparing(Interval::from));
        return new Plan(universeCode, floor, List.copyOf(intervals), List.copyOf(steps));
    }

    /**
     * 계획을 원장에 쓴다.
     *
     * <p><b>MUST: 트랜잭션 단위가 「유니버스 하나의 격자 전체」다.</b> 격자점 단위로 쪼개면
     * ADR-0008이 <i>"부분 적용된 유니버스는 「제외만 되고 편입이 안 된」 중간 상태를 스크리닝에
     * 노출한다"</i>로 기각한 것의 <b>시간 축 판</b>이 된다 — 중간에 실패하면 일부 주만 복원된
     * 유니버스가 화면에 실린다.
     *
     * @param replace 이미 있는 복원분을 같은 트랜잭션에서 지우고 다시 넣는다. 없으면
     *                {@code ON CONFLICT DO NOTHING}이 막아 행이 안 늘지만, <b>구간이 달라진
     *                경우에는 옛 구간이 그대로 남는다</b> — 계산을 고쳤으면 이것을 켠다
     */
    @Transactional
    public int apply(Plan plan, boolean replace) {
        if (replace) {
            int wiped = jdbcTemplate.update("""
                    DELETE FROM universe_membership
                    WHERE universe_code = ? AND origin = 'RESTORED'
                    """, plan.universeCode());
            log.info("복원분을 지우고 다시 넣는다. {} 지운 행={}", plan.universeCode(), wiped);
        }
        List<Object[]> rows = plan.intervals().stream()
                .map(i -> new Object[] {plan.universeCode(), i.stockId(), i.from(), i.to()})
                .toList();
        int[] written = jdbcTemplate.batchUpdate("""
                INSERT INTO universe_membership
                    (universe_code, stock_id, effective_from, effective_to, origin, created_at)
                VALUES (?, ?, ?, ?, 'RESTORED', now())
                ON CONFLICT (universe_code, stock_id, effective_from) DO NOTHING
                """, rows);
        int inserted = 0;
        for (int n : written) {
            inserted += Math.max(n, 0);
        }
        log.info("과거 편입을 복원했다. {} 구간={} 삽입={} 하한={}",
                plan.universeCode(), plan.intervals().size(), inserted, plan.floor());
        return inserted;
    }

    private StepDiff diff(String universeCode, LocalDate from, LocalDate to,
                          Set<Long> before, Set<Long> after) {
        List<Long> removed = minus(before, after);
        List<Long> added = minus(after, before);
        if (!before.isEmpty()
                && removed.size() >= UniverseReconciler.MIN_REMOVALS_FOR_RATIO_CHECK
                && (double) removed.size() / before.size() > UniverseReconciler.MAX_REMOVAL_RATIO) {
            throw new IllegalStateException(
                    "복원 격자에서 제외가 지나치게 많다: %s %s→%s 제외 %d/%d (임계 %.0f%%) — 그 날짜의 소스가 부분 목록일 수 있다. 격자에 구멍을 내고 이으면 없던 편입을 지어낸다"
                            .formatted(universeCode, from, to, removed.size(), before.size(),
                                    UniverseReconciler.MAX_REMOVAL_RATIO * 100));
        }
        return new StepDiff(from, to, added.size(), removed.size(), after.size());
    }

    private static List<Long> minus(Set<Long> from, Set<Long> other) {
        Set<Long> result = new LinkedHashSet<>(from);
        result.removeAll(other);
        return new ArrayList<>(new TreeSet<>(result));
    }

    /** 격자점 하나 — 그날의 편입 종목. */
    public record DatedMembers(LocalDate date, Set<Long> stockIds) {
    }

    /** 복원할 닫힌 구간 하나. {@code to}는 절대 {@code null}이 아니다. */
    public record Interval(long stockId, LocalDate from, LocalDate to) {
    }

    /** 격자점 사이의 교체. 대조가 회전율을 이것으로 낸다. */
    public record StepDiff(LocalDate from, LocalDate to, int added, int removed, int size) {
    }

    /** 쓰기 전에 완성된 계획. {@code --apply} 없이 도는 대조가 이것을 보고한다. */
    public record Plan(String universeCode, LocalDate floor,
                       List<Interval> intervals, List<StepDiff> steps) {

        /** 그 날짜에 편입인 종목 — 대조가 관측 원장과 견줄 때 쓴다. */
        public Set<Long> membersOn(LocalDate date) {
            Set<Long> members = new LinkedHashSet<>();
            for (Interval interval : intervals) {
                if (!date.isBefore(interval.from()) && date.isBefore(interval.to())) {
                    members.add(interval.stockId());
                }
            }
            return members;
        }
    }
}
