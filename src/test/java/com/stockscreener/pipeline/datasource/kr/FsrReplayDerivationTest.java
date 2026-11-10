package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.collector.FinancialWriter;
import com.stockscreener.pipeline.collector.NoOpIndicatorInvalidationPort;
import com.stockscreener.pipeline.datasource.FullStatementResult;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StatementPeriod;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncomeDeriver;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrXbrlStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>정기 FSR을 0콜로 미리 돌린다</b> — 녹화본({@code .dartcache})을 실제 전체 재무제표 어댑터 · 실제 쓰기기({@link FinancialWriter} ·
 * {@code FILL_WITHOUT_REVISION} · {@code FullStatementCollector}와 같은 호출)로 작은 DB에 쓰고, 실제 국내 보통주 몫 유도({@link KrCommonIncomeDeriver})를
 * 돌린다. 쓰기 규칙(관리 컬럼 · 덮기 · 지우기)을 손으로 다시 짜지 않으려는 도구다 — 그 DB의 전/후를 SQL로 대조한다.
 *
 * <p>MUST: 작은 DB에만 붙는다 — 이름이 {@code stock_screener_gate_}로 시작하고 {@code _test}가 든 DB(판정 뒤 지운다). 운영 DB에 쓰면 재무가
 * 녹화본 시점으로 덮인다.
 *
 * <pre>
 * FSR_DB=stock_screener_gate_fsr_test DART_REPLAY_DIR=$PWD/.dartcache FSR_KR_GATE=true|false ./gradlew test --tests "*FsrReplayDerivationTest*" --rerun
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "FSR_DB", matches = "stock_screener_gate_.*_test")
class FsrReplayDerivationTest {

    @Test
    void replayRefillThenDerive() throws Exception {
        String db = System.getenv("FSR_DB");
        boolean gate = Boolean.parseBoolean(System.getenv().getOrDefault("FSR_KR_GATE", "false"));
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:5432/" + db);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        DataSourceProperties properties = new DataSourceProperties();
        properties.getDart().setBaseUrl("https://opendart.fss.or.kr/api");
        properties.getDart().setApiKey("replay");
        properties.getDart().setConnectTimeout(Duration.ofSeconds(10));
        properties.getDart().setReadTimeout(Duration.ofSeconds(60));
        DartRecordedHttp http = new DartRecordedHttp(java.nio.file.Path.of(System.getenv("DART_REPLAY_DIR")), DartRecordedHttp.Mode.REPLAY);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("dart", Integer.MAX_VALUE / 2, 0);
        DartDailyQuota quota = new DartDailyQuota(0, Clock.systemDefaultZone());
        DartCorpCodeCache corpCodes = new DartCorpCodeCache(http, properties, limiter, quota);
        DartFullStatementAdapter full = new DartFullStatementAdapter(http, properties, limiter, quota, corpCodes);
        full.krGate(gate);
        FinancialWriter writer = new FinancialWriter(jdbc, new NoOpIndicatorInvalidationPort());

        int from = Integer.parseInt(System.getenv().getOrDefault("DART_FROM", "2019"));
        int to = java.time.LocalDate.now().getYear();
        List<SecurityRef> stocks = jdbc.query("SELECT id, market_code, ticker FROM stock WHERE market_code IN ('KOSPI', 'KOSDAQ') ORDER BY id",
                (rs, i) -> new SecurityRef(rs.getLong("id"), rs.getString("market_code"), rs.getString("ticker")));
        int written = 0;
        int skipped = 0;
        // FSR_SKIP_REFILL=true — 이미 재생한 DB에서 유도만 다시 돈다(엔진 규칙을 들일 때마다 · 0콜).
        boolean skipRefill = Boolean.parseBoolean(System.getenv().getOrDefault("FSR_SKIP_REFILL", "false"));
        for (SecurityRef s : skipRefill ? List.<SecurityRef>of() : stocks) {
            try {
                FullStatementResult r = full.fetchStatements(s, from, to, EnumSet.allOf(StatementPeriod.class));
                writer.writeDetailed(s.stockId(), r.reports(), FinancialWriter.WriteMode.FILL_WITHOUT_REVISION);
                writer.applyFlowBases(s.stockId(), r.flowBases());
                written++;
            } catch (com.stockscreener.pipeline.datasource.DataSourceException | RuntimeException e) {
                // 녹화본에 없는 종목(편출된 과거 편입) — 그 종목은 지금 행 그대로다. 사유를 남긴다.
                skipped++;
                System.out.println("[fsr-replay] 건너뜀 " + s.ticker() + ": " + e.getMessage());
            }
        }
        KrCommonIncomeDeriver deriver = new KrCommonIncomeDeriver(jdbc, new KrXbrlStore(jdbc), new DataSourceTransactionManager(ds));
        deriver.krGate(gate);
        KrCommonIncomeDeriver.Derived derived = deriver.derive();
        System.out.printf("[fsr-replay] 관문=%s 쓴 종목 %d · 건너뜀 %d · 유도 %s%n", gate, written, skipped, derived);
        assertThat(skipRefill || written > 0).isTrue();
    }
}
