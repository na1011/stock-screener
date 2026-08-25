package com.stockscreener.pipeline.batch;

/**
 * 프로세스가 내려가기 시작해 배치가 스스로 멈췄다는 신호. <b>버그가 아니다.</b>
 *
 * <p>{@link BatchExecutionContext}가 던진다 — 잡이 새 작업 단위를 시작하려 할 때
 * ({@code process}·{@code stage}·{@code plan}) 이미 종료 마감이 일어났으면 그 자리에서
 * 루프를 끊는다. 인터럽트를 쓰지 않는 이유는 ADR-0078에 있다: 소켓 읽기가 인터럽트를
 * 무시하고, 외부 호출을 아예 안 하는 잡에는 신호가 도달할 입구조차 없다.
 *
 * <h2>왜 잡을 안 고쳐도 되는가</h2>
 * <p>이 예외가 나갈 수 있는 것은 <b>마감 권리를 이미 가져간 뒤</b>뿐이다 —
 * {@code abandonedOnShutdown}은 {@code claimTerminal}에 성공한 다음에만 켜진다. 그래서 잡의
 * {@code catch (RuntimeException)}이 {@code failUnexpectedly}를 부르든 그냥 다시 던지든
 * 실행 행은 이미 {@code ABORTED}이고 <b>RUNNING으로 남을 수 없다.</b> 그 불변식을 깨면
 * 잡 열한 개를 전부 고쳐야 한다.
 *
 * <h2>Sentry</h2>
 * <p>{@code com.stockscreener.observability.SentryConfig}가 이 예외를 이슈로 올리지 않는다.
 * <b>다만 그것은 백스톱이다</b> — 종료 중 나는 예외 대부분은 원본 그대로 로그에 실리므로,
 * 오염을 실제로 막는 것은 <b>ERROR로 찍지 않는 것</b>이다({@link StockTaskExecutor}).
 */
public final class BatchShutdownException extends RuntimeException {

    public BatchShutdownException(String message) {
        super(message);
    }

    public BatchShutdownException(String message, Throwable cause) {
        super(message, cause);
    }
}
