package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FinancialFetch;
import com.stockscreener.pipeline.datasource.FullStatementResult;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StatementPeriod;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.RateLimiter;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

/**
 * <b>국내 주요계정 스윕</b> — 국내 관문(재고 「7.」)의 0콜 드라이런 수단. 미국 {@code SecEdgarFactsSweepTest}에 대응한다.
 *
 * <p>두 단계다. ① <b>녹화</b>({@code DART_RECORD_DIR} · {@code liveTest} · DART 콜): 실제 두 어댑터(주요계정 {@link DartFinancialAdapter} ·
 * 전체 재무제표 {@link DartFullStatementAdapter})를 그대로 돌리며 응답을 {@link DartRecordedHttp}로 저장한다. ② <b>재생</b>({@code DART_REPLAY_DIR}
 * · 0콜): 같은 어댑터를 저장본으로 돌려 보고서 전 성분을 TSV로 찍는다 — 코드를 고친 전/후를 diff하면 재적재 없이 영향 범위가 나온다.
 *
 * <p>MUST: DART 리미터를 앱과 같은 600/분으로 둔다 — 1,000/분 이상이면 1시간 IP 차단이고 그 사이 평일 배치 사슬이 실패한다(application.yaml).
 * 경로마다 하루 한도(18,000)도 앱과 같다.
 *
 * <pre>
 * DART_RECORD_DIR=&lt;dir&gt; DART_TICKERS=&lt;한 줄에 하나&gt; DART_FROM=2019 ./gradlew liveTest --tests "*DartRecordedSweepTest*"
 * DART_REPLAY_DIR=&lt;dir&gt; DART_TICKERS=&lt;file&gt; DART_FROM=2019 DART_SWEEP_OUT=&lt;tsv&gt; KR_GATE=true|false ./gradlew test --tests "*DartRecordedSweepTest*"
 * </pre>
 */
class DartRecordedSweepTest {

    private static DataSourceProperties properties() {
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source dart = properties.getDart();
        dart.setBaseUrl("https://opendart.fss.or.kr/api");
        String key = System.getenv("DART_API_KEY");
        dart.setApiKey(key == null || key.isBlank() ? "replay" : key);
        dart.setConnectTimeout(Duration.ofSeconds(10));
        dart.setReadTimeout(Duration.ofSeconds(60));
        return properties;
    }

    private static void run(Path dir, DartRecordedHttp.Mode mode, boolean krGate, PrintWriter out) throws Exception {
        DataSourceProperties properties = properties();
        DartRecordedHttp http = new DartRecordedHttp(dir, mode);
        RateLimiter limiter = mode == DartRecordedHttp.Mode.RECORD
                ? new TokenBucketRateLimiter("dart", 600, 0)
                : new TokenBucketRateLimiter("dart", Integer.MAX_VALUE / 2, 0);
        DartDailyQuota quota = new DartDailyQuota(mode == DartRecordedHttp.Mode.RECORD ? 18_000 : 0, Clock.systemDefaultZone());
        DartCorpCodeCache corpCodes = new DartCorpCodeCache(http, properties, limiter, quota);
        DartFinancialAdapter summary = new DartFinancialAdapter(http, properties, limiter, quota, corpCodes, krGate);
        DartFullStatementAdapter full = new DartFullStatementAdapter(http, properties, limiter, quota, corpCodes, krGate);
        int from = Integer.parseInt(System.getenv().getOrDefault("DART_FROM", "2019"));
        int to = java.time.LocalDate.now().getYear();
        List<String> tickers = Files.readAllLines(Path.of(System.getenv("DART_TICKERS"))).stream()
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (out != null) {
            StringBuilder head = new StringBuilder("ticker\tsource");
            for (RecordComponent c : RawFinancialReport.class.getRecordComponents()) {
                head.append('\t').append(c.getName());
            }
            out.println(head);
        }
        int done = 0;
        for (String ticker : tickers) {
            SecurityRef security = new SecurityRef(0L, ticker.startsWith("9") ? "KOSDAQ" : "KOSPI", ticker);
            try {
                FinancialFetch fetched = summary.fetchReports(security, from);
                FullStatementResult statements = full.fetchStatements(security, from, to, EnumSet.allOf(StatementPeriod.class));
                if (out != null) {
                    print(out, ticker, "summary", fetched.reports());
                    print(out, ticker, "full", statements.reports());
                    printFlows(out, ticker, "flow-summary", fetched.flowBases());
                    printFlows(out, ticker, "flow-full", statements.flowBases());
                }
            } catch (DataSourceException e) {
                // 종목 단위로 격리하되 삼키지 않는다 — 이유를 행으로 남긴다.
                System.out.printf("[%s] %s: %s%n", mode, ticker, e.getMessage());
                if (out != null) {
                    out.println(ticker + "\tERROR\t" + e.kind() + "\t" + e.getMessage().replace('\t', ' '));
                }
            }
            if (++done % 25 == 0) {
                System.out.printf("[%s] %d/%d · 오늘 콜 %d%n", mode, done, tickers.size(), limiter.usedToday());
            }
        }
    }

