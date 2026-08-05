package com.stockscreener.pipeline.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;

/**
 * 지표 계산기가 붙기 전(Phase 3 이전)의 기본 구현.
 *
 * <p>MUST NOT: 조용히 아무것도 하지 않는 진짜 no-op으로 두지 않는다. 로그를 남겨야
 * Phase 3에서 "무효화가 실제로 호출되고 있었는가"를 확인할 수 있다. 호출 지점이 빠져
 * 있었다면 이 로그가 없는 것으로 드러난다.
 */
public class NoOpIndicatorInvalidationPort implements IndicatorInvalidationPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpIndicatorInvalidationPort.class);

    @Override
    public void invalidate(long stockId, LocalDate fromDate, InvalidationReason reason) {
        log.info("지표 무효화 요청. stockId={} fromDate={} reason={} "
                        + "— 지표 계산은 Phase 3이므로 지금은 지울 대상이 없다.",
                stockId, fromDate, reason);
    }

    /**
     * Phase 3이 실제 구현을 등록하면 이 기본 구현은 물러난다.
     *
     * <p>{@code @ConditionalOnMissingBean}을 {@code @Bean} 메서드에 거는 이유는 컴포넌트
     * 스캔으로 등록되는 빈에는 이 조건이 걸리지 않기 때문이다.
     */
    @Configuration
    public static class DefaultInvalidationPortConfig {

        @Bean
        @ConditionalOnMissingBean(IndicatorInvalidationPort.class)
        public IndicatorInvalidationPort noOpIndicatorInvalidationPort() {
            return new NoOpIndicatorInvalidationPort();
        }
    }
}
