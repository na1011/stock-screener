package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.collector.identity.IdentityJudge.Ambiguous;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Rename;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Reuse;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.SplitCandidate;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Verdict;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.us.IdentityProbeSupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.TreeMap;

/**
 * 다음 신원 판정이 운영에 무엇을 쓸지 — <b>잡의 {@link UsIdentityTriage#plan}을 그대로</b> 운영 상태에 대 본다(계획
 * {@code US-PAST-LISTING-PLAN-1017.md} §1 P0 「배포 전 실측」). 운영 DB는 읽기 전용 세션이고 {@code plan}은 쓰지 않는다.
 *
 * <p>{@link UsIdentityProbeLiveTest}(R0')는 V69_1 전 상태를 닻을 손으로 지어 재현했다 — 접기 뒤에는 저장된 닻 · 등록인 · 시대를
 * 읽어야 같은 질문이 된다. 그래서 이 탐침은 잡의 읽기 경로를 쓴다.
 *
 * <p>칠 때: {@code ( set -a; . ./.env; set +a; TRIAGE_PROBE=1 ./gradlew liveTest --tests '*TriagePlanProbeLiveTest*' -PskipFrontend -i )}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "TRIAGE_PROBE", matches = ".+")
class TriagePlanProbeLiveTest {

    @Test
    void probe() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/stock_screener?options=-c%20default_transaction_read_only%3Don",
                System.getProperty("user.name"), "");
        IdentitySource source = IdentityProbeSupport.source(properties(), new ExternalApiClientFactory(),
                new TokenBucketRateLimiter("alpaca", 150, 0), new TokenBucketRateLimiter("sec-edgar", 300, 0));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("identitySource", source);
        UsIdentityTriage triage = new UsIdentityTriage(new JdbcTemplate(ds), new DataSourceTransactionManager(ds),
                beans.getBeanProvider(IdentitySource.class), new EraClosing(new JdbcTemplate(ds)));

        LocalDate asOf = LocalDate.now(ZoneId.of("America/New_York"));
        UsIdentityTriage.Plan plan = triage.plan(asOf, source);

        Map<String, Integer> kinds = new TreeMap<>();
        for (Verdict v : plan.verdicts()) {
            kinds.merge(v.getClass().getSimpleName(), 1, Integer::sum);
            System.out.println("PLAN " + v.getClass().getSimpleName() + " " + describe(v));
        }
        for (UsIdentityTriage.Split s : plan.splits()) {
            kinds.merge("Split", 1, Integer::sum);
            System.out.println("PLAN Split " + s.candidate().oldTicker() + "→" + s.candidate().newSymbol() + " " + s.switchDate()
                    + " · " + s.evidence());
        }
        for (UsIdentityTriage.EndRow e : plan.ends()) {
            kinds.merge("End", 1, Integer::sum);
            System.out.println("PLAN End " + e.ticker() + " 시대 ~" + e.end().eraTo() + " · " + e.end().evidence());
        }
        System.out.println("PLAN 기준일 " + asOf + " · 수 " + plan.counts() + " · 판정 " + kinds + " · 등록 보류 " + plan.blocked()
                + " · 목록이 실어 안 닫은 끝 " + plan.endsHeldByListing());
    }

    private static String describe(Verdict v) {
        return switch (v) {
            case Rename r -> r.oldTicker() + "→" + r.newSymbol() + " " + r.renamedOn() + " · " + r.evidence();
            case SplitCandidate c -> c.oldTicker() + "→" + c.newSymbol() + " · " + c.evidence();
            case Reuse r -> r.ticker() + " · " + r.evidence();
            case Ambiguous a -> a.symbol() + " · " + a.evidence();
        };
    }

    private static DataSourceProperties properties() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setBaseUrl("https://paper-api.alpaca.markets");
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        properties.getAlpaca().setReadTimeout(Duration.ofSeconds(60));
        properties.getSecEdgar().setApiKey(System.getenv("SEC_EDGAR_USER_AGENT"));
        properties.getSecEdgar().setReadTimeout(Duration.ofSeconds(60));
        return properties;
    }
}
