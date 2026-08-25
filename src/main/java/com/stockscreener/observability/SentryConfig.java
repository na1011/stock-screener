package com.stockscreener.observability;

import com.stockscreener.pipeline.batch.BatchShutdownException;
import com.stockscreener.web.ApiException;

import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sentry가 <b>무엇을 이슈로 올리지 않는가</b>를 정한다.
 *
 * <p>Sentry의 자동 구성은 {@code sentryExceptionResolver}를 등록하고, 그것은
 * {@code DispatcherServlet}의 예외 경로를 지나는 <b>모든</b> 예외를 잡는다. 그대로 두면
 * "없는 전략을 조회했다"({@code SCREEN_NOT_FOUND}), "조건이 올바르지 않다" 같은 <b>예상된
 * 거절</b>이 전부 이슈로 쌓인다. 그런 이슈가 대부분을 차지하는 순간 Sentry는 "봐야 하는 곳"이
 * 아니게 되고, 그때부터 진짜 예외도 함께 묻힌다.
 *
 * <p>설계상 역할 분담이 이것을 요구한다 — 자체 알림({@code AlertPort})은 코드가 <b>예상한</b>
 * 사건(배치 실패·워치리스트)을 맡고, Sentry는 <b>예상 못 한</b> 예외와 알림 코드 자체가 죽은
 * 경우를 맡는다. 둘이 서로를 대체하지 않는 것이 요점이므로, 예상된 거절은 여기서 걸러진다.
 *
 * <p>5xx {@link ApiException}은 <b>거르지 않는다.</b> 그것은 우리 쪽 문제이고
 * {@code ApiExceptionHandler}가 이미 {@code log.error}로 남기는 것과 짝이 맞는다.
 *
 * <p>거르는 것이 하나 더 있다 — {@link BatchShutdownException}. 프로세스가 내려가서 배치가
 * 스스로 멈춘 것은 코드가 예상하고 {@code ABORTED}로 기록까지 하는 사건이라 위의 역할
 * 분담에서 자체 알림 쪽에 속한다.
 *
 * <p><b>MUST NOT: 이 판정을 종료 오염의 주된 방어로 여기지 않는다.</b> 종료 중에 나는 로그
 * 대부분은 <b>원본 예외</b>를 싣고 있어 원인 체인에 이 타입이 없다. 실제로 막는 것은
 * {@code StockTaskExecutor}가 그때 ERROR로 찍지 않는 것이고, 여기는 그 뒤에 남는 자리를
 * 받는다 — {@code BootstrapRunner}가 대표적이다. 그쪽은 잡을 메인 스레드에서 부르므로
 * 예외가 {@code SpringApplication}의 실패 처리까지 올라가고, <b>거기서는 이 판정이 유일한
 * 방어다.</b>
 *
 * <p>IMPORTANT — 확장점으로 {@code SentryOptions.BeforeSendCallback} 빈을 쓴다.
 * {@code Sentry.OptionsConfiguration} 빈을 <b>{@code sentryOptionsConfiguration}이라는 이름으로</b>
 * 정의하면 안 된다. Sentry의 자동 구성이 그 이름에
 * {@code @ConditionalOnMissingBean(names = "sentryOptionsConfiguration")}을 걸고 있어,
 * 같은 이름의 빈을 두면 <b>자기 구성을 통째로 물러나게 한다</b> — 이벤트 프로세서·통합·전송
 * 계층·in-app 패키지 판정이 전부 사라지는데 <b>아무 에러도 나지 않는다.</b>
 */
@Configuration
public class SentryConfig {

    /** 원인 체인을 따라갈 최대 깊이. 순환 참조가 있어도 여기서 멈춘다. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * MUST NOT: 이 빈의 이름을 {@code sentryOptionsConfiguration}으로 짓지 않는다 —
     * 위 javadoc의 함정이다. 그 밖의 이름은 안전하다. Sentry는 이 빈을 <b>타입으로</b> 찾는다.
     */
    @Bean
    public SentryOptions.BeforeSendCallback dropExpectedEvents() {
        return (event, hint) -> isExpected(event.getThrowable()) ? null : event;
    }

    /**
     * 이 예외가 "코드가 예상한 사건"인가 — 설명된 거절이거나, 종료로 인한 중단이거나.
     *
     * <p>{@link SentryEvent#getThrowable()}은 Sentry가 씌운 래퍼를 벗겨 주지만 우리 코드가
     * 감싼 것까지는 벗기지 않으므로 원인 체인을 따라간다.
     *
     * <p>MUST: 종료 판정을 {@link ApiException} 판정보다 <b>앞</b>에서 한다. 그쪽은 만나는
     * 즉시 돌아가므로 뒤에 두면 {@code ApiException}이 낀 체인에서 종료 신호가 안 보인다.
     */
    static boolean isExpected(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof BatchShutdownException) {
                return true;
            }
            if (current instanceof ApiException apiException) {
                return apiException.status().is4xxClientError();
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }
}
