package com.stockscreener.pipeline.datasource.http;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 외부 소스 접근에 필요한 공통 빈.
 *
 * <p>레이트리미터를 소스별로 <b>하나씩</b> 만들어 공유한다. 같은 API 키를 쓰는 이상
 * 쿼터도 공유되므로, 어댑터마다 리미터를 만들면 각자 한도를 세어 실제 한도를 넘긴다.
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceHttpConfig {

    @Bean
    public RateLimiter dataGoKrRateLimiter(DataSourceProperties properties) {
        DataSourceProperties.Source source = properties.getDataGoKr();
        return new TokenBucketRateLimiter(
                "data-go-kr", source.getPermitsPerMinute(), source.getDailyQuota());
    }

    /**
     * Alpaca 전체를 센다. 시세 어댑터가 붙어도 <b>같은 빈을 공유한다</b> — 같은 계정이면
     * 쿼터도 같기 때문이다.
     */
    @Bean
    public RateLimiter alpacaRateLimiter(DataSourceProperties properties) {
        DataSourceProperties.Source source = properties.getAlpaca();
        return new TokenBucketRateLimiter(
                "alpaca", source.getPermitsPerMinute(), source.getDailyQuota());
    }

    @Bean
    public RateLimiter dartRateLimiter(DataSourceProperties properties) {
        DataSourceProperties.Source source = properties.getDart();
        return new TokenBucketRateLimiter(
                "dart", source.getPermitsPerMinute(), source.getDailyQuota());
    }

    /**
     * SEC EDGAR 전체를 센다. <b>호스트가 둘이어도 같은 빈을 공유한다</b> —
     * 초당 한도가 {@code data.sec.gov}와 {@code www.sec.gov}에 <b>사용자 단위로</b> 함께
     * 걸리기 때문이다. 매핑 파일만 따로 세면 그만큼 한도를 넘긴다.
     */
    @Bean
    public RateLimiter secEdgarRateLimiter(DataSourceProperties properties) {
        DataSourceProperties.Source source = properties.getSecEdgar();
        return new TokenBucketRateLimiter(
                "sec-edgar", source.getPermitsPerMinute(), source.getDailyQuota());
    }
}
