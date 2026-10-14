package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.AdjustmentRecalculator;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 원장에서 수정계수를 다시 짓는다. <b>저장된 시세를 소급해 다시 쓰는 유일한 경로다</b>(ADR-0049).
 *
 * <h2>왜 이 잡만 화면에 없는가 — 그리고 스케줄러에는 왜 있는가</h2>
 * <p>MUST NOT: {@link AdminJobLauncher}의 카탈로그에 넣지 않는다. 버튼 한 번이 전 구간
 * {@code adj_close}를 다시 쓰고 <b>보정된 종목의 {@code indicator_value}를 보존 창 전체에서
 * 지운다</b>({@code StoredIndicatorInvalidationPort}의 {@code >=}). 25만 행 남짓이고 다시
 * 채우는 데 실측 2.6~8시간이 든다. 다른 잡은 잘못 눌러도 다시 누르면 되지만 이것은
 * <b>누른 뒤에 백필이 따라와야 끝나는</b> 잡이다. {@code AdminJobLauncherTest}가 카탈로그를
 * 이름으로 못박고 있어, 넣으면 그 테스트가 함께 깨진다.
 *
 * <p><b>스케줄러 금지는 풀렸고, 푼 것은 「백필이 따라온다」를 구조로 만든 것이다.</b>
 * {@code PipelineScheduler}가 {@link #runAndReport}로 불러 <b>무효화된 종목을 받아 백필까지
 * 이어 붙인다</b> — 위 문단이 금지의 근거로 든 것이 정확히 그 「따라오지 않음」이었다.
 * 근거와 남은 한계는 ADR-0116에 있다. MUST NOT: 그 연결을 끊고 이 잡만 스케줄에 남기지
 * 않는다 — 그러면 금지의 근거가 그대로 되살아난다.
 *
 * <p>손으로 도는 경로는 그대로다: {@code BootstrapRunner}의 {@code --job=adjustment-rebuild}.
 * {@code .claude/hooks/guard-rebuild-without-dump.sh}가 <b>덤프가 하나도 없을 때</b> 그 명령을
 * 막는다(ADR-0049가 요구하는 것이 "첫 실행 전에"다). <b>IMPORTANT — 그 훅은 셸 명령을 보므로
 * 스케줄 경로를 못 본다.</b> 런타임 가드는 없다.
 *
 * <h2>왜 원장에 행이 있는 종목만 돌지 않는가</h2>
 * <p>{@link AdjustmentRecalculator#rebuild}는 적용 대상이 하나도 없으면 <b>계수를 전량 1로
 * 되돌린다</b> — 그것이 반영을 취소하는 방법이다. 그래서 보존 창 편입 전량을 도는 것이 곧
 * <i>"원장을 거치지 않은 계수가 없다"</i>의 검증이 된다. 원장에 없는 종목은 갱신 0행으로
 * 끝나므로 무효화도 일어나지 않는다.
 *
 * <p>{@code --tickers}로 좁힐 수 있는 이유는 <b>되돌리기와 정정</b>이다. 판정 하나를 고친 뒤
 * 그 종목만 다시 짓고 그 종목만 백필하면 몇 분에 끝난다({@code IndicatorBackfillJob}도 같은
 * 인자를 받는다). 좁힐 수 없으면 오탐 하나를 고치는 비용이 8시간이 되고, 그러면 아무도 고치지 않는다.
 */
@Component
public class AdjustmentRebuildJob {

    public static final String JOB_NAME = "adjustment-rebuild";

    /** 알림 본문에 이름으로 적을 종목 수. 넘는 것은 로그가 답한다. */
    private static final int STOCKS_IN_ALERT = 20;

    private static final Logger log = LoggerFactory.getLogger(AdjustmentRebuildJob.class);

    private final AdjustmentRecalculator recalculator;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;

    public AdjustmentRebuildJob(AdjustmentRecalculator recalculator, StockRegistrar stockRegistrar,
                                BatchExecutions batchExecutions) {
        this.recalculator = recalculator;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
    }

    /** 계수가 실제로 바뀐 종목. 무효화가 일어난 종목과 같은 집합이다. */
    private record Rebuilt(long stockId, String ticker, long changedRows, LocalDate earliest) {
    }

    /**
     * 이 실행이 무효화한 것. <b>백필로 다시 채워야 할 대상이다.</b>
     *
     * @param tickers  계수가 바뀐 종목. 비어 있으면 지워진 지표도 없다.
     * @param earliest 그중 가장 이른 무효화 시작일. 비어 있으면 {@code null}이다.
     *                 <b>보존 경계보다 앞일 수 있다</b> — 백필 구간을 정할 때 늦은 쪽을 쓴다.
     */
    public record Invalidation(BatchStatus status, List<String> tickers, LocalDate earliest) {

        public boolean isEmpty() {
            return tickers.isEmpty();
        }
    }

    /** 정기 실행(14:00 사슬)은 티커·유니버스를 안 준다. 기준일과 꼬리는 기록 계층이 본다. */
    public static BatchExecutionRecorder.DeclaredScope scopeOf(List<String> tickers, String universeCode) {
        return BatchExecutionRecorder.DeclaredScope.byArgs((tickers == null || tickers.isEmpty())
                && (universeCode == null || universeCode.isBlank()));
    }

    /**
     * 원장의 적용 대상으로 계수와 수정종가를 다시 짓는다.
     *
     * @param tickers      비면 편입 중인 활성 종목 전체
     * @param universeCode 손으로 고른 유니버스. 주면 <b>활성 여부를 보지 않는다</b>(ADR-0090).
     *                     <b>이 잡의 「전량이 곧 검증」이 그만큼 갈린다</b> — 인자 없이 도는
     *                     실행은 여전히 활성 유니버스만 보므로, 비활성 시장의 계수는 손으로
     *                     이 인자를 줄 때만 다시 지어진다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           List<String> tickers, String universeCode) {
        return runAndReport(asOf, triggeredBy, tickers, universeCode).status();
    }

    /**
     * {@link #run}과 같은 일을 하되 <b>무효화된 종목까지 돌려준다.</b>
     *
     * <p>스케줄러가 백필을 이어 붙이려면 이것이 필요하다. {@code run}이 {@link BatchStatus}만
     * 내던 시절에는 <b>무엇을 다시 채워야 하는지가 알림 본문에만 있었고</b>, 그래서 사람이
     * 그것을 읽어 명령을 다시 쳐야 했다 — 그 손이 빠지면 지운 채로 남는다.
     *
     * <p>MUST NOT: 반환값이 비었다는 것을 "재구축이 아무것도 안 했다"로 읽지 않는다. 계수가
     * 원장과 이미 일치하면 갱신 0행이고 <b>그것이 정상이자 멱등의 증거다.</b>
     */
    public Invalidation runAndReport(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                                     List<String> tickers, String universeCode) {
        BatchExecutionRecorder.DeclaredScope scope = scopeOf(tickers, universeCode);
        List<SecurityRef> targets =
                stockRegistrar.toSecurityRefs(resolveTargets(tickers, universeCode));
        if (targets.isEmpty()) {
            log.info("재구축할 대상이 없다. 유니버스를 먼저 갱신한다.");
            return new Invalidation(
                    batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0, scope).finish(),
                    List.of(), null);
        }

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size(), scope);
        List<Rebuilt> rebuilt = new ArrayList<>();
        int appliedEvents = 0;
        // 종목별 무변경 줄을 DEBUG로 내리면서 그 수가 갈 곳이 필요해졌다.
        // 지역 변수로 센다. **원래 근거였던 「RebuildResult에 필드를 더하면 테스트 13곳이
        // 깨진다」는 거짓이다** — 그 record는 테스트에 0회 나오고 생성 자리가 main에 하나뿐이라
        // 필드를 더해도 아무것도 안 깨진다(2026-09-13 컴파일로 확인). 13이라는 수는 **다른
        // 메서드**(detectCandidates)에 붙어 있었고 그쪽은 실제로 12개소가 깨졌다.
        // MUST NOT: 이 수를 RebuildResult로 올리지 않는다. 근거는 비용이 아니라 **경로**다 —
        // rebuild()는 withoutUnjudgeable을 안 지나므로 그 record에 실으면 언제나 0이 된다.
        // 이 루프는 순차라 평범한 int로 충분하다. 나중에 워커로 흩으면(ADR-0138이 지표
        // 백필에 한 것처럼) 이 카운터는 **예외 없이 조용히 틀린다** — 그때 함께 고친다.
        int alreadyMatching = 0;

        log.info("수정계수 재구축을 시작한다. 대상 {}종목 — 원장의 적용 대상에서 매번 다시 짓는다."
                + " 갱신 0행이 정상이고 그 0이 멱등의 증거다.", targets.size());

        try {
            execution.plan(CollectionPhase.ADJUSTMENT, targets.size());
            execution.stage("종목별 수정계수 재구축");

            for (SecurityRef target : targets) {
                // 람다 밖에서 결과를 받아야 알림을 걸 수 있다. process는 성공 여부만 준다.
                List<AdjustmentRecalculator.RebuildResult> captured = new ArrayList<>(1);
                execution.process(target, CollectionPhase.ADJUSTMENT, security -> {
                    AdjustmentRecalculator.RebuildResult result =
                            recalculator.rebuild(security.stockId());
                    captured.add(result);
                    return result.changedRows();
                });
                if (captured.isEmpty()) {
                    continue;
                }
                AdjustmentRecalculator.RebuildResult result = captured.get(0);
                appliedEvents += result.appliedEvents();
                if (result.changedRows() > 0) {
                    rebuilt.add(new Rebuilt(target.stockId(),
                            target.ticker(), result.changedRows(), result.earliestChanged()));
                } else {
                    alreadyMatching++;
                }
            }

            alertOnInvalidation(execution, rebuilt, appliedEvents, targets.size(), alreadyMatching);
        } catch (DataSourceException e) {
            // 외부 호출이 없으므로 정상적으로는 도달하지 않는다. StockTask의 시그니처가 이 예외를
            // 선언하고 있어 형식상 잡을 뿐이며, 실제로 온다면 그것 자체가 이상 신호다.
            execution.failFatally(e);
            return new Invalidation(BatchStatus.FAILED, List.of(), null);
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        // 화해로 넘기는 것은 보존 창 편입뿐이다 — 화해는 그 모집단만 다시 쓰고, 창 밖 종목(순위 후보 · ADR-0178)의 지표 행은 그날 편입으로
        // 거르는 읽기 경로 어디에도 안 닿는다(FinancialRefillJob.outsideWindowLine과 같은 사실).
        Set<Long> window = rebuilt.isEmpty() ? Set.of() : new HashSet<>(stockRegistrar.windowMemberIds());
        List<Rebuilt> refillable = rebuilt.stream().filter(r -> window.contains(r.stockId())).toList();
        return new Invalidation(execution.finish(),
                refillable.stream().map(Rebuilt::ticker).toList(),
                refillable.stream().map(Rebuilt::earliest).filter(java.util.Objects::nonNull)
                        .min(LocalDate::compareTo).orElse(null));
    }

    /**
     * 무효화가 일어났으면 <b>백필이 남았다는 것</b>을 알린다.
     *
     * <p>MUST NOT: 이것을 로그로만 두지 않는다. 무효화는 지우기만 하므로, 백필이 따라오지 않으면
     * 그 종목이 화면에서 통째로 사라지는데 <b>에러도 경고도 나지 않는다</b>(phase 기준 §P9).
     * 이 잡의 성공은 "계수를 고쳤다"이고 그 상태는 아직 <b>일이 끝나지 않은</b> 상태다.
     */
    private void alertOnInvalidation(BatchExecutionContext execution, List<Rebuilt> rebuilt,
                                     int appliedEvents, int targetCount, int alreadyMatching) {
        // 종목별 무변경 줄이 DEBUG로 내려갔으므로 그 수가 여기서만 보인다.
        // MUST: 양쪽 갈래에서 찍는다 — 한쪽에만 두면 「다시 지은 것이 있는 날」의 무변경 수가
        // 어디에도 안 남고, 그 수가 곧 멱등의 증거다.
        log.info("수정계수 재구축 끝. 대상 {}종목 · 이미 일치 {} · 다시 지음 {} · 적용 대상 사건 {}건"
                        + " — 종목별 무변경은 DEBUG다(.env의 LOG_ADJUSTMENT=DEBUG).",
                targetCount, alreadyMatching, rebuilt.size(), appliedEvents);
        if (rebuilt.isEmpty()) {
            return;
        }
        long changedRows = rebuilt.stream().mapToLong(Rebuilt::changedRows).sum();
        LocalDate earliest = rebuilt.stream()
                .map(Rebuilt::earliest)
                .min(LocalDate::compareTo)
                .orElseThrow();

        StringBuilder body = new StringBuilder(
                ("%d종목의 수정계수를 다시 지었다(갱신 %d행 · 적용 사건 %d건 · 최초 변경일 %s).\n"
                        + "**그 %d종목의 지표가 보존 창 전체에서 지워졌다.** 정기 사슬(평일 14:00)이면"
                        + " 같은 사슬이 곧바로 화해한다. 손 실행이면 다음 평일 14:20 화해가 채우고,"
                        + " 그 전에 채우려면 평일 13:30~15:40 밖에서:\n"
                        + "  ./gradlew bootRun --args='--spring.profiles.active=bootstrap"
                        + " --job=indicator-reconcile --tickers=<아래 종목>'\n")
                        .formatted(rebuilt.size(), changedRows, appliedEvents, earliest,
                                rebuilt.size()));
        rebuilt.stream().limit(STOCKS_IN_ALERT).forEach(item -> body.append(
                "- %s: %d행 (%s부터)\n".formatted(item.ticker(), item.changedRows(), item.earliest())));
        if (rebuilt.size() > STOCKS_IN_ALERT) {
            body.append("나머지 %d종목은 로그에 있다.".formatted(rebuilt.size() - STOCKS_IN_ALERT));
        }
        execution.alertOperator(AlertSeverity.WARNING, "수정계수 재구축 — 화해가 채운다",
                body.toString());
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * 이 잡은 <b>재실행에서 0행이 정상</b>이므로 오타로 대상이 빈 것과 이미 일치하는 것이
     * 로그에서 구분되지 않는다({@link CorporateActionJob}과 같은 자리다).
     */
    private List<Long> resolveTargets(List<String> tickers, String universeCode) {
        if (universeCode != null && !universeCode.isBlank()) {
            List<String> requested = tickers == null ? List.of() : tickers;
            List<Long> members = stockRegistrar.oneUniverseMemberIds(universeCode, requested);
            if (members.isEmpty()) {
                // MUST: 0건이면 예외다. oneUniverseMemberIds는 빈 목록을 낼 뿐이라,
                // 안 막으면 위쪽 targets.isEmpty() 갈래로 빠져 SUCCESS로 끝난다(ADR-0088 §규칙).
                throw new IllegalArgumentException(
                        "유니버스 '" + universeCode + "'에 대상이 하나도 없다"
                                + (requested.isEmpty() ? "" : " (티커 " + requested + ")")
                                + " — 유니버스 코드와, 그 시장이 CollectedMarkets에 있는지 확인하라."
                                + " 대상 0건으로 성공시키지 않는다.");
            }
            return members;
        }
        if (tickers == null || tickers.isEmpty()) {
            // 보존 창 편입 전원이다 — 지금 편입만이 아니다. 일일 갱신이 과거 편입의 시세 구멍도
            // 채우는데(StockRegistrar.priceGapRuns) 새 봉은 계수 1로 들어간다. 지금 편입만 돌면 그
            // 봉이 적용된 사건 앞에 놓여도 영영 안 바로잡힌다. 원장 수집은 지금 편입만 돌므로 과거
            // 편입은 <b>저장된 원장</b>으로 다시 짓는다 — 편출 뒤 사건은 원래 안 묻는다.
            // 창 밖이라도 적용 대기(APPROVED) 행이 있는 종목은 함께 — 미국 순위 후보의 분할 다리가 쓴 행이다(ADR-0178). 봉이 없으면
            // 다시 쓸 시세가 없고 표시만 한다(rebuild의 「갱신 0행이어도 표시」) — 그래야 순위의 주식수 보정이 그 행을 읽는다.
            java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>(stockRegistrar.windowMemberIds());
            ids.addAll(stockRegistrar.unappliedApprovalIdsOutsideWindow());
            return List.copyOf(ids);
        }
        List<Long> matched = stockRegistrar.activeUniverseMemberIdsByTicker(tickers);
        if (matched.isEmpty()) {
            throw new IllegalArgumentException(
                    "준 티커 중 편입 중인 활성 종목이 하나도 없다: " + tickers
                            + " — 종목코드를 확인하라. 대상 0건으로 성공시키지 않는다.");
        }
        if (matched.size() < tickers.size()) {
            log.warn("티커 {}개 중 {}개만 편입 중인 활성 종목이다. 나머지는 건너뛴다: {}",
                    tickers.size(), matched.size(), tickers);
        }
        return matched;
    }
}
