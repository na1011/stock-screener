package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.FinancialCollector;
import com.stockscreener.pipeline.collector.FinancialWriter;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 이미 저장된 재무제표의 <b>결손 컬럼만</b> 다시 받아 채운다(ADR-0040).
 *
 * <p>스키마에 금액 컬럼이 늘어나면 이미 저장된 행은 그 자리가 NULL인 채로 남는다. 재수집을
 * 돌려도 {@code FinancialWriter}가 "같은 공시일 = 재수집"으로 판정해 아무것도 하지 않기
 * 때문이다 — <b>DART를 1만 콜 태우고 한 행도 바뀌지 않으며, 잡은 성공으로 끝난다.</b>
 * V16이 더한 세 컬럼이 실제로 10,882행 전부 NULL로 남아 Altman Z가 한 종목도 계산되지 않았다.
 *
 * <p>MUST NOT: 이 잡을 {@link InitialLoadJob}에 플래그로 합치지 않는다. 저쪽은 대상 선정이
 * "시세가 없는 종목"이고 시세를 함께 받는다. 여기는 <b>재무만</b> 받고 대상이 편입 전 종목이라
 * 대상도 단계도 다르며, 섞으면 "지금 무엇을 하는 잡인가"를 플래그 조합으로 읽어야 한다.
 *
 * <p>MUST NOT: {@link AdminJobLauncher}의 카탈로그에 넣지 않는다. 종목당 16콜이라 전 종목이면
 * DART 일일 한도의 3분의 1을 한 번에 태운다 — 그 클래스가 {@code InitialLoadJob}을 뺀 이유와
 * 같다. 그 결과 실행 이력 표에는 뜨지만 잡 필터로는 고를 수 없다.
 *
 * <p>이 잡은 한 번 쓰고 버리는 것이 아니다. 영업활동현금흐름(원본 명세 H-05)이 같은 자리를
 * 기다리고 있고, 그때 <b>무효화를 생략해도 되는지 다시 답해야 한다</b>(ADR-0040).
 */
@Component
public class FinancialRefillJob {

    public static final String JOB_NAME = "financial-refill";

    /** 기본 3년. 지표 리더가 읽는 12분기 창을 덮는 최소치다. */
    private static final int DEFAULT_YEARS_BACK = 3;

    private static final Logger log = LoggerFactory.getLogger(FinancialRefillJob.class);

    private final FinancialCollector financialCollector;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;

    public FinancialRefillJob(FinancialCollector financialCollector, StockRegistrar stockRegistrar,
                              BatchExecutions batchExecutions) {
        this.financialCollector = financialCollector;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
    }

