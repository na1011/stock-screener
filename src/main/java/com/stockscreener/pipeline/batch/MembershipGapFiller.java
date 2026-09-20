package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.MembershipGap;
import com.stockscreener.pipeline.collector.StockRegistrar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 리밸런싱이 끝난 뒤 <b>새로 생긴 편입 결손을 채운다.</b> 감지는
 * {@link StockRegistrar#membershipGaps()}, 통지는 {@link UniverseIntegrityAlerter}가 하고
 * 여기서는 <b>무엇을 부를지</b>만 정한다.
 *
 * <h2>왜 리밸런싱 꼬리인가</h2>
 * <p>결손이 생기는 유일한 순간이 <b>편입이 바뀌는 때</b>다. 그리고 그 창이 좁다 —
 * 리밸런싱(토 20:00)부터 다음 일일 갱신(월 13:30)까지 약 41시간이고, 그 사이에 아무도 안
 * 채우면 그 종목은 <b>지표가 대부분 판정불가인 채 유니버스에 남는다.</b>
 *
 * <p><b>다만 41시간에 매여 있지는 않다.</b> {@code InitialLoadJob}은 티커를 주면
 * {@code NOT EXISTS}를 적용하지 않고(그 javadoc), 감지 술어도 일일 갱신이 안 지운다. 그래서
 * 토요일 크론이 안 떠도 <b>평일 15:30 점검이 같은 결손을 신고</b>하고 사람이 같은 명령을 친다.
 * ADR-0116 §버린 대안이 <i>"토요일 크론은 실측 발화가 2회다"</i>로 토요일 배정을 기각했는데,
 * 다시 재니 <b>4/4</b>이고(2026-08-22·08-29·09-05·09-12) <b>안 떠도 무너지지 않는</b> 것이
 * 여기에 거는 근거다.
 *
 * <h2>MUST: 축별로 갈라 부른다</h2>
 * <p>한 번에 부르면 <b>이미 재무가 있는 종목에 정정본이 쌓여</b> 그 종목 지표가 보존 창
 * 전체에서 지워지는데({@code InitialLoadJob}의 {@code revised} 경고) <b>꼬리에 백필이 없다.</b>
 * 시세 결손은 {@code skipFinancial}, 재무 결손은 {@code skipPrice}로 간다 — 후자의 대상은
 * 정의상 재무 0행이라 덮을 기존 행이 없다.
 *
 * <p><b>영업활동현금흐름 결손이 셋째 갈래다.</b> {@code full-statement-refill}은 <b>결손 컬럼만</b>
 * 채우므로 기존 값을 덮지 않는다 — 실측(2026-09-20 {@code 001820})으로 <b>채움 53 · 정정 0 ·
 * 교정 0 · 정정보류 0</b>이었고 FY 영업활동현금흐름이 0행에서 11행이 됐다. <b>국내만 부른다</b>
 * ({@link MembershipGap#refillableStatements}) — 그 잡은 DART 전용이라 미국에는 한 행도 못 쓴다.
 *
 * <p><b>이 갈래가 없던 동안 그 결손은 영영 안 닫혔다.</b> {@code UniverseIntegrityAlerter}가
 * 닫는 명령을 <b>문장으로만</b> 냈고 부르는 코드가 없었다 — 값이 없는 자리에 이유를 적는 것과
 * 값을 만드는 것은 다르다.
 *
 * <h2>MUST NOT: 여기에 기업 행위 수집·계수 재구축을 잇지 않는다</h2>
 * <p>ADR-0116이 <b>수집 → 재구축 → 백필 셋을 한 사슬로</b> 묶었으므로(2026-09-01 사용자 결정)
 * 앞의 둘만 부르면 <b>지운 지표를 사람에게 남기게 된다.</b> 그 사슬은 평일 14:00 자리다.
 *
 * <p><b>그 대가를 알고 쓴다</b>: 여기서 적재한 시세는 <b>미보정</b>이다
 * ({@code DataGoKrPriceAdapter}·{@code AlpacaPriceAdapter} 둘 다 {@code fetchCorporateActions}를
 * 오버라이드하지 않아 원장에 한 건도 안 적힌다). 월 13:30 일일 갱신이 그 미보정 봉으로 지표를
 * 내고, 월 14:00 사슬이 바로잡는다 — <b>그 한 시간은 ADR-0055의 억제도 안 걸린다</b>(원장이
 * 비어 있어 억제할 대상이 없다). 실측으로 최근 여섯 리밸런싱의 신규 편입 84종목 중 2년 안에
 * 기업행위가 있는 것은 <b>5(6.0%)</b>라 {@code AUTO_BACKFILL_STOCK_LIMIT}(10) 안이지만
 * <b>기댓값이지 보장이 아니다.</b>
 *
 * <p><b>적재한 티커를 로그에 남기는 이유</b>가 거기 있다. 이 적재가 굳힌 값이 ADR-0126의
 * 시총 급변 축에 걸리는지는 <b>여기서 못 잰다</b> — {@code MARKET_CAP}은 다음 평일 지표 계산이
 * 만들기 때문이다. 그 펜스가 다음 세션 시작에 신고하고, <b>이 로그가 귀속을 가능하게 한다.</b>
 */
@Component
public class MembershipGapFiller {

    /**
     * 한 판에 자동으로 적재할 종목 수의 상한. 넘으면 <b>돌리지 않고 알린다.</b>
     *
     * <p>실측 근거: 보통 리밸런싱의 신규 편입이 <b>6·13·6·40·10·14</b>종목이다(2026-08-07·
     * 08-21·08-28·09-04·09-07·09-11 — 350·8,504·1,000짜리 세 날은 일회성 부트스트랩이라 뺐다).
     * 최대 40에 여유를 둔 값이고, 그보다 크면 리밸런싱이 아니라 <b>유니버스를 다시 그은 것</b>이라
     * 사람이 봐야 한다.
     *
     * <p><b>MUST: 이 계열을 한 기저로 읽지 않는다 — 앞 셋과 뒤 셋의 모집단이 다르다.</b>
     * {@code US_TOP1000}이 2026-08-31에 켜졌으므로(ADR-0107) <b>앞 셋(6·13·6)은 국내 전용</b>이고
     * <b>뒤 셋(40·10·14)만 국내+미국</b>이다. 그래서 「미국이 켜진 뒤」의 표본은 <b>셋뿐</b>이고
     * 최댓값 40은 그 셋에서 나온 수다. 이 상한을 소요 기반으로 바꿀 때 <b>같은 기저로 다시 잰다.</b>
     *
     * <p><b>MUST: 세는 질의를 {@code origin = 'OBSERVED'}로 좁힌다 — {@code is_active}로는 못 가른다.</b>
     * 안 좁히면 08-21이 13 대신 <b>28</b>로 나오는데, 늘어난 15는 {@code US_TOP1000}의
     * {@code RESTORED} 행이다 — ADR-0135가 <b>2026-09-17 11:31에 사후로 복원한 둘째 평면</b>이지
     * 그날 리밸런싱이 만든 편입이 아니다. 그 유니버스는 <b>지금 활성</b>이므로
     * {@code u.is_active}는 그것을 원리적으로 못 거른다(있으나 없으나 28이다 · 2026-09-18 실측).
     *
     * <p><b>이 값은 예산이지 실측이 아니다</b> — 이 경로의 소요 시간을 재본 적이 없다.
     * {@link InvalidationBackfiller#AUTO_BACKFILL_STOCK_LIMIT}이 같은 문장을 쓴다.
     */
    private static final int AUTO_LOAD_STOCK_LIMIT = 50;

    /**
     * 거버넌스 갈래의 상한. <b>위 상한의 합집합에 넣지 않는다.</b>
     *
     * <p><b>왜 따로인가</b> — 거버넌스 축은 <b>신규 편입 전원</b>을 잡는다({@code governance-load}가
     * 현재 편입만 도니 처음 편입되는 종목은 예외 없이 원문이 0행이다). 기존 세 축은 그중 일부만
     * 잡았으므로, 합집합에 넣으면 그 수의 뜻이 「원자료가 빈 신규」에서 <b>「신규 전원」</b>으로
     * 바뀐다. 리밸런싱 회차별 신규 편입을 다시 재니 <b>40 · 20 · 14 · 13 · 10 · 6</b>(2026-09-20 ·
     * 부트스트랩 제외)이라 여유가 <b>10</b>뿐이고, 넘으면 {@code loadMissingRawData}가 앞에서
     * 멈춰 <b>시세·재무 적재까지 통째로 건너뛴다.</b> 거버넌스 때문에 시세가 안 들어오는 것은
     * 상한이 막으려던 것과 무관하다.
     *
     * <p><b>이 값은 소요로 잡았다</b>(위 상한과 달리 실측이 있다). 리미터가 국내 100ms · 미국
     * 600ms이고 종목당 표가 국내 24 · 미국 4라 <b>양쪽 다 약 2.4초/종목</b>이다 — 200종목이면
     * 약 8분이고, 토요일 20시 뒤에는 밀릴 다른 스케줄이 없다. 유니버스를 다시 그은 경우
     * (2026-08-31 미국 1,000종목)는 그대로 막힌다.
     */
    private static final int AUTO_GOVERNANCE_STOCK_LIMIT = 200;

    /**
     * 시세를 받을 연수.
     *
     * <p><b>MUST NOT: {@code LoadRange.defaults()}의 2년을 쓰지 않는다.</b> 2년은 약 490봉인데
     * 러너의 읽기 창은 {@code maxEffectiveLookback + 255}라, 기본값으로 채우면 <b>창이 긴 지표가
     * 안 나는 종목을 매주 새로 만든다.</b> 실측으로 이미 편입 155종목(11.5%)이 1,441봉 미만이고
     * 46종목이 500봉 미만이다.
     *
     * <p><b>소스가 긴 창을 거절하는 상태가 실재한다</b>({@code [05] SERVICETIMEOUT_ERROR}).
     * 그때는 짧게 들어오고 {@code PRICE_COVERAGE} 축이 <b>못 잡는다</b> — 앵커가 첫 봉이라
     * 충족률이 100%가 된다. 그것을 가르려면 상장일이 필요한데 소스가 없다.
     */
    static final int PRICE_YEARS = 7;

    private static final Logger log = LoggerFactory.getLogger(MembershipGapFiller.class);

    private final StockRegistrar stockRegistrar;
    private final InitialLoadJob initialLoadJob;
    private final CompanyProfileJob companyProfileJob;
    private final FullStatementRefillJob fullStatementRefillJob;
    private final GovernanceLoadJob governanceLoadJob;
    private final GovernanceDeriveJob governanceDeriveJob;
    private final UniverseIntegrityAlerter alerter;
    private final AlertPort alertPort;
    private final BatchExecutionReader batchExecutionReader;

    public MembershipGapFiller(StockRegistrar stockRegistrar, InitialLoadJob initialLoadJob,
                               CompanyProfileJob companyProfileJob,
                               FullStatementRefillJob fullStatementRefillJob,
                               GovernanceLoadJob governanceLoadJob,
                               GovernanceDeriveJob governanceDeriveJob,
                               UniverseIntegrityAlerter alerter, AlertPort alertPort,
                               BatchExecutionReader batchExecutionReader) {
        this.stockRegistrar = stockRegistrar;
        this.initialLoadJob = initialLoadJob;
        this.companyProfileJob = companyProfileJob;
        this.fullStatementRefillJob = fullStatementRefillJob;
        this.governanceLoadJob = governanceLoadJob;
        this.governanceDeriveJob = governanceDeriveJob;
        this.alerter = alerter;
        this.alertPort = alertPort;
        this.batchExecutionReader = batchExecutionReader;
    }

    /**
     * 감지 → 채운다 → 다시 감지 → 못 닫은 것만 알린다.
     *
     * <p><b>MUST: 다시 감지한다.</b> 채운 것을 그대로 「닫혔다」로 보고하면 <b>돌렸는데 소스가
     * 안 준 경우</b>가 성공으로 접힌다 — 이 기제가 없애려는 조용한 0건과 같은 모양이다.
     *
     * <p><b>MUST NOT: 여기서 예외를 위로 올리지 않는다.</b> 호출자는 리밸런싱을 <b>이미 성공으로
     * 끝낸 뒤</b>이고, 여기서 던지면 {@code @Scheduled} 밖으로 나가 Spring 기본 처리가 ERROR로
     * 찍는다 — {@code sentry.logging.minimum-event-level}이 error라 <b>매주 이슈가 하나씩
     * 쌓인다.</b> 그리고 그 로그가 리밸런싱까지 실패한 것처럼 보이게 만든다.
     */
    public void fillAndAlert(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        try {
            fill(asOf, triggeredBy);
            // MUST: 다시 잰 것으로 알린다. 이 두 줄이 try 밖에 있으면 재감지나 통지가 던질 때
            // 예외가 @Scheduled 밖으로 나가고, 이 메서드의 「던지지 않는다」 계약이 조용히 깨진다.
            boolean sent = alerter.alert(stockRegistrar.membershipGaps());
            // MUST: 보냈는지를 찍는다. CompositeAlertPort는 성공 전달에 로그를 한 줄도 안 남긴다
            // (억제·채널 예외·delivered==0 셋에서만 찍는다). 그래서 이 줄이 없으면 「발화했는가」를
            // 사후에 원리적으로 못 가른다 — 침묵이 미발화와 구분되지 않는다. 2026-09-20에 그
            // 침묵을 미발화로 읽고 한 시간을 태웠고, 15:30 갈래는 같은 줄이 있어서 갈렸다
            // (PipelineScheduler.checkJobFreshness).
            log.info("편입 결손을 다시 쟀다. 알림={}", sent ? "보냈다" : "없거나 이미 보냈다");
        } catch (BatchShutdownException e) {
            // 종료 신호다. 실패가 아니므로 알리지 않는다 — 다음 리밸런싱이나 평일 15:30 점검이
            // 같은 결손을 다시 잰다. MUST: 리밸런싱이 중단됐다고 적지 않는다. 그것은 이미 끝났다.
            log.info("편입 결손 보충이 종료로 중단됐다 — 리밸런싱은 이미 끝났다. "
                    + "남은 결손은 평일 15:30 점검이 다시 신고한다.");
            return;
        } catch (RuntimeException e) {
            // MUST NOT: 삼키지 않는다. 채우기가 실패한 것과 채울 것이 없던 것은 다르고,
            // 둘을 구분할 수 있는 자리가 여기뿐이다.
            log.error("편입 결손 보충·통지가 실패했다. 리밸런싱은 정상이며 되돌리지 않는다.", e);
            alertPort.send(AlertEvent.toOperator(AlertSeverity.ERROR, "편입 결손 보충 실패",
                    "유니버스 리밸런싱은 정상이다. 새 편입 종목의 원자료를 채우지 못했고 "
                            + "그 종목은 지표가 판정불가인 채로 남는다. 평일 15:30 점검이 "
                            + "무엇이 비었는지 다시 신고한다: " + e,
                    "universe-integrity-fill"));
        }
    }

    private void fill(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        if (!blockedByAnotherBatch()) {
            loadMissingRawData(stockRegistrar.membershipGaps(), asOf);
        }

        // MUST: 티커를 주지 않고, 결손이 0이어도 매주 부른다. 그리고 위가 상한을 넘겨
        // 아무것도 안 적재한 주에도 부른다 — 그래서 이 호출이 위 메서드 밖에 있다.
        //
        // ① 티커를 주면 CompanyProfileJob이 업종 유무를 아예 안 본다 — 선택 로직 사본이 생긴다.
        //    인자 없는 기본 대상이 곧 업종 결손 집합이다.
        // ② JobFreshnessPolicy가 company-profile에 12일 예산을 주며 "리밸런싱 뒤에 붙는다"고
        //    적어 뒀는데 붙이는 코드가 없었다. 그리고 그 정책은 SCHEDULER 행이 하나라도 생기면
        //    아무 트리거나 세던 폴백을 끄므로, **결손이 있는 주에만 부르면** 13일 뒤
        //    "고칠 것이 없는데 붉는" 연체 알림이 된다. 매주면 7일 간격이라 예산 안이다.
        //    적재 상한 초과도 그 "안 부르는 주"를 만든다 — 상한은 적재를 막는 것이지
        //    업종 수집을 막는 것이 아니고, 대가는 종목당 1콜이다.
        //
        // 대가는 원리적으로 안 채워지는 면제 종목에 대한 주당 몇 콜뿐이다(종목당 1콜).
        companyProfileJob.run(asOf, triggeredBy, List.of(), null);
    }

    /**
     * 지금 다른 배치가 돌고 있으면 <b>적재만</b> 건너뛴다.
     *
     * <h2>MUST NOT: 업종 수집까지 막지 않는다</h2>
     * <p>막는 이유가 「같은 {@code price_daily}에 동시에 쓴다」인데 {@code company-profile}은
     * 거기 안 쓴다(종목당 1콜 · {@code stock}의 업종 두 컬럼). 그리고 그것을 함께 막으면
     * <b>「안 부르는 주」가 또 하나 생겨</b> {@code JobFreshnessPolicy}가 토요일 전용 시계로
     * 갈아탄 뒤 가짜 연체를 낸다 — 적재 상한이 만들던 같은 결함을 다른 문으로 다시 여는 것이다.
     *
     * <h2>겹침을 막아야 하는 이유</h2>
     *
     * <h2>왜 필요한가</h2>
     * <p>{@code application.yaml}의 {@code spring.task.scheduling.pool.size: 1}이
     * <b>배치끼리 안 겹친다는 유일한 근거</b>라고 스스로 적어 뒀는데, 그 보장은
     * {@code @Scheduled}끼리만이다. 이 꼬리가 {@code UniverseRebalanceJob} 안으로 들어오면서
     * <b>관리자 화면</b>({@code applicationTaskExecutor} — 다른 풀)과 <b>CLI</b>
     * ({@code BootstrapRunner} — 별도 JVM)도 꼬리를 끌게 됐고, 그 둘은 그 풀을 안 지난다.
     * 리밸런싱 15회 중 손·관리자 실행이 11회이고 그중 평일이 여럿이다 — 지금까지 안 겹친
     * 이유는 리밸런싱이 최장 26초였기 때문이지 막는 것이 있어서가 아니다.
     *
     * <p>겹치면 7년 시세 적재가 13:30 일일 갱신과 <b>같은 {@code price_daily}의 같은
     * (종목, 날짜)에 동시에 쓴다.</b>
     *
     * <h2>MUST: 유령이 아니라 살아 있는 진척을 본다</h2>
     * <p>{@code status = 'RUNNING'}만 보면 죽은 CLI가 남긴 {@code MANUAL} 행 하나가 이 갈래를
     * <b>영구히</b> 끈다 — 웹을 재기동해도 그 행은 안 닫힌다({@link BatchExecutionSweeper}가
     * 기동 때 {@code SCHEDULER}·{@code ADMIN}만 본다). 그래서
     * {@link BatchExecutionReader#liveJobNames()}가 <b>진척이 살아 있는 것만</b> 돌려준다.
     *
     * <h2>MUST: 조용히 멈추지 않는다</h2>
     * <p>막힌 것과 채울 것이 없던 것은 화면에서 구분되지 않는다. 로그만 남기면 이 갈래가
     * 안 돈 채로 초록이 된다.
     */
    private boolean blockedByAnotherBatch() {
        List<String> running = batchExecutionReader.liveJobNames();
        if (running.isEmpty()) {
            return false;
        }
        log.warn("다른 배치가 도는 중이라 편입 원자료 적재를 건너뛴다. 도는 잡={}", running);
        alertPort.send(AlertEvent.toOperator(AlertSeverity.WARNING, "편입 원자료 적재를 건너뛰었다",
                ("다른 배치가 도는 중이라 새 편입 종목의 원자료를 안 채웠다(도는 잡=%s). "
                        + "같은 price_daily에 동시에 쓰는 것을 피한 것이다. 업종 수집은 그대로 돌았다. "
                        + "남은 결손은 평일 15:30 점검이 다시 신고하고, 그 전에 채우려면 그 배치가 "
                        + "끝난 뒤 리밸런싱을 다시 돌린다.")
                        .formatted(running),
                "universe-integrity-busy"));
        return true;
    }

    /** 원자료가 빈 편입 종목을 축별로 갈라 적재한다. <b>상한을 넘으면 적재만 건너뛴다.</b> */
    /**
     * 결손 원자료를 받는다. <b>전부 티커로 좁힌 실행이다.</b>
     *
     * <p><b>MUST: 물려받은 트리거를 쓰지 않고 {@link BatchExecutionRecorder.TriggeredBy#GAP_FILL}로
     * 기록한다.</b> {@link JobFreshnessPolicy}가 「스케줄러 실행은 언제나 전량이다」라는 불변식에
     * 기대고 있는데, 리밸런싱이 SCHEDULER로 돌면 꼬리도 그것을 물려받아 <b>좁은 실행이 전량 잡의
     * 시계를 되돌린다.</b> 여기서 갈라 두지 않으면 증상이 「연체 경보가 안 뜨는 것」이라 어디에도 안 붉는다.
     *
     * <p>{@code companyProfileJob}은 이 메서드 밖에 있고 <b>물려받은 트리거를 그대로 쓴다</b> —
     * 꼬리에서도 전량을 돌기 때문이다(전량이 「업종이 빈 종목」이라 실측 4). 가르는 축은
     * <b>「좁혔는가」이지 「꼬리인가」가 아니다.</b>
     */
    private void loadMissingRawData(List<MembershipGap> gaps, LocalDate asOf) {
        BatchExecutionRecorder.TriggeredBy triggeredBy = BatchExecutionRecorder.TriggeredBy.GAP_FILL;
        List<String> priceTickers = tickersOf(gaps, MembershipGap.Axis.PRICE_HISTORY);
        List<String> financialTickers = tickersOf(gaps, MembershipGap.Axis.FINANCIAL);
        List<String> cashFlowTickers = gaps.stream()
                .filter(gap -> gap.missing().contains(MembershipGap.Axis.CASH_FLOW))
                .filter(MembershipGap::refillableStatements)
                .map(MembershipGap::ticker)
                .toList();

        Set<String> distinct = new LinkedHashSet<>(priceTickers);
        distinct.addAll(financialTickers);
        distinct.addAll(cashFlowTickers);
        if (distinct.size() > AUTO_LOAD_STOCK_LIMIT) {
            // MUST: 알리고 멈춘다. 자동으로 시간 단위 작업을 시작하면 그날의 다른 스케줄이
            // 전부 밀리고, 이 크기는 리밸런싱이 아니라 유니버스를 다시 그은 것이다.
            log.warn("편입 결손이 {}종목으로 자동 적재 상한({})을 넘는다. 돌리지 않는다. 종목={}",
                    distinct.size(), AUTO_LOAD_STOCK_LIMIT, distinct);
            alertPort.send(AlertEvent.toOperator(AlertSeverity.WARNING, "편입 결손이 상한을 넘는다",
                    ("원자료가 빈 편입 종목이 %d개로 자동 적재 상한 %d를 넘어 돌리지 않았다. "
                            + "이 크기는 정기 리밸런싱이 아니라 유니버스를 다시 그은 것이다 — "
                            + "손으로 확인하고 적재한다. 종목=%s")
                            .formatted(distinct.size(), AUTO_LOAD_STOCK_LIMIT, distinct),
                    "universe-integrity-limit"));
            // 적재만 건너뛴다. 호출자가 이어서 부르는 company-profile은 이 상한과 무관하다.
            return;
        }

        if (!priceTickers.isEmpty()) {
            log.info("편입 결손 보충 — 시세 {}종목을 {}년으로 받는다. 종목={}",
                    priceTickers.size(), PRICE_YEARS, priceTickers);
            initialLoadJob.run(asOf, triggeredBy, new InitialLoadJob.LoadRange(
                    PRICE_YEARS, 0, false, priceTickers, null, true, false));
        }
        if (!financialTickers.isEmpty()) {
            log.info("편입 결손 보충 — 재무 {}종목을 받는다. 종목={}",
                    financialTickers.size(), financialTickers);
            initialLoadJob.run(asOf, triggeredBy, new InitialLoadJob.LoadRange(
                    0, InitialLoadJob.LoadRange.defaults().financialYears(), false,
                    financialTickers, null, false, true));
        }
        if (!cashFlowTickers.isEmpty()) {
            log.info("편입 결손 보충 — 전체 재무제표 {}종목을 받는다. 종목={}",
                    cashFlowTickers.size(), cashFlowTickers);
            // MUST: 분기를 함께 받는다. FY만 채우면 Q4 유도가 안 따라가 재고자산이 그 자리에서
            // 새 결손이 된다(ADR-0069) — 채우는 잡이 다음 결손을 만드는 자리다. 이 갈래는
            // 티커로 좁혀져 있으므로 콜 4배가 전 종목이 아니라 그 몇 종목에만 붙는다.
            //
            // MUST NOT: 여기에 백필을 잇지 않는다. 이 잡은 indicator_value를 건드리지 않고,
            // 실측(2026-09-20 001820)으로 정정 0·교정 0이라 지워지는 지표가 없다. 채운 값은
            // 다음 평일 13:30 일일 갱신이 반영한다 — IndicatorRunner가 retain(points, cutoff)로
            // 보존 창 전체를 다시 쓰기 때문이다. 백필을 붙이면 그 일을 두 번 한다.
            fullStatementRefillJob.run(asOf, triggeredBy, null, cashFlowTickers, true, false);
        }

        // 거버넌스는 **위 합집합 밖**이다 — 이 축이 신규 편입 전원을 잡아 그 수의 뜻을 바꾸고,
        // 합집합에 넣으면 거버넌스 때문에 시세 적재가 멈춘다(AUTO_GOVERNANCE_STOCK_LIMIT javadoc).
        List<String> governanceTickers = tickersOf(gaps, MembershipGap.Axis.GOVERNANCE);
        if (governanceTickers.isEmpty()) {
            // MUST: 빈 목록이면 부르지 않는다. GovernanceLoadJob.resolveTargets의 else가
            // **활성 편입 전량**이라, 여기서 그냥 부르면 결손 0인 주마다 토요일 20시에
            // 12,050콜짜리 전량 적재가 돈다 — 그리고 GAP_FILL이라 아무 데도 안 뜬다.
            return;
        }
        if (governanceTickers.size() > AUTO_GOVERNANCE_STOCK_LIMIT) {
            log.warn("거버넌스 결손이 {}종목으로 상한({})을 넘는다. 돌리지 않는다. 종목={}",
                    governanceTickers.size(), AUTO_GOVERNANCE_STOCK_LIMIT, governanceTickers);
            alertPort.send(AlertEvent.toOperator(AlertSeverity.WARNING,
                    "거버넌스 결손이 상한을 넘는다",
                    ("거버넌스 원문이 빈 편입 종목이 %d개로 상한 %d를 넘어 돌리지 않았다. "
                            + "이 크기는 정기 리밸런싱이 아니라 유니버스를 다시 그은 것이다 — "
                            + "손으로 확인하고 적재한다. 종목=%s")
                            .formatted(governanceTickers.size(), AUTO_GOVERNANCE_STOCK_LIMIT,
                                    governanceTickers),
                    "universe-integrity-governance-limit"));
            return;
        }
        log.info("편입 결손 보충 — 거버넌스 {}종목을 받는다. 종목={}",
                governanceTickers.size(), governanceTickers);
        // MUST: span을 주지 않는다(넷째 인자). 표마다 필요한 해 수가 다른데 스칼라 하나로 덮으면
        // 나머지 표가 조용히 좁아진다 — PipelineScheduler의 거버넌스 갈래와 같은 근거다.
        governanceLoadJob.run(asOf, triggeredBy, null, null, List.of(), governanceTickers);
        // MUST: 유도를 이어 부른다. 적재만 하면 governance_fact가 안 바뀌는데 둘 다 성공으로 끝난다.
        //
        // MUST NOT: 여기에 백필을 잇지 않는다. 신규 편입은 다음 평일 13:30 일일 갱신의 대상이고
        // (activeUniverseMemberIds), IndicatorRunner가 retain(points, cutoff)로 보존 창 전체를
        // 다시 쓰므로 저절로 반영된다 — CASH_FLOW 갈래와 같은 근거다.
        governanceDeriveJob.run(asOf, triggeredBy, governanceTickers);
    }

    private static List<String> tickersOf(List<MembershipGap> gaps, MembershipGap.Axis axis) {
        return gaps.stream()
                .filter(gap -> gap.missing().contains(axis))
                .map(MembershipGap::ticker)
                .toList();
    }
}
