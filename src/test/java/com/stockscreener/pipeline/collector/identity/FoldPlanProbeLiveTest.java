package com.stockscreener.pipeline.collector.identity;

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
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * 운영 접기 계획(ADR-0180) — {@code stock-fold --apply=false}와 같은 계획 함수를 <b>읽기 전용 연결</b>로 부른다. 잡으로 치면 실행 행 한 줄을
 * 운영에 쓰므로, 승인 요청에 실을 수(짝 · 판정 · 표마다 옮길·버릴·멈출 행)는 이 탐침이 낸다.
 *
 * <p>칠 때: {@code ( set -a; . ./.env; set +a; FOLD_PROBE_SINCE=2026-10-17T19:59:00+09:00 ./gradlew liveTest --tests '*FoldPlanProbeLiveTest*' -PskipFrontend -i )}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FOLD_PROBE_SINCE", matches = ".+")
class FoldPlanProbeLiveTest {

    @Test
    void plan() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/stock_screener?options=-c%20default_transaction_read_only%3Don",
                System.getProperty("user.name"), ""));
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setBaseUrl("https://paper-api.alpaca.markets");
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        properties.getAlpaca().setReadTimeout(Duration.ofSeconds(60));
        properties.getSecEdgar().setApiKey(System.getenv("SEC_EDGAR_USER_AGENT"));
        properties.getSecEdgar().setReadTimeout(Duration.ofSeconds(60));
        IdentitySource source = IdentityProbeSupport.source(properties, new ExternalApiClientFactory(),
                new TokenBucketRateLimiter("alpaca", 150, 0), new TokenBucketRateLimiter("sec-edgar", 300, 0));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("identity", source);
        // 계획은 읽기만 한다 — 판 재정렬·무효화는 apply 갈래뿐이라 null이다.
        StockFold fold = new StockFold(jdbc, null, null, new com.stockscreener.pipeline.collector.PriceObservationWindow(jdbc));
        FoldPlanner planner = new FoldPlanner(jdbc, beans.getBeanProvider(IdentitySource.class), fold);

        FoldPlanner.Result result = planner.plan(java.time.OffsetDateTime.parse(System.getenv("FOLD_PROBE_SINCE")).toInstant(),
                LocalDate.parse(System.getenv().getOrDefault("FOLD_PROBE_AS_OF", LocalDate.now().toString())));
        Map<String, StockFold.Tally> totals = new TreeMap<>();
        Map<String, Integer> kinds = new TreeMap<>();
        for (FoldPlanner.Pair pair : result.pairs()) {
            kinds.merge(pair.kind().name(), 1, Integer::sum);
            System.out.println("FOLD " + pair.kind() + " " + pair.targetTicker() + "(" + pair.target() + ") ← " + pair.sourceTicker()
                    + "(" + pair.source() + ") " + (pair.switchDate() == null ? "" : "교체일 " + pair.switchDate() + " ") + "· " + pair.evidence());
            if (pair.plan() != null && pair.kind() == FoldPlanner.Kind.FOLD) {
                pair.plan().tables().forEach((t, x) -> totals.merge(t, x, (a, b) -> new StockFold.Tally(a.moved() + b.moved(),
                        a.dropped() + b.dropped(), a.conflicting() + b.conflicting(), a.droppedDiffering() + b.droppedDiffering())));
            }
        }
        System.out.println("FOLD 판정 " + kinds + " · 짝 없는 새 행 " + result.unmatched().size() + " · 규칙 없는 자식 표 " + result.uncoveredTables());
        totals.forEach((t, x) -> System.out.println("FOLD 합 " + t + " 옮김 " + x.moved() + " · 버림 " + x.dropped()
                + "(값 다름 " + x.droppedDiffering() + ") · 멈춤 " + x.conflicting()));
    }
}
