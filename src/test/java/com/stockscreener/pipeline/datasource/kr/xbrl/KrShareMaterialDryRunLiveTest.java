package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.kr.DartCorpCodeCache;
import com.stockscreener.pipeline.datasource.kr.DartDailyQuota;
import com.stockscreener.pipeline.datasource.kr.DartXbrlClient;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>주식수 표 받기 드라이런</b>(계획 K3-b · 국내 관문 켬) — 실제 받기({@link KrXbrlCollector} 켬)를 작은 DB의 의심 종목에만 돌린다. 운영이 아니다:
 * 이름이 {@code stock_screener_gate_}로 시작하고 {@code _test}가 든 DB에만 붙는다(판정 뒤 지운다).
 *
 * <pre>
 * FSR_DB=stock_screener_gate_fsr_test PROBE_TICKERS=&lt;티커 파일&gt; ./gradlew liveTest --tests "*KrShareMaterialDryRunLiveTest*"
 * </pre>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FSR_DB", matches = "stock_screener_gate_.*_test")
class KrShareMaterialDryRunLiveTest {

    @Test
    void collectsShareMaterialForSuspectStocks() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:5432/" + System.getenv("FSR_DB")));
        DataSourceProperties properties = new DataSourceProperties();
        properties.getDart().setBaseUrl("https://opendart.fss.or.kr/api");
        properties.getDart().setApiKey(System.getenv("DART_API_KEY"));
        properties.getDart().setConnectTimeout(java.time.Duration.ofSeconds(10));
        properties.getDart().setReadTimeout(java.time.Duration.ofSeconds(60));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("dart", 300, 0);
        DartXbrlClient client = new DartXbrlClient(new ExternalApiClientFactory(), properties, limiter, DartDailyQuota.unlimited());
        DartCorpCodeCache corpCodes = new DartCorpCodeCache(new ExternalApiClientFactory(), properties, limiter, DartDailyQuota.unlimited());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("client", client);
        beans.addBean("corpCodes", corpCodes);
        KrXbrlStore store = new KrXbrlStore(jdbc);
        store.krGate(true);
        KrXbrlCollector collector = new KrXbrlCollector(beans.getBeanProvider(DartXbrlClient.class), beans.getBeanProvider(DartCorpCodeCache.class),
                store, System.getenv().getOrDefault("KR_XBRL_CACHE", System.getProperty("user.home") + "/.stock-screener/kr-xbrl"));
        collector.krGate(true);
        List<String> tickers = Files.readAllLines(Path.of(System.getenv("PROBE_TICKERS"))).stream().map(String::strip).filter(t -> !t.isEmpty()).toList();
        AtomicInteger budget = new AtomicInteger(Integer.parseInt(System.getenv().getOrDefault("PROBE_BUDGET", "8000")));
        int materials = 0;
        for (KrXbrlStore.Target target : store.targets(tickers)) {
            KrXbrlCollector.Collected c = collector.collect(target, LocalDate.now(), budget);
            materials += c.materials();
            System.out.printf("[k3-dry] %s 받은 판 %d · 명부 %d · 주식수 표 %d%n", target.ticker(), c.fetched(), c.registers(), c.materials());
        }
        System.out.printf("[k3-dry] 종목 %d · 주식수 표 %d · 남은 상한 %d%n", tickers.size(), materials, budget.get());
        assertThat(materials).isPositive();
    }
}
