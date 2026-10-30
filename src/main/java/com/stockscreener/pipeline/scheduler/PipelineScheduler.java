package com.stockscreener.pipeline.scheduler;

import com.stockscreener.pipeline.batch.AdjustmentRebuildJob;
import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
import com.stockscreener.pipeline.batch.BatchExecutions;
import com.stockscreener.pipeline.batch.BatchShutdownException;
import com.stockscreener.pipeline.batch.CandidateSplitJob;
import com.stockscreener.pipeline.batch.CorporateActionJob;
import com.stockscreener.pipeline.batch.DailyUpdateJob;
import com.stockscreener.pipeline.batch.FilingConsumptionJob;
import com.stockscreener.pipeline.batch.EarningsReleaseJob;
import com.stockscreener.pipeline.batch.FilingListingJob;
import com.stockscreener.pipeline.batch.FinancialRefillJob;
import com.stockscreener.pipeline.batch.FreshnessCatchUpJob;
import com.stockscreener.pipeline.batch.UsCandidateFeed;
import com.stockscreener.pipeline.batch.FullStatementRefillJob;
import com.stockscreener.pipeline.batch.GovernanceDeriveJob;
import com.stockscreener.pipeline.batch.GovernanceLoadJob;
import com.stockscreener.pipeline.batch.IndicatorReconciler;
import com.stockscreener.pipeline.batch.JobFreshnessAlerter;
import com.stockscreener.pipeline.batch.JobFreshnessPolicy;
import com.stockscreener.pipeline.batch.UniverseIntegrityAlerter;
import com.stockscreener.pipeline.batch.UniverseRebalanceJob;
import com.stockscreener.pipeline.lock.WriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 배치를 정해진 시각에 띄운다.
 *
 * <p><b>초기 적재는 크론에 없다.</b> 스케줄에 걸면 애플리케이션 재시작마다 850종목 수집이
 * 시작되기 때문이다. 다만 <b>리밸런싱 꼬리에서 티커를 줘 부르는 갈래는 있다</b>
 * ({@code MembershipGapFiller} · ADR-0141) — 그것은 크론이 아니라 <b>편입이 바뀐 직후</b>가
 * 트리거이고, 대상이 <b>새로 결손이 생긴 종목뿐</b>이라 위 근거가 걸리지 않는다.
 * <b>그 꼬리는 이제 {@code UniverseRebalanceJob} 안에 있다</b> — 손·관리자 경로도 타야 한다.
 *
 * <p>{@code pipeline.scheduler.enabled}로 끌 수 있게 한 이유는 개발 중에 배치가 저절로
 * 도는 것을 막기 위해서다. 기본값은 꺼짐이다 — 어댑터가 없는 상태에서 켜두면 매일
 * 실패 알림만 쌓인다. 운영 배포 시 명시적으로 켠다.
 */
