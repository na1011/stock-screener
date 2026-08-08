package com.stockscreener.observability;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.protocol.SentryId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentry 관통테스트. <b>실제로 이벤트 1건을 보낸다.</b>
 *
 * <p>{@code ./gradlew liveTest}로만 실행된다. DSN이 없으면 스스로 스킵한다.
 *
 * <p>목이 검증하지 못하는 것 하나를 지킨다 — <b>이 DSN이 살아 있는 프로젝트를 가리키는가.</b>
 * 형식이 맞고 SDK가 활성화되어도 프로젝트가 지워졌거나 키가 회수되었으면 이벤트는 조용히
 * 버려진다. 그 실패는 "이슈가 안 뜬다"로만 보이고, 정작 그것을 알아야 하는 때는 진짜 장애가
 * 났을 때다.
 *
 * <p>여기서 확인되는 것은 <b>전송이 거부되지 않았다</b>까지다. 실제로 그 채널에 도착했는지는
 * 대시보드를 봐야 갈리므로 {@code TODO.md}가 사람에게 넘긴다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SENTRY_DSN", matches = ".+",
        disabledReason = "SENTRY_DSN이 없어 건너뛴다")
class SentryLiveTest {

    @Test
    @DisplayName("[live] .env의 DSN으로 이벤트 1건이 나간다")
    void sendsOneEvent() {
        // 전역 상태를 건드리므로 이 태스크 안에서 닫고 나간다. liveTest는 단위 test와
        // 별도 JVM이라 여기서 초기화한 Sentry가 다른 테스트에 새지 않는다.
        Sentry.init(options -> {
            options.setDsn(System.getenv("SENTRY_DSN"));
            // 실장애 이슈와 섞이지 않게 환경을 갈라 둔다.
            options.setEnvironment("live-test");
        });
        try {
            assertThat(Sentry.isEnabled())
                    .as("DSN 형식이 틀리면 SDK가 조용히 비활성으로 남는다")
                    .isTrue();

            SentryId id = Sentry.captureMessage(
                    "[live] stock-screener 관통테스트 — 이 메시지가 보이면 백엔드 DSN이 살아 있다",
                    SentryLevel.INFO);
            // 전송은 비동기다. 큐에 남긴 채 끝내면 이벤트가 나가지 않는다.
            Sentry.flush(10_000);

            assertThat(id)
                    .as("이벤트가 거부되면 빈 ID가 돌아온다")
                    .isNotEqualTo(SentryId.EMPTY_ID);
        } finally {
            Sentry.close();
        }
    }
}