    /**
     * 편입 중인 활성 종목의 재무 결손을 채운다.
     *
     * @param asOf       기준일. 여기서 {@code yearsBack}만큼 거슬러 올라간다.
     * @param yearsBack  몇 년 거슬러 받을지. null이면 기본 3년.
     * @param tickers    비면 전 종목. 값이 있으면 <b>편입 종목과의 교집합</b>만 처리한다.
     * @param universeCode <b>비활성 유니버스를 대상으로 잡는 유일한 길이다.</b> null이면
     *                     활성 유니버스의 편입 종목이다 — 그 갈래는 미국에 한 건도 안 닿는다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           Integer yearsBack, List<String> tickers, String universeCode) {
        int years = yearsBack == null ? DEFAULT_YEARS_BACK : yearsBack;
        if (years < 0) {
            throw new IllegalArgumentException("재무 연수는 음수일 수 없다: " + years);
        }

        List<SecurityRef> targets =
                stockRegistrar.toSecurityRefs(resolveTargets(tickers, universeCode));
        if (targets.isEmpty()) {
            log.info("편입 중인 활성 종목이 없다. 채울 것이 없다.");
            return batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0).finish();
        }

        int fromFiscalYear = asOf.getYear() - years;
        // 어댑터가 fromFiscalYear부터 올해까지 양끝을 포함해 돌고, 연도마다 보고서 코드 넷을
        // 부른다(Q4는 로컬 유도라 콜이 없다). 착수 전에 쿼터를 가늠할 수 있어야 한다.
        int estimatedCalls = targets.size() * (asOf.getYear() - fromFiscalYear + 1) * 4;
        log.info("재무 결손 보정을 시작한다. 대상 {}종목, {}년 이후, 예상 호출 약 {}회",
                targets.size(), fromFiscalYear, estimatedCalls);

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size());
        execution.plan(CollectionPhase.FINANCIAL, targets.size());
        execution.stage("종목별 재무 결손 보정");

        // 정정을 반영하면 그 종목의 지표가 보존 창 전체에서 지워진다. 그 수를 세지 않으면
        // 백필이 남았다는 것을 알릴 수 없고, 그러면 그 종목이 화면에서 조용히 사라진다.
        AtomicInteger revised = new AtomicInteger();
        AtomicInteger filled = new AtomicInteger();
        AtomicInteger mismatched = new AtomicInteger();
        List<String> revisedTickers = new ArrayList<>();

        try {
            for (SecurityRef target : targets) {
                execution.process(target, CollectionPhase.FINANCIAL, security -> {
                    FinancialWriter.Outcome outcome = financialCollector.collectDetailed(
                            security, fromFiscalYear, FinancialWriter.WriteMode.FILL_MISSING);
                    if (outcome.revised() > 0) {
                        revised.addAndGet(outcome.revised());
                        revisedTickers.add(security.ticker() + "(" + outcome.revised() + "기간)");
                    }
                    filled.addAndGet(outcome.filled());
                    mismatched.addAndGet(outcome.mismatched());
                    return outcome.changed();
                });
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }

        String note = "연도=%d~%d 대상=%d종목 정정=%d 채움=%d 불일치=%d"
                .formatted(fromFiscalYear, asOf.getYear(), targets.size(),
                        revised.get(), filled.get(), mismatched.get());
        log.info("재무 결손 보정을 마쳤다. {}", note);
        alertOnInvalidation(execution, revisedTickers, revised.get());
        return execution.finish(note);
    }

    /**
     * 정정을 반영했으면 <b>백필이 남았다는 것</b>을 알린다.
     *
     * <p>MUST NOT: 이것을 로그로만 두지 않는다. 정정 반영은 그 종목의 {@code indicator_value}를
     * <b>회계기간 시작일 이후 전부</b> 지우는데, 사업보고서면 그것이 보존 창 전량이다. 백필이
     * 따라오지 않으면 그 종목이 화면에서 통째로 사라지고 <b>에러도 경고도 나지 않는다.</b>
     * 이 잡의 성공은 "정정을 반영했다"이고 그 상태는 아직 <b>일이 끝나지 않은</b> 상태다.
     *
     * <p>같은 이유로 {@link AdjustmentRebuildJob}이 이미 같은 형태의 알림을 낸다 — 두 잡이
     * 같은 결과를 만드는데 한쪽만 말하면, 말하지 않는 쪽으로 사고가 몰린다.
     */
    private void alertOnInvalidation(BatchExecutionContext execution, List<String> revisedTickers,
                                     int revisedPeriods) {
        if (revisedTickers.isEmpty()) {
            return;
        }
        String tickers = String.join(", ", revisedTickers);
        execution.alertOperator(AlertSeverity.WARNING, "정정공시 반영 — 백필이 남았다",
                ("""
                %d종목의 정정공시를 반영했다(%d기간): %s
                **그 종목의 지표가 보존 창 전체에서 지워졌다.** 백필이 따라오지 않으면 화면에서 \
                통째로 사라지는데 에러도 경고도 나지 않는다. 영업활동현금흐름은 이 경로가 \
                채우지 못하므로 전체 재무제표를 함께 돌린다:
                  ./gradlew bootRun --args='--spring.profiles.active=bootstrap --server.port=0 \
                --job=full-statement-refill --tickers=<위 종목>'
                  ./gradlew bootRun --args='--spring.profiles.active=bootstrap --server.port=0 \
                --job=backfill --from=<보존 창 첫 거래일> --tickers=<위 종목>'
                """).formatted(revisedTickers.size(), revisedPeriods, tickers));
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * 대상 0건으로 조용히 성공하면 오타 하나가 "보정을 돌렸는데 아무것도 안 바뀌었다"로
     * 끝나는데, 그것은 이 잡이 고치려는 결함과 <b>정확히 같은 모양</b>이다.
     * 일부만 매칭된 경우도 로그로 알린다 — 편입되지 않은 종목을 준 것이다.
     */
    private List<Long> resolveTargets(List<String> tickers, String universeCode) {
        if (universeCode != null) {
            // MUST: 유니버스를 명시하면 활성 여부를 보지 않는다. oneUniverseMemberIds가
            // 그렇게 만들어져 있고(InitialLoadJob이 같은 술어를 쓴다) 그것이 이 갈래의
            // 존재 이유다 — **비활성** 유니버스는 아래 activeUniverseMemberIds가 u.is_active를
            // 조인해 한 건도 안 닿으므로, 그것을 채우려면 이 갈래뿐이다.
            //
            // MUST NOT: 이 갈래를 "미국 전용"으로 읽지 않는다. US_TOP1000은 2026-08-31에
            // 활성이 됐고(ADR-0107), 그때부터 **인자 없는 기본 경로의 대상이 미국을 포함한다**
            // — 지금 그 경로는 국내 350 + 미국 1,000이고 미국 몫은 DART가 아니라 SEC EDGAR를
            // 부른다. 인자 없이 치면 국내만 돈다고 읽으면 소요와 콜을 둘 다 적게 잡는다.
            //
            // 0건이면 조용히 성공시키지 않는다 — 그것은 이 잡이 고치려는 결함과 같은 모양이다.
            List<Long> members = stockRegistrar.oneUniverseMemberIds(
                    universeCode, tickers == null ? List.of() : tickers);
            if (members.isEmpty()) {
                throw new IllegalArgumentException(
                        "유니버스 '" + universeCode + "'에 대상이 하나도 없다"
                                + (tickers == null || tickers.isEmpty()
                                        ? "" : " (티커 " + tickers + ")")
                                + " — 유니버스 코드와, 그 시장이 CollectedMarkets에 있는지"
                                + " 확인하라. 대상 0건으로 성공시키지 않는다.");
            }
            return members;
        }
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
