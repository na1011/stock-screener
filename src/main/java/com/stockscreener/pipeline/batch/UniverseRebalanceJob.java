package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.collector.UniverseReconciler;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.UniverseDataSource;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 지수 구성종목 리밸런싱.
 *
 * <p>{@code data-pipeline.md}가 "수동 개입 없이 자동 반영"을 요구한다. 시드 소스만 있으면
 * 사람이 CSV를 갱신해야 하므로, 스크래핑 어댑터가 붙는 시점에 이 요구가 완전히 충족된다.
 * 그때까지도 배치 자체는 자동으로 돌며 시드의 변경을 반영한다.
 *
 * <p>MUST NOT: 스크래핑 실패 시 시드로 <b>조용히</b> 폴백하지 않는다. 폴백은 반드시 알림을
 * 동반한다 — 조용한 폴백은 "6개월 전 유니버스로 스크리닝하고 있었다"는 결과를 만들고,
 * 그 상태는 아무 에러도 내지 않아 발견되지 않는다.
 *
 * <p>이 배치만 종목 단위 실패 격리를 쓰지 않는다. 유니버스는 전체가 하나의 정합적
 * 스냅샷이라 부분 적용이 의미 없고, "제외만 되고 편입이 안 된" 중간 상태가 스크리닝에
 * 노출되면 안 되기 때문이다.
 */
@Component
public class UniverseRebalanceJob {

    public static final String JOB_NAME = "universe-rebalance";

    private static final Logger log = LoggerFactory.getLogger(UniverseRebalanceJob.class);

    private final DataSourceRegistry registry;
    private final UniverseReconciler reconciler;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;
    private final AlertPort alertPort;
    private final JdbcTemplate jdbcTemplate;
    private final MembershipGapFiller membershipGapFiller;
    private final FreshnessCatchUpJob freshnessCatchUp;
    private final CandidateIntake candidateIntake;
    private final UsCandidateFeed candidateFeed;

    /**
     * 정기 경로가 활성 지수와 함께 화해하는 <b>후보 풀</b>(ADR-0178) — 비활성인 채로 둔다. 스크리닝 모집단·지표·결손 보충·따라잡기는
     * 활성만 보므로 이 풀의 편입은 그 어디에도 안 닿고, 하는 일은 새 상장에 {@code stock} 행을 만드는 것(공시 장부가 그 행에 공시를
     * 붙인다)과 사라진 상장의 편입을 닫는 것이다.
     */
    static final List<String> CANDIDATE_POOLS = List.of("US_LISTED");

    public UniverseRebalanceJob(DataSourceRegistry registry, UniverseReconciler reconciler,
                                StockRegistrar stockRegistrar, BatchExecutions batchExecutions,
                                AlertPort alertPort, JdbcTemplate jdbcTemplate,
                                MembershipGapFiller membershipGapFiller, FreshnessCatchUpJob freshnessCatchUp,
                                CandidateIntake candidateIntake, UsCandidateFeed candidateFeed) {
        this.registry = registry;
        this.reconciler = reconciler;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
        this.alertPort = alertPort;
        this.jdbcTemplate = jdbcTemplate;
        this.membershipGapFiller = membershipGapFiller;
        this.freshnessCatchUp = freshnessCatchUp;
        this.candidateIntake = candidateIntake;
        this.candidateFeed = candidateFeed;
    }

    /**
     * 활성 지수 전체를 리밸런싱한다.
     *
     * <p>한 지수의 실패가 다른 지수를 막지 않는다. 지수는 서로 독립이며, 코스피200 소스가
     * 깨졌다고 S&amp;P500까지 갱신하지 않을 이유가 없다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        return run(asOf, triggeredBy, null);
    }

    /** 지수 하나를 손으로 고르면 정기 실행(활성 지수 전체)과 범위가 다르다. 기준일은 기록 계층이 본다. */
    public static BatchExecutionRecorder.DeclaredScope scopeOf(String onlyCode) {
        return BatchExecutionRecorder.DeclaredScope.byArgs(onlyCode == null);
    }

