package com.stockscreener.pipeline.scheduler;

import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
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
     * 일일 갱신. 평일 저녁 8시(KST)에 <b>그날</b>의 종가를 받는다.
     *
     * <p>시각과 대상 일자가 함께 정해진다. 새벽 4시에 전일을 받던 구성은 두 가지가 틀렸다.
     *
     * <p><b>첫째, 금요일 종가가 한 번도 수집되지 않았다.</b> 월요일 새벽 실행의 대상이
     * 일요일이라 {@code isWeekend}로 걸러지고, 토요일에는 실행이 없다. 그래서 금요일 종가를
     * 받는 실행이 어느 요일에도 존재하지 않았다. 저녁으로 옮기고 대상을 당일로 바꾸면
     * 월~금 다섯 거래일이 각자의 실행을 갖는다.
     *
     * <p><b>둘째, 이 앱은 맥북 위에서 돈다.</b> 새벽 4시는 맥북이 자고 있을 가능성이 가장 큰
     * 시각이고, 잠든 사이의 스케줄은 그냥 지나간다. 사람이 기기를 쓰고 있을 시각에 도는 것이
     * 온프레미스의 실제 가용성과 맞는다(spec §6).
     *
     * <p>IMPORTANT — 20시는 <b>국내 장 마감(15:30) 이후</b>이므로 당일 종가가 확정된 시각이다.
     * 다만 data.go.kr이 그 시각에 당일치를 이미 내주는지는 실측하지 않았다. 아직이면 수집이
     * 0건으로 끝나는데, {@link DailyUpdateJob}이 그 경우를 <b>알린다</b> — 소리 없이 지나가면
     * "배치는 성공했는데 데이터가 안 늘어난" 상태를 며칠 뒤에야 알게 된다. 알림이 매일 오면
     * 이 시각을 늦추면 된다.
     *
     * <p>미국 시장(v1.1, ADR-0010)은 이 시각을 쓸 수 없다 — 미국 장은 한국 시각 다음 날
     * 오전 5~6시에 닫는다. 붙일 때 별도 스케줄이 필요하다.
     *
     * <p>MUST NOT: 여기서 초기 적재 여부를 분기하지 않는다.
     */
    @Scheduled(cron = "0 0 20 * * MON-FRI", zone = "Asia/Seoul")
    public void runDailyUpdate() {
        LocalDate targetDate = targetDateFor(LocalDate.now(KR_ZONE));
        log.info("일일 갱신을 시작한다. targetDate={}", targetDate);
        dailyUpdateJob.run(targetDate, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
    }

    /**
     * 실행 일자로부터 수집 대상 거래일을 정한다.
     *
     * <p>한 줄짜리를 메서드로 빼 둔 이유는 <b>크론과 짝을 이뤄야만 의미가 있기 때문</b>이다.
     * 어느 거래일이 덮이는가는 크론만으로도, 이 규칙만으로도 알 수 없고 둘을 합쳐야 나온다.
     * 실제로 금요일 종가가 새던 것이 그 조합에서 생긴 구멍이었다.
     * {@code PipelineSchedulerTest}가 둘을 합쳐 검사한다.
     */
    static LocalDate targetDateFor(LocalDate runDate) {
        return runDate;
    }

    /**
     * 유니버스 리밸런싱. 매주 토요일 저녁 8시(KST).
     *
     * <p>정기변경은 연 2회지만 수시변경이 있어 주 1회 확인한다. 스냅샷이 같으면 아무것도
     * 하지 않으므로(멱등) 자주 도는 비용이 낮고, 반대로 놓치면 없는 종목을 계속 수집한다.
     *
     * <p>새벽 3시가 아닌 이유는 일일 갱신과 같다 — 맥북이 자고 있으면 실행 자체가 없다.
     * 일일 갱신과 같은 시각인데, 토요일에는 일일 갱신이 돌지 않으므로 겹치지 않는다.
     */
    @Scheduled(cron = "0 0 20 * * SAT", zone = "Asia/Seoul")
    public void runUniverseRebalance() {
        LocalDate asOf = LocalDate.now(KR_ZONE);
        log.info("유니버스 리밸런싱을 시작한다. asOf={}", asOf);
        universeRebalanceJob.run(asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
    }
}
