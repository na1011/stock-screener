package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import java.lang.reflect.Proxy;

/** rebut16 변이 — 범위 형을 범위 밖에서 구현하는 네 꼴(어느 것도 주입 배선은 안 했다 · 성질 시험만 묻는다). */
final class R16Evasions {
    static final boolean FLAG = Boolean.getBoolean("r16");
    /** PM3: 캐스트한 람다. */
    static final FilingSupplementer CAST = (FilingSupplementer) (cik, facts, from, ctx) -> SecFilingSupplement.Outcome.none();
    /** PM4: 조건식 안의 람다. */
    static final FilingSupplementer COND = FLAG ? FilingSupplementer.NONE : (cik, facts, from, ctx) -> SecFilingSupplement.Outcome.none();
    /** PM5: 동적 프록시. */
    static final Object PROXY = Proxy.newProxyInstance(R16Evasions.class.getClassLoader(),
            new Class<?>[] {SupplementLedger.class}, (p, m, a) -> null);

    /** PM2: 생성자 인자로 넘긴 람다. */
    static SecEdgarFinancialAdapter adapter(ExternalApiClientFactory f, DataSourceProperties p, RateLimiter l,
                                            SecTickerCikCache c, String url) {
        return new SecEdgarFinancialAdapter(f, p, l, c, url, false, (cik, facts, from, ctx) -> SecFilingSupplement.Outcome.none());
    }

    private R16Evasions() {
    }
}
