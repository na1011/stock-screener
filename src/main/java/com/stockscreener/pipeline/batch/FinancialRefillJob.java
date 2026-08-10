package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.FinancialCollector;
import com.stockscreener.pipeline.collector.FinancialWriter;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

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
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           Integer yearsBack, List<String> tickers) {
        int years = yearsBack == null ? DEFAULT_YEARS_BACK : yearsBack;
        if (years < 0) {
            throw new IllegalArgumentException("재무 연수는 음수일 수 없다: " + years);
        }

        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(resolveTargets(tickers));
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

        try {
            for (SecurityRef target : targets) {
                execution.process(target, CollectionPhase.FINANCIAL,
                        security -> financialCollector.collect(security, fromFiscalYear,
                                FinancialWriter.WriteMode.FILL_MISSING));
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        return execution.finish();
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * 대상 0건으로 조용히 성공하면 오타 하나가 "보정을 돌렸는데 아무것도 안 바뀌었다"로
     * 끝나는데, 그것은 이 잡이 고치려는 결함과 <b>정확히 같은 모양</b>이다.
     * 일부만 매칭된 경우도 로그로 알린다 — 편입되지 않은 종목을 준 것이다.
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