    /**
     * 지수 하나만 골라 리밸런싱한다. <b>비활성 지수도 고를 수 있다.</b>
     *
     * <p>비활성인 채로 채우는 상태가 실재하기 때문이다 — 미국은 유니버스 구성만 쌓고 시세·재무는
     * 아직 모으지 않는다. 데이터를 모으기 전에도 구성을 쌓아 두는데, 켜 버리면 편입 종목이
     * <b>스크리닝 모집단</b>에 들어와 지표 하나 없는 종목이 0점·전 조건 판정불가로 화면에
     * 실린다(ADR-0087).
     *
     * <p><b>MUST NOT: 그 이유를 「지나간 시점의 구성은 사후 복원이 불가능하다」로 적지 않는다 —
     * 2026-09-10에 거짓으로 판명됐다</b>(ADR-0135 · 소스가 그날 명부를 준다). 남는 이유는
     * <b>관측이 공짜이고 정확하다</b>는 것이다.
     *
     * <p>MUST NOT: 이 경로를 스케줄러에 걸지 않는다. 손으로 고른 지수 하나를 채우는 수단이다.
     * 자동 실행은 활성 지수와 <b>이름 붙은 후보 풀</b>({@link #CANDIDATE_POOLS} · ADR-0178)만 돈다 — 후보 풀은 비활성인 채로
     * 화해만 하므로 "활성 = 스크리닝 모집단"의 뜻은 그대로 한 가지다.
     *
     * @param onlyCode 채울 지수 코드. {@code null}이면 활성 지수 전체다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           String onlyCode) {
        List<UniverseTarget> targets =
                onlyCode == null ? scheduledTargets() : oneUniverse(onlyCode);
        boolean candidatePool = targets.stream().anyMatch(t -> CANDIDATE_POOLS.contains(t.universeCode()));
        Instant runStart = candidatePool ? candidateIntake.now() : null;
        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size(), scopeOf(onlyCode));

        // 이 잡의 작업 단위는 지수다. 종목 격리를 쓰지 않으므로 process()를 부르지 않지만,
        // 계획은 똑같이 신고한다 — 안 하면 이 잡만 화면에서 분모 없이 뜬다.
        execution.plan(CollectionPhase.UNIVERSE, targets.size());

        int succeeded = 0;
        int failed = 0;
        long changedRows = 0;
        try {
            for (UniverseTarget target : targets) {
                // 이 잡은 process()도 루프 안 stage()도 쓰지 않아 자동 게이트가 안 닿는다.
                execution.abortIfAbandoned();
                try {
                    UniverseReconciler.Result result = rebalanceOne(target, asOf);
                    changedRows += result.addedCount() + result.removedCount();
                    succeeded++;
                    if (result.changed()) {
                        alert(execution, AlertEvent.toOperator(
                                AlertSeverity.INFO,
                                target.universeCode() + " 구성종목 변경",
                                "편입 %d, 제외 %d (기준일 %s)"
                                        .formatted(result.addedCount(), result.removedCount(), asOf),
                                JOB_NAME + ':' + target.universeCode() + ':' + asOf));
                    }
                } catch (BatchShutdownException e) {
                    // MUST: 아래 두 catch보다 앞이다. 뒤에 두면 종료 신호가 "갱신 거부"로
                    // 둔갑해 가짜 알림이 나간다.
                    throw e;
                } catch (DataSourceException e) {
                    // MUST: 이 판정이 먼저다. 종료가 fetch 도중에 들어오면 그 실패는 소스의
                    // 문제가 아닌데, 여기 그대로 흘리면 "소스 조회에 실패했다"가 나간다.
                    execution.abortIfAbandoned();
                    failed++;
                    log.error("유니버스 갱신 실패. universe={} kind={} source={}",
                            target.universeCode(), e.kind(), e.sourceName(), e);
                    alert(execution, AlertEvent.toOperator(
                            AlertSeverity.ERROR,
                            target.universeCode() + " 구성종목 갱신 실패",
                            "소스 조회에 실패했다: %s (%s). 이전 구성으로 스크리닝이 계속된다."
                                    .formatted(e.getMessage(), e.sourceName()),
                            JOB_NAME + ':' + target.universeCode() + ':' + asOf));
                } catch (RuntimeException e) {
                    // 안전장치(제외 비율 초과)에 걸린 경우가 여기 온다. 조용히 넘기지 않는다.
                    // 다만 종료가 만든 실패는 거부가 아니다 — 풀이 닫히며 나는
                    // CannotCreateTransactionException이 정확히 여기 떨어진다.
                    execution.abortIfAbandoned();
                    failed++;
                    log.error("유니버스 갱신이 거부되었다. universe={}", target.universeCode(), e);
                    alert(execution, AlertEvent.toOperator(
                            AlertSeverity.ERROR,
                            target.universeCode() + " 구성종목 갱신 거부",
                            e.getMessage(),
                            JOB_NAME + ':' + target.universeCode() + ':' + asOf));
                }
            }
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }

        BatchStatus status = finish(execution, succeeded, failed, changedRows);

        // 편입이 바뀐 직후가 결손이 생기는 유일한 순간이다.
        //
        // MUST: 이 호출이 여기 있는 이유는 「스케줄러 말고도 이 잡을 부르는 길이 있다」이다.
        // 스케줄러에 걸어 뒀더니 리밸런싱 15회 중 손 9·관리자 2가 꼬리를 안 탔다 — 그 11회의
        // 신규 편입은 원자료가 빈 채로 화면에 들어왔고, 아무 데도 안 떴다.
        //
        // MUST: finish() 뒤다. 앞에 두면 이 잡의 실행 행이 꼬리 내내 RUNNING으로 남고,
        // ops/lib.sh가 그 행으로 「도는 배치가 있다」를 판정해 배포 창이 달라진다
        // (실측 리밸런싱 소요는 15회 중 최장 26초인데 꼬리는 분 단위다).
        //
        // MUST NOT: 중단(BatchShutdownException) 때 돌지 않는다 — 위 지수 루프의 catch가
        // 그대로 되던지므로 여기 못 온다. 덜 반영된 명부로 재면 곧 편출될 종목에 콜을 태운다.
        membershipGapFiller.fillAndAlert(asOf, triggeredBy);
        // 원자료가 이미 있던 편입(재편입 · 일괄 적재로 행이 있는 종목)을 정기 범위로 맞춘다(ADR-0177) — 결손 갈래는 0행만 채운다.
        // 결손 보충 뒤라 그 갈래가 방금 넣은 0행 종목도 이 술어에 들어온다(initial-load는 표지를 안 쓴다 · 같은 값을 한 번 더 맞춘다).
        // 따로 부르는 이유: 이 단계의 실패가 「편입 결손 보충 실패」 알림으로 읽히지 않게. 둘 다 던지지 않는다.
        freshnessCatchUp.runAfterRebalance(triggeredBy);
        // 후보 풀을 돈 실행이면 그 실행이 만든 미국 행에 업종·재무를 한 번 붙인다(ADR-0178) — 손으로 US_LISTED 하나를 골라도 같다.
        // 던지지 않는다(리밸런싱은 이미 끝났다 · 실패는 그 안에서 알린다).
        if (candidatePool) {
            candidateIntake.run(asOf, runStart);
        }
        return status;
    }

    /**
     * 정기 경로의 대상 — 활성 지수 전체와, {@link UsCandidateFeed}가 켜졌으면 후보 풀({@link #CANDIDATE_POOLS}). 후보 풀이 이미
     * 활성이면(누가 켰다) 한 번만 돈다. 후보 풀은 지수 루프 안이라 꼬리(결손 보충·업종·따라잡기)가 한 번만 돈다.
     */
    private List<UniverseTarget> scheduledTargets() {
        List<UniverseTarget> targets = new ArrayList<>(activeUniverses());
        if (candidateFeed.enabled()) {
            for (String code : CANDIDATE_POOLS) {
                if (targets.stream().anyMatch(t -> t.universeCode().equals(code))) {
                    continue;
                }
                // 그 풀의 소스가 없는 환경(Alpaca 키 없음 — 시험·키 없는 개발)은 미국 순위 자체가 없다. 운영에서 키가 빠졌으면 활성인
                // US_TOP1000이 같은 소스라 먼저 실패로 알린다 — 여기서 실패를 하나 더 만들지 않는다.
                if (registry.findUniverseSource(code).isEmpty()) {
                    log.info("후보 풀 {}의 소스가 등록되지 않아 정기 리밸런싱에서 뺀다.", code);
                    continue;
                }
                targets.addAll(oneUniverse(code));
            }
        }
        return targets;
    }

