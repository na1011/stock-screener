package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 탐침용 신원 판정 소스 조립(ADR-0180) — 스프링 문맥 없이 운영과 같은 어댑터를 짓는다. 기본 URL은 운영 설정 그대로다.
 */
public final class IdentityProbeSupport {

    private IdentityProbeSupport() {
    }

    public static IdentitySource source(DataSourceProperties properties, ExternalApiClientFactory factory,
                                        RateLimiter alpaca, RateLimiter sec) {
        SecTickerCikCache cikCache = new SecTickerCikCache(factory, properties, sec);
        SecEdgarFinancialAdapter facts = new SecEdgarFinancialAdapter(factory, properties, sec, cikCache,
                properties.getSecEdgar().getBaseUrl());
        SecFilingClient filings = new SecFilingClient(factory, properties, sec);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("facts", facts);
        beans.addBean("filings", filings);
        return new AlpacaSecIdentityAdapter(factory, properties, alpaca,
                new AlpacaUniverseAdapter(factory, properties, alpaca, new SecurityNameCache()),
                new AlpacaCorporateActionAdapter(factory, properties, alpaca), cikCache,
                beans.getBeanProvider(SecEdgarFinancialAdapter.class), beans.getBeanProvider(SecFilingClient.class));
    }
}
