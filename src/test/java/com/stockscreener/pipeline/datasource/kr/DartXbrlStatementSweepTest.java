package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StatementPeriod;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlLabels;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * <b>인스턴스로 옮긴 행이 DART 응답 행과 같은 보고서를 짓는가</b>(0콜 · {@link DartXbrlStatementRows}의 대조군). 녹화본 전체 재무제표 응답마다
 * 같은 접수번호의 인스턴스(디스크 캐시)를 같은 매퍼에 태워 보고서 금액 칸을 견준다 — 칸마다 같음 · 응답만 · 인스턴스만 · 다름을 센다.
 *
 * <pre>
 * DART_REPLAY_DIR=.dartcache KR_XBRL_DIR=~/.stock-screener/kr-xbrl DART_SWEEP_OUT=&lt;tsv&gt; ./gradlew test --tests "*DartXbrlStatementSweepTest*"
 * </pre>
 */
class DartXbrlStatementSweepTest {

    private static final Pattern FILE = Pattern.compile(
            "fnlttSinglAcntAll\\.json_bsns_year=(\\d{4})_corp_code=(\\d+)_fs_div=(CFS|OFS)_reprt_code=(\\d+)");

    @Test
    @DisplayName("녹화본 응답과 같은 접수번호의 인스턴스가 같은 보고서를 짓는다 — 칸마다 센다")
    void compare() throws Exception {
        String replay = System.getenv("DART_REPLAY_DIR");
        String xbrl = System.getenv("KR_XBRL_DIR");
        String outPath = System.getenv("DART_SWEEP_OUT");
        Assumptions.assumeTrue(replay != null && xbrl != null && outPath != null, "DART_REPLAY_DIR · KR_XBRL_DIR · DART_SWEEP_OUT이 없어 건너뛴다");
        DataSourceProperties properties = new DataSourceProperties();
        properties.getDart().setBaseUrl("https://opendart.fss.or.kr/api");
        properties.getDart().setApiKey("replay");
        properties.getDart().setConnectTimeout(Duration.ofSeconds(10));
        properties.getDart().setReadTimeout(Duration.ofSeconds(60));
        DartRecordedHttp http = new DartRecordedHttp(Path.of(replay), DartRecordedHttp.Mode.REPLAY);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("dart", Integer.MAX_VALUE / 2, 0);
        DartDailyQuota quota = new DartDailyQuota(0, Clock.systemDefaultZone());
        DartFullStatementAdapter full = new DartFullStatementAdapter(http, properties, limiter, quota,
                new DartCorpCodeCache(http, properties, limiter, quota));
        ObjectMapper json = new ObjectMapper();
        Map<String, int[]> tally = new TreeMap<>();
        int[] files = new int[6];
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(outPath), StandardCharsets.UTF_8));
             Stream<Path> paths = Files.walk(Path.of(replay))) {
            out.println("corp\tyear\treprt\tfs\tcolumn\tkind\tdart\tinstance");
            for (Path p : (Iterable<Path>) paths.filter(Files::isRegularFile).sorted()::iterator) {
                Matcher m = FILE.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                JsonNode body = json.readTree(Files.readString(p));
                if (!"000".equals(body.path("status").asString(""))) {
                    continue;
                }
                JsonNode list = body.path("list");
                int year = Integer.parseInt(m.group(1));
                String corp = m.group(2);
                boolean cfs = "CFS".equals(m.group(3));
                String reprt = m.group(4);
                StatementPeriod period = switch (reprt) {
                    case "11013" -> StatementPeriod.Q1;
                    case "11012" -> StatementPeriod.Q2;
                    case "11014" -> StatementPeriod.Q3;
                    default -> StatementPeriod.FY;
                };
                String rcept = list.get(0).path("rcept_no").asString("");
                Path instance = Path.of(xbrl, corp, rcept + ".xbrl.gz");
                files[0]++;
                if (!Files.exists(instance)) {
                    files[1]++;
                    continue;
                }
                DartXbrlFactReader.Statements facts;
                try (InputStream in = new GZIPInputStream(Files.newInputStream(instance))) {
                    facts = DartXbrlFactReader.readStatements(in, corp + " " + rcept);
                }
                Path labelFile = Path.of(xbrl, corp, rcept + ".lab-ko.xml.gz");
                java.util.Map<String, String> labels = java.util.Map.of();
                if (Files.exists(labelFile)) {
                    files[5]++;
                    try (InputStream in = new GZIPInputStream(Files.newInputStream(labelFile))) {
                        labels = DartXbrlLabels.entityLabels(in.readAllBytes(), rcept);
                    }
                }
                DartXbrlStatementRows.Built built = DartXbrlStatementRows.rows(facts.facts(), year, period, rcept, reprt,
                        DartXbrlStatementRows.Face.of(list), labels);
                if (built == null || built.consolidated() != cfs) {
                    files[built == null ? 2 : 3]++;
                    continue;
                }
                files[4]++;
                SecurityRef security = new SecurityRef(0L, "KOSPI", corp);
                RawFinancialReport a = full.toReport(list, year, period, security, false, cfs);
                RawFinancialReport b = full.instanceReport(built, year, period, security, false);
                for (RecordComponent c : RawFinancialReport.class.getRecordComponents()) {
                    if (c.getType() != BigDecimal.class) {
                        continue;
                    }
                    BigDecimal x = (BigDecimal) c.getAccessor().invoke(a);
                    BigDecimal y = (BigDecimal) c.getAccessor().invoke(b);
                    String kind = x == null && y == null ? "none" : x == null ? "instance-only" : y == null ? "dart-only"
                            : x.compareTo(y) == 0 ? "same" : "differ";
                    int[] t = tally.computeIfAbsent(c.getName(), k -> new int[5]);
                    t[switch (kind) { case "none" -> 0; case "same" -> 1; case "dart-only" -> 2; case "instance-only" -> 3; default -> 4; }]++;
                    if (!kind.equals("none") && !kind.equals("same")) {
                        out.println(String.join("\t", corp, String.valueOf(year), reprt, m.group(3), c.getName(), kind,
                                String.valueOf(x), String.valueOf(y)));
                    }
                }
            }
        }
        System.out.printf("files=%d noInstance=%d noRows=%d basisMismatch=%d compared=%d withLabels=%d%n", files[0], files[1], files[2],
                files[3], files[4], files[5]);
        System.out.println("column\tnone\tsame\tdart-only\tinstance-only\tdiffer");
        tally.forEach((k, t) -> System.out.printf("%s\t%d\t%d\t%d\t%d\t%d%n", k, t[0], t[1], t[2], t[3], t[4]));
    }

    /**
     * <b>드라이런</b> — 저장 이른 판(운영에서 읽기만 해 TSV로 뽑은 행)을 잡과 같은 경로로 짓는다: 녹화본 재생 위에서 {@code fetchStatements}를 돌리고
     * 이른 판 공급자만 TSV의 접수번호로 바꾼다(종목 단위 게이트 증거 · 부재 판정 · 중단영업 판정이 운영과 같다). 칸마다 채움 · 같음 · 다름 · 지움을 센다
     * — 지움은 쓰기기가 판정된 부재로 지우는 칸이다.
     *
     * <pre>
     * FROZEN_TSV=&lt;tsv&gt; DART_REPLAY_DIR=.dartcache KR_XBRL_DIR=~/.stock-screener/kr-xbrl DART_SWEEP_OUT=&lt;tsv&gt; ./gradlew test --tests "*DartXbrlStatementSweepTest.frozen*"
     * </pre>
     */
    @Test
    @DisplayName("드라이런 — 저장 이른 판을 잡과 같은 경로로 지으면 칸마다 무엇이 바뀌나")
    void frozen() throws Exception {
        String rows = System.getenv("FROZEN_TSV");
        String replay = System.getenv("DART_REPLAY_DIR");
        String xbrl = System.getenv("KR_XBRL_DIR");
        String outPath = System.getenv("DART_SWEEP_OUT");
        Assumptions.assumeTrue(rows != null && replay != null && xbrl != null && outPath != null, "FROZEN_TSV 등이 없어 건너뛴다");
        List<String> lines = Files.readAllLines(Path.of(rows));
        List<String> head = List.of(lines.get(0).split("\\t", -1));
        List<String[]> frozen = lines.subList(1, lines.size()).stream().map(l -> l.split("\\t", -1)).toList();
        List<String> tickers = frozen.stream().map(v -> v[0]).distinct().toList();
        DartEarlierInstances instances = (stockId, year, reprt, latestRcept) -> {
            List<DartEarlierInstances.Instance> out = new java.util.ArrayList<>();
            for (String[] v : frozen) {
                if (v[0].equals(tickers.get((int) stockId)) && Integer.parseInt(v[2]) == year && v[4].equals(reprt)
                        && !v[5].substring(0, 8).equals(latestRcept.substring(0, Math.min(8, latestRcept.length())))) {
                    try (InputStream in = new GZIPInputStream(Files.newInputStream(Path.of(xbrl, v[1], v[5] + ".xbrl.gz")))) {
                        out.add(new DartEarlierInstances.Instance(v[5], in.readAllBytes()));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            return out;
        };
        DataSourceProperties properties = new DataSourceProperties();
        properties.getDart().setBaseUrl("https://opendart.fss.or.kr/api");
        properties.getDart().setApiKey("replay");
        properties.getDart().setConnectTimeout(Duration.ofSeconds(10));
        properties.getDart().setReadTimeout(Duration.ofSeconds(60));
        DartRecordedHttp http = new DartRecordedHttp(Path.of(replay), DartRecordedHttp.Mode.REPLAY);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("dart", Integer.MAX_VALUE / 2, 0);
        DartDailyQuota quota = new DartDailyQuota(0, Clock.systemDefaultZone());
        DartFullStatementAdapter full = new DartFullStatementAdapter(http, properties, limiter, quota,
                new DartCorpCodeCache(http, properties, limiter, quota), instances);
        Map<String, String> column = new java.util.LinkedHashMap<>();
        for (String c : List.of("revenue", "operating_income", "net_income", "total_assets", "total_equity", "total_equity_controlling",
                "total_liabilities", "current_assets", "current_liabilities", "retained_earnings", "operating_cash_flow", "cost_of_sales",
                "cash_and_equivalents", "capex", "inventories", "net_income_controlling", "income_tax_expense", "profit_before_tax",
                "discontinued_income", "discontinued_income_controlling")) {
            StringBuilder camel = new StringBuilder();
            for (String part : c.split("_")) {
                camel.append(camel.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1));
            }
            column.put(c, camel.toString());
        }
        Map<String, int[]> tally = new TreeMap<>();
        int[] seen = new int[2];
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(outPath), StandardCharsets.UTF_8))) {
            out.println("ticker\tyear\tperiod\tcolumn\tkind\tstored\tinstance");
            for (int i = 0; i < tickers.size(); i++) {
                String ticker = tickers.get(i);
                int from = frozen.stream().filter(v -> v[0].equals(ticker)).mapToInt(v -> Integer.parseInt(v[2])).min().orElseThrow();
                List<RawFinancialReport> reports;
                try {
                    reports = full.fetchStatements(new SecurityRef(i, "KOSPI", ticker), from - 1, java.time.LocalDate.now().getYear(),
                            java.util.EnumSet.allOf(StatementPeriod.class)).reports();
                } catch (Exception e) {
                    System.out.println("재생 실패 " + ticker + " " + e.getMessage());
                    seen[1] += (int) frozen.stream().filter(v -> v[0].equals(ticker)).count();
                    continue;
                }
                for (String[] v : frozen) {
                    if (!v[0].equals(ticker)) {
                        continue;
                    }
                    seen[0]++;
                    java.time.LocalDate day = java.time.LocalDate.parse(v[5].substring(0, 8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                    RawFinancialReport report = reports.stream().filter(r -> r.fiscalYear() == Integer.parseInt(v[2])
                            && r.fiscalPeriod().equals(v[3]) && r.publishedAt().equals(day)).findFirst().orElse(null);
                    if (report == null) {
                        seen[1]++;
                        continue;
                    }
                    for (Map.Entry<String, String> c : column.entrySet()) {
                        int at = head.indexOf(c.getKey());
                        String raw = at < 0 ? "" : v[at];
                        BigDecimal stored = raw.isEmpty() ? null : new BigDecimal(raw);
                        BigDecimal got = (BigDecimal) RawFinancialReport.class.getMethod(c.getValue()).invoke(report);
                        boolean cleared = got == null && stored != null && report.assertedAbsent().contains(c.getKey());
                        String kind = cleared ? "clear" : got == null ? (stored == null ? "none" : "kept") : stored == null ? "fill"
                                : stored.compareTo(got) == 0 ? "same" : "differ";
                        int[] t = tally.computeIfAbsent(c.getKey(), k -> new int[6]);
                        t[switch (kind) { case "none" -> 0; case "kept" -> 1; case "fill" -> 2; case "same" -> 3; case "differ" -> 4; default -> 5; }]++;
                        if (!kind.equals("none") && !kind.equals("same") && !kind.equals("kept")) {
                            out.println(String.join("\t", ticker, v[2], v[3], c.getKey(), kind, String.valueOf(stored), String.valueOf(got)));
                        }
                    }
                }
            }
        }
        System.out.printf("rows=%d unbuilt=%d%n", seen[0], seen[1]);
        System.out.println("column\tnone\tkept\tfill\tsame\tdiffer\tclear");
        tally.forEach((k, t) -> System.out.printf("%s\t%d\t%d\t%d\t%d\t%d\t%d%n", k, t[0], t[1], t[2], t[3], t[4], t[5]));
    }
}
