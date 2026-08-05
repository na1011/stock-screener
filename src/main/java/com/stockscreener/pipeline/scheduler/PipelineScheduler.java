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
     * 일일 갱신. 평일 새벽 4시(KST).
     *
     * <p>이 시각인 이유는 미국 장 마감(한국 시각 오전 5~6시, 서머타임에 따라 변동) <b>이전</b>이
     * 아니라 국내 장 마감 이후여야 하기 때문이다. 대상 일자를 전일로 두면 두 시장 모두
     * 마감된 상태가 보장된다.
     *
     * <p>MUST NOT: 여기서 초기 적재 여부를 분기하지 않는다.
     */
    @Scheduled(cron = "0 0 4 * * MON-FRI", zone = "Asia/Seoul")
    public void runDailyUpdate() {
        LocalDate targetDate = LocalDate.now(KR_ZONE).minusDays(1);
        log.info("일일 갱신을 시작한다. targetDate={}", targetDate);
        dailyUpdateJob.run(targetDate, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
    }

    /**
     * 유니버스 리밸런싱. 매주 토요일 새벽 3시(KST).
     *
     * <p>정기변경은 연 2회지만 수시변경이 있어 주 1회 확인한다. 스냅샷이 같으면 아무것도
     * 하지 않으므로(멱등) 자주 도는 비용이 낮고, 반대로 놓치면 없는 종목을 계속 수집한다.
     */
    @Scheduled(cron = "0 0 3 * * SAT", zone = "Asia/Seoul")
    public void runUniverseRebalance() {
        LocalDate asOf = LocalDate.now(KR_ZONE);
        log.info("유니버스 리밸런싱을 시작한다. asOf={}", asOf);
        universeRebalanceJob.run(asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
    }
}
