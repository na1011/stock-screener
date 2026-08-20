package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.CorporateActionCollector;
import com.stockscreener.pipeline.collector.PriceCalendar;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 기업 행위를 모아 원장에 적는다. <b>계수는 건드리지 않는다</b>(ADR-0049).
 *
 * <p>MUST NOT: {@code AdjustmentRecalculator.rebuild}를 여기서 부르지 않는다. 그것이 저장된
 * 시세를 소급해 다시 쓰는 유일한 지점이고, 되돌리기가 원장의 정확성 위에서만 성립하므로
 * <b>첫 호출 전에 {@code pg_dump}가 필요하다.</b> 이 잡의 산출물은 사람이 판정할 후보이고,
 * 그 판정 경로는 관리자 API 단계에서 열린다.
 *
 * <p>일일 갱신에 붙이지 않은 이유는 {@link CompanyProfileJob}과 같다 — 기업 행위는
 * <b>정기보고서에만 실려 최대 넉 달 늦으므로</b>(api-spec §10-B) 매일 불러 얻는 최신성이 0이고,
 * 콜만 태운다. 소스의 주기가 분기이므로 이 잡의 주기도 그쪽에 맞춘다.
 *
 * <p><b>소스가 답하지 못하는 구간이 있다.</b> 정기보고서 제출 전 구간(오늘 기준 최근 넉 달
 * 남짓)은 조회해도 원리적으로 0건이다. 그 구간의 불연속은 휴리스틱이 {@code OBSERVED} 후보로
 * 올리므로 <b>"그런 사건이 없다"로 읽히지는 않는다</b>(phase 기준 §P9).
 */
@Component
public class CorporateActionJob {

    public static final String JOB_NAME = "corporate-action";

    /** 알림 본문에 이름으로 적을 충돌의 수. 넘는 것은 로그가 답한다. */
    private static final int CONFLICTS_IN_ALERT = 10;

    private static final Logger log = LoggerFactory.getLogger(CorporateActionJob.class);

    private final CorporateActionCollector collector;
    private final StockRegistrar stockRegistrar;
    private final PriceCalendar priceCalendar;
    private final DataSourceRegistry registry;
    private final BatchExecutions batchExecutions;

    public CorporateActionJob(CorporateActionCollector collector, StockRegistrar stockRegistrar,
                              PriceCalendar priceCalendar, DataSourceRegistry registry,
                              BatchExecutions batchExecutions) {
        this.collector = collector;
        this.stockRegistrar = stockRegistrar;
        this.priceCalendar = priceCalendar;
        this.registry = registry;
        this.batchExecutions = batchExecutions;
    }

    /**
     * {@code [from, asOf]}에 효력일이 드는 기업 행위를 모은다.
     *
     * @param from    비면 <b>저장된 시세의 첫 거래일</b>. MUST NOT: 시세의 15일 창을 그대로 쓰지
     *                않는다 — 소스가 정기보고서에만 실려 <b>원리적으로 0건</b>이 된다(§10-B).
     *                창을 넓혀도 콜은 거의 안 는다(전 구간 종목당 2콜, 한 달 1콜).
     * @param tickers 비면 편입 중인 활성 종목 전체.
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy,
                           LocalDate from, List<String> tickers) {
        List<SecurityRef> targets = stockRegistrar.toSecurityRefs(resolveTargets(tickers));

        // 시세가 하나도 없으면 소스를 부를 근거가 없다. 어댑터가 우리 시계열에서 일자와 비율을
        // 유도하므로, 이 확인이 없으면 종목마다 콜을 태우고 전부 유도 실패로 끝난다.
        Optional<LocalDate> earliest = priceCalendar.earliestTradeDate();
        if (targets.isEmpty() || earliest.isEmpty()) {
            log.info("기업 행위를 모을 대상이 없다. 대상={}종목 저장된 시세={} — 시세를 먼저 적재한다.",
                    targets.size(), earliest.map(LocalDate::toString).orElse("없음"));
            return batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0).finish();
        }

        LocalDate windowStart = from != null ? from : earliest.get();

        log.info("기업 행위 수집을 시작한다. 대상 {}종목, 창 {} ~ {}"
                        + " — 종목당 콜 수는 창을 덮는 정기보고서의 최소 집합이 정한다.",
                targets.size(), windowStart, asOf);

        BatchExecutionContext execution =
                batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size());
        List<CorporateActionCollector.NearDuplicate> conflicts = new ArrayList<>();
        List<CorporateActionCollector.PendingCandidate> pending = new ArrayList<>();

        try {
            // MUST: 창 검증을 실행 행을 연 **뒤에** 한다. 앞에서 던지면 batch_execution에 아무
            // 흔적도 안 남고, 관리자 화면으로 띄운 경우 런처의 스레드 경계 로그만 남는다 —
            // 화면에는 202 뒤 아무 일도 안 일어난 것으로 보인다(ADR-0050).
            if (windowStart.isAfter(asOf)) {
                throw new IllegalArgumentException(
                        "조회 창의 시작이 대상일보다 늦다: " + windowStart + " > " + asOf);
            }

            // MUST: 루프에 들어가기 전에 소스를 해석한다. 등록되지 않았을 때 나오는 NOT_FOUND는
            // fatal()이 아니라, 루프 안에 두면 **전 종목이 같은 이유로 실패하고 배치는 끝까지
            // 돈다.** 키가 없으면 수정계수가 영영 1로 남는데 그것이 부분 성공으로 기록된다.
            requireSources(targets);

            execution.plan(CollectionPhase.ADJUSTMENT, targets.size());
            execution.stage("종목별 기업 행위 수집");

            for (SecurityRef target : targets) {
                execution.process(target, CollectionPhase.ADJUSTMENT, security -> {
                    CorporateActionCollector.Result result =
                            collector.collect(security, windowStart, asOf);
                    conflicts.addAll(result.nearDuplicates());
                    pending.addAll(result.pending());
                    return result.recorded();
                });
            }

            alertOnConflicts(execution, conflicts);
            alertOnPendingJudgement(execution, pending);
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
     * 대상 종목의 시장마다 담당 소스가 있는지 확인한다.
     *
     * <p>IMPORTANT — 이 확인의 값은 <b>루프 앞에 있다는 것</b>이다. 국내 어댑터가
     * {@code @ConditionalOnApiKey}라 키가 없으면 빈으로 등록조차 되지 않고, 그때 나오는
     * {@code NOT_FOUND}는 종목 실패로 기록될 뿐 배치를 멈추지 않는다. {@code DataSourceRegistry}가
     * 이 포트에 {@code Optional} 변형을 금지한 이유와 같은 자리다.
     */
    private void requireSources(List<SecurityRef> targets) throws DataSourceException {
        Set<String> markets = new LinkedHashSet<>();
        for (SecurityRef target : targets) {
            markets.add(target.marketCode());
        }
        for (String market : markets) {
            registry.corporateActionSource(market);
        }
    }

