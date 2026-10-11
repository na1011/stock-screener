package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import java.util.Optional;

/**
 * 라이브 탐침의 공시 보충 설정(ADR-0173) — 어댑터 출력은 companyfacts + 공시 보충이므로, {@code SecEdgarFacts.build}로
 * 「우리 코드가 무엇을 만드는가」를 재는 탐침은 보충을 태울지 <b>명시해야</b> 돈다. 안 주면 운영과 다른 출력을 조용히 낸다.
 *
 * <ul>
 *   <li>{@code PROBE_SUPPLEMENT=live} — 운영 {@link SecFilingSupplement}를 실키로(메모리 원장 · 운영과 같은 간격)</li>
 *   <li>{@code PROBE_SUPPLEMENT=off} — companyfacts만. 운영 어댑터의 출력이 아니므로 그 출력을 판정하는 데 쓰지 않는다</li>
 * </ul>
 */
final class ProbeSupplement {

    private ProbeSupplement() {
    }

    static Optional<SecFilingSupplement> fromEnv() {
        String value = System.getenv("PROBE_SUPPLEMENT");
        if ("off".equals(value)) {
            return Optional.empty();
        }
        if (!"live".equals(value)) {
            throw new IllegalStateException("PROBE_SUPPLEMENT가 없다 — live(운영과 같은 보충을 실키로) 또는 "
                    + "off(companyfacts만 — 운영 어댑터 출력이 아니다)를 준다(ADR-0173).");
        }
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey(System.getenv("SEC_EDGAR_USER_AGENT"));
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        // 운영과 같은 간격(초당 5회).
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 300, 0);
        return Optional.of(new SecFilingSupplement(new SecFilingClient(factory, properties, limiter),
                new MemorySupplementLedger()));
    }
}
