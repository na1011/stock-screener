package com.stockscreener.observability;

import com.stockscreener.support.TestDatabaseGuard;

import io.sentry.IScopes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>DSN이 실린 채로</b> 컨텍스트가 기동되는지 본다.
 *
 * <p>IMPORTANT — 이 테스트가 없으면 Sentry 도입의 결함은 <b>빌드로 판정되지 않는다.</b>
 * {@code SentryAutoConfiguration}은 {@code @ConditionalOnProperty("sentry.dsn")}이고,
 * {@code .env}는 {@code test} 태스크에 주입되지 않으므로(그것은 지켜야 하는 규칙이다)
 * 평소 테스트에서는 그 가지가 <b>평가조차 되지 않는다.</b> 실제로 2026-08-08에 잘못된
 * 아티팩트로 435건이 전부 초록이었는데 {@code bootRun}은 컨텍스트 기동에 실패했다.
 *
 * <p>그래서 실키 대신 <b>형식만 유효한 가짜 DSN</b>을 프로퍼티로 준다. 규칙을 어기지 않으면서
 * 같은 가지를 밟는다. 호스트가 {@code 127.0.0.1}이고 {@code sentry.enabled=false}라
 * 밖으로 나가는 것은 없다.
 *
 * <p>{@code StockScreenerApplicationTests}가 <b>DSN 없는</b> 경로를 이미 덮으므로, 둘이 합쳐
 * "키가 있어도 없어도 기동한다"의 양쪽이 된다.
 *
 * <p>MUST NOT: 이 테스트를 지우거나 {@code sentry.dsn} 프로퍼티를 빼지 않는다. 그 순간
 * 판정이 다시 {@code bootRun}으로만 가능해지는데, 그것을 아무도 알려주지 않는다.
 */
@SpringBootTest(properties = {
        // 형식만 유효한 가짜 DSN. 실제 키가 아니며 어디에도 도달하지 않는다.
        "sentry.dsn=https://0123456789abcdef0123456789abcdef@127.0.0.1/0",
        "sentry.enabled=false",
})
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class SentryAutoConfigurationSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("DSN이 있으면 Sentry 자동 구성이 실제로 매칭되고 컨텍스트가 뜬다")
    void autoConfigurationActivates() {
        // "기동했다"만으로는 부족하다 — 조건이 안 맞아 통째로 건너뛰어도 기동은 한다.
        // Sentry 자동 구성만이 만드는 빈을 확인해야 가지가 평가·매칭된 것이 증명된다.
        assertThat(context.getBeanNamesForType(IScopes.class))
                .as("IScopes는 SentryAutoConfiguration.HubConfiguration#sentryHub만 만든다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Sentry가 예외 리졸버를 등록한다 — 4xx 필터가 필요한 이유가 이것이다")
    void registersExceptionResolver() {
        // 이 빈이 DispatcherServlet의 예외 경로를 전부 잡는다. 있으면 SentryConfig의
        // beforeSend 필터가 필요하고, 사라지면 그 필터는 아무것도 막지 않는 채 남는다.
        assertThat(context.containsBean("sentryExceptionResolver")).isTrue();
    }
}
