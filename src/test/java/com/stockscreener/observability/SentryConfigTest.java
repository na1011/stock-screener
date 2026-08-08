package com.stockscreener.observability;

import com.stockscreener.web.ApiExceptions;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "예상된 거절은 Sentry에 올리지 않는다"가 실제로 성립하는지 본다.
 *
 * <p>Sentry 없이 도는 순수 테스트다. {@code beforeSend} 판정이 SDK 초기화에 얽혀 있으면
 * 이 규칙은 실키가 있는 환경에서만 검증되고, 그런 검증은 사실상 없는 것과 같다.
 */
class SentryConfigTest {

    private final SentryOptions.BeforeSendCallback callback = new SentryConfig().dropExpectedApiRejections();

    @Test
    @DisplayName("4xx ApiException은 버린다 — 사용자에게 설명된 거절은 이슈가 아니다")
    void dropsClientErrors() {
        SentryEvent event = new SentryEvent(new ApiExceptions.ScreenNotFoundException(42L));

        assertThat(callback.execute(event, new Hint())).isNull();
    }

    @Test
    @DisplayName("5xx ApiException은 올린다 — 상태 코드가 우리 쪽 문제라고 말한다")
    void keepsServerErrors() {
        SentryEvent event = new SentryEvent(new ApiExceptions.IndicatorDataNotReadyException());

        assertThat(callback.execute(event, new Hint())).isSameAs(event);
    }

    @Test
    @DisplayName("ApiException이 아닌 예외는 올린다 — 그것이 Sentry가 잡으려는 바로 그것이다")
    void keepsUnexpectedExceptions() {
        SentryEvent event = new SentryEvent(new IllegalStateException("예상 못 한 것"));

        assertThat(callback.execute(event, new Hint())).isSameAs(event);
    }

    @Test
    @DisplayName("원인 체인 안쪽의 4xx도 버린다 — 감싸는 순간 새는 규칙이면 규칙이 아니다")
    void looksThroughCauseChain() {
        SentryEvent event = new SentryEvent(
                new RuntimeException("감쌌다", new ApiExceptions.ScreenNotFoundException(42L)));

        assertThat(callback.execute(event, new Hint())).isNull();
    }

    @Test
    @DisplayName("자기 자신을 원인으로 가진 예외에도 멈춘다")
    void survivesSelfReferencingCause() {
        SelfCausedException loop = new SelfCausedException();
        SentryEvent event = new SentryEvent(loop);

        assertThat(callback.execute(event, new Hint())).isSameAs(event);
    }

    @Test
    @DisplayName("예외가 없는 이벤트(로그 기반)는 올린다")
    void keepsEventsWithoutThrowable() {
        SentryEvent event = new SentryEvent();

        assertThat(callback.execute(event, new Hint())).isSameAs(event);
    }

    /** {@code initCause}는 자기 참조를 거부하므로 {@code getCause}를 덮어 만든다. */
    private static final class SelfCausedException extends RuntimeException {
        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
