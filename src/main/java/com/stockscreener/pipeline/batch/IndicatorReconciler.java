package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorCalendar;
import com.stockscreener.pipeline.datasource.CollectedMarkets;
import com.stockscreener.pipeline.collector.ShareBasisDeriver;
import com.stockscreener.pipeline.collector.ShareClassDeriver;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncomeDeriver;
import com.stockscreener.pipeline.datasource.us.UsMarket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 저장된 지표를 <b>지금 원자료·지금 코드</b>에 맞춘다 — 매 평일 화해. 외부 호출이 0이다.
 *
 * <h2>왜 무효화마다 백필하던 것을 대체하는가</h2>
 * <p>옛 모양은 지우는 잡마다 <b>지운 종목만</b> 다시 채우고 상한(기업 행위 10 · 재무 100)을 넘으면
 * 지운 채 두었다. 미국 공시 시즌의 정정 무효화는 28일 창 49개 중 11개에서 100을 넘었다(최대 185 ·
 * 2026-10-04 스윕 근사). 그리고 지운 종목만 채우는 것으로는 <b>그 종목이 끼던 다른 종목의 과거
 * 순위</b>가 안 닫힌다 — 13:30 일일 갱신이 파생층을 최신일 하루만 내기 때문이다.
 *
 * <p>그래서 무엇이 지웠는지를 묻지 않고 <b>매 평일 한 번 전부</b>를 맞춘다. ① 보존 창 편입 전원의
 * 원본층을 한 판 다시 쓰고, ② 창 전체의 파생층을 다시 낸다. 값이 같으면 쓰는 행이 0이라(죽은 튜플이
 * 안 는다) 정상 상태의 비용은 시간과 행 잠금의 WAL이다.
 *
 * <h2>MUST: 이 화해는 「데이터 변경을 반영하는 유지」다 — 계산기 변경을 퍼뜨리는 통로가 아니다</h2>
 * <p>설정 뒤에 없는 계산기 변경이 main에 들어가면 <b>그다음 화해가 곧 전 종목 반영</b>이다. 값을 바꾸는
 * 계산기 코드를 기본값 꺼짐 설정 뒤에 둔다는 저장소 규칙({@code CLAUDE.md} 「재적재·백필」)이
 * 이 화해의 전제다.
 */
@Component
public class IndicatorReconciler {

    private static final Logger log = LoggerFactory.getLogger(IndicatorReconciler.class);

    private final IndicatorBackfillJob indicatorBackfillJob;
    private final ShareBasisDeriver shareBasisDeriver;
    private final GovernanceDeriveJob governanceDeriveJob;
    private final IndicatorCalendar indicatorCalendar;
    private final ShareClassDeriver shareClassDeriver;
    /** 미국 관문 설정 — 켜졌을 때만 클래스 축 판정을 기저 판정 앞에 돈다. */
    private final boolean usGate;
    private final KrCommonIncomeDeriver krCommonIncomeDeriver;
    /** 국내 관문 설정 — 켜졌을 때만 국내 재무 행에 보통주 몫을 붙인다(ADR-0192). */
    private final boolean krGate;

    @Autowired
    public IndicatorReconciler(IndicatorBackfillJob indicatorBackfillJob,
                               ShareBasisDeriver shareBasisDeriver,
                               GovernanceDeriveJob governanceDeriveJob,
                               IndicatorCalendar indicatorCalendar,
                               ShareClassDeriver shareClassDeriver,
                               @Value("${stockscreener.gate.us}") boolean usGate,
                               KrCommonIncomeDeriver krCommonIncomeDeriver,
                               @Value("${stockscreener.gate.kr}") boolean krGate) {
        this.indicatorBackfillJob = indicatorBackfillJob;
        this.shareBasisDeriver = shareBasisDeriver;
        this.governanceDeriveJob = governanceDeriveJob;
        this.indicatorCalendar = indicatorCalendar;
        this.shareClassDeriver = shareClassDeriver;
        this.usGate = usGate;
        this.krCommonIncomeDeriver = krCommonIncomeDeriver;
        this.krGate = krGate;
    }

    /** 국내 관문 밖 시험용 — 국내 유도 없이. */
    public IndicatorReconciler(IndicatorBackfillJob indicatorBackfillJob, ShareBasisDeriver shareBasisDeriver,
                               GovernanceDeriveJob governanceDeriveJob, IndicatorCalendar indicatorCalendar,
                               ShareClassDeriver shareClassDeriver, boolean usGate) {
        this(indicatorBackfillJob, shareBasisDeriver, governanceDeriveJob, indicatorCalendar, shareClassDeriver, usGate, null, false);
    }

    /**
     * 화해의 기준일 — 13:30 일일 갱신의 대상일이되 <b>지표 달력의 최신일을 넘지 않는다.</b>
     *
     * <p>대상일과 같아야 하는 이유는 {@link IndicatorBackfillJob#rewriteOriginLayer}에 있다(asOf의 주식수를
     * 따라가는 지표가 두 판 사이를 오간다). <b>넘지 않아야 하는 이유</b>는 거래일 색인이 지표 행이 하나라도
     * 있는 날짜를 화면 기준일로 열기 때문이다({@code IndicatorTradeDateIndex}) — 13:30이 그날 안 돌았으면
     * (앱이 13:30 뒤에 떴거나, 주말·월요일 오전의 손 실행) 대상일 봉은 토요일 꼬리가 넣은 몇 종목에만 있고,
     * 화해가 그 날짜를 쓰면 <b>화면 최신일이 몇 종목짜리 날</b>이 된다. 실측: 2026-10-05 00시에 10-02 봉이
     * IOVA·KOD 둘뿐이었다. 정상인 날은 13:30이 달력을 대상일까지 늘려 둘이 같다.
     */
    public LocalDate dataAsOf(LocalDate today) {
        return dataAsOfFor(DailyUpdateJob.targetDateFor(today), CollectedMarkets.ALL);
    }

