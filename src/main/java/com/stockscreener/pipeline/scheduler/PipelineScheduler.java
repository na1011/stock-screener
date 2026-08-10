package com.stockscreener.pipeline.scheduler;

import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
import com.stockscreener.pipeline.batch.DailyUpdateJob;
import com.stockscreener.pipeline.batch.UniverseRebalanceJob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
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
     * 일일 갱신. 평일 15시(KST)에 <b>직전 평일</b>의 종가를 받는다.
     *
     * <p>시각과 대상 일자가 함께 정해진다. 소스가 거래일 T의 종가를 <b>T의 다음 영업일
     * 13시 이후</b>에 내기 때문이다(ADR-0042). 그러므로 시각은 13시보다 뒤여야 하고, 대상은
     * 실행일보다 앞이어야 한다. 15시는 그 13시에 두 시간의 여유를 둔 값이다 — 안내가
     * "13시 <b>이후</b>"로 하한만 보증하므로 붙여 두지 않는다.
     *
     * <p>IMPORTANT — 15시는 <b>국내 장 마감(15:30) 전</b>이다. 그래도 옳다: <b>당일 종가를
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
    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Seoul")
    public void runDailyUpdate() {
        LocalDate targetDate = targetDateFor(LocalDate.now(KR_ZONE));
        log.info("일일 갱신을 시작한다. targetDate={}", targetDate);
        dailyUpdateJob.run(targetDate, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
    }

    /**
     * 실행 일자로부터 수집 대상일을 정한다 — <b>직전 평일</b>이다.
     *
     * <p>메서드로 빼 둔 이유는 <b>크론과 짝을 이뤄야만 의미가 있기 때문</b>이다. 어느 거래일이
     * 덮이는가는 크론만으로도, 이 규칙만으로도 알 수 없고 둘을 합쳐야 나온다. 실제로 금요일
     * 종가가 새던 것이 그 조합에서 생긴 구멍이었다.
     *
     * <p>MUST NOT: 이것을 "직전 <b>영업일</b>"로 부르지 않는다. 공휴일을 모르므로 낼 수 있는
     * 값은 직전 <i>평일</i>뿐이고, 그 차이가 결손을 낳는다 — 월(거래)·화(공휴일)·수(거래)일 때
     * 월요일치는 <b>수요일</b> 13시에 공개되는데, 화요일 실행은 월요일(미공개)을 수요일 실행은
     * 화요일(휴장)을 노려 <b>월요일을 대상으로 삼는 실행이 어느 요일에도 없다.</b>
     *
     * <p>그 결손을 여기서 고치지 않는다. 고치려면 공휴일 달력이 필요한데 그것을 두지 않기로
     * 했고({@link com.stockscreener.pipeline.collector.MarketCalendar}), 대신
     * {@link DailyUpdateJob#PRICE_LOOKBACK_DAYS} 창이 흡수한다(ADR-0042). <b>셋을 합쳐야
     * 모든 거래일이 덮인다</b> — {@code PipelineSchedulerTest}가 셋을 합쳐 검사한다.
     */
    static LocalDate targetDateFor(LocalDate runDate) {
        LocalDate date = runDate.minusDays(1);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.minusDays(1);
        }
        return date;
    }

    /**
     * 유니버스 리밸런싱. 매주 토요일 저녁 8시(KST).
     *
     * <p>정기변경은 연 2회지만 수시변경이 있어 주 1회 확인한다. 스냅샷이 같으면 아무것도
     * 하지 않으므로(멱등) 자주 도는 비용이 낮고, 반대로 놓치면 없는 종목을 계속 수집한다.
     *
     * <p>새벽 3시가 아닌 이유는 일일 갱신과 같다 — 맥북이 자고 있으면 실행 자체가 없다.
     * 일일 갱신을 15시로 옮긴 뒤로 시각이 갈렸지만 토요일에는 일일 갱신이 돌지 않으므로
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
        LocalDate asOf = targetDateFor(LocalDate.now(KR_ZONE));
        log.info("유니버스 리밸런싱을 시작한다. asOf={}", asOf);
        universeRebalanceJob.run(asOf, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
    }
}
