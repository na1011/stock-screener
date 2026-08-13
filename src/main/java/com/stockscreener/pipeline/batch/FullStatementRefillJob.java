package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.FullStatementCollector;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StatementPeriod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 전체 재무제표에서 결손을 받아 채운다. 요약본이 주지 않는 <b>영업활동현금흐름</b>이 목적이다.
 *
 * <p>{@link FinancialRefillJob}과 나뉜 이유는 소스도 쓰기 방식도 다르기 때문이다. 저쪽은
 * 요약본을 받아 정정공시를 반영하고, 여기는 전체본을 받아 <b>정정을 만나면 남긴다</b>. 섞으면
 * "지금 무엇을 하는 잡인가"를 플래그 조합으로 읽어야 하고, 무엇보다 무효화 가지가 다시 열린다.
 *
 * <p>MUST NOT: {@link AdminJobLauncher}의 카탈로그에 넣지 않는다. 전 종목이면 2,100콜이라
 * DART 보수 예산의 5분의 1을 한 번에 태운다 — {@code FinancialRefillJob}을 뺀 이유와 같다.
 *
 * <p>MUST NOT: 이 잡만 돌고 끝내지 않는다. {@code indicator_value}를 건드리지 않으므로
 * <b>백필이 없으면 {@code financial_statement}가 다 차도 화면과 프리셋이 한 글자도 안 바뀐다.</b>
 * 에러도 경고도 나지 않는다.
 */
@Component
public class FullStatementRefillJob {

    public static final String JOB_NAME = "full-statement-refill";

    /**
     * 기본 5년. H-05의 가공법이 {@code sum(operatingCashFlow, 최근 5개 회계연도)}다.
     *
     * <p>{@code fromFiscalYear}부터 <b>올해까지</b> 도므로 실제 호출은 종목당 6이다. 올해분은
     * 아직 사업보고서가 없어 {@code status 013}으로 버려지는데, 그래도 부르는 이유는 결산월이
     * 12월이 아닌 종목의 최신 회계연도가 거기 있을 수 있기 때문이다.
     */
    private static final int DEFAULT_YEARS_BACK = 5;

    private static final Logger log = LoggerFactory.getLogger(FullStatementRefillJob.class);

    private final FullStatementCollector collector;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;

    public FullStatementRefillJob(FullStatementCollector collector, StockRegistrar stockRegistrar,
                                  BatchExecutions batchExecutions) {
        this.collector = collector;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
    }