    /**
     * 알림을 보내되 <b>종료 중에는 로그로 내린다.</b>
     *
     * <p>이 잡만 {@code alertPort}를 직접 부른다 — 작업 단위가 종목이 아니라 지수여서
     * dedupeKey를 지수 코드로 짓기 때문이다. 그래서 {@code BatchExecutionContext}의
     * {@code alertOperator} 가드가 안 닿고, 여기서 같은 판단을 다시 한다. 종료 경로에서
     * 알림 포트를 부르지 않는 이유는 ADR-0078에 있다 — 동기 전송이고 웹훅이 최악 15초라
     * 종료 예산을 그만큼 먹는다.
     */
    private void alert(BatchExecutionContext execution, AlertEvent event) {
        if (execution.abandonedOnShutdown()) {
            log.warn("종료 중이라 알림을 보내지 않는다 — 이 로그가 유일한 기록이다. 제목={} 본문={}",
                    event.title(), event.body());
            return;
        }
        alertPort.send(event);
    }

    private UniverseReconciler.Result rebalanceOne(UniverseTarget target, LocalDate asOf)
            throws DataSourceException {
        UniverseDataSource source = registry.universeSource(target.universeCode());
        UniverseSnapshot snapshot = source.fetchConstituents(target.universeCode(), asOf);

        // 티커 해석이 먼저다. universe_membership이 stock_id를 FK로 참조한다.
        List<Long> stockIds =
                stockRegistrar.resolveOrCreate(target.marketCode(), snapshot.tickers());
        return reconciler.reconcile(snapshot, stockIds);
    }

