package com.stockscreener.pipeline.scheduler;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>운영 구성으로</b> 컨텍스트가 기동되는지 본다.
 *
 * <p>IMPORTANT — 이 테스트가 없으면 스케줄러를 켠 구성은 <b>어디에서도 검증되지 않는다.</b>
 * {@code pipeline.scheduler.enabled}는 기본이 꺼짐이고(그 기본값에는 별도 근거가 있다 —
 * 켜면 앱을 띄우는 것만으로 외부 API 쿼터를 태운다), 그래서 {@code ./gradlew build}도
 * 평소 {@code bootRun}도 이 가지를 밟지 않는다.
 *
 * <p>실제로 그 공백에서 결함이 하나 나왔다(2026-08-09). 스케줄러를 켜면 Boot이
 * {@code taskScheduler} 빈을 만드는데 그것도 {@code AsyncTaskExecutor}를 구현해
 * {@code AdminJobLauncher}의 주입 후보가 둘이 되고, 컨텍스트가 기동에 실패했다.
 * 435건이 전부 초록인 채로 launchd의 첫 기동에서 죽었다.
 *
 * <p>MUST NOT: 이 테스트를 지우거나 프로퍼티를 빼지 않는다. 그 순간 운영 구성의 판정이
 * 다시 "실제로 배포해 보기"로만 가능해지는데, 그것을 아무도 알려주지 않는다.
 *
 * <p>스케줄이 <b>언제</b> 도는가는 {@code PipelineSchedulerTest}가 본다. 여기서는
 * 켠 채로 뜨는가만 본다 — 이 테스트에서 잡이 실제로 돌면 안 된다(쿼터를 태운다).
 * 크론이 평일 20시·토요일 20시라 테스트가 도는 동안 발화하지 않는다.
 */
@SpringBootTest(properties = "pipeline.scheduler.enabled=true")
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class SchedulerEnabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("스케줄러를 켠 채로 컨텍스트가 뜬다 — 운영이 쓰는 유일한 구성이다")
    void contextLoadsWithSchedulerEnabled() {
        assertThat(context.getBean(PipelineScheduler.class)).isNotNull();
    }

    @Test
    @DisplayName("스케줄링 인프라가 실제로 켜진다 — taskScheduler가 생기는 것이 결함의 발생점이었다")
    void schedulingInfrastructureIsActive() {
        // 이 빈이 있어야 @Scheduled가 돈다. 동시에 이 빈이 AsyncTaskExecutor 주입을
        // 모호하게 만들던 원인이므로, 있다는 것 자체가 위 테스트의 의미를 만든다.
        assertThat(context.containsBean("taskScheduler")).isTrue();
    }
}
