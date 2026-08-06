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

    @Bean
    public RateLimiter dartRateLimiter(DataSourceProperties properties) {
        DataSourceProperties.Source source = properties.getDart();
        return new TokenBucketRateLimiter(
                "dart", source.getPermitsPerMinute(), source.getDailyQuota());
    }
}
