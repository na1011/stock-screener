package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.batch.BatchExecutionRecorder.TriggeredBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 마감되지 못한 채 남은 {@code RUNNING} 행을 정리한다.
 *
 * <p>마감은 {@link BatchExecutionContext}가 살아 있는 JVM 안에서만 한다. 정상 종료는
 * {@link BatchExecutions}의 종료 훅이 즉시 닫으므로, 여기 남는 것은 <b>SIGKILL·전원
 * 차단·크래시</b>처럼 그 훅조차 돌지 못한 경우다. 실제로 그렇게 10건이 쌓였다.
 *
 * <p><b>시간 임계값을 쓰지 않는다.</b> "N시간 넘게 RUNNING이면 죽은 것"은 틀린 판정이다 —
 * {@code BootstrapRunner}가 문서에 적어 둔 {@code --from=2020-01-02} 백필은 1,619거래일이라
 * 실측 환산 12시간이 넘고, {@code --from} 기본값(2년)도 3시간을 넘는다. 임계값을 넘긴
 * <b>살아 있는</b> 잡을 "프로세스가 사라졌다"고 마감하면 이 클래스가 없애려던 모순을
 * 그대로 다시 만든다.
 *
 * <p>대신 <b>소유자와 기동 시각</b>으로 판정한다. 어느 프로세스가 어떤 행을 만드는지가
 * {@code triggered_by}에 이미 남아 있다 — 웹 인스턴스는 {@code ADMIN}·{@code SCHEDULER}만,
 * {@code BootstrapRunner}는 {@code MANUAL}만 만든다. 그러므로 <b>내가 만들 수 있는 종류인데
 * 내가 뜨기 전에 시작된 RUNNING 행</b>은 정의상 죽은 프로세스의 것이다(인스턴스가 하나라는
 * 전제 위에 선다 — ADR-0025). 이 판정은 오탐이 구조적으로 없고, 다른 JVM의 행은 종류가
 * 달라 애초에 대상이 아니다.
 *
 * <p>MUST NOT: 알림을 보내지 않는다. 이 정리는 이력 표시를 고칠 뿐이고, 종료 경로에서
 * 알림 포트에 의존하면 마감 자체를 잃는다.
 *
 * <p>MUST NOT: 이것을 단일 실행 판정의 근거로 삼지 않는다. 그 정본은 여전히
 * {@link AdminJobLauncher}의 인메모리 집합이다.
 */
@Component
public class BatchExecutionSweeper {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutionSweeper.class);

    /** 웹 인스턴스가 만드는 실행. 스케줄러와 관리자 화면 둘이다. */
    private static final Set<TriggeredBy> WEB_OWNED = Set.of(TriggeredBy.SCHEDULER, TriggeredBy.ADMIN);

    /** {@code BootstrapRunner}가 만드는 실행. */
    private static final Set<TriggeredBy> BOOTSTRAP_OWNED = Set.of(TriggeredBy.MANUAL);

    /**
     * {@code GAP_FILL}이지만 <b>웹 인스턴스만</b> 만드는 잡 — 14:00 사슬의 좁힌 화해 ①이다
     * ({@code IndicatorReconciler.originTrigger}는 SCHEDULER만 GAP_FILL로 바꾸고 CLI 화해는 MANUAL로 남는다).
     *
     * <p>{@code GAP_FILL} 전체를 웹 소유로 두지 않는 이유: 리밸런싱 꼬리는 <b>두 JVM 모두</b>에서 돈다(토요일
     * 크론 · CLI {@code --job=universe}). 그 행은 트리거만으로 소유자를 가를 수 없어 지금은 어느 스윕도
     * 닫지 않는다 — 운영 고아는 0건이었다(2026-10-04). 잡 이름까지 보면 갈리는 것만 여기 둔다.
     */
    private static final Set<String> WEB_OWNED_GAP_FILL_JOBS = Set.of(IndicatorBackfillJob.ORIGIN_JOB_NAME);

    private final JdbcTemplate jdbcTemplate;

    /**
     * 이 JVM이 배치 행을 만들 수 있게 되기 전의 시각.
     *
     * <p>빈 생성 시점이라 어떤 잡보다도 앞선다. 이보다 <b>나중에</b> 시작된 행은 이 JVM이
     * 만든 것이고 {@link BatchExecutions}가 들고 있으므로 정리 대상이 아니다.
     */
    private final Instant startedAt = Instant.now();

    public BatchExecutionSweeper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 웹 인스턴스가 기동하면 이전 웹 인스턴스가 남긴 실행을 닫는다.
     *
     * <p>IMPORTANT — <b>부트스트랩 JVM은 이 경로를 타지 않는다.</b>
     * {@code SpringApplication.run()}은 {@code callRunners()}를 {@code ApplicationReadyEvent}
     * 발행보다 <b>먼저</b> 부르고, {@code BootstrapRunner}는 그 안에서 {@code System.exit()}로
     * 내려간다. 그래서 부트스트랩 프로파일에서 이 이벤트는 발행되지 않는다 — 그쪽 정리는
     * {@link #sweepBootstrapOrphans()}를 러너가 직접 부르는 것으로 한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweep(WEB_OWNED, WEB_OWNED_GAP_FILL_JOBS, "웹 인스턴스");
    }

    /**
     * 이전 부트스트랩 실행이 남긴 {@code MANUAL} 행을 닫는다. 러너가 잡을 시작하기 전에 부른다.
     *
     * <p>한계 — 적재 <b>두 개를 동시에</b> 띄우면 뒤엣것이 앞엣것의 살아 있는 행을 마감한다.
     * 그 상황은 이미 아무도 막지 못하고({@code AdminJobLauncher}의 판정은 JVM을 못 넘는다)
     * 셸 훅이 실행 시점에 경고한다. 여기서 더 정확해지려면 소유자 토큰이 필요한데, 그것은
     * 스키마 변경이라 이 결함의 크기에 맞지 않는다.
     */
    public void sweepBootstrapOrphans() {
        sweep(BOOTSTRAP_OWNED, Set.of(), "부트스트랩");
    }

    private void sweep(Set<TriggeredBy> owners, Set<String> gapFillJobs, String label) {
        List<Object> args = new ArrayList<>();
        StringBuilder placeholders = new StringBuilder();
        for (TriggeredBy owner : owners) {
            if (!placeholders.isEmpty()) {
                placeholders.append(", ");
            }
            placeholders.append('?');
            args.add(owner.name());
        }
        StringBuilder gapFillPlaceholders = new StringBuilder();
        for (String job : gapFillJobs) {
            if (!gapFillPlaceholders.isEmpty()) {
                gapFillPlaceholders.append(", ");
            }
            gapFillPlaceholders.append('?');
            args.add(job);
        }
        String gapFillClause = gapFillJobs.isEmpty()
                ? ""
                : " OR (triggered_by = 'GAP_FILL' AND job_name IN (" + gapFillPlaceholders + "))";
        args.add(Timestamp.from(startedAt));

        int closed = jdbcTemplate.update("""
                UPDATE batch_execution
                   SET status      = 'ABORTED',
                       finished_at = now(),
                       note        = COALESCE(note || ' ', '')
                                     || '프로세스가 사라져 마감되지 못한 실행을 정리했다.'
                 WHERE status = 'RUNNING'
                   AND (triggered_by IN (%s)%s)
                   AND started_at < ?
                """.formatted(placeholders, gapFillClause), args.toArray());

        if (closed > 0) {
            log.info("마감되지 못한 배치 실행을 정리했다. {}건 ({} 소유, 기동 시각 {} 이전)",
                    closed, label, startedAt);
        }
    }
}