    /**
     * 실행을 마감한다.
     *
     * <p>이 배치의 처리 단위가 종목이 아니라 지수여서 {@code process()}를 쓰지 않고 스스로
     * 센다. 그래서 집계를 {@link BatchExecutionContext#countExternally}로 넘겨준 뒤 마감한다 —
     * 그러지 않으면 이 잡만 화면에서 단계별 집계 없이 뜬다.
     *
     * <p>MUST NOT: {@code execution.finish()}를 쓰지 않는다. 그쪽은 부분 실패에 요약 알림을
     * 한 건 보내는데, 이 잡은 이미 <b>지수마다</b> 실패를 알렸다. 둘 다 나가면 지수 하나가
     * 깨진 날 같은 사실이 두 번 온다. 카운터만 넘기고 마감은 직접 하는 이유가 그것이다.
     */
    private BatchStatus finish(BatchExecutionContext execution, int succeeded, int failed,
                               long changedRows) {
        execution.countExternally(CollectionPhase.UNIVERSE, succeeded, failed, changedRows);
        BatchStatus status = execution.finishQuietly();
        if (status == BatchStatus.PARTIAL_SUCCESS) {
            log.warn("유니버스 리밸런싱 부분 실패. 성공={} 실패={}", succeeded, failed);
        }
        return status;
    }

    /** 활성 지수와 그 지수가 속한 시장. 새 지수를 추가해도 코드를 고치지 않는다. */
    private List<UniverseTarget> activeUniverses() {
        return jdbcTemplate.query("""
                SELECT code, market_code FROM universe WHERE is_active ORDER BY code
                """,
                (rs, rowNum) -> new UniverseTarget(
                        rs.getString("code"), rs.getString("market_code")));
    }

    /**
     * 이름으로 고른 지수 하나. <b>활성 여부를 보지 않는다.</b>
     *
     * <p>없는 코드면 빈 목록이 아니라 예외다 — 오타 하나가 "대상 0건 성공"으로 끝나면
     * 채웠다고 믿게 된다. {@code BootstrapRunner}의 인자 가드가 같은 자리를 지킨다.
     */
    private List<UniverseTarget> oneUniverse(String universeCode) {
        List<UniverseTarget> found = jdbcTemplate.query("""
                SELECT code, market_code, is_active FROM universe WHERE code = ?
                """,
                (rs, rowNum) -> {
                    if (!rs.getBoolean("is_active")) {
                        log.info("{}는 비활성이다 — 구성만 쌓고 수집·스크리닝에는 넣지 않는다.",
                                rs.getString("code"));
                    }
                    return new UniverseTarget(rs.getString("code"), rs.getString("market_code"));
                }, universeCode);
        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                    "그런 유니버스가 없다: " + universeCode + " — universe 테이블의 code를 확인하라.");
        }
        return found;
    }

    private record UniverseTarget(String universeCode, String marketCode) {
    }

    /** 관리자 화면이 쓸 요약. 어느 지수에 몇 종목이 편입되어 있는가. */
    public Map<String, Integer> currentSizes() {
        Map<String, Integer> sizes = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT universe_code, count(*) AS member_count FROM universe_membership
                WHERE effective_to IS NULL GROUP BY universe_code ORDER BY universe_code
                """, rs -> {
            sizes.put(rs.getString("universe_code"), rs.getInt("member_count"));
        });
        return sizes;
    }
}
