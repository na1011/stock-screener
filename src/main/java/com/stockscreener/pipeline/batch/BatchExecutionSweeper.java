package com.stockscreener.pipeline.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 마감되지 못한 채 남은 {@code RUNNING} 행을 정리한다.
 *
 * <p>마감은 {@link BatchExecutionContext}가 살아 있는 JVM 안에서만 한다. 그래서 프로세스가
 * 비정상으로 사라지면 그 행은 <b>영원히 RUNNING으로 남는다.</b> 실제로 10건이 그렇게 쌓였다.
 *
 * <p>계층이 둘이다. 정상적인 종료(SIGTERM·launchd 재기동·컨텍스트 종료)는
 * {@link BatchExecutions}가 종료 훅에서 즉시 마감하므로 여기까지 오지 않는다. 이 클래스가
 * 맡는 것은 <b>SIGKILL·전원 차단·크래시</b>처럼 종료 훅조차 돌지 못한 경우다.
 *
 * <p>MUST NOT: 알림을 보내지 않는다. 임계값을 넘겨 <b>도는 중인 잡</b>을 잘못 마감해도, 그
 * 잡이 끝나면 {@code finish()}가 같은 행을 덮어써 스스로 교정된다 — 알림만 보내지 않으면
 * 남는 피해가 없다. 이 성질이 임계값을 보수적으로 크게 잡지 않아도 되는 근거다.
 *
 * <p>MUST NOT: 이 정리를 단일 실행 판정의 근거로 삼지 않는다. 그 정본은 여전히
 * {@link AdminJobLauncher}의 인메모리 집합이다(ADR-0025).
 */
@Component
public class BatchExecutionSweeper {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutionSweeper.class);

    /**
     * 이 시간을 넘겨 RUNNING인 행은 마감되지 못한 것으로 본다.
     *
     * <p>가장 긴 잡(7년치 초기 적재)보다 짧아도 되는 이유는 위 "스스로 교정된다"에 있다.
     * 반대로 너무 길면 관리자 화면이 그만큼 오래 거짓을 말한다.
     */
    static final int STALE_AFTER_HOURS = 3;

    private final JdbcTemplate jdbcTemplate;

    public BatchExecutionSweeper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 기동 직후 한 번 쓸어낸다.
     *
     * <p>부트스트랩 JVM도 이 이벤트를 낸다. 적재를 띄울 때마다 정리가 도는 셈이라 실제로
     * 가장 자주 걸리는 경로이고, 그것이 의도다 — 고아 행을 만드는 것도 대개 그 프로세스다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweepStale();
    }

    /**
     * 마감되지 못한 실행을 {@code ABORTED}로 닫는다.
     *
     * @return 마감한 행 수
     */
    public int sweepStale() {
        int closed = jdbcTemplate.update("""
                UPDATE batch_execution
                   SET status      = 'ABORTED',
                       finished_at = now(),
                       note        = COALESCE(note || ' ', '')
                                     || '프로세스가 사라져 마감되지 못한 실행을 정리했다.'
                 WHERE status = 'RUNNING'
                   AND started_at < now() - make_interval(hours => ?)
                """, STALE_AFTER_HOURS);

        if (closed > 0) {
            log.info("마감되지 못한 배치 실행을 정리했다. {}건 (기준 {}시간)", closed, STALE_AFTER_HOURS);
        }
        return closed;
    }
}