@Component
@ConditionalOnProperty(name = "pipeline.scheduler.enabled", havingValue = "true")
public class PipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(PipelineScheduler.class);

    /** 국내 시장 기준 시간대. 대상 일자 판정에 쓴다. */
    private static final ZoneId KR_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 무거운 잡을 다시 돌리기까지의 최소 간격(일).
     *
     * <p>{@code JobFreshnessPolicy}의 예산(40일)보다 <b>짧아야 한다.</b> 이쪽이 「돌린다」이고
     * 그쪽이 「안 돌았다고 알린다」이므로, 이 값이 더 크면 <b>정상 운영에서 알림이 뜬다.</b>
     */
    private static final int HEAVY_JOB_MIN_INTERVAL_DAYS = 25;

    private final DailyUpdateJob dailyUpdateJob;
    private final UniverseRebalanceJob universeRebalanceJob;
    private final CorporateActionJob corporateActionJob;
    private final AdjustmentRebuildJob adjustmentRebuildJob;
    private final GovernanceLoadJob governanceLoadJob;
    private final GovernanceDeriveJob governanceDeriveJob;
    private final FullStatementRefillJob fullStatementRefillJob;
    private final FinancialRefillJob financialRefillJob;
    private final JobFreshnessAlerter freshnessAlerter;
    private final JobFreshnessPolicy freshnessPolicy;
    private final IndicatorReconciler indicatorReconciler;
    private final UniverseIntegrityAlerter universeIntegrityAlerter;
    private final com.stockscreener.pipeline.batch.PriceIntegrityAlerter priceIntegrityAlerter;
    private final BatchExecutions batchExecutions;
    private final WriteLock writeLock;
    private final FilingListingJob filingListingJob;
    private final EarningsReleaseJob earningsReleaseJob;
    private final FilingConsumptionJob filingConsumptionJob;
    private final FreshnessCatchUpJob freshnessCatchUpJob;
    private final com.stockscreener.pipeline.batch.KrXbrlCollectJob krXbrlCollectJob;
    private final UsCandidateFeed usCandidateFeed;
    private final CandidateSplitJob candidateSplitJob;

    public PipelineScheduler(DailyUpdateJob dailyUpdateJob,
                             UniverseRebalanceJob universeRebalanceJob,
                             CorporateActionJob corporateActionJob,
                             AdjustmentRebuildJob adjustmentRebuildJob,
                             GovernanceLoadJob governanceLoadJob,
                             GovernanceDeriveJob governanceDeriveJob,
                             FullStatementRefillJob fullStatementRefillJob,
                             FinancialRefillJob financialRefillJob,
                             JobFreshnessAlerter freshnessAlerter,
                             JobFreshnessPolicy freshnessPolicy,
                             IndicatorReconciler indicatorReconciler,
                             UniverseIntegrityAlerter universeIntegrityAlerter,
                             BatchExecutions batchExecutions,
                             WriteLock writeLock,
                             FilingListingJob filingListingJob,
                             FilingConsumptionJob filingConsumptionJob,
                             FreshnessCatchUpJob freshnessCatchUpJob,
                             UsCandidateFeed usCandidateFeed,
                             CandidateSplitJob candidateSplitJob,
                             com.stockscreener.pipeline.batch.PriceIntegrityAlerter priceIntegrityAlerter,
                             com.stockscreener.pipeline.batch.KrXbrlCollectJob krXbrlCollectJob,
                             EarningsReleaseJob earningsReleaseJob) {
        this.priceIntegrityAlerter = priceIntegrityAlerter;
        this.earningsReleaseJob = earningsReleaseJob;
        this.krXbrlCollectJob = krXbrlCollectJob;
        this.dailyUpdateJob = dailyUpdateJob;
        this.universeRebalanceJob = universeRebalanceJob;
        this.corporateActionJob = corporateActionJob;
        this.adjustmentRebuildJob = adjustmentRebuildJob;
        this.governanceLoadJob = governanceLoadJob;
        this.governanceDeriveJob = governanceDeriveJob;
        this.fullStatementRefillJob = fullStatementRefillJob;
        this.financialRefillJob = financialRefillJob;
        this.freshnessAlerter = freshnessAlerter;
        this.freshnessPolicy = freshnessPolicy;
        this.indicatorReconciler = indicatorReconciler;
        this.universeIntegrityAlerter = universeIntegrityAlerter;
        this.batchExecutions = batchExecutions;
        this.writeLock = writeLock;
        this.filingListingJob = filingListingJob;
        this.filingConsumptionJob = filingConsumptionJob;
        this.freshnessCatchUpJob = freshnessCatchUpJob;
        this.usCandidateFeed = usCandidateFeed;
        this.candidateSplitJob = candidateSplitJob;
    }

    /**
     * 쓰기 크론의 몸통을 쓰기 잠금 안에서 돌린다(ADR-0175). 잠금이 차 있으면 <b>풀릴 때까지</b> 기다린다(상한 없음 · 매시간 알림).
     * 대상일은 몸통 안에서 — 잠금을 쥔 뒤에 — 계산한다.
     *
     * <p>MUST: 각 쓰기 크론의 바깥 {@code try} <b>맨 앞</b>에서 부른다 — 끝 줄은 그 {@code finally}가 찍으므로 못 돈 발화에도 남고
     * ({@code ops/crons-done.sh}가 그날을 「남은 발화」로 안 막는다), 무거운 잡의 「마지막 정기 시도」 조회보다 앞이라 못 돈 발화가
     * 시도로 세어지지 않는다(잠금 v5 8'). 못 도는 경우는 둘이다: 종료 신호로 그만둠(빈 값 · 실행 행 없음)과 잠금 표 실패(예외 —
     * {@code WriteLock}이 표지를 걷고 ERROR로 알린 뒤 던진다). 어느 쪽도 {@code recordStartFailure}를 안 지난다 — 지나면 토큰 없이
     * FAILED 행을 쓰다 가드에 거절되고 원래 사유가 덮인다(잠금 v4 반증 L1-4).
     *
     * <p>사정거리: 이 잠금은 progress의 손 쓰기 문(「다음 명목 발화 전에 끝낸다」)의 <b>백스톱</b>이다 — 13:50 손 bootRun이 쥐면
     * 14:00~14:20 사슬은 그것이 끝날 때까지 밀린다(배타는 서지만 사슬 단계 사이의 중간 상태는 그만큼 길어진다). 손 쓰기 문은 사용자
     * 결정 「규칙 유지 + 잠금은 백업」으로 2부 (나) 뒤에도 선다. 스케줄러 풀이 1이라 기다리는 동안 이 JVM의 다른 크론은 줄을 서 늦게 돈다.
     */
    private void underWriteLock(String cron, Runnable body) {
        Optional<WriteLock.Token> token = writeLock.awaitForCron(cron);
        if (token.isEmpty()) {
            return; // 종료 신호로 그만둠 — 내려가는 중이다.
        }
        try (WriteLock.Token held = token.get()) {
            body.run();
        }
    }

    /**
     * 일일 갱신. 평일 13시 30분(KST)에 <b>직전 평일</b>의 종가를 받는다.
     *
     * <p>시각과 대상 일자가 함께 정해진다. 소스가 거래일 T의 종가를 <b>T의 다음 영업일
     * 13시 이후</b>에 내기 때문이다(ADR-0042). 그러므로 시각은 13시보다 뒤여야 하고, 대상은
     * 실행일보다 앞이어야 한다. 13시 30분은 그 하한에 <b>30분</b>의 여유를 둔 값이다.
     *
     * <p><b>여유가 30분뿐인 것을 감수하는 근거가 둘이다.</b> 안내는 "13시 <i>이후</i>"로
     * 하한만 보증하므로 늦게 나오는 날이 있을 수 있는데, ① 그날은
     * {@link DailyUpdateJob#PRICE_LOOKBACK_DAYS} 창이 이후 실행에서 그 날짜를 다시 시도해
     * 메우고, ② 그것이 거래일마다 반복되면 {@code DailyUpdateJob}의 0건 경고가 매일 떠
     * <b>시각이 이르다는 사실 자체가 드러난다.</b> 즉 이 선택은 되돌릴 수 있고 검출된다.
     *
     * <p>IMPORTANT — 13시 30분은 <b>국내 장 마감(15:30) 전</b>이다. 그래도 옳다: <b>당일 종가를
     * 받는 것이 아니기 때문</b>이다. MUST NOT: "장중이라 종가가 아직 없다"를 근거로 이 시각을
     * 뒤로 옮기지 않는다 — 몇 시로 옮겨도 당일치는 그날 자정까지 오지 않는다(20:13·22:18·23:43
     * 실측 셋이 모두 0건이었다. {@code docs/api-spec/data-go-kr-stock-price.md} §공개 지연).
     *
     * <p><b>이 앱은 맥북 위에서 돈다.</b> 앱이 꺼져 있던 날은 실행이 통째로 빠진다(spec §6). 켜진 채
     * 잠들면 건너뛰지 않고 <b>잠든 만큼 늦게</b> 돈다 — 다음 발화까지의 지연을 단조 시계로 재고 그 시계가
     * 수면 동안 멈춘다(2026-10-06 실측: 전날 저녁 배터리 수면 — pmset 합 6,912초 · JVM 단조 지연 6,891초 — 뒤 13:30 크론이 15:24:51에 돌았다 ·
     * {@code docs/reload-gate.md} 「앱 크론이 기계 수면만큼 밀린다」). 밀린 채 재기동하면 새 JVM은 다음 발화를
     * 그 뒤로 잡아 그날 남은 발화가 빠진다. 놓친 날짜는 {@link DailyUpdateJob}의 조회 창이 흡수한다 —
     * 이후 실행이 계속 다시 시도한다.
     *
     * <p>미국 시장(v1.1, ADR-0010)은 이 시각을 쓸 수 없다 — 미국 장은 한국 시각 다음 날
     * 오전 5~6시에 닫는다. 붙일 때 별도 스케줄이 필요하다.
     *
     * <p>MUST NOT: 여기서 초기 적재 여부를 분기하지 않는다.
     *
     * <h2>일일 갱신 앞에서 전날 공시를 먼저 먹인다</h2>
     * <p>순서는 목록 → 소비 → (먹인 국내 종목의) 보통주 몫 재료 → (먹인 종목이 있으면) 입력 유도 → 일일 갱신 → (소비가 지운 종목이
     * 있으면) 좁힌 화해다. <b>일일 갱신이 최신일 지표와 워치리스트를 쓰는데, 그 전날 공시가 14:16에야 소비되면 그 종목의 최신일 행이
     * 14:20 화해 전까지 직전 분기 재무로 계산되고 워치리스트는 그 값으로 하루 한 번 판정된다</b>(2026-10-30 실측: GOOGL 10-Q
     * filingDate 10-29 · 10-29 행이 13:40에 종가 283.82 × Q2 TTM EPS 19.9677로 쓰였고 15:16 화해가 Q3로 고쳤다). 미국 일일 색인은
     * 그날 미 동부 22:01~22:16(KST 다음 날 11:01~11:16)에 올라와 13:30이면 전날까지 닫힌다(5일 실측 · {@code sec-edgar-daily-index.md}).
     * <ul>
     *   <li><b>유도까지 앞에 두는 이유</b>: 새 판의 주식수 기저 판정은 {@code NULL}(= OK)이고 거버넌스 사실은 FY 판에서 짓는다 —
     *       유도 없이 일일 갱신을 돌리면 새 재무와 어제 판정·사실이 섞인 행이 워치리스트에 실린다. 유도는 저장분만 읽어 외부 0콜이다.</li>
     *   <li><b>재료를 좁혀 받는 이유</b>: 국내 관문 켬에서 보통주 몫이 안 붙은 새 판은 미표시다. 정기 받기(14:16 끝)는 상한이 밀린 판에
     *       먼저 쓰여 방금 먹인 판을 보장하지 않으므로 먹인 국내 종목만 상한 없이 받는다(값을 안 바꾸는 재료 표).</li>
     *   <li><b>좁힌 화해</b>: 소비 FR이 정정을 만나면 그 종목 지표를 창 전체로 지운다. 일일 갱신은 원본층 창과 최신일 파생층만 다시
     *       쓰므로 과거 날짜 백분위가 14:20까지 빈다 — 14:00 사슬과 같은 꼴로 그 자리에서 채운다.</li>
     * </ul>
     * <p>앞 단계의 실패는 시세를 막지 않는다 — 모아 두고 일일 갱신 뒤에 던진다. 일일 갱신이 던지면 그것이 주 예외이고 앞 단계 예외는
     * 그 아래에 단다. 종료 신호는 그 자리에서 끝낸다(ADR-0078).
     */
    @Scheduled(cron = "0 30 13 * * MON-FRI", zone = "Asia/Seoul")
    public void runDailyUpdate() {
        try {
            underWriteLock("runDailyUpdate", () -> {
                LocalDate today = LocalDate.now(KR_ZONE);
                LocalDate targetDate = DailyUpdateJob.targetDateFor(today);
                RuntimeException held = null;
                FilingConsumptionJob.Report consumption = null;
                try {
                    filingListingJob.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                    consumption = filingConsumptionJob.runAndReport(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                    krXbrlCollectJob.runForLoadedStatements(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER,
                            consumption.fedKrTickers());
                    if (!consumption.fedTickers().isEmpty()) {
                        indicatorReconciler.deriveInputs(targetDate, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                    }
                } catch (BatchShutdownException e) {
                    log.info("일일 갱신 앞 공시 소비가 종료로 중단됐다. today={} — 일일 갱신을 시작하지 않는다.", today);
                    return;
                } catch (RuntimeException e) {
                    // 목록이 던져도 소비는 이미 쌓인 행을 먹일 수 있지만, 여기서는 한 덩어리로 끊는다 — 14:16이 같은 순서로 다시 돈다.
                    held = e;
                }
                // 실적 발표 감지(ADR-0195) — 목록이 넣은 8-K의 항목을 묻고 남은 과거를 훑는다. 값을 안 바꾸지만 일일 갱신 안의 워치리스트
                // 알림이 「새 실적 · 지표 미반영」을 싣으려면 그 앞이어야 한다. 앞 덩어리와 따로 돈다 — 소비가 던져도 목록이 넣은 줄은 있다.
                try {
                    earningsReleaseJob.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                } catch (BatchShutdownException e) {
                    log.info("일일 갱신 앞 실적 발표 감지가 종료로 중단됐다. today={} — 일일 갱신을 시작하지 않는다.", today);
                    return;
                } catch (RuntimeException e) {
                    held = keepFirst(held, e);
                }
                log.info("일일 갱신을 시작한다. targetDate={}", targetDate);
                try {
                    dailyUpdateJob.run(targetDate, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                    if (consumption != null && !consumption.invalidatedTickers().isEmpty()) {
                        indicatorReconciler.reconcile(indicatorReconciler.dataAsOf(today),
                                consumption.invalidatedTickers(), BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                    }
                } catch (BatchShutdownException e) {
                    // IMPORTANT — 운영에서 종료가 배치를 끊는 경로가 실제로 여기다. 안 잡으면 Spring의
                    // 기본 처리가 ERROR로 찍고, sentry.logging.minimum-event-level이 error라 배포마다
                    // 이슈가 하나씩 쌓인다. catch-all이 아니라 이름 붙은 신호 하나만 받는다.
                    log.info("일일 갱신이 종료로 중단됐다. targetDate={} — ABORTED로 남는다. "
                            + "시세는 조회 창이 다시 시도하지만 백분위·워치리스트는 안 이어진다.", targetDate);
                    return;
                } catch (RuntimeException e) {
                    if (held != null) {
                        e.addSuppressed(held);
                    }
                    throw e;
                }
                if (held != null) {
                    throw held;
                }
            });
        } finally {
            // 끝 줄 — ops/crons-done.sh가 크론마다 「명목 시각 뒤에 끝났다」를 이 줄로 잰다(크론마다 밀림이 다르다).
            log.info("크론 발화를 마쳤다. 크론={}", "runDailyUpdate");
        }
    }

    /**
     * 유니버스 리밸런싱. 매주 토요일 저녁 8시(KST).
     *
     * <p>정기변경은 연 2회지만 수시변경이 있어 주 1회 확인한다. 스냅샷이 같으면 아무것도
     * 하지 않으므로(멱등) 자주 도는 비용이 낮고, 반대로 놓치면 없는 종목을 계속 수집한다.
     *
     * <p>새벽 3시가 아닌 이유는 일일 갱신과 같다 — 맥북이 자고 있으면 실행 자체가 없다.
     * 일일 갱신을 13시 30분으로 옮긴 뒤로 시각이 갈렸지만 토요일에는 일일 갱신이 돌지 않으므로
     * 여전히 겹치지 않는다. <b>여기를 평일로 옮기지 않는 이유</b>가 그것이다 — 옮기면 두 배치가
     * 같은 날 돌게 되고, 얻는 것은 유니버스의 1~2거래일 신선도뿐인데 그것은 시가총액
     * <b>순위</b>라 편입 유지 밴드가 이미 흡수한다.
     *
     * <p>대상 일자는 일일 갱신과 같은 규칙을 쓴다. 토요일에 당일을 물으면 소스에 없어
     * 어댑터가 하루씩 거슬러 올라가는데, 그 소급이 <b>날짜당 콜을 태우기 때문</b>이다
     * (시세 쪽은 기간조회가 돼서 창을 쓰지만 전종목 조회는 {@code basDt} 단일값만 받는다).
     */
    @Scheduled(cron = "0 0 20 * * SAT", zone = "Asia/Seoul")
    public void runUniverseRebalance() {
        try {
            underWriteLock("runUniverseRebalance", () -> {
                LocalDate asOf = DailyUpdateJob.targetDateFor(LocalDate.now(KR_ZONE));
                log.info("유니버스 리밸런싱을 시작한다. asOf={}", asOf);
                try {
                    universeRebalanceJob.run(asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                } catch (BatchShutdownException e) {
                    // 위와 같은 이유다. 이 잡은 process()·stage()를 안 지나므로 신호가 나려면
                    // UniverseRebalanceJob의 지수 루프에 건 게이트가 살아 있어야 한다 — 그것을
                    // 지우면 이 catch가 조용히 죽은 코드가 된다.
                    log.info("유니버스 리밸런싱이 종료로 중단됐다. asOf={} — 다음 토요일에 다시 돈다.", asOf);
                }
                // MUST NOT: 여기에 결손 보충(꼬리)을 두지 않는다 — UniverseRebalanceJob 안으로 옮겼다.
                // 여기 있으면 손·관리자 경로가 그 꼬리를 안 타고, 실측으로 리밸런싱 15회 중 11회가
                // 그 경로였다. 잡 안에 두면 어느 경로로 불려도 편입 직후에 한 번 돈다.
            });
        } finally {
            // 끝 줄 — ops/crons-done.sh가 크론마다 「명목 시각 뒤에 끝났다」를 이 줄로 잰다(크론마다 밀림이 다르다).
            log.info("크론 발화를 마쳤다. 크론={}", "runUniverseRebalance");
        }
    }

    /**
     * 기업 행위 사슬. <b>평일 14시(KST)</b> — 수집 → 계수 재구축 → (무효화가 있으면) 그 자리에서 화해.
     *
     * <h2>왜 셋을 한 자리에 묶는가</h2>
     * <p><b>수집만 걸면 지금 고치려는 결함을 매 평일 재생산한다.</b> 소스가 종류까지 준 사건은
     * 원장에 {@code APPROVED}로 들어오는데 {@code CorporateActionLedger.findUnadjusted}는
     * {@code OBSERVED}·{@code UNSUPPORTED}만 돌려주므로 <b>{@code APPROVED}는 ADR-0055의 억제에도
     * {@code CorporateActionJob}의 「판정 대기」 알림에도 안 걸린다.</b> 계수를 넣는 곳은
     * {@code CorporateActionLedger.markApplied} 하나이고 그 유일한 호출자가
     * {@code AdjustmentRecalculator.rebuild}다 — 즉 재구축이 빠지면 <b>계수 없는 승인</b>이
     * 조용히 쌓이고 값은 전부 그럴듯하다.
     *
     * <p>실증이 있다: 2026-09-01 첫 실행이 셀트리온의 2026-06-04 무상증자
     * ({@code 1.0492721033})를 찾았는데, 그 사건은 가격 배수가 0.9393이라 <b>휴리스틱 임계
     * (0.70~1.30) 안</b>이었다 — 공시만이 잡을 수 있었고 그 공시는 08-13부터 안 읽힌 채였다.
     *
     * <p>그리고 재구축이 지운 지표를 <b>백필이 안 채우면 그 종목이 화면에서 통째로 사라진다.</b>
     * 그래서 셋이 한 사슬이다. MUST NOT: 앞의 둘만 걸고 백필을 사람에게 남기지 않는다 —
     * 그 손이 빠지면 지운 채로 남는다(2026-09-01 사용자 결정).
     *
     * <p><b>14:20 정기 화해가 있는데도 여기서 바로 화해하는 이유</b>는 무거운 잡 날의 공백이다. 14:15
     * 잡이 한 시간 가까이 돌면(풀 1이라 14:20이 그 뒤로 밀린다) 지워진 종목이 그동안 화면에서 빠진다 —
     * 옛 사슬 백필은 약 4분 뒤에 채웠다. 상한은 없다 — 원본층은 지운 종목만, 파생층은 창 전체다
     * ({@link IndicatorReconciler#reconcile}).
     *
     * <h2>왜 14시인가</h2>
     * <p>13시 30분 일일 갱신은 2026-10 정기 성공 22회가 14분 22초~27분 41초(중앙값 21분)이고, 그 앞에 전날 공시 소비가 붙는다
     * ({@link #runDailyUpdate}) — 긴 날은 14시를 넘겨 이 사슬이 밀린다(값은 안 틀린다). 그리고 스케줄러 스레드
     * 풀이 1이라 두 {@code @Scheduled}가 <b>원리적으로 동시에 돌지 않는다</b> — 앞이 길어지면
     * 이쪽이 밀릴 뿐이다. MUST NOT: {@code spring.task.scheduling.pool.size}를 키우지 않는다.
     * 키우는 순간 이 겹침 방지가 사라지고, 두 잡이 같은 종목의 {@code price_daily}를 함께 쓴다.
     *
     * <p>MUST NOT: {@code universeCode}에 값을 주지 않는다. 비활성 유니버스를 손으로 고르는
     * 갈래는 <b>자동 실행에 걸지 않는다</b>(ADR-0088·0090). 인자 없는 기본 경로가 이미 활성
     * 유니버스 전량을 덮는다 — {@code US_TOP1000}이 활성이 된 뒤로 그 안에 미국이 들어 있다
     * (실측: 이 경로의 대상이 1,350종목).
     *
     * <p>IMPORTANT — {@code guard-rebuild-without-dump.sh}는 <b>셸 명령</b>을 보는 훅이라 이
     * 경로를 못 본다. 런타임 가드는 없다. 근거는 ADR-0116에 있다.
     */
    @Scheduled(cron = "0 0 14 * * MON-FRI", zone = "Asia/Seoul")
    public void runCorporateActionChain() {
        try {
            underWriteLock("runCorporateActionChain", () -> {
                LocalDate asOf = LocalDate.now(KR_ZONE);
                log.info("기업 행위 사슬을 시작한다. asOf={} — 수집 → 재구축 → 화해까지 한 번에 간다.", asOf);
                try {
                    corporateActionJob.run(asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER,
                            null, List.of(), null);
                    // 미국 순위 후보의 공시 뒤 분할을 원장에 적는다(ADR-0178) — 재구축 앞이라 같은 사슬이 APPLIED로 올린다. 자기 try다:
                    // 후보 다리의 실패가 편입 종목의 재구축·화해를 막지 않게 하고, 처음 예외는 사슬 끝에서 다시 던진다.
                    RuntimeException candidateFailure = null;
                    if (usCandidateFeed.enabled()) {
                        try {
                            candidateSplitJob.run(asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                        } catch (BatchShutdownException e) {
                            throw e;
                        } catch (RuntimeException e) {
                            candidateFailure = e;
                        }
                    }

                    AdjustmentRebuildJob.Invalidation invalidation = adjustmentRebuildJob.runAndReport(
                            asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of(), null);
                    if (invalidation.isEmpty()) {
                        log.info("계수가 원장과 이미 일치한다 — 지워진 지표가 없어 사슬 안 화해도 없다.");
                        if (candidateFailure != null) {
                            throw candidateFailure;
                        }
                        return;
                    }
                    // 화해의 asOf는 13:30 일일 갱신의 대상일이고 지표 달력 최신일을 넘지 않는다(IndicatorReconciler.dataAsOf).
                    //
                    // MUST NOT: 여기서 입력 유도(deriveInputs)를 먼저 부르지 않는다. 재구축이 원장을 APPLIED로
                    // 바꿔 기저 판정의 입력이 바뀐 것은 맞지만, 유도가 던지면 지워진 종목이 화해 없이 남아
                    // 무거운 잡 날 한 시간을 빈다 — 이 자리를 둔 이유가 통째로 무너진다. 그날 13:30까지의 판정으로 채워도
                    // 13:30 일일 갱신과 같은 입력이고(판정 NULL = OK), 같은 오후 14:18 유도 → 14:20 화해가 새 판정으로 덮는다.
                    LocalDate dataAsOf = indicatorReconciler.dataAsOf(asOf);
                    indicatorReconciler.reconcile(dataAsOf, invalidation.tickers(),
                            BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                    if (candidateFailure != null) {
                        throw candidateFailure;
                    }
                } catch (BatchShutdownException e) {
                    // 앞의 두 잡과 같은 이유다(runDailyUpdate 참조). 사슬 중간에 끊기면 다음 실행이
                    // 이어받는다 — 수집은 전 구간 재조회라 멱등이고, 재구축도 원장에서 매번 다시 짓는다.
                    log.info("기업 행위 사슬이 종료로 중단됐다. asOf={} — 다음 평일 실행이 다시 시도한다."
                            + " 화해까지 못 갔으면 그 종목의 지표는 다음 화해까지 지워진 채로 남는다.", asOf);
                }
            });
        } finally {
            // 끝 줄 — ops/crons-done.sh가 크론마다 「명목 시각 뒤에 끝났다」를 이 줄로 잰다(크론마다 밀림이 다르다).
            log.info("크론 발화를 마쳤다. 크론={}", "runCorporateActionChain");
        }
    }

    /**
     * 무거운 잡 하나. <b>평일 14시 15분(KST)</b> — 요일로 나누되 <b>연체를 보정한다.</b>
     *
     * <h2>왜 하루에 하나인가</h2>
     * <p>넷을 같은 날 돌리면 DART 콜이 23,570으로 우리 리미터({@code dart.daily-quota: 18000})를
     * 넘는다. 하루 하나 + 14시의 기업 행위 수집(700)이면 그 안이다. 잰 것 중 가장 무거운 하루는 전 종목 분기의
     * {@code full-statement-refill}이다 — 국내 350종목 · 2018~2026 · 보고서 넷에 실제콜 15,844(2026-09-21
     * #647+#648)라 700을 더해 16,544다. {@code financial-refill}·{@code governance-load}의 국내 실제콜은 실행 기록이
     * 안 남겨 재지 않았다(요청은 각각 12,600 · 8,050). 예전 문장의 「최대 10,970」은 연수가 2021~2026이던 때의 같은
     * 잡(#190 10,270)이었고, 적재 기본 창을 올린 뒤(4ef9dbd1) 낡았다.
     * <b>그 보장이 「요일로 나눈다」의 실제 근거다</b> — 소스 쪽 한도가 엔드포인트마다 따로인지는 미확인이지만
     * ({@code api-sources} skill) 어느 쪽이든 <b>우리 쪽에서 먼저 막힌다.</b>
     *
     * <h2>왜 요일만으로는 부족한가</h2>
     * <p>이 앱은 사람이 켠 동안만 돈다(ADR-0043). 지정 요일에 안 떠 있었으면 그 잡은 <b>다음
     * 달까지 밀린다</b> — 실측으로 13시 30분 크론조차 평일의 87%만 발화했다. 그래서 요일이 맞지
     * 않아도 <b>연체된 것이 있으면 하나를 돌린다.</b> 이것이 ADR-0042가 시세에 대해 「15일 창」으로
     * 푼 것과 같은 자리다 — 놓친 실행을 흡수하는 것이 주기 자체보다 앞선다.
     */
    @Scheduled(cron = "0 15 14 * * MON-FRI", zone = "Asia/Seoul")
    public void runHeavyJobOfTheDay() {
        try {
            underWriteLock("runHeavyJobOfTheDay", () -> {
                LocalDate asOf = DailyUpdateJob.targetDateFor(LocalDate.now(KR_ZONE));
                DayOfWeek today = LocalDate.now(KR_ZONE).getDayOfWeek();

                String due = heavyJobDueToday(today);
                if (due == null) {
                    log.info("오늘 돌릴 무거운 잡이 없다. today={} — 배정된 잡이 최근에 돌았거나 요일이 아니다.",
                            today);
                    return;
                }
                log.info("무거운 잡을 하나 돌린다. job={} today={} — 하루에 하나만 돌려 DART 한도를 지킨다.",
                        due, today);
                // 잡이 실행 행을 만들기 전에 던지면 그 시도가 어디에도 안 남는다 — 그러면 연체 보정 순서의 키(마지막 정기 시도)가
                // 그 잡에서 멈춰, 그 잡이 매 평일 맨 앞에 서고 다른 연체 잡의 차례를 빼앗는다. 행이 새로 생겼는지는 「마지막 정기
                // 시도」가 바뀌었는가로 가른다 — 두 시계(JVM·DB)를 비교하지 않는다.
                // MUST: 이 조회와 아래 catch는 바깥 finally 안이다 — 밖이면 그 DB 예외가 끝 줄을 건너뛰어 crons-done.sh가 그날을
                // 「남은 발화」로 막는다.
                Instant attemptBefore = freshnessPolicy.lastScheduledAttemptAt(due);
                try {
                    runHeavy(due, asOf);
                } catch (BatchShutdownException e) {
                    log.info("무거운 잡이 종료로 중단됐다. job={} — 연체로 남아 다음 평일 실행이 집어 간다.", due);
                } catch (RuntimeException e) {
                    if (Objects.equals(attemptBefore, freshnessPolicy.lastScheduledAttemptAt(due))) {
                        recordStartFailure(due, asOf, e);
                    }
                    throw e;
                }
            });
        } finally {
            // 끝 줄 — ops/crons-done.sh가 크론마다 「명목 시각 뒤에 끝났다」를 이 줄로 잰다(크론마다 밀림이 다르다).
            log.info("크론 발화를 마쳤다. 크론={}", "runHeavyJobOfTheDay");
        }
    }

    /**
     * 잡이 시작하기 전에 던진 것을 <b>FAILED 시도 행</b>으로 남긴다(ADR-0174).
     *
     * <p>잡 안에서 던진 것은 잡이 {@code failUnexpectedly}로 이미 마감하므로 여기 안 온다(행이 생겼다). 범위는 정기 경로의
     * 선언 그대로({@code FULL})다 — FAILED는 어차피 시계에 안 세고, 이 행이 하는 일은 「시도했다」를 남기는 것뿐이다.
     */
    private void recordStartFailure(String jobName, LocalDate asOf, RuntimeException cause) {
        batchExecutions.start(jobName, asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 0,
                        BatchExecutionRecorder.DeclaredScope.FULL)
                .failUnexpectedly("시작 실패", cause);
    }

    /**
     * 오늘 돌 잡을 고른다. <b>배정 요일이 먼저이고, 없으면 연체 잡 중 가장 오래 정기 시도를 못 받은 것</b>이다.
     *
     * <p>MUST: {@link #HEAVY_JOB_MIN_INTERVAL_DAYS} 안에 <b>시계에 센 성공</b>이 있는 잡은 고르지 않는다. 거버넌스·재무는
     * 공시일에 계단으로 바뀌므로 주 1회 돌면 <b>콜만 태운다</b> — 같은 값을 다시 받는 것이
     * {@code governance-load}는 국내 8,050 + 미국 4,000 = 12,050콜이다. 시장 미완료로 끝난 정기 실행은 시계에 안 세므로
     * (ADR-0174) 그 잡은 연체로 남아 다시 고를 수 있다 — 그것이 의도다.
     *
     * <h2>연체가 둘 이상이면 — 키는 「마지막 정기 시도」다(ADR-0174)</h2>
     * <p>키 = 그 잡의 마지막 SCHEDULER 행 {@code started_at}(상태·범위 무관) · 없으면 가장 오래된 것으로 친다 · 같으면
     * 마지막 성공에서 오래된 것부터. 「마지막 성공」만 키로 쓰면 매번 미완료로 끝나는 잡(예: 한 시장 소스가 계속 죽은 G)이
     * 늘 가장 오래 밀린 것으로 남아 <b>다른 연체 잡의 차례를 영영 빼앗는다</b> — 운영 시계를 재생하면 10-22~10-29 G가
     * 매일 미완료일 때 옛 키로는 FR이 배정 요일(11-05 목)까지 밀리고, 이 키로는 10-30에 돈다
     * ({@code PipelineSchedulerChainTest}). 잡이 시작 전에 던져도 시도 행을 남기는 것({@link #recordStartFailure})이
     * 이 키가 멈추지 않게 한다.
     */
    // 패키지 가시성인 이유는 **요일을 이 클래스가 스스로 읽기 때문**이다. 호출부가
    // LocalDate.now()로 오늘을 정하므로, 이 판단을 밖에서 못 부르면 「화요일에 무엇이 도는가」를
    // 화요일에만 시험할 수 있다. 인자로 받는 순수 함수라 여는 대가가 작다.
    String heavyJobDueToday(DayOfWeek today) {
        Map<DayOfWeek, String> assignment = Map.of(
                DayOfWeek.TUESDAY, GovernanceLoadJob.JOB_NAME,
                DayOfWeek.WEDNESDAY, FullStatementRefillJob.JOB_NAME,
                DayOfWeek.THURSDAY, FinancialRefillJob.JOB_NAME);

        String assigned = assignment.get(today);
        if (assigned != null && isOverdue(assigned)) {
            return assigned;
        }
        // 연체 보정. 정기 시도가 가장 오래된 것(없음이 맨 앞) → 마지막 성공이 가장 오래된 것.
        Comparator<String> byLastAttempt = Comparator.comparing(
                freshnessPolicy::lastScheduledAttemptAt, Comparator.nullsFirst(Comparator.naturalOrder()));
        Comparator<String> byStaleness = Comparator.comparingLong(this::daysSinceSuccess).reversed();
        return assignment.values().stream()
                .filter(this::isOverdue)
                .sorted() // Map.of의 순회 순서는 정해져 있지 않다 — 키가 전부 같을 때도 답이 하나이게.
                .min(byLastAttempt.thenComparing(byStaleness))
                .orElse(null);
    }

    private boolean isOverdue(String jobName) {
        return daysSinceSuccess(jobName) > HEAVY_JOB_MIN_INTERVAL_DAYS;
    }

    private long daysSinceSuccess(String jobName) {
        return freshnessPolicy.daysSinceLastSuccess(jobName, Instant.now());
    }

    private void runHeavy(String jobName, LocalDate asOf) {
        BatchExecutionRecorder.TriggeredBy by = BatchExecutionRecorder.TriggeredBy.SCHEDULER;
        if (GovernanceLoadJob.JOB_NAME.equals(jobName)) {
            // MUST: 유도를 이어 부른다. 적재만 하면 governance_fact가 안 바뀌고 화면은 옛 값
            // 그대로인데 둘 다 성공으로 끝난다.
            //
            // MUST: span을 주지 않는다. 표마다 필요한 해 수가 다른데(GovernanceLoadJob.YEARS_BACK)
            // 스칼라 하나로 덮으면 SHARE_TOTALS의 3년 차분(끝점 Y-3·Y)과 OUTSIDE_DIRECTOR의
            // 3년 합이 **원리적으로 불성립**한다 — 그래도 잡은 성공으로 끝난다.
            // 여기 있던 2는 「회계연도가 역년 둘에 걸치니 두 해를 받는다」가 근거였는데,
            // 그 문제는 요청 역년의 **위쪽 끝**(GovernanceDataSource.latestRequestableYear)이 풀었다.
            //
            // **「뒤로 넓히는 것은 +1종목」은 최신일에서 잰 수다**(2026-09-20). 보존 창
            // **앞부분**에서 다시 재면 다르다 — 창 시작의 커버가 span 3에 856, span 4에 970,
            // span 5에도 970이다(2026-09-20 · 편입 1,000). 그래서 DIVIDEND의 기본값을 4로
            // 올렸고, 다섯째 해는 여전히 +0이다.
            // 유도(governance-derive)는 여기서 안 부른다 — 14:18 deriveReconcileInputs가 매 평일
            // 보존 창 편입 전원으로 돈다. 풀이 1이라 이 잡이 길어지면 그 뒤로 밀릴 뿐 건너뛰지 않는다.
            governanceLoadJob.run(asOf, by, null, null, List.of(), List.of());
        } else if (FullStatementRefillJob.JOB_NAME.equals(jobName)) {
            // 전 종목 분기가 필요하다 — 주당 지표가 분기 행을 읽는다(ADR-0098).
            fullStatementRefillJob.run(asOf, by, null, List.of(), false, true);
        } else if (FinancialRefillJob.JOB_NAME.equals(jobName)) {
            // 정정이 지운 지표는 14:20 화해가 채운다(상한 없음). 옛 모양은 여기서 무효화 종목만
            // 백필하고 100종목을 넘으면 지운 채 두었다 — 미국 공시 시즌에 그 상한을 넘는다.
            FinancialRefillJob.Invalidation invalidation =
                    financialRefillJob.runAndReport(asOf, by, null, List.of(), null);
            log.info("재무 정정·교정으로 지표가 지워진 종목 {}개 — 14:20 화해가 다시 쓴다.",
                    invalidation.tickers().size());
        } else {
            throw new IllegalStateException("배정에 없는 잡을 고를 수 없다: " + jobName);
        }
        // 재무를 쓴 뒤의 주식수 기저 재판정(ADR-0162)도 14:18이 맡는다 — 매 평일 돌므로
        // 재무를 쓴 날만 거는 것보다 넓고, 토요일 리밸런싱 전에 반드시 한 번 이상 돈다.
    }

    /**
     * 원장 공시 목록. <b>평일 14시 16분(KST)</b> — 미국 EDGAR 일일 색인 · 국내 DART 공시검색을 받아 소비 장부에 쌓는다(ADR-0176).
     *
     * <p><b>14:15 무거운 잡과 크론을 가르는 이유</b>: 그 잡은 실패하면 예외를 다시 던진다 — 같은 크론에 두면 그날 목록이 함께
     * 빠진다. 풀이 1이라 이 크론은 14:15 뒤, 14:18 앞에 돈다(앞이 길어지면 밀릴 뿐 순서는 그대로다). 대상일은 실행일(KST)이다 —
     * 미국은 어제(미 동부)까지의 색인이 KST 11시께 올라와 있고, 국내는 어제까지 닫고 오늘은 받기만 한다.
     *
     * <p><b>13:30 일일 갱신 앞에서 목록·소비가 이미 한 번 돌았다</b>({@link #runDailyUpdate}). 여기서 다시 도는 이유는 둘이다 —
     * 미국 companyfacts가 아직 그 공시를 안 실어 열린 채 남은 행을 다시 묻고(소비 판정은 그 응답이 근거다), 국내 장중 접수분을
     * 받는다. 미국 커서는 이미 전날까지 닫혀 분기 목록 1콜로 끝난다. 장부는 공시 단위로 멱등이고 시도 수는 하루에 한 번 센다.
     *
     * <p>목록 뒤에 소비({@link FilingConsumptionJob})를 잇는다 — 열린 장부 행을 지금 편입 종목의 좁힌 FR·FSR({@code GAP_FILL})로
     * 먹이고, 그 종목을 쓴 트랜잭션 안에서 소비됨을 표시한다. 정정이 지운 지표는 14:20 화해가 채운다.
     *
     * <p>소비 뒤에 편입 원자료 따라잡기({@link FreshnessCatchUpJob} · ADR-0177)를 잇는다 — 편입 뒤로 FR·FSR·G가 정기 범위로
     * 맞춘 적이 없는 편입을 좁힌 실행({@code GAP_FILL})으로 맞춘다. 주 자리는 리밸런싱 꼬리이고 여기는 남은 것을 집는다.
     *
     * <p>끝에 미국 순위 후보의 공시 소비({@link FilingConsumptionJob#runCandidates} · ADR-0178 · {@link UsCandidateFeed}가 켜졌을 때)를
     * 잇는다 — 활성 편입 없는 미국 종목의
     * 열린 행을 같은 판정으로 먹여 순위 재료(주식수)를 최신 공시에 맞춘다. 편입 일이 다 끝난 뒤라 그 단계의 실패가 편입을 안 막는다.
     */
    @Scheduled(cron = "0 16 14 * * MON-FRI", zone = "Asia/Seoul")
    public void runFilingListing() {
        try {
            underWriteLock("runFilingListing", () -> {
                LocalDate today = LocalDate.now(KR_ZONE);
                log.info("원장 공시 목록을 받는다. today={}", today);
                // 단계마다 자기 try다 — 앞 단계가 예외로 끝나도 뒤 단계는 돈다(셋이 서로의 입력이 아니다: 소비는 이미 쌓인 행을,
                // 따라잡기는 표지를 읽는다). 잡 안에서 던진 것은 그 잡이 이미 FAILED와 알림을 냈으므로 여기서는 모아 끝에서 처음
                // 것을 다시 던진다(@Scheduled 밖에서 ERROR로 남는다). MUST: 종료 신호는 그 자리에서 사슬을 끝낸다 — 삼키고 다음
                // 단계를 돌면 종료가 시작된 뒤에도 쿼터를 태운다(ADR-0078).
                RuntimeException held = null;
                try {
                    filingListingJob.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                } catch (BatchShutdownException e) {
                    log.info("공시 목록이 종료로 중단됐다. today={} — 커서는 닫은 날까지이고 다음 평일이 이어 받는다.", today);
                    return;
                } catch (RuntimeException e) {
                    held = e;
                }
                // 목록 뒤에 소비한다 — 방금 쌓은 행까지 같은 날 먹인다(2차). 목록이 시장 하나를 못 받아도(실행은 끝난다) 이미
                // 쌓인 행은 먹인다.
                try {
                    filingConsumptionJob.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                } catch (BatchShutdownException e) {
                    log.info("공시 소비가 종료로 중단됐다. today={} — 표시는 종목 트랜잭션 안이라 끝낸 종목까지만 남고 나머지는 다음 평일이 먹인다.",
                            today);
                    return;
                } catch (RuntimeException e) {
                    held = keepFirst(held, e);
                }
                // 소비 뒤에 따라잡는다(ADR-0177) — 소비가 방금 표지를 쓴 종목(미국 기본 창 FR)은 술어에서 빠진다. 꼬리가 안 돌았거나
                // 상한·실패로 남은 편입을 집는다. 같은 오후 14:18 유도·14:20 화해가 덮으므로 사슬 안 화해를 안 한다.
                try {
                    freshnessCatchUpJob.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of());
                } catch (BatchShutdownException e) {
                    log.info("편입 원자료 따라잡기가 종료로 중단됐다. today={} — 표지는 종목 트랜잭션 안이라 남은 종목은 다음 차례가 집는다.",
                            today);
                    return;
                } catch (RuntimeException e) {
                    held = keepFirst(held, e);
                }
                // 편입 일이 다 끝난 뒤 미국 순위 후보를 먹인다(ADR-0178) — 순위 재료(주식수)라 같은 오후의 유도·화해와 무관하고,
                // 여기서 던져도 앞 셋은 이미 끝났다.
                try {
                    if (usCandidateFeed.enabled()) {
                        filingConsumptionJob.runCandidates(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                    }
                } catch (BatchShutdownException e) {
                    log.info("후보 공시 소비가 종료로 중단됐다. today={} — 표시는 종목 트랜잭션 안이라 남은 행은 다음 평일이 먹인다.", today);
                    return;
                } catch (RuntimeException e) {
                    held = keepFirst(held, e);
                }
                // 끝에 국내 보통주 몫 재료를 받는다(ADR-0192) — 값을 안 바꾸는 재료 표만 쓰고, 앞 단계가 그날 새로 쓴 국내 판까지 받으려고
                // 맨 뒤다. 한 판에 새로 받는 수는 설정 상한이 묶는다(14:18 · 14:20을 안 민다).
                try {
                    krXbrlCollectJob.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of(), null);
                } catch (BatchShutdownException e) {
                    log.info("국내 보통주 몫 재료 받기가 종료로 중단됐다. today={} — 판마다 표에 남아 다음 평일이 잇는다.", today);
                    return;
                } catch (RuntimeException e) {
                    held = keepFirst(held, e);
                }
                // 끝에 실적 발표 감지(ADR-0195) — 13:30에 submissions에 아직 없던 8-K 항목을 다시 묻고 훑기를 잇는다. 값을 안 바꾼다.
                try {
                    earningsReleaseJob.run(today, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                } catch (BatchShutdownException e) {
                    log.info("실적 발표 감지가 종료로 중단됐다. today={} — 표는 종목마다 남아 다음 평일이 잇는다.", today);
                    return;
                } catch (RuntimeException e) {
                    held = keepFirst(held, e);
                }
                if (held != null) {
                    throw held;
                }
            });
        } finally {
            // 끝 줄 — ops/crons-done.sh가 크론마다 「명목 시각 뒤에 끝났다」를 이 줄로 잰다(크론마다 밀림이 다르다).
            log.info("크론 발화를 마쳤다. 크론={}", "runFilingListing");
        }
    }

    /** 사슬에서 처음 난 예외를 지키고 뒤의 것은 그 아래에 단다 — 뒤의 것이 원인을 덮지 않게. */
    private static RuntimeException keepFirst(RuntimeException held, RuntimeException next) {
        if (held == null) {
            return next;
        }
        held.addSuppressed(next);
        return held;
    }

    /**
     * 화해의 입력 유도. <b>평일 14시 18분(KST)</b> — 주식수 기저(미국) → 거버넌스 사실(보존 창 편입).
     *
     * <p><b>화해(14:20)와 크론을 가르는 이유</b>는 실패가 서로를 막지 않게 하려는 것이다. 유도가 실패하면
     * 어제 판정·유도가 남아 있고 화해는 그것으로 돈다 — 13:30 일일 갱신도 그 앞 유도(소비가 먹인 날만)가 던지든 말든
     * 계산한다(판정 {@code NULL}은 OK로 읽힌다). 한 메서드에 묶으면 유도 실패 하루가 전 종목의
     * 과거 순위를 하루 낡게 둔다.
     *
     * <p>14:15 무거운 잡 뒤인 이유: 재무·거버넌스 원문을 쓰는 것이 그 잡이고, 풀이 1이라 이 크론은
     * 그 잡이 끝난 뒤에야 돈다 — 그날 새로 들어온 판을 그날 유도한다.
     */
    @Scheduled(cron = "0 18 14 * * MON-FRI", zone = "Asia/Seoul")
    public void deriveReconcileInputs() {
        try {
            underWriteLock("deriveReconcileInputs", () -> {
                LocalDate asOf = DailyUpdateJob.targetDateFor(LocalDate.now(KR_ZONE));
                log.info("화해 입력을 유도한다. asOf={} — 주식수 기저 → 거버넌스 사실", asOf);
                try {
                    indicatorReconciler.deriveInputs(asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                } catch (BatchShutdownException e) {
                    log.info("화해 입력 유도가 종료로 중단됐다. asOf={} — 어제 판정으로 화해가 돈다.", asOf);
                }
            });
        } finally {
            // 끝 줄 — ops/crons-done.sh가 크론마다 「명목 시각 뒤에 끝났다」를 이 줄로 잰다(크론마다 밀림이 다르다).
            log.info("크론 발화를 마쳤다. 크론={}", "deriveReconcileInputs");
        }
    }

    /**
     * 지표 화해. <b>평일 14시 20분(KST)</b> — 보존 창 편입 전원의 원본층 한 판 → 창 전체의 파생층.
     *
     * <p>무엇이 지웠는지(기업 행위 · 재무 정정 · 새 라벨)를 묻지 않고 매 평일 전부를 맞춘다
     * ({@link IndicatorReconciler} javadoc). 13:30 일일 갱신이 지금 편입의 원본층을 창 전체로,
     * 파생층을 최신일 하루만 쓰므로 그 둘이 못 닫는 것 — 과거 구성원의 원본층과 최신일 앞의 파생층 —
     * 이 이 자리의 몫이다.
     *
     * <p><b>대상일은 일일 갱신과 같고 지표 달력 최신일을 넘지 않는다</b>({@link IndicatorReconciler#dataAsOf}).
     * 다르면 {@code asOf}의 주식수를 따라가는 지표가 두 판 사이를 오가고, 넘으면 13:30이 안 돈 날 몇 종목짜리
     * 날짜가 화면 최신일이 된다.
     */
    @Scheduled(cron = "0 20 14 * * MON-FRI", zone = "Asia/Seoul")
    public void reconcileIndicators() {
        try {
            underWriteLock("reconcileIndicators", () -> {
                LocalDate asOf = indicatorReconciler.dataAsOf(LocalDate.now(KR_ZONE));
                log.info("지표 화해를 시작한다. asOf={} — 원본층(보존 창 편입 전원) → 파생층(창 전체)", asOf);
                try {
                    indicatorReconciler.reconcile(asOf, List.of(), BatchExecutionRecorder.TriggeredBy.SCHEDULER);
                } catch (BatchShutdownException e) {
                    log.info("지표 화해가 종료로 중단됐다. asOf={} — 다음 평일 화해가 다시 돈다.", asOf);
                }
            });
        } finally {
            // 끝 줄 — ops/crons-done.sh가 크론마다 「명목 시각 뒤에 끝났다」를 이 줄로 잰다(크론마다 밀림이 다르다).
            log.info("크론 발화를 마쳤다. 크론={}", "reconcileIndicators");
        }
    }

    /**
     * 신선도 점검. <b>평일 15시 30분(KST)</b> — 안 돈 잡과 끊긴 사슬을 재서 알린다.
     *
     * <p><b>안 돈 것은 어디에도 안 뜬다</b> — {@code batch_execution}은 <b>돈 것</b>만 적기
     * 때문이다. 그래서 이 자리가 필요하고, 그것이 이 변경 전체의 이유이기도 하다.
     *
     * <p>15시 30분인 근거는 앞선 무거운 잡이다. {@code governance-load}의 실측이
     * <b>54분 58초</b>다(2026-09-20 · 1,350종목 12,050콜 = 국내 8,050 + 미국 4,000).
     * 스레드 풀이 1이라 그전에는 이 평가가 밀린다.
     *
     * <p><b>소요는 콜 수에 선형이 아니라 소스별 리미터가 정한다</b>({@code dart} 600/분 = 100ms ·
     * {@code alpaca} 100/분 = 600ms, 버스트 없는 균등 간격). 그 모델이 두 실측을 다 재현한다 —
     * 11,050콜에 43분 25초(실측 44분 52초) · 12,050콜에 53분 25초(실측 54분 58초)로 오차 3% 안이다.
     * <b>MUST: 표별 해 수나 시장을 바꾸면 이 모델로 먼저 재고, 실행 뒤 실측으로 갈아끼운다</b> —
     * 이 수는 코드 변경 없이도 자란다(21분 30초 → 44분 52초 → 54분 58초).
     *
     * <p><b>그래서 무거운 잡을 14시 15분으로 당겼다</b>(2026-09-20). 14시 30분이면 15시 24분 58초에
     * 끝나 여유가 <b>5분</b>뿐이었다. 14시 사슬의 실측 최악이 {@code corporate-action} 12분 27초 +
     * {@code adjustment-rebuild} 2초 + {@code indicator-backfill} 4분 1초라 14시 16분 30초에 끝나고,
     * 풀이 1이라 겹치면 <b>밀릴 뿐</b>이다. 밀려도 틀리지는 않지만 <b>같은 날 안에 끝나는 것</b>이 알림의
     * 뜻을 지킨다.
     *
     * <p><b>매 평일 화해(14:18·14:20)가 들어온 뒤의 최악은 여유 약 2분이다</b>(2026-10-04 실행 이력):
     * 사슬 끝 14:12:44(corporate-action 최악 12:44) → 사슬 안 화해 ② 최악 7:57 → G 54:58 → 유도 3.8초 +
     * 기저 판정(사본 실측 0.1초) → ① 4:08 → ② 7:57 = <b>15시 27분 58초</b>. 무효화가 없는 G 날은
     * 15시 22분 8초다. 이 수도 코드 변경 없이 자라므로(G가 그랬다) 넘으면 이 점검이 밀린다 — 값은
     * 안 틀린다.
     *
     * <p><b>MUST: 이 시각을 앞당기려면 그 잡의 소요를 먼저 잰다.</b> 소요는 표별 기본 해 수와
     * 시장 수가 정하므로 <b>코드 변경 없이도 자란다</b> — 실제로 15시 10분이던 동안 소요가
     * 21분에서 44분으로 늘었는데 시험은 초록이었다(간격 40분 ≥ 가드 22분).
     *
     * <p>MUST NOT: {@code batch_execution}에 행을 만들지 않는다. 읽기이고, 실행 이력에 섞이면
     * <b>「잡이 돌았다」와 「잡이 안 돈 것을 확인했다」가 같은 목록에 뜬다.</b>
     */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Seoul")
    public void checkJobFreshness() {
        try {
            boolean sent = freshnessAlerter.evaluateAndAlert();
            log.info("배치 신선도를 점검했다. 알림={}", sent ? "보냈다" : "없거나 이미 보냈다");

            // 편입 계약도 같은 자리에서 잰다 — 읽기이고, batch_execution에 행을 만들지 않는다.
            //
            // MUST: 여기서는 채우지 않는다. 채우는 것은 편입이 바뀐 직후(토요일 꼬리)의 일이고,
            // 매 평일 적재를 걸면 「돌렸는데 소스가 안 준」 종목에 콜이 매일 나간다.
            //
            // MUST: 13:30 일일 갱신 뒤여야 한다. 지표 축(INDICATOR_DROP)은 그날 지표가 쓰인 뒤에만
            // 뜻이 있고, 토요일에는 잴 것이 없다.
            boolean gapsSent = universeIntegrityAlerter.evaluateAndAlert();
            log.info("편입 원자료를 점검했다. 알림={}", gapsSent ? "보냈다" : "없거나 이미 보냈다");

            // 미국 시세 원장 무결성(ADR-0181) — 다른 증권 봉 · 빠진 원장 사건의 새 꼴. 읽기만 하고 batch_execution에 행을 안 만든다.
            boolean priceSent = priceIntegrityAlerter.evaluateAndAlert();
            log.info("시세 원장을 점검했다. 알림={}", priceSent ? "보냈다" : "없거나 이미 보냈다");
        } finally {
            // 끝 줄 — ops/crons-done.sh가 크론마다 「명목 시각 뒤에 끝났다」를 이 줄로 잰다(크론마다 밀림이 다르다).
            log.info("크론 발화를 마쳤다. 크론={}", "checkJobFreshness");
        }
    }
}
