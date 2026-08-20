package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 배치 실행을 시작해 {@link BatchExecutionContext}를 만든다.
 *
 * <p>잡이 recorder·executor·alertPort를 각각 주입받아 조립하지 않게 하는 이유는, 조립을
 * 잡마다 반복하면 어느 잡은 알림 포트를 빼먹기 때문이다. 그 잡의 실패는 기록만 되고
 * 통지되지 않는데, 그 사실은 실패가 실제로 일어나기 전까지 드러나지 않는다.
 */
@Component
public class BatchExecutions {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutions.class);

    /**
     * 아직 마감되지 않은 실행.
     *
     * <p>IMPORTANT — 이것이 없으면 <b>프로세스가 내려갈 때 열린 행이 영원히 RUNNING으로
     * 남는다.</b> 마감은 {@link BatchExecutionContext}가 살아 있는 JVM 안에서만 하기
     * 때문이고, devtools 재기동·SIGTERM·launchd 재기동이 전부 그 경로다. 실제로 그렇게
     * 쌓인 고아 행이 10건이었다.
     *
     * <p>이 집합은 <b>단일 실행 판정에 쓰지 않는다.</b> 그 정본은 {@link AdminJobLauncher}에
     * 있고, 둘을 겸하면 잡이 죽었을 때 다시 띄우지 못하게 된다(ADR-0025의 단일 인스턴스 전제).
     */
    private final Map<Long, BatchExecutionContext> open = new ConcurrentHashMap<>();

    private final StockTaskExecutor executor;
    private final BatchExecutionRecorder recorder;
    private final AlertPort alertPort;

    /** 내가 속한 컨텍스트. 자식 컨텍스트가 발행한 종료 이벤트를 걸러내는 데만 쓴다. */
    private final ApplicationContext applicationContext;

    public BatchExecutions(StockTaskExecutor executor, BatchExecutionRecorder recorder,
                           AlertPort alertPort, ApplicationContext applicationContext) {
        this.executor = executor;
        this.recorder = recorder;
        this.alertPort = alertPort;
        this.applicationContext = applicationContext;
    }

    /** 새 실행을 시작한다. */
    public BatchExecutionContext start(String jobName, LocalDate targetDate,
                                       BatchExecutionRecorder.TriggeredBy triggeredBy,
                                       int targetCount) {
        long executionId = recorder.start(jobName, targetDate, triggeredBy, targetCount);
        return track(new BatchExecutionContext(
                executionId, jobName, targetDate, executor, recorder, alertPort, open::remove));
    }

    /** 실패한 부분만 다시 도는 실행을 시작한다. */
    public BatchExecutionContext startRetry(String jobName, LocalDate targetDate,
                                            int targetCount, long retriedFromId) {
        long executionId = recorder.startRetry(jobName, targetDate, targetCount, retriedFromId);
        return track(new BatchExecutionContext(
                executionId, jobName, targetDate, executor, recorder, alertPort, open::remove));
    }

    private BatchExecutionContext track(BatchExecutionContext context) {
        open.put(context.executionId(), context);
        return context;
    }

    /**
     * 컨텍스트가 닫히기 시작하면 마감한다.
     *
     * <p>IMPORTANT — {@code @PreDestroy}가 아니라 이 이벤트인 이유. {@code doClose()}는
     * <b>이벤트 발행 → lifecycle 정지 → 빈 파괴</b> 순이므로, 여기는 {@code DataSource}는 물론
     * {@code EntityManagerFactory}가 살아 있는 것이 <b>프레임워크 계약으로</b> 보장되는
     * 자리다. {@code @PreDestroy}였을 때는 그 보장이 <b>빈 생성 순서</b>에서 나왔고, 그 순서는
     * 컴포넌트 스캔이 클래스를 만나는 차례에 달려 있었다 — 어긋나도 아무 데도 안 드러난다.
     * {@code recorder}의 {@code REQUIRES_NEW}가 {@code JpaTransactionManager}를 타므로
     * {@code DataSource}만 보던 옛 전제로는 애초에 부족했다.
     *
     * <p>MUST NOT: 여기서 예외가 밖으로 나가게 두지 않는다. {@code @PreDestroy}에서 새는
     * 예외는 빈 하나로 끝났지만, <b>이벤트 리스너에서 새면 같은 이벤트의 나머지 리스너가
     * 통째로 건너뛰어진다</b> — executor의 조기 정지도 그중 하나다.
     */
    @EventListener
    void onContextClosed(ContextClosedEvent event) {
        // 자식 컨텍스트(관리 서버 등)가 닫혀도 부모의 리스너가 불린다. 그때 부모의 열린
        // 실행을 닫으면 도는 배치가 중단된 것으로 기록된다. Spring 자신이 쓰는 가드와 같다.
        if (event.getApplicationContext() != applicationContext) {
            return;
        }
        try {
            closeOpenExecutions();
        } catch (RuntimeException e) {
            log.warn("종료 마감이 통째로 실패했다 — 열린 실행은 RUNNING으로 남고 "
                    + "BatchExecutionSweeper가 다음 기동에 정리한다.", e);
        }
    }

    /**
     * 아직 열려 있는 실행을 {@code ABORTED}로 닫는다.
     *
     * <p>MUST NOT: 하나가 실패했다고 나머지를 포기하지 않는다 — 그래서 catch가 루프 안에 있다.
     * 리스너 쪽의 catch는 그 바깥을 받는 별도의 그물이다.
     */
    void closeOpenExecutions() {
        for (BatchExecutionContext context : open.values()) {
            try {
                // MUST: 어느 잡에나 참인 문장이어야 한다. "다음 실행이 이어서 처리한다"였던
                // 시절이 있는데, 그것은 daily-update의 시세에서만 참이다 — 나머지 여덟 잡은
                // 사람이 손으로 띄우므로 이어받는 것이 없다. 종료 마감은 알림을 보내지 않으니
                // 이 문장이 사람이 보는 유일한 설명이다.
                context.abortOnShutdown(
                        "프로세스가 종료돼 중단됐다. 이 실행은 저절로 이어지지 않는다 — 필요하면 다시 띄운다.");
                log.info("내려가면서 열린 배치 실행을 마감했다. id={} job={}",
                        context.executionId(), context.jobName());
            } catch (RuntimeException e) {
                log.warn("열린 배치 실행을 마감하지 못했다. id={} — RUNNING으로 남는다. "
                        + "BatchExecutionSweeper가 나중에 정리한다.", context.executionId(), e);
            }
        }
        open.clear();
    }

    /**
     * 아직 마감되지 않았는가.
     *
     * <p>테스트 전용이다. 마감이 {@code onTerminal}로 이 목록에서 빠지는지를 <b>직접</b>
     * 확인할 길이 이것뿐이기 때문에 있다 — DB 상태로 물으면 {@code updateTerminalState}의
     * 가드가 결과를 지켜 주므로 <b>배선이 빠져도 초록</b>이고, 실제로 그 상태로 오래 있었다.
     */
    boolean isOpen(long executionId) {
        return open.containsKey(executionId);
    }

    public BatchExecutionRecorder recorder() {
        return recorder;
    }
}