    /**
     * 편입 중인 활성 종목의 전체 재무제표 결손을 채운다.
     *
     * @param yearsBack       몇 년 거슬러 받을지. null이면 기본 5년
     * @param tickers         비면 전 종목. 값이 있으면 <b>편입 종목과의 교집합</b>만 처리한다
     * @param includeQuarters 분기 보고서도 받을지. <b>티커를 함께 줘야 한다</b> — 아래 참조
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           Integer yearsBack, List<String> tickers, boolean includeQuarters) {
        int years = yearsBack == null ? DEFAULT_YEARS_BACK : yearsBack;
        if (years < 0) {
            throw new IllegalArgumentException("재무 연수는 음수일 수 없다: " + years);
        }
        Set<StatementPeriod> periods = resolvePeriods(includeQuarters, tickers);

        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(resolveTargets(tickers));
        if (targets.isEmpty()) {
            log.info("편입 중인 활성 종목이 없다. 채울 것이 없다.");
            return batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0).finish();
        }

        int fromFiscalYear = asOf.getYear() - years;
        int toFiscalYear = asOf.getYear();
        // MUST: 이 식이 어댑터의 루프와 같은 모양이어야 한다. 보고서 코드 수를 상수로 박으면
        // 요청 기간을 바꿀 때 로그만 조용히 틀린다 — FinancialRefillJob의 `× 4`가 그 형태다.
        //
        // 예상 호출은 요청 보고서 수와 같다. 연결이 없어 별도로 다시 부르는 종목만 2배가 되고
        // 그 비율은 미실측이라, 이것은 하한이고 상한은 그 2배다.
        int requestedReports = targets.size() * (toFiscalYear - fromFiscalYear + 1) * periods.size();
        log.info("전체 재무제표 결손 보정을 시작한다. 대상 {}종목, {}~{} 회계연도, 기간 {}, "
                        + "요청 보고서 {}건, 예상 호출 {}~{}회",
                targets.size(), fromFiscalYear, toFiscalYear, periods,
                requestedReports, requestedReports, requestedReports * 2);

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size());
        execution.plan(CollectionPhase.FINANCIAL, targets.size());
        execution.stage("종목별 전체 재무제표 보정");

        // 리미터의 일일 카운터는 인메모리라 실행이 끝나면 사라진다. 여기서 세지 않으면
        // 소량 검증 → 전량 → 재실행이 이어질 때 실제 누적을 아무도 모른다.
        AtomicInteger actualCalls = new AtomicInteger();
        AtomicInteger reportsWritten = new AtomicInteger();
        AtomicInteger missingReports = new AtomicInteger();
        AtomicInteger deferredRevisions = new AtomicInteger();
        // 계정 매칭이 기존 값과 다른 답을 낸 컬럼 수. 덮지 않기로 정했으므로(ADR-0056) 이것이
        // 0이 아니면 두 매칭이 갈렸다는 뜻이고, 그 사실이 로그에만 있으면 다음 세션이 못 찾는다.
        AtomicInteger mismatched = new AtomicInteger();

        try {
            for (SecurityRef target : targets) {
                execution.process(target, CollectionPhase.FINANCIAL, security -> {
                    FullStatementCollector.Collected collected =
                            collector.collect(security, fromFiscalYear, toFiscalYear, periods);
                    actualCalls.addAndGet(collected.apiCalls());
                    reportsWritten.addAndGet(collected.reportsWritten());
                    missingReports.addAndGet(collected.missingReports());
                    deferredRevisions.addAndGet(collected.outcome().deferredRevisions());
                    mismatched.addAndGet(collected.outcome().mismatched());
                    return collected.outcome().changed();
                });
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }

        // MUST: '없음'을 요청 수에서 빼서 구하지 않는다. 분기 경로는 4분기 잔액을 유도해
        // 함께 쓰므로 쓴 보고서가 요청 수를 넘고, 빼면 음수가 나온다. 어댑터가 직접 센 값을
        // 쓴다 — 그 수가 곧 "상장 전 회계연도"와 "아직 오지 않은 분기"의 합이다.
        String note = "보고서=%s 연도=%d~%d 요청=%d건 실제콜=%d 쓴보고서=%d 공시없음=%d 정정보류=%d 불일치=%d"
                .formatted(periods, fromFiscalYear, toFiscalYear, requestedReports,
                        actualCalls.get(), reportsWritten.get(), missingReports.get(),
                        deferredRevisions.get(), mismatched.get());
        log.info("전체 재무제표 결손 보정을 마쳤다. {}", note);
        return execution.finish(note);
    }

    /**
     * 받을 기간을 정한다.
     *
     * <p>IMPORTANT — 분기를 받으려면 티커를 함께 줘야 한다. 분기가 필요한 것은 이익잉여금
     * 결손 세 종목뿐이고(요약본이 그 행을 아예 주지 않는다), 전 종목으로 넓히면 <b>얻는 것
     * 없이 콜만 4배</b>가 된다. 이것을 관행이 아니라 예외로 막는 이유는, 관행은 "이왕 도는
     * 김에"라는 한 번의 판단으로 무너지고 그 대가가 6,300콜이기 때문이다.
     */
    private static Set<StatementPeriod> resolvePeriods(boolean includeQuarters,
                                                       List<String> tickers) {
        if (!includeQuarters) {
            return EnumSet.of(StatementPeriod.FY);
        }
        if (tickers == null || tickers.isEmpty()) {
            throw new IllegalArgumentException(
                    "분기 보고서는 종목을 지정해야 받을 수 있다. 분기가 필요한 것은 이익잉여금 "
                            + "결손 종목뿐이고, 전 종목으로 넓히면 얻는 것 없이 호출이 4배가 된다.");
        }
        return EnumSet.allOf(StatementPeriod.class);
    }

    /**
     * 대상 종목을 정한다. {@link FinancialRefillJob#run}과 같은 규칙이다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * 대상 0건으로 조용히 성공하면 오타 하나가 "보정을 돌렸는데 아무것도 안 바뀌었다"로
     * 끝나는데, 그것은 이 잡이 고치려는 결함과 <b>정확히 같은 모양</b>이다.
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
