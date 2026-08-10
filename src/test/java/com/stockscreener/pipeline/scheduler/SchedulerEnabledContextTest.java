package com.stockscreener.pipeline.scheduler;

import com.stockscreener.pipeline.batch.DailyUpdateJob;
import com.stockscreener.pipeline.batch.UniverseRebalanceJob;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
 * 켠 채로 뜨는가만 본다.
 */
@SpringBootTest(properties = "pipeline.scheduler.enabled=true")
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class SchedulerEnabledContextTest {

    // MUST: 두 잡을 목으로 둔다. 여기가 스케줄러가 켜진 **유일한** 컨텍스트이고, Spring
    // TestContext 캐시는 그것을 JVM이 끝날 때까지 닫지 않는다 — 즉 노출 창은 이 클래스의
    // 실행 시간이 아니라 `./gradlew test` 전체다. 배치가 평일 15시로 옮겨진 뒤로 그 시각에
    // 테스트를 돌릴 확률은 낮지 않다.
    //
    // 발화해도 쿼터는 안 탄다(테스트 유니버스 편입이 0건이고 어댑터도 없다). 문제는
    // batch_execution·indicator_value에 행이 생겨 **그 테이블을 세는 다른 테스트가 시각에
    // 따라 깨지는 것**이다 — 증상이 원인과 전혀 안 닮은 플래키가 된다.
    //
    // 목으로 바꿔도 이 테스트의 가치는 줄지 않는다. 잡아낸 결함은 taskScheduler 빈이 생기면서
    // AsyncTaskExecutor 주입 후보가 둘이 되는 것이고, 그 경로는 잡을 목으로 바꿔도 그대로 밟힌다.
    @MockitoBean
    private DailyUpdateJob dailyUpdateJob;

    @MockitoBean
    private UniverseRebalanceJob universeRebalanceJob;

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
