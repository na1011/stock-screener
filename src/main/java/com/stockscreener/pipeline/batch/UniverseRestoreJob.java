package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.collector.UniverseLedgerRestorer;
import com.stockscreener.pipeline.collector.UniverseLedgerRestorer.DatedMembers;
import com.stockscreener.pipeline.collector.UniverseLedgerRestorer.Plan;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 보존 창 안의 과거 편입을 소스에서 복원한다(ADR-0135).
 *
 * <h2>기본이 쓰기가 아니다</h2>
 * <p>{@code --apply=true}가 없으면 <b>한 행도 안 쓴다.</b> 이 저장소의 CLI 관례가
 * {@code Boolean.parseBoolean(value(args, …, "false"))}라 <b>맨 플래그는 {@code false}가 되므로</b>
 * ({@code BootstrapRunner}가 그 함정을 적어 뒀다) {@code --dry-run}으로 두면 오타 한 번이
 * <b>되돌릴 수 없는 쓰기</b>가 된다. 되돌릴 수 없는 잡은 쓰기를 옵트인으로 둔다.
 *
 * <p>그래서 쓰기 없는 갈래가 <b>티커를 해석할 때도 행을 안 만든다</b> —
 * {@link StockRegistrar#existingIdsByTicker}를 쓰고, <b>못 찾은 티커의 수를 보고한다.</b>
 * 그 수가 곧 「그날 편입이었는데 우리가 데이터를 안 가진 종목」의 크기다.
 *
 * <h2>{@code batch_execution}에 안 남긴다</h2>
 * <p>{@code corporate-action-review}와 같은 지위다. 그리고 이 잡의 자취는 배치 행보다
 * <b>원장 자체가 더 정확하게</b> 들고 있다 — {@code origin = 'RESTORED'}인 행의 {@code created_at}이
 * 구간마다 남는다.
 *
 * <h2>안 정한 것 하나 — 복원이 만드는 종목의 {@code is_active}</h2>
 * <p>{@link StockRegistrar#resolveOrCreate}가 {@code true}로 넣는데, 그중 <b>이미 상장폐지된
 * 종목</b>이 섞인다. 그것을 가르려면 「오늘 상장 전종목」을 물어야 하고 <b>지금 어댑터에 그 면이
 * 없다.</b> 그래서 <b>재보지 않았고 {@code true}로 둔다.</b>
 *
 * <p><b>지금 그것을 읽고 틀리는 경로가 없다</b>(2026-09-09 실측): 스크리닝은 편입 술어가,
 * {@code StockRegistrar}의 대상 선정 다섯은 {@code effective_to IS NULL}이,
 * {@code JdbcStoredShareCounts}는 시장 축이 그 종목을 먼저 뺀다. <b>결함이 되는 조건은
 * 「{@code stock.is_active}를 편입 술어 없이 읽는 경로가 생기는 것」</b>이고, 그때 이 자리를 연다.
 */
@Component
public class UniverseRestoreJob {

    public static final String JOB_NAME = "universe-restore";

    private static final Logger log = LoggerFactory.getLogger(UniverseRestoreJob.class);

    private final DataSourceRegistry registry;
    private final StockRegistrar stockRegistrar;
    private final UniverseLedgerRestorer restorer;
    private final JdbcTemplate jdbcTemplate;

    public UniverseRestoreJob(DataSourceRegistry registry, StockRegistrar stockRegistrar,
                              UniverseLedgerRestorer restorer, JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.stockRegistrar = stockRegistrar;
        this.restorer = restorer;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 복원을 계획하고, {@code apply}면 쓴다.
     *
     * <p>넷으로 가른다 — <b>A 페치(트랜잭션 밖) → B 접기(순수) → C 보고 → D 쓰기(트랜잭션 하나).</b>
     * A를 트랜잭션 밖에 두는 것이 요점이다: 국내 100콜·미국 약 1,060콜 동안 표에 락이 걸리면
     * 토요일 크론과 겹친다.
     *
     * @param dates 격자를 직접 준다. <b>{@code apply}일 때는 못 준다</b> — 관측 하한 뒤를 가리키는
     *              격자로 쓰면 관측 구간과 겹치는 행을 만들려다 GiST에 걸리는데, <b>막힌 이유를
     *              사람이 해석해야 한다.</b> 대조는 그 날짜들이 필요하므로 쓰기 없는 갈래에서만 연다
     */
    public Report run(String universeCode, boolean apply, boolean replace, List<LocalDate> dates) {
        if (apply && dates != null && !dates.isEmpty()) {
            throw new IllegalArgumentException(
                    "--dates는 --apply와 함께 못 쓴다 — 관측 하한 뒤의 격자로 쓰면 겹치는 구간을 만들려다 제약에 걸리고, 막힌 이유를 사람이 해석해야 한다. 대조만 할 때 쓴다.");
        }
        Target target = target(universeCode);
        LocalDate floor = restorer.observedFloor(universeCode).orElseThrow(() ->
                new IllegalStateException(
                        "관측 편입이 없어 복원 상한을 정할 수 없다: " + universeCode
                                + " — 리밸런싱을 한 번도 안 돌린 유니버스다. 복원은 관측 원장의 하한에서 끊는다."));

        boolean replay = dates != null && !dates.isEmpty();
        List<LocalDate> grid = replay ? dates : restorer.weeklyGrid(target.marketCode(), floor);
        if (grid.isEmpty()) {
            log.info("복원할 격자점이 없다. {} 하한={} — 보존 창이 이미 관측 구간 안이다",
                    universeCode, floor);
            return new Report(universeCode, floor, List.of(), 0, 0, Set.of(), null, 0, List.of());
        }

        // ── Phase A: 페치. 트랜잭션 밖이고, 쓰기 갈래에서만 stock 행을 만든다.
        List<DatedMembers> points = new ArrayList<>();
        Set<String> unresolved = new TreeSet<>();
        int fetched = 0;
        for (LocalDate date : grid) {
            // MUST: 격자점 하나가 실패하면 그 유니버스를 통째로 중단한다. 그 날짜만 빼고
            // 이으면 런렝스가 구멍 위를 이어 **없던 편입을 지어내고**, 그 값은 그럴듯해서
            // 대조의 정확도 수까지 오염시킨다. Phase D가 아직 안 돌았으므로 쓰기는 0이다.
            UniverseSnapshot snapshot;
            try {
                snapshot = registry.universeSource(universeCode).fetchConstituents(universeCode, date);
            } catch (DataSourceException e) {
                throw new IllegalStateException(
                        "복원 격자점의 스냅샷을 못 만들었다: %s %s (%s) — 그 날짜만 빼고 이으면 없던 편입을 지어낸다. 소스를 고치고 다시 돌린다"
                                .formatted(universeCode, date, e.kind()), e);
            }
            fetched++;
            points.add(new DatedMembers(date, resolve(target, snapshot, apply, unresolved)));
        }

        // ── Phase B: 접기. 순수 함수라 여기까지가 공짜다.
        //
        // 닫는 날짜가 갈린다. 복원은 관측 하한에서 끊지만, 대조는 격자가 하한 **뒤**라
        // 하한으로 닫으면 [from, to)의 to가 from보다 이르다. 대조에서는 「마지막 격자일
        // 다음 날」이 「우리가 아는 것이 끝나는 날」이다.
        LocalDate closeAt = replay ? grid.get(grid.size() - 1).plusDays(1) : floor;
        Plan plan = restorer.plan(universeCode, closeAt, points);

        // ── Phase C: 보고.
        log.info("복원 계획. {} 격자={}점({}~{}) 하한={} 닫는날={} 구간={} 해석 못 한 티커={}",
                universeCode, grid.size(), grid.get(0), grid.get(grid.size() - 1), floor,
                closeAt, plan.intervals().size(), unresolved.size());
        if (!unresolved.isEmpty()) {
            log.info("그날 편입이었는데 stock에 없는 종목 {}개: {}", unresolved.size(),
                    unresolved.stream().limit(40).toList());
        }
        List<Recall> recalls = replay ? compare(universeCode, grid, plan) : List.of();

        // ── Phase D: 쓰기.
        int inserted = apply ? restorer.apply(plan, replace) : 0;
        return new Report(universeCode, floor, grid, fetched, plan.intervals().size(),
                Set.copyOf(unresolved), plan, inserted, recalls);
    }

    /**
     * 티커를 id로 바꾼다. <b>쓰기 갈래에서만 없는 종목을 만든다.</b>
     *
     * <p>MUST: 쓰기 갈래의 {@code resolveOrCreate}가 <b>같은 프로세스</b>에서 돌아야 콜이 안 는다 —
     * 국내 어댑터가 {@code fetchConstituents}에서 후보 전원의 이름을 {@code SecurityNameCache}에
     * 넣어 두므로, 나누면 만드는 종목 수만큼 마스터 조회가 붙고 실패하면 이름이 티커로 남는다.
     */
    private Set<Long> resolve(Target target, UniverseSnapshot snapshot, boolean apply,
                              Set<String> unresolved) {
        // 있는 것부터 한 질의로 찾는다. resolveOrCreate는 티커마다 조회하므로 격자 하나에
        // 천 번이 나가고 53점이면 오만 번이 된다 — 실측으로 격자점당 37초였다.
        Map<String, Long> found =
                stockRegistrar.existingIdsByTicker(target.marketCode(), snapshot.tickers());
        List<String> missing = snapshot.tickers().stream()
                .filter(ticker -> !found.containsKey(ticker))
                .toList();
        if (!apply) {
            unresolved.addAll(missing);
            return new LinkedHashSet<>(found.values());
        }
        // 쓰기 갈래에서만 없는 종목을 만든다. 대상이 없는 격자점에서는 아예 안 부른다.
        Set<Long> ids = new LinkedHashSet<>(found.values());
        if (!missing.isEmpty()) {
            ids.addAll(stockRegistrar.resolveOrCreate(target.marketCode(), missing));
        }
        return ids;
    }

    /**
     * 재생한 명부를 <b>그날의 관측 원장</b>과 견준다 — 이것이 복원을 쓸지 정하는 판정이다.
     *
     * <p><b>격자가 아니라 원장이 실제로 쓴 날짜로 재생하는 것이 요점이다.</b> 주간 격자로
     * 대조하면 미국은 관측 구간에 격자점이 <b>둘</b>밖에 안 걸리고(하한 뒤 주별 마지막 거래일이
     * 그것뿐이다), 손으로 돌린 실행은 그 주의 마지막 거래일도 아니라 아예 안 걸린다.
     * 원장의 {@code effective_from}을 그대로 재생하면 <b>대조가 격자 선택과 무관해져
     * 「복원 오차」만 남는다.</b>
     *
     * <p><b>MUST: 놓친 종목을 수로만 보지 않는다.</b> 그 종목이 복원 명부의 <b>몇 위였는지</b>가
     * 경계 잡음(1,001~1,020위)과 순위 축 파손(3,000위)을 가른다 — 뒤쪽이면 고칠 수 있는
     * 결함이지 복원의 한계가 아니다. 지금은 순위를 안 들고 있으므로 <b>티커를 낸다.</b>
     */
    private List<Recall> compare(String universeCode, List<LocalDate> grid, Plan plan) {
        List<Recall> recalls = new ArrayList<>();
        for (LocalDate date : grid) {
            Set<Long> observed = new LinkedHashSet<>(jdbcTemplate.queryForList("""
                    SELECT stock_id FROM universe_membership
                    WHERE universe_code = ? AND origin = 'OBSERVED'
                      AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                    """, Long.class, universeCode, date, date));
            Set<Long> restored = plan.membersOn(date);
            Set<Long> hit = new LinkedHashSet<>(observed);
            hit.retainAll(restored);
            Set<Long> missed = new LinkedHashSet<>(observed);
            missed.removeAll(restored);
            Set<Long> extra = new LinkedHashSet<>(restored);
            extra.removeAll(observed);
            double recall = observed.isEmpty() ? Double.NaN : (double) hit.size() / observed.size();
            recalls.add(new Recall(date, observed.size(), restored.size(), hit.size(),
                    tickers(missed), tickers(extra), recall));
            log.info("재생 대조. {} {} 관측={} 복원={} 재현율={} 놓침={} 더든것={}",
                    universeCode, date, observed.size(), restored.size(),
                    observed.isEmpty() ? "잴 것이 없었다" : "%.4f".formatted(recall),
                    tickers(missed), tickers(extra));
        }
        return recalls;
    }

    private List<String> tickers(Set<Long> stockIds) {
        if (stockIds.isEmpty()) {
            return List.of();
        }
        String holes = String.join(",", java.util.Collections.nCopies(stockIds.size(), "?"));
        return jdbcTemplate.queryForList(
                "SELECT ticker FROM stock WHERE id IN (" + holes + ") ORDER BY ticker",
                String.class, stockIds.toArray());
    }

    private Target target(String universeCode) {
        List<Target> found = jdbcTemplate.query("""
                SELECT code, market_code FROM universe WHERE code = ?
                """, (rs, n) -> new Target(rs.getString("code"), rs.getString("market_code")),
                universeCode);
        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                    "그런 유니버스가 없다: " + universeCode + " — universe 테이블의 code를 확인하라.");
        }
        return found.get(0);
    }

    private record Target(String universeCode, String marketCode) {
    }

    /**
     * 복원 결과.
     *
     * @param unresolvedTickers 그날 편입이었는데 {@code stock}에 없는 종목. <b>쓰기 없는 갈래에서만
     *                          찬다</b> — 쓰기 갈래는 만들어 버리므로 0이다. 이 수가 「데이터를 안
     *                          가진 과거 구성원」의 실측이고, 그 뒤 적재를 정하는 근거다
     * @param plan 쓰기 없는 갈래의 대조가 이것을 관측 원장과 견준다
     */
    public record Report(String universeCode, LocalDate floor, List<LocalDate> grid,
                         int fetchedPoints, int intervals, Set<String> unresolvedTickers,
                         Plan plan, int inserted, List<Recall> recalls) {
    }

    /**
     * 그 날짜의 재생 정확도.
     *
     * @param recall {@code |복원∩관측| / |관측|}. 관측이 0이면 {@code NaN}이고 그것은
     *               <b>「잴 것이 없었다」</b>이지 0이 아니다 — 둘을 섞으면 못 잰 것이 실패로 읽힌다
     */
    public record Recall(LocalDate date, int observed, int restored, int hit,
                         List<String> missedTickers, List<String> extraTickers, double recall) {
    }
}
