package com.stockscreener.pipeline.scheduler;

import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
import com.stockscreener.pipeline.batch.BatchShutdownException;
import com.stockscreener.pipeline.batch.DailyUpdateJob;
import com.stockscreener.pipeline.batch.UniverseRebalanceJob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 배치를 정해진 시각에 띄운다.
 *
 * <p>초기 적재는 여기 없다. 1회성 수동 작업이며, 스케줄에 걸면 애플리케이션 재시작마다
 * 850종목 수집이 시작된다.
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

    private final DailyUpdateJob dailyUpdateJob;
    private final UniverseRebalanceJob universeRebalanceJob;

    public PipelineScheduler(DailyUpdateJob dailyUpdateJob,
                             UniverseRebalanceJob universeRebalanceJob) {
        this.dailyUpdateJob = dailyUpdateJob;
        this.universeRebalanceJob = universeRebalanceJob;
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
     * <p><b>이 앱은 맥북 위에서 돈다.</b> 잠든 사이의 스케줄은 그냥 지나가므로 실행이 통째로
     * 빠지는 날이 생긴다(spec §6). 그것을 시각 선택으로 막지 않고 {@link DailyUpdateJob}의
     * 조회 창이 흡수한다 — 놓친 날짜를 이후 실행이 계속 다시 시도한다.
     *
     * <p>미국 시장(v1.1, ADR-0010)은 이 시각을 쓸 수 없다 — 미국 장은 한국 시각 다음 날
     * 오전 5~6시에 닫는다. 붙일 때 별도 스케줄이 필요하다.
     *
     * <p>MUST NOT: 여기서 초기 적재 여부를 분기하지 않는다.
     */
    @Scheduled(cron = "0 30 13 * * MON-FRI", zone = "Asia/Seoul")
    public void runDailyUpdate() {
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
    }
}
