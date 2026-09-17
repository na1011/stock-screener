package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.FinancialCollector;
import com.stockscreener.pipeline.collector.FinancialWriter;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
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
    // MUST: 이 값은 IndicatorRegistry.maxFinancialYears()(지금 7)보다 커야 한다 —
    // FinancialLoadWindowTest가 그 부등식을 단언하므로, 지표가 창을 넓히면 시험이 붉는다.
    // 8인 근거: 보존 창 앞머리(asOf − 약 1년)에서 7개 연간이 FY(asOf.year−8)부터 시작한다.
    // 국내 콜은 350 × 9년 × 보고서 4종 = 12,600으로 dart.daily-quota 18,000의 70%이고
    // 목요일에 이 잡만 돈다(ADR-0116). **그 수는 아직 실행으로 재지 않았다.**
    static final int DEFAULT_YEARS_BACK = 8;

    private static final Logger log = LoggerFactory.getLogger(FinancialRefillJob.class);

    private final FinancialCollector financialCollector;
    private final StockRegistrar stockRegistrar;
    private final BatchExecutions batchExecutions;
    private final FinancialLoadWindow loadWindow;
    private final DataSourceRegistry registry;

    public FinancialRefillJob(FinancialCollector financialCollector, StockRegistrar stockRegistrar,
                              BatchExecutions batchExecutions, FinancialLoadWindow loadWindow,
                              DataSourceRegistry registry) {
        this.financialCollector = financialCollector;
        this.stockRegistrar = stockRegistrar;
        this.batchExecutions = batchExecutions;
        this.loadWindow = loadWindow;
        this.registry = registry;
    }

    /**
     * 편입 중인 활성 종목의 재무 결손을 채운다.
     *
     * @param asOf       기준일. 여기서 {@code yearsBack}만큼 거슬러 올라간다.
     * @param yearsBack  몇 년 거슬러 받을지. null이면 {@link #DEFAULT_YEARS_BACK}.
     * @param tickers    비면 전 종목. 값이 있으면 <b>편입 종목과의 교집합</b>만 처리한다.
     * @param universeCode <b>비활성 유니버스를 대상으로 잡는 유일한 길이다.</b> null이면
     *                     활성 유니버스의 편입 종목이다 — 그 갈래는 미국에 한 건도 안 닿는다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           Integer yearsBack, List<String> tickers, String universeCode) {
        return run(asOf, triggeredBy, yearsBack, tickers, universeCode, false);
    }

    /**
     * 위와 같되 <b>저장본보다 이른 판을 넣을지</b>를 받는다. 손 실행
     * ({@code --insert-earlier-vintages=true}) 전용이다 — {@link #runAndReport}의 5-인자 판이
     * 스케줄러 경로이고 늘 거짓이다.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           Integer yearsBack, List<String> tickers, String universeCode,
                           boolean insertEarlierVintages) {
        return runAndReport(asOf, triggeredBy, yearsBack, tickers, universeCode,
                insertEarlierVintages).status();
    }

    /**
     * 위와 같되 <b>지표가 지워진 종목을 함께 낸다.</b>
     *
     * <p><b>MUST: 스케줄러는 이쪽을 부르고 백필로 잇는다.</b> 정정·교정 반영은 그 종목의
     * {@code indicator_value}를 회계기간 시작일 이후 전부 지우는데, 백필이 따라오지 않으면
     * <b>그 종목이 화면에서 통째로 사라지고 에러도 경고도 나지 않는다.</b> 실측으로 표본
     * 30종목 실행이 2종목을 145/256거래일로 떨어뜨렸고, 창 11년 전수 재적재는 <b>757종목</b>을
     * 그렇게 만들었다. 기업 행위 사슬이 같은 자리에서 같은 결론에 도달했다(ADR-0116).
     */
    public Invalidation runAndReport(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                                     Integer yearsBack, List<String> tickers, String universeCode) {
        return runAndReport(asOf, triggeredBy, yearsBack, tickers, universeCode, false);
    }

    /**
     * 위와 같되 <b>저장본보다 이른 판을 넣을지</b>를 받는다.
     *
     * <p><b>MUST NOT: 스케줄러에서 참으로 부르지 않는다.</b> 참이면 이른 판이 든 종목마다 지표가
     * 그 공시일 이후 지워지는데(실측 수백 종목) 자동 백필 상한을 넘어 지운 채 남는다.
     */
    public Invalidation runAndReport(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                                     Integer yearsBack, List<String> tickers, String universeCode,
                                     boolean insertEarlierVintages) {
        int years = yearsBack == null ? DEFAULT_YEARS_BACK : yearsBack;
        if (years < 0) {
            throw new IllegalArgumentException("재무 연수는 음수일 수 없다: " + years);
        }

        List<SecurityRef> targets =
                stockRegistrar.toSecurityRefs(resolveTargets(tickers, universeCode));
        if (targets.isEmpty()) {
            log.info("편입 중인 활성 종목이 없다. 채울 것이 없다.");
            return new Invalidation(
                    batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0).finish(), List.of());
        }

        int fromFiscalYear = asOf.getYear() - years;
        // 어댑터가 fromFiscalYear부터 올해까지 양끝을 포함해 돌고, 연도마다 보고서 코드 넷을
        // 부른다(Q4는 로컬 유도라 콜이 없다). 착수 전에 쿼터를 가늠할 수 있어야 한다.
        int estimatedCalls = targets.size() * (asOf.getYear() - fromFiscalYear + 1) * 4;
        log.info("재무 결손 보정을 시작한다. 대상 {}종목, {}년 이후, 예상 호출 약 {}회",
                targets.size(), fromFiscalYear, estimatedCalls);
        loadWindow.warnIfShorterThanIndicators(JOB_NAME, years);

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size());
        execution.plan(CollectionPhase.FINANCIAL, targets.size());
        execution.stage("종목별 재무 결손 보정");

        // 정정을 반영하면 그 종목의 지표가 보존 창 전체에서 지워진다. 그 수를 세지 않으면
        // 백필이 남았다는 것을 알릴 수 없고, 그러면 그 종목이 화면에서 조용히 사라진다.
        //
        // MUST: 교정(같은 공시일인데 우리 추출이 바뀌어 값을 덮은 것)도 같이 센다. 무효화가
        // 걸리는 것이 같으므로 백필이 남는 것도 같은데, 정정만 세면 교정으로 지워진 종목이
        // 아무 말 없이 빈 채로 남는다.
        AtomicInteger revised = new AtomicInteger();
        AtomicInteger filled = new AtomicInteger();
        AtomicInteger corrected = new AtomicInteger();
        AtomicInteger insertedEarlier = new AtomicInteger();
        List<String> invalidatedTickers = new ArrayList<>();
        // MUST: 알림 문구와 백필 대상을 따로 모은다. 위 목록은 "005930(정정 1 · 교정 0)"
        // 형태라 사람이 읽는 것이고, 백필에는 티커만 있어야 한다.
        List<String> tickersToBackfill = new ArrayList<>();
        // 전체 재무제표 소스가 있는 시장의 종목만 — 미국(SEC)은 영업활동현금흐름을 이 경로가 이미 채운다.
        List<String> fullStatementTickers = new ArrayList<>();

        try {
            for (SecurityRef target : targets) {
                execution.process(target, CollectionPhase.FINANCIAL, security -> {
                    FinancialWriter.Outcome outcome = financialCollector.collectDetailed(
                            security, fromFiscalYear, FinancialWriter.WriteMode.FILL_MISSING,
                            insertEarlierVintages);
                    revised.addAndGet(outcome.revised());
                    corrected.addAndGet(outcome.corrected());
                    insertedEarlier.addAndGet(outcome.insertedEarlier());
                    // 이른 판 삽입도 그 공시일 이후의 지표를 지운다 — 정정·교정과 같이 세지 않으면
                    // 그 종목이 백필 목록에서 빠져 지워진 채 남는다.
                    if (outcome.revised() > 0 || outcome.corrected() > 0 || outcome.insertedEarlier() > 0) {
                        invalidatedTickers.add("%s(정정 %d · 교정 %d · 이른 판 %d)".formatted(
                                security.ticker(), outcome.revised(), outcome.corrected(),
                                outcome.insertedEarlier()));
                        tickersToBackfill.add(security.ticker());
                        if (registry.hasFullStatementSource(security.marketCode())) {
                            fullStatementTickers.add(security.ticker());
                        }
                    }
                    filled.addAndGet(outcome.filled());
                    return outcome.changed();
                });
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return new Invalidation(BatchStatus.FAILED, List.of());
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }

        String note = "연도=%d~%d 대상=%d종목 정정=%d 채움=%d 교정=%d 이른판=%d"
                .formatted(fromFiscalYear, asOf.getYear(), targets.size(),
                        revised.get(), filled.get(), corrected.get(), insertedEarlier.get());
        log.info("재무 결손 보정을 마쳤다. {}", note);
        alertOnInvalidation(execution, invalidatedTickers,
                revised.get() + corrected.get() + insertedEarlier.get(), fullStatementTickers);
        return new Invalidation(execution.finish(note), List.copyOf(tickersToBackfill));
    }

    /**
     * 이 실행이 지표를 지운 종목. <b>호출부가 백필을 이어야 한다.</b>
     *
     * <p>{@code AdjustmentRebuildJob.Invalidation}과 같은 형태다 — 그쪽은 기업 행위 사슬이
     * 받아서 백필로 잇고, 이쪽은 {@code PipelineScheduler.runHeavy}가 받는다.
     *
     * <p><b>무효화 시작일을 담지 않는다.</b> 재무 정정은 {@code report.periodStart()}부터
     * 지우는데 사업보고서면 그것이 <b>보존 창 전량</b>이라, 호출부가 보존 경계부터 채우는 것이
     * 언제나 옳고 더 좁게 잡을 이득이 없다.
     */
    public record Invalidation(BatchStatus status, List<String> tickers) {

        public boolean isEmpty() {
            return tickers.isEmpty();
        }
    }

    /**
     * 정정을 반영했으면 <b>백필이 남았다는 것</b>을 알린다.
     *
     * <p>MUST NOT: 이것을 로그로만 두지 않는다. 정정 반영은 그 종목의 {@code indicator_value}를
     * <b>회계기간 시작일 이후 전부</b> 지우는데, 사업보고서면 그것이 보존 창 전량이다. 백필이
     * 따라오지 않으면 그 종목이 화면에서 통째로 사라지고 <b>에러도 경고도 나지 않는다.</b>
     * 이 잡의 성공은 "정정을 반영했다"이고 그 상태는 아직 <b>일이 끝나지 않은</b> 상태다.
     *
     * <p><b>교정도 같은 알림을 탄다.</b> 같은 공시일인데 우리 추출이 바뀌어 값을 덮은 경우이고,
     * 무효화가 걸리는 것이 정정과 같으므로 남는 일도 같다.
     *
     * <p>같은 이유로 {@link AdjustmentRebuildJob}이 이미 같은 형태의 알림을 낸다 — 두 잡이
     * 같은 결과를 만드는데 한쪽만 말하면, 말하지 않는 쪽으로 사고가 몰린다.
     */
    private void alertOnInvalidation(BatchExecutionContext execution, List<String> invalidatedTickers,
                                     int invalidatedPeriods, List<String> fullStatementTickers) {
        if (invalidatedTickers.isEmpty()) {
            return;
        }
        execution.alertOperator(AlertSeverity.WARNING, "재무 무효화 — 백필이 남았다",
                invalidationAlertBody(invalidatedTickers, invalidatedPeriods, fullStatementTickers));
    }

    /** 알림 본문. 패키지 공개인 이유는 실행 컨텍스트 없이 문구를 시험하기 위해서다. */
    static String invalidationAlertBody(List<String> invalidatedTickers, int invalidatedPeriods,
                                        List<String> fullStatementTickers) {
        StringBuilder body = new StringBuilder("""
                %d종목의 재무를 정정·교정·이른 판 삽입으로 다시 썼다(%d기간): %s
                **그 종목의 지표가 보존 창 전체에서 지워졌다.** 백필이 따라오지 않으면 화면에서 \
                통째로 사라지는데 에러도 경고도 나지 않는다.
                """.formatted(invalidatedTickers.size(), invalidatedPeriods, String.join(", ", invalidatedTickers)));
        // 전체 재무제표는 소스가 있는 시장(국내 DART)에만 시킨다 — 미국 티커로 돌리면 종목마다 실패한다.
        if (!fullStatementTickers.isEmpty()) {
            body.append("""
                    영업활동현금흐름은 이 경로가 채우지 못하는 시장이 있어 그 종목은 전체 재무제표를 함께 돌린다:
                      ./gradlew bootRun --args='--spring.profiles.active=bootstrap \
                    --job=full-statement-refill --tickers=%s'
                    """.formatted(String.join(",", fullStatementTickers)));
        }
        // 백필은 짝이다. 날짜 하나로 돌린 원본층은 보존 창 전체를 다시 쓰지만 백분위·업종 상대값은 그 날짜에만 낸다.
        body.append("""
                백필은 원본층 한 판과 파생층 루프를 잇는다(batch-load skill):
                  ./gradlew bootRun --args='--spring.profiles.active=bootstrap \
                --job=backfill --from=<최신 거래일> --as-of=<최신 거래일> --tickers=<위 종목>'
                  ./gradlew bootRun --args='--spring.profiles.active=bootstrap \
                --job=backfill --from=<보존 창 첫 거래일> --as-of=<최신 거래일> --tickers=<같은 시장 1종목>'
                """);
        return body.toString();
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
