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
import java.util.List;

/**
 * 원장에서 수정계수를 다시 짓는다. <b>저장된 시세를 소급해 다시 쓰는 유일한 경로다</b>(ADR-0049).
 *
 * <h2>왜 이 잡만 화면에 없는가</h2>
 * <p>MUST NOT: {@link AdminJobLauncher}의 카탈로그에 넣지 않는다. 스케줄러에도 넣지 않는다.
 * 버튼 한 번이 전 구간 {@code adj_close}를 다시 쓰고 <b>보정된 종목의
 * {@code indicator_value}를 보존 창 전체에서 지운다</b>({@code StoredIndicatorInvalidationPort}의
 * {@code >=}). 25만 행 남짓이고 다시 채우는 데 실측 2.6~8시간이 든다. 다른 잡은 잘못 눌러도
 * 다시 누르면 되지만 이것은 <b>누른 뒤에 백필이 따라와야 끝나는</b> 유일한 잡이다.
 * {@code AdminJobLauncherTest}가 카탈로그를 이름으로 못박고 있어, 넣으면 그 테스트가 함께 깨진다.
 *
 * <p>대신 {@code BootstrapRunner}의 {@code --job=adjustment-rebuild}가 경로이고,
 * {@code .claude/hooks/guard-rebuild-without-dump.sh}가 <b>덤프가 하나도 없을 때</b> 그 명령을
 * 막는다(ADR-0049가 요구하는 것이 "첫 실행 전에"다).
 *
 * <h2>왜 원장에 행이 있는 종목만 돌지 않는가</h2>
 * <p>{@link AdjustmentRecalculator#rebuild}는 적용 대상이 하나도 없으면 <b>계수를 전량 1로
 * 되돌린다</b> — 그것이 반영을 취소하는 방법이다. 그래서 활성 유니버스 전량을 도는 것이 곧
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
    private record Rebuilt(String ticker, long changedRows, LocalDate earliest) {
    }

    /**
     * 원장의 적용 대상으로 계수와 수정종가를 다시 짓는다.
     *
     * @param tickers 비면 편입 중인 활성 종목 전체
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           List<String> tickers) {
        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(resolveTargets(tickers));
        if (targets.isEmpty()) {
            log.info("재구축할 대상이 없다. 유니버스를 먼저 갱신한다.");
            return batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0).finish();
        }

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size());
        List<Rebuilt> rebuilt = new ArrayList<>();
        int appliedEvents = 0;

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
                    rebuilt.add(new Rebuilt(
                            target.ticker(), result.changedRows(), result.earliestChanged()));
                }
            }

            alertOnInvalidation(execution, rebuilt, appliedEvents);
        } catch (DataSourceException e) {
            // 외부 호출이 없으므로 정상적으로는 도달하지 않는다. StockTask의 시그니처가 이 예외를
            // 선언하고 있어 형식상 잡을 뿐이며, 실제로 온다면 그것 자체가 이상 신호다.
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        return execution.finish();
    }

    /**
     * 무효화가 일어났으면 <b>백필이 남았다는 것</b>을 알린다.
     *
     * <p>MUST NOT: 이것을 로그로만 두지 않는다. 무효화는 지우기만 하므로, 백필이 따라오지 않으면
     * 그 종목이 화면에서 통째로 사라지는데 <b>에러도 경고도 나지 않는다</b>(phase 기준 §P9).
     * 이 잡의 성공은 "계수를 고쳤다"이고 그 상태는 아직 <b>일이 끝나지 않은</b> 상태다.
     */
    private void alertOnInvalidation(BatchExecutionContext execution, List<Rebuilt> rebuilt,
                                     int appliedEvents) {
        if (rebuilt.isEmpty()) {
            log.info("계수가 이미 원장과 일치한다. 적용 대상 사건 {}건 — 다시 쓸 것이 없었고"
                    + " 지표도 지우지 않았다.", appliedEvents);
            return;
        }
        long changedRows = rebuilt.stream().mapToLong(Rebuilt::changedRows).sum();
        LocalDate earliest = rebuilt.stream()
                .map(Rebuilt::earliest)
                .min(LocalDate::compareTo)
                .orElseThrow();

        StringBuilder body = new StringBuilder(
                ("%d종목의 수정계수를 다시 지었다(갱신 %d행 · 적용 사건 %d건 · 최초 변경일 %s).\n"
                        + "**그 %d종목의 지표가 보존 창 전체에서 지워졌다.** 백필이 따라오지 않으면"
                        + " 화면에서 통째로 사라지는데 에러도 경고도 나지 않는다:\n"
                        + "  ./gradlew bootRun --args='--spring.profiles.active=bootstrap"
                        + " --job=backfill --from=<보존 창 첫 거래일> --server.port=0'\n")
                        .formatted(rebuilt.size(), changedRows, appliedEvents, earliest,
                                rebuilt.size()));
        rebuilt.stream().limit(STOCKS_IN_ALERT).forEach(item -> body.append(
                "- %s: %d행 (%s부터)\n".formatted(item.ticker(), item.changedRows(), item.earliest())));
        if (rebuilt.size() > STOCKS_IN_ALERT) {
            body.append("나머지 %d종목은 로그에 있다.".formatted(rebuilt.size() - STOCKS_IN_ALERT));
        }
        execution.alertOperator(AlertSeverity.WARNING, "수정계수 재구축 — 백필이 남았다",
                body.toString());
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * 이 잡은 <b>재실행에서 0행이 정상</b>이므로 오타로 대상이 빈 것과 이미 일치하는 것이
     * 로그에서 구분되지 않는다({@link CorporateActionJob}과 같은 자리다).
     */
    private List<Long> resolveTargets(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return stockRegistrar.activeUniverseMemberIds();
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
