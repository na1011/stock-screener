package com.stockscreener.pipeline.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled}를 활성화한다.
 *
 * <p>{@link PipelineScheduler}와 같은 조건을 걸어둔 이유는, 스케줄러 빈이 없는데
 * 스케줄링 인프라만 켜두면 아무 일도 하지 않는 스레드 풀이 남기 때문이다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pipeline.scheduler.enabled", havingValue = "true")
public class SchedulingConfig {
}
