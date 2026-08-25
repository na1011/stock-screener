package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 종목 단위 실패 격리의 실체.
 *
 * <p>{@code data-pipeline.md}가 요구하는 두 가지를 여기서 동시에 만족시킨다 — 일부 종목이
 * 실패해도 나머지는 정상 처리되고, 어느 종목이 왜 실패했는지 나중에 식별할 수 있다.
 *
 * <h2>Spring Batch의 skip을 쓰지 않는 이유</h2>
 * <p>IMPORTANT — {@code faultTolerant().skip()}으로 같은 일을 할 수 있어 보이지만 쓰지 않는다.
 * skip 정책은 청크를 롤백한 뒤 아이템을 하나씩 다시 처리하므로, 실패한 종목 하나 때문에
 * <b>같은 청크의 성공 종목이 API를 다시 호출한다.</b> 일일 쿼터가 있는 소스에서 이는 그대로
 * 낭비이며, 850종목 초기 적재에서는 쿼터 소진을 앞당긴다. 이 판단을 되돌리려 할 다음
 * 세션을 위해 ADR-0007에 남겼다.
 *
 * <h2>트랜잭션 구조</h2>
 * <p>작업과 실패 기록이 <b>각각 독립된</b> {@code REQUIRES_NEW} 트랜잭션이다. 작업
 * 트랜잭션 안에서 실패를 기록하면 롤백과 함께 기록도 사라져 실패가 조용히 증발한다.
 * 실제 전파는 {@link StockTaskRunner}(작업)와 {@link BatchExecutionRecorder}(기록)에 걸려 있다 —
 * 자기 호출은 프록시를 거치지 않아 {@code @Transactional}이 무시되기 때문에 별도 빈으로 나눴다.
 */
@Component
public class StockTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(StockTaskExecutor.class);

    private final StockTaskRunner runner;
    private final BatchExecutionRecorder recorder;

    public StockTaskExecutor(StockTaskRunner runner, BatchExecutionRecorder recorder) {
        this.runner = runner;
        this.recorder = recorder;
    }

    /**
     * 한 종목을 격리해 처리한다. 실패해도 예외를 밖으로 내지 않는다 — 단, {@code AUTH}는 예외다.
     *
     * <p>MUST NOT: 실패를 조용히 넘기지 않는다. 모든 실패 경로가 기록과 로그를 남긴다.
     *
     * <p>{@code shuttingDown}이면 실패를 <b>버그로 신고하지 않는다.</b> 아래
     * {@link #abandonOnShutdown} 참고 — 그 분기가 없으면 종료 한 번에 ERROR 로그가
     * <b>종목 수만큼</b> 나고 그대로 Sentry 이슈가 된다.
     *
     * @param shuttingDown 프로세스가 내려가는 중인가. 판정의 정본은
     *                     {@code BatchExecutionContext}의 종료 플래그이고 여기는 그것을
     *                     넘겨받기만 한다 — 이 클래스가 스스로 물으면 판정이 둘로 갈린다.
     * @throws DataSourceException {@link DataSourceException#fatal()}인 경우에만. 키가 틀렸는데
     *                             850종목을 모두 시도하면 쿼터만 태우고 850건의 동일한 실패
     *                             기록을 남길 뿐이다. 배치를 즉시 멈추는 편이 낫다.
     * @throws BatchShutdownException {@code shuttingDown} 중에 실패했을 때. 종료 신호이지
     *                             실패가 아니다.
     */
    public Outcome execute(long executionId, SecurityRef security, CollectionPhase phase,
                           StockTask task, boolean shuttingDown) throws DataSourceException {
        try {
            long written = runner.runInNewTransaction(security, task);
            return Outcome.success(written);

        } catch (DataSourceException e) {
            if (shuttingDown) {
                throw abandonOnShutdown(security, phase, e,
                        () -> recorder.recordFailure(executionId, security.stockId(), phase, e));
            }
            if (e.fatal()) {
                // 기록하고 다시 던진다. 이 실패는 종목의 문제가 아니라 배치 전체의 문제다.
                recorder.recordFailure(executionId, security.stockId(), phase, e);
                log.error("배치를 중단한다 — 복구 불가능한 수집 실패. stockId={} ticker={} source={} kind={}",
                        security.stockId(), security.ticker(), e.sourceName(), e.kind(), e);
                throw e;
            }
            recorder.recordFailure(executionId, security.stockId(), phase, e);
            log.warn("종목 수집 실패. stockId={} ticker={} phase={} source={} kind={} message={}",
                    security.stockId(), security.ticker(), phase, e.sourceName(), e.kind(),
                    e.getMessage());
            return Outcome.failure();

        } catch (RuntimeException e) {
            if (shuttingDown) {
                throw abandonOnShutdown(security, phase, e,
                        () -> recorder.recordUnexpectedFailure(
                                executionId, security.stockId(), phase, e));
            }
            // 예상하지 못한 실패는 우리 코드의 버그일 가능성이 높다. DataSourceException과
            // 섞어 catch하지 않는 이유가 그것이다 — 뭉뚱그리면 버그가 수집 실패 통계에 묻힌다.
            recorder.recordUnexpectedFailure(executionId, security.stockId(), phase, e);
            log.error("종목 처리 중 예상하지 못한 오류. stockId={} ticker={} phase={}",
                    security.stockId(), security.ticker(), phase, e);
            return Outcome.failure();
        }
    }

    /**
     * 종료가 끊은 종목 하나를 <b>실패가 아니라 신호로</b> 처리한다.
     *
     * <p>IMPORTANT — 여기가 종료 시 Sentry 오염의 주된 출처였다. 위 두 catch가 원본 예외를
     * {@code log.error}로 찍는데 {@code sentry.logging.minimum-event-level}이 {@code error}라
     * 그것이 곧 이벤트이고, <b>돌던 종목 수만큼</b> 난다. 원인 체인에 종료 신호가 없으므로
     * {@code SentryConfig}의 필터로는 걸러지지 않는다 — 그래서 애초에 ERROR로 안 찍는다.
     *
     * <p>MUST: 기록은 <b>시도한다.</b> 그 종목은 실제로 처리되지 않았으므로 진짜 결손이다.
     * 다만 종료 유예가 끝난 뒤라면 커넥션 풀이 이미 닫혀 기록 자체가 실패하는데, 그것을
     * 삼키지 않고 {@code addSuppressed}로 신호에 실어 보낸다.
     */
    private BatchShutdownException abandonOnShutdown(SecurityRef security, CollectionPhase phase,
                                                     Exception cause, Runnable recordAttempt) {
        BatchShutdownException signal = new BatchShutdownException(
                "프로세스가 내려가는 중에 종목 처리가 끊겼다. stockId=%d ticker=%s phase=%s"
                        .formatted(security.stockId(), security.ticker(), phase), cause);
        try {
            recordAttempt.run();
        } catch (RuntimeException e) {
            signal.addSuppressed(e);
        }
        log.warn("종료 중이라 종목 처리가 끊겼다 — 버그가 아니다. stockId={} ticker={} phase={} 원인={}",
                security.stockId(), security.ticker(), phase, cause.toString());
        return signal;
    }

    /**
     * 한 종목 처리의 결과.
     *
     * @param succeeded    성공 여부
     * @param writtenRows  저장한 행 수. 실패면 0이다.
     */
    public record Outcome(boolean succeeded, long writtenRows) {

        static Outcome success(long writtenRows) {
            return new Outcome(true, writtenRows);
        }

        static Outcome failure() {
            return new Outcome(false, 0L);
        }
    }
}