    /**
     * 사람이 갈라야 할 충돌을 <b>요약 한 건</b>으로 알린다.
     *
     * <p>MUST NOT: 종목마다 보내지 않는다. 그리고 MUST NOT: 로그로만 두지 않는다 — 진짜 별개
     * 사건이면 원장에 자리가 없어 영영 반영되지 않고, 같은 사건이면 일자 보정이 어긋났다는
     * 신호다. 둘 다 사람이 봐야 한다.
     */
    private void alertOnConflicts(BatchExecutionContext execution,
                                  List<CorporateActionCollector.NearDuplicate> conflicts) {
        if (conflicts.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder(
                "%d건이 기존 원장 행과 며칠 안에 겹쳐 적지 못했다. 같은 사건의 일자 보정이 어긋났거나"
                        .formatted(conflicts.size())
                        + " 진짜 별개 사건인데 원장에 자리가 없는 것이다.\n");
        conflicts.stream().limit(CONFLICTS_IN_ALERT).forEach(conflict -> body.append(
                "- %s: 새 %s / 기존 %s (%s)\n".formatted(conflict.ticker(), conflict.proposed(),
                        conflict.existing(), conflict.source())));
        if (conflicts.size() > CONFLICTS_IN_ALERT) {
            body.append("나머지 %d건은 로그에 있다.".formatted(conflicts.size() - CONFLICTS_IN_ALERT));
        }
        execution.alertOperator(AlertSeverity.WARNING, "기업 행위 근접 중복", body.toString());
    }

    /**
     * 새로 생긴 판정 대기 후보를 <b>요약 한 건</b>으로 알린다.
     *
     * <p>MUST NOT: 로그로만 두지 않는다. ADR-0055부터 판정되지 않은 후보는 그 종목의 장기창
     * 지표를 억제하므로, 후보 하나가 생기는 순간 <b>그 종목이 모든 프리셋에서 사라진다</b> —
     * 스크리닝은 최신 일자 한 장만 읽기 때문이다. 회복은 사람이 판정할 때까지이고
     * {@code ICHI_*}는 사건 뒤 <b>읽기 창 전체</b>(2026-08-20 기준 1,261거래일 ≈ 5년)까지 이어진다.
     *
     * <p>이번 실행이 <b>새로 만든</b> 후보만 센다. 남아 있는 후보 전체를 세면 판정이 밀리는
     * 동안 매 실행 같은 알림이 나가고, 며칠 안에 아무도 그 알림을 보지 않게 된다
     * ({@link #alertOnConflicts}가 판정된 행을 빼는 것과 같은 이유다).
     */
    private void alertOnPendingJudgement(BatchExecutionContext execution,
                                         List<CorporateActionCollector.PendingCandidate> pending) {
        if (pending.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder(
                ("%d건이 판정을 기다린다. 판정 전까지 아래 종목의 장기창 지표는 계산되지 않고, "
                        + "그 종목은 프리셋 결과에서 빠진다(ADR-0055).\n").formatted(pending.size()));
        pending.stream().limit(CONFLICTS_IN_ALERT).forEach(candidate -> body.append(
                "- %s: 효력일 %s (%s)\n".formatted(
                        candidate.ticker(), candidate.effectiveDate(), candidate.source())));
        if (pending.size() > CONFLICTS_IN_ALERT) {
            body.append("나머지 %d건은 로그에 있다.".formatted(pending.size() - CONFLICTS_IN_ALERT));
        }
        body.append("판정은 PUT /api/admin/corporate-actions/{id}이다 — 화면은 없다(ADR-0049).");
        execution.alertOperator(AlertSeverity.WARNING, "기업 행위 판정 대기", body.toString());
    }

    /**
     * 대상 종목을 정한다.
     *
     * <p>IMPORTANT — 티커를 줬는데 하나도 매칭되지 않으면 <b>예외로 즉시 실패시킨다.</b>
     * 이 잡은 <b>재실행에서 0행이 정상</b>이므로, 오타로 대상이 빈 것과 이미 다 적힌 것이
     * 로그에서 구분되지 않는다({@link CompanyProfileJob}과 같은 자리다).
     */
    private List<Long> resolveTargets(List<String> tickers) {
        if (tickers != null && !tickers.isEmpty()) {
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
        return stockRegistrar.activeUniverseMemberIds();
    }
}
