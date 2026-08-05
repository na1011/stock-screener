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
     * @throws DataSourceException {@link DataSourceException#fatal()}인 경우에만. 키가 틀렸는데
     *                             850종목을 모두 시도하면 쿼터만 태우고 850건의 동일한 실패
     *                             기록을 남길 뿐이다. 배치를 즉시 멈추는 편이 낫다.
     */
    public Outcome execute(long executionId, SecurityRef security, CollectionPhase phase,
                           StockTask task) throws DataSourceException {
        try {
            long written = runner.runInNewTransaction(security, task);
            return Outcome.success(written);

        } catch (DataSourceException e) {
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
            // 예상하지 못한 실패는 우리 코드의 버그일 가능성이 높다. DataSourceException과
            // 섞어 catch하지 않는 이유가 그것이다 — 뭉뚱그리면 버그가 수집 실패 통계에 묻힌다.
            recorder.recordUnexpectedFailure(executionId, security.stockId(), phase, e);
            log.error("종목 처리 중 예상하지 못한 오류. stockId={} ticker={} phase={}",
                    security.stockId(), security.ticker(), phase, e);
            return Outcome.failure();
        }
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