    /** 손익 칸 기저 판정(국내 관문 켬 · 재고 A6) — 대체가 있는 행만 · 칸은 이름순. */
    private static void printFlows(PrintWriter out, String ticker, String source,
                                   List<com.stockscreener.pipeline.datasource.FlowBasis> bases) {
        for (com.stockscreener.pipeline.datasource.FlowBasis b : bases) {
            if (!b.superseded().isEmpty()) {
                out.println(ticker + "\t" + source + "\t" + b.fiscalYear() + "\t" + b.fiscalPeriod() + "\t" + b.publishedAt() + "\t"
                        + new java.util.TreeMap<>(b.superseded()) + (b.restated().isEmpty() ? "" : "\t" + new java.util.TreeMap<>(b.restated())));
            }
        }
    }

    private static void print(PrintWriter out, String ticker, String source, List<RawFinancialReport> reports) throws Exception {
        for (RawFinancialReport r : reports) {
            StringBuilder line = new StringBuilder(ticker).append('\t').append(source);
            for (RecordComponent c : RawFinancialReport.class.getRecordComponents()) {
                Object v = c.getAccessor().invoke(r);
                // 집합은 정렬해 찍는다 — 출력 순서가 실행마다 달라 전/후 diff가 거짓 변화를 낸다.
                String text = v == null ? "" : v instanceof java.math.BigDecimal b ? b.toPlainString()
                        : v instanceof java.util.Set<?> set ? new java.util.TreeSet<>(set.stream().map(String::valueOf).toList()).toString()
                        : v.toString();
                line.append('\t').append(text);
            }
            out.println(line);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("live")
    @DisplayName("[live][record] 편입 국내 종목의 DART 응답을 녹화한다")
    void record() throws Exception {
        String dir = System.getenv("DART_RECORD_DIR");
        Assumptions.assumeTrue(dir != null && !dir.isBlank() && System.getenv("DART_API_KEY") != null,
                "DART_RECORD_DIR · DART_API_KEY가 없어 건너뛴다");
        DartRecordedHttp.requireDirectory(Path.of(dir));
        // 녹화는 관문 켬으로 돈다 — 켬 갈래가 부르는 응답이 꺼짐의 상위집합이다(같은 엔드포인트 · 같은 보고서).
        run(Path.of(dir), DartRecordedHttp.Mode.RECORD, true, null);
    }

    @Test
    @DisplayName("[sweep] 녹화본으로 두 국내 어댑터를 돌려 보고서 전 성분을 찍는다(0콜)")
    void replay() throws Exception {
        String dir = System.getenv("DART_REPLAY_DIR");
        String outPath = System.getenv("DART_SWEEP_OUT");
        Assumptions.assumeTrue(dir != null && !dir.isBlank() && outPath != null, "DART_REPLAY_DIR · DART_SWEEP_OUT이 없어 건너뛴다");
        boolean krGate = Boolean.parseBoolean(System.getenv().getOrDefault("KR_GATE", "false"));
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(outPath), StandardCharsets.UTF_8))) {
            run(Path.of(dir), DartRecordedHttp.Mode.REPLAY, krGate, out);
        }
    }
}