    /**
     * 위와 같은 규칙을 대상일과 시장을 받아 쓴다 — 미국 저녁 사슬의 좁힌 화해가 미국 일일 갱신의 대상 세션으로 부른다(그 사슬의
     * 대상일은 국내 「직전 평일」이 아니다 · {@link UsSessionTarget}).
     */
    public LocalDate dataAsOfFor(LocalDate target, java.util.Collection<String> markets) {
        return indicatorCalendar.latestIndicatorDate(markets)
                .filter(latest -> latest.isBefore(target))
                .orElse(target);
    }

    /**
     * 화해가 읽는 입력 둘을 다시 판정·유도한다 — 주식수 기저(미국, ADR-0162) → 거버넌스 사실(보존 창 편입).
     * 미국 관문이 켜졌으면 그 앞에 주식수 클래스 축 판정을 돈다 — 기저 판정이 판독기가 읽는 계열(N_L)로 배수를 재고,
     * 거버넌스 유도의 배당 총액도 그 주식수를 읽는다.
     *
     * <p>둘 다 저장된 원자료만 읽는다. 재무 판이나 기업 행위 원장이 바뀐 날 판정이 안 따라가면 새 판은
     * {@code NULL}로 남고 {@code NULL}은 OK로 읽혀 <b>판정 대기 종목이 조용히 값을 되찾는다.</b>
     */
    public void deriveInputs(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy) {
        deriveShareBases();
        governanceDeriveJob.run(asOf, triggeredBy, List.of());
    }

    /**
     * 미국 주식수의 두 판정 — 관문이 켜졌으면 공시마다 예탁 비율 k를 먼저 정하고(클래스 축이 ADS 원장 계수를 보통주 계수로 바꿀 때
     * 그 k를 쓴다 · ADR-0183), 클래스 축 판정, 그다음 기저 판정(그 계열로 배수를 잰다). 재무·기업행위를 쓴 손 잡 뒤에도 이것을 부른다
     * ({@code BootstrapRunner}) — 순서가 한 자리에만 있게.
     */
    public void deriveShareBases() {
        if (usGate) {
            shareBasisDeriver.decideDepositaryRatios(UsMarket.CODE);
            shareClassDeriver.derive(UsMarket.CODE);
        }
        shareBasisDeriver.derive(UsMarket.CODE);
        if (krGate) {
            // 국내 보통주 몫(ADR-0192) — 재료 표 · 재무 행 · 기업행위 원장만 읽는다(외부 0콜). 거버넌스 유도 앞이라 그날의 판이 그날 붙는다.
            krCommonIncomeDeriver.derive();
        }
    }

    /**
     * ① 원본층 → ② 파생층.
     *
     * <p><b>②를 안 도는 것은 ①이 던지거나 {@code FAILED}일 때뿐이다.</b> 종목 몇의 실패({@code PARTIAL_SUCCESS})는
     * 종목별로 격리돼 있어(ADR-0007) 나머지의 원본이 이미 지금 값이고, ②는 그 위에서 순위를 다시 매긴다 —
     * 막으면 실패 하나가 전 종목의 순위를 하루 낡게 둔다. {@code FAILED}는 잡 전체가 멈춘 것이라 그날은
     * 다음 평일 화해에 맡기고 크게 남긴다.
     *
     * @param asOf    13:30 일일 갱신의 대상일과 같아야 한다({@link IndicatorBackfillJob#rewriteOriginLayer})
     * @param tickers 비면 보존 창 편입 전원. 주면 ①만 그 티커로 좁고 ②는 언제나 창 전체다
     */
    public BatchStatus reconcile(LocalDate asOf, List<String> tickers,
                                 BatchExecutionRecorder.TriggeredBy triggeredBy) {
        BatchStatus origin = indicatorBackfillJob.rewriteOriginLayer(
                asOf, originTrigger(triggeredBy, tickers), tickers);
        if (origin == BatchStatus.FAILED) {
            log.error("원본층 다시 쓰기가 실패해 파생층을 안 돈다. asOf={} — 다음 평일 화해가 다시 시도한다."
                    + " 그때까지 바뀐 원본의 과거 순위가 낡은 채 남는다.", asOf);
            return origin;
        }
        return indicatorBackfillJob.rebuildDerived(asOf, triggeredBy);
    }

    /**
     * 좁힌 ① 의 트리거. <b>스케줄러가 티커로 좁혀 부르면 {@code GAP_FILL}이다</b>
     * ({@link BatchExecutionRecorder.TriggeredBy#GAP_FILL} — 가르는 축은 「좁혔는가」다).
     *
     * <p>{@link JobFreshnessPolicy}의 판정이 「스케줄러 실행은 언제나 전량」에 기댄다. 14:00 사슬이
     * 무효화 종목만 다시 쓴 ① 을 {@code SCHEDULER}로 남기면 14:20 전량 ① 이 며칠 실패해도 사슬이
     * 돈 날마다 시계가 되돌아가 연체가 안 뜬다. ② 는 언제나 창 전체라 받은 트리거를 그대로 쓴다.
     */
    private static BatchExecutionRecorder.TriggeredBy originTrigger(
            BatchExecutionRecorder.TriggeredBy triggeredBy, List<String> tickers) {
        boolean narrowed = tickers != null && !tickers.isEmpty();
        return narrowed && triggeredBy == BatchExecutionRecorder.TriggeredBy.SCHEDULER
                ? BatchExecutionRecorder.TriggeredBy.GAP_FILL
                : triggeredBy;
    }
}
