package com.stockscreener.pipeline.scheduler;

import com.stockscreener.pipeline.batch.AdjustmentRebuildJob;
import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
import com.stockscreener.pipeline.batch.BatchShutdownException;
import com.stockscreener.pipeline.batch.CorporateActionJob;
import com.stockscreener.pipeline.batch.DailyUpdateJob;
import com.stockscreener.pipeline.batch.FinancialRefillJob;
import com.stockscreener.pipeline.batch.FullStatementRefillJob;
import com.stockscreener.pipeline.batch.GovernanceDeriveJob;
import com.stockscreener.pipeline.batch.GovernanceLoadJob;
import com.stockscreener.pipeline.batch.IndicatorReconciler;
import com.stockscreener.pipeline.batch.JobFreshnessAlerter;
import com.stockscreener.pipeline.batch.JobFreshnessPolicy;
import com.stockscreener.pipeline.batch.UniverseIntegrityAlerter;
import com.stockscreener.pipeline.batch.UniverseRebalanceJob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

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
                             UniverseIntegrityAlerter universeIntegrityAlerter) {
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
     */
    @Scheduled(cron = "0 30 13 * * MON-FRI", zone = "Asia/Seoul")
    public void runDailyUpdate() {
        try {
            LocalDate targetDate = DailyUpdateJob.targetDateFor(LocalDate.now(KR_ZONE));
            log.info("일일 갱신을 시작한다. targetDate={}", targetDate);
            try {
                dailyUpdateJob.run(targetDate, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
            } catch (BatchShutdownException e) {
                // IMPORTANT — 운영에서 종료가 배치를 끊는 경로가 실제로 여기다. 안 잡으면 Spring의
                // 기본 처리가 ERROR로 찍고, sentry.logging.minimum-event-level이 error라 배포마다
                // 이슈가 하나씩 쌓인다. catch-all이 아니라 이름 붙은 신호 하나만 받는다.
                log.info("일일 갱신이 종료로 중단됐다. targetDate={} — ABORTED로 남는다. "
                        + "시세는 조회 창이 다시 시도하지만 백분위·워치리스트는 안 이어진다.", targetDate);
            }
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
     * <p>13시 30분 일일 갱신이 실측 13분 36초라 13시 44분에 끝난다. 그리고 스케줄러 스레드
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
            LocalDate asOf = LocalDate.now(KR_ZONE);
            log.info("기업 행위 사슬을 시작한다. asOf={} — 수집 → 재구축 → 화해까지 한 번에 간다.", asOf);
            try {
                corporateActionJob.run(asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER,
                        null, List.of(), null);

                AdjustmentRebuildJob.Invalidation invalidation = adjustmentRebuildJob.runAndReport(
                        asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of(), null);
                if (invalidation.isEmpty()) {
                    log.info("계수가 원장과 이미 일치한다 — 지워진 지표가 없어 사슬 안 화해도 없다.");
                    return;
                }
                // 화해의 asOf는 13:30 일일 갱신의 대상일이고 지표 달력 최신일을 넘지 않는다(IndicatorReconciler.dataAsOf).
                //
                // MUST NOT: 여기서 입력 유도(deriveInputs)를 먼저 부르지 않는다. 재구축이 원장을 APPLIED로
                // 바꿔 기저 판정의 입력이 바뀐 것은 맞지만, 유도가 던지면 지워진 종목이 화해 없이 남아
                // 무거운 잡 날 한 시간을 빈다 — 이 자리를 둔 이유가 통째로 무너진다. 어제 판정으로 채워도
                // 13:30과 같은 의미이고(판정 NULL = OK), 같은 오후 14:18 유도 → 14:20 화해가 새 판정으로 덮는다.
                LocalDate dataAsOf = indicatorReconciler.dataAsOf(asOf);
                indicatorReconciler.reconcile(dataAsOf, invalidation.tickers(),
                        BatchExecutionRecorder.TriggeredBy.SCHEDULER);
            } catch (BatchShutdownException e) {
                // 앞의 두 잡과 같은 이유다(runDailyUpdate 참조). 사슬 중간에 끊기면 다음 실행이
                // 이어받는다 — 수집은 전 구간 재조회라 멱등이고, 재구축도 원장에서 매번 다시 짓는다.
                log.info("기업 행위 사슬이 종료로 중단됐다. asOf={} — 다음 평일 실행이 다시 시도한다."
                        + " 화해까지 못 갔으면 그 종목의 지표는 다음 화해까지 지워진 채로 남는다.", asOf);
            }
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
     * <b>그 보장이 「요일로 나눈다」의 실제 근거다</b> — 소스 쪽 한도는 엔드포인트마다 따로지만
     * ({@code api-sources} skill) <b>우리 쪽에서 먼저 막힌다.</b>
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
            try {
                runHeavy(due, asOf);
            } catch (BatchShutdownException e) {
                log.info("무거운 잡이 종료로 중단됐다. job={} — 연체로 남아 다음 평일 실행이 집어 간다.", due);
            }
        } finally {
            // 끝 줄 — ops/crons-done.sh가 크론마다 「명목 시각 뒤에 끝났다」를 이 줄로 잰다(크론마다 밀림이 다르다).
            log.info("크론 발화를 마쳤다. 크론={}", "runHeavyJobOfTheDay");
        }
    }

    /**
     * 오늘 돌 잡을 고른다. <b>배정 요일이 먼저이고, 없으면 가장 오래 밀린 것</b>이다.
     *
     * <p>MUST: {@link #HEAVY_JOB_MIN_INTERVAL_DAYS} 안에 돈 잡은 고르지 않는다. 거버넌스·재무는
     * 공시일에 계단으로 바뀌므로 주 1회 돌면 <b>콜만 태운다</b> — 같은 값을 다시 받는 것이
     * {@code governance-load}는 국내 8,050 + 미국 4,000 = 12,050콜이다.
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
        // 연체 보정. 배정 요일을 놓친 잡을 가장 오래 밀린 것부터 집는다.
        return assignment.values().stream()
                .filter(this::isOverdue)
                .min((a, b) -> Long.compare(daysSinceSuccess(b), daysSinceSuccess(a)))
                .orElse(null);
    }

    private boolean isOverdue(String jobName) {
        return daysSinceSuccess(jobName) > HEAVY_JOB_MIN_INTERVAL_DAYS;
    }

    private long daysSinceSuccess(String jobName) {
        return freshnessPolicy.daysSinceLastSuccess(jobName, java.time.Instant.now());
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
     * 화해의 입력 유도. <b>평일 14시 18분(KST)</b> — 주식수 기저(미국) → 거버넌스 사실(보존 창 편입).
     *
     * <p><b>화해(14:20)와 크론을 가르는 이유</b>는 실패가 서로를 막지 않게 하려는 것이다. 유도가 실패하면
     * 어제 판정·유도가 남아 있고 화해는 그것으로 돈다 — 13:30 일일 갱신도 판정과 무관하게 매일
     * 계산한다(판정 {@code NULL}은 OK로 읽힌다). 한 메서드에 묶으면 유도 실패 하루가 전 종목의
     * 과거 순위를 하루 낡게 둔다.
     *
     * <p>14:15 무거운 잡 뒤인 이유: 재무·거버넌스 원문을 쓰는 것이 그 잡이고, 풀이 1이라 이 크론은
     * 그 잡이 끝난 뒤에야 돈다 — 그날 새로 들어온 판을 그날 유도한다.
     */
    @Scheduled(cron = "0 18 14 * * MON-FRI", zone = "Asia/Seoul")
    public void deriveReconcileInputs() {
        try {
            LocalDate asOf = DailyUpdateJob.targetDateFor(LocalDate.now(KR_ZONE));
            log.info("화해 입력을 유도한다. asOf={} — 주식수 기저 → 거버넌스 사실", asOf);
            try {
                indicatorReconciler.deriveInputs(asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
            } catch (BatchShutdownException e) {
                log.info("화해 입력 유도가 종료로 중단됐다. asOf={} — 어제 판정으로 화해가 돈다.", asOf);
            }
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
            LocalDate asOf = indicatorReconciler.dataAsOf(LocalDate.now(KR_ZONE));
            log.info("지표 화해를 시작한다. asOf={} — 원본층(보존 창 편입 전원) → 파생층(창 전체)", asOf);
            try {
                indicatorReconciler.reconcile(asOf, List.of(), BatchExecutionRecorder.TriggeredBy.SCHEDULER);
            } catch (BatchShutdownException e) {
                log.info("지표 화해가 종료로 중단됐다. asOf={} — 다음 평일 화해가 다시 돈다.", asOf);
            }
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
        } finally {
            // 끝 줄 — ops/crons-done.sh가 크론마다 「명목 시각 뒤에 끝났다」를 이 줄로 잰다(크론마다 밀림이 다르다).
            log.info("크론 발화를 마쳤다. 크론={}", "checkJobFreshness");
        }
    }
}
