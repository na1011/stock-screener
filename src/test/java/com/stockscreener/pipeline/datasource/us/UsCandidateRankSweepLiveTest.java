package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.collector.JdbcStoredShareCounts;
import com.stockscreener.pipeline.collector.ShareClassDeriver;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.StoredShareCounts.ShareCount;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.IssuerFacts;
import com.stockscreener.pipeline.datasource.us.shareclass.MembershipHold;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassFileStore;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassLine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 후보 순위 효과 스윕 — 관문 2단계 측정 도구를 순위 후보까지(`docs/reload-gate.md` 순서 2 · 탐색 {@code CANDIDATE-FR-PLAN-1016.md} v2
 * 반증 B-2). <b>운영 DB에 안 쓴다</b> — 연결이 {@code default_transaction_read_only}라 쓰기는 거절된다.
 *
 * <ol>
 *   <li><b>받기</b>: 모집단마다 companyfacts와 앵커 후보 공시의 인스턴스를 {@link ShareClassCollector}로 받아 재료 폴더
 *       ({@link ShareClassFileStore})에 둔다 — 관문 재적재가 운영 표에 둘 것과 같은 재료다. 폴더가 남아 있으면 이어 받는다.</li>
 *   <li><b>유도</b>: {@link ShareClassDeriver}를 그 폴더 위에서 돈다(판정은 메모리).</li>
 *   <li><b>순위</b>: 같은 날 {@link UsMarketCapUniverseAdapter#rank}를 꺼짐(지금 판독기)과 켬(클래스 축 판독기 · 보류 포함)으로 두 번
 *       돈다 — 앞 편입은 지금 운영 명부다.</li>
 * </ol>
 *
 * <p><b>켬 순위는 「오늘 재무 행 위의 클래스 축」이다.</b> 재적재가 더할 새 행(오늘 행이 없는 공시)은 못 본다. 다만 재무 행이 창 안에 하나도
 * 없는 줄은 재료의 판정(그날 서 있던 판의 N_L)으로 더한다 — 재적재가 처음 행을 만드는 후보다(출처 「재료만」). 후보의 공시 보충 판
 * (ADR-0173)은 재적재가 처음 받으므로 여기서 앵커 후보에 안 든다(운영 {@code sec_filing_supplement}에 후보 행이 거의 없다).
 *
 * <p><b>대조군</b>: 재료 없는 켬 판독기의 주식수가 꺼짐 판독기와 같다(0콜). 다르면 켬 판독기 자체의 차이라 스윕 수를 읽기 전에 본다.
 *
 * <pre>
 * 작은 DB(관문 표를 넣은 {@code stock_screener_gate_…})에서 판정을 재려면 {@code CLASS_SWEEP_DB=<그 DB>}를 주고
 * {@code ops/write-session.sh exec <id> --db <그 DB> -- …}로 감싼다(쓰기 가드 · 연결 이름은 PGAPPNAME).
 * ( set -a; . ./.env; set +a; CLASS_SWEEP_DIR=… [CLASS_SWEEP_POP=all|candidates|members|none|티커파일]
 *   [CLASS_SWEEP_CF_DIR=…(티커.json) CLASS_SWEEP_INST_DIR=…(CIK_접수번호.xml) — 저장 재료로 0콜 재생] [CLASS_SWEEP_AS_OF=…]
 *   ./gradlew liveTest --tests "*UsCandidateRankSweepLiveTest*" -PskipFrontend )
 * </pre>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "CLASS_SWEEP_DIR", matches = ".+")
class UsCandidateRankSweepLiveTest {

    private static final int TOP_N = 1_000;
    /** {@code UsMarketCapUniverseAdapter.SHARES_MAX_AGE_YEARS}와 같아야 한다. */
    private static final int SHARES_MAX_AGE_YEARS = 2;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sweep(@TempDir Path emptyMaterials) throws Exception {
        Path dir = Path.of(System.getenv("CLASS_SWEEP_DIR"));
        LocalDate asOf = LocalDate.parse(env("CLASS_SWEEP_AS_OF", LocalDate.now().toString()));
        Path out = Path.of(env("CLASS_SWEEP_OUT", "build/candidate-rank-sweep.tsv"));
        String db = env("CLASS_SWEEP_DB", "stock_screener");
        // 운영 DB는 읽기 전용으로만 연다. 작은 DB(stock_screener_gate_ · 관문 표를 넣은 드라이런 재료)는 유도가 판정을 쓴다 — 읽기 전용이면
        // financial_common_income 쓰기가 거절돼 모든 줄이 유도 실패로 끝난다(2026-10-31). 작은 DB도 쓰기 가드가 켜져 있으므로
        // ops/write-session.sh exec <id> --db <작은 DB> -- 로 돌린다 — 그 래퍼가 주는 PGAPPNAME을 JDBC는 스스로 안 읽어 연결 이름으로 넘긴다.
        boolean smallDb = db.startsWith("stock_screener_gate_");
        String appName = Optional.ofNullable(System.getenv("PGAPPNAME")).filter(v -> !v.isBlank()).orElse(null);
        String params = smallDb
                ? (appName == null ? "" : "?ApplicationName=" + java.net.URLEncoder.encode(appName, java.nio.charset.StandardCharsets.UTF_8))
                : "?options=-c%20default_transaction_read_only%3Don";
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:5432/" + db + params,
                env("DB_USERNAME", System.getProperty("user.name")), "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setBaseUrl("https://paper-api.alpaca.markets");
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        properties.getAlpaca().setReadTimeout(Duration.ofSeconds(60));
        properties.getSecEdgar().setApiKey(System.getenv("SEC_EDGAR_USER_AGENT"));
        properties.getSecEdgar().setReadTimeout(Duration.ofSeconds(60));
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        SecurityNameCache names = new SecurityNameCache();
        TokenBucketRateLimiter alpacaLimiter = new TokenBucketRateLimiter("alpaca", 150, 0);
        // 운영 설정(permits-per-minute 300)과 같다 — 공표 초당 10회의 절반.
        TokenBucketRateLimiter secLimiter = new TokenBucketRateLimiter("sec-edgar", 300, 0);
        SecTickerCikCache cikCache = new SecTickerCikCache(factory, properties, secLimiter);

        ShareClassFileStore store = new ShareClassFileStore(jdbc, dir);
        Map<String, Integer> fetched = fetch(jdbc, store, dir, cikCache, factory, properties, secLimiter);
        System.out.println("SWEEP 받기 " + fetched + " 인스턴스 상태(지금 읽기 판)=" + store.factStatusCounts());
        // 판독기 판이 오른 뒤 옛 판 행이 남으면 유도가 그 공시를 인스턴스 없이 다뤄 켬이 꺼짐과 같아진다 — 대조군도 통과하므로 여기서 세운다.
        // 모집단 밖 종목(CLASS_SWEEP_POP=none · 티커 파일)의 옛 판 행이 그 자리다. 다시 받으려면 그 종목을 모집단에 넣는다.
        if (store.staleFactRows() > 0) {
            throw new IllegalStateException("옛 판독기 판의 인스턴스 행 " + store.staleFactRows() + "개가 남았다(지금 판 "
                    + ClassFactReader.READER_VERSION + ") — 그 종목을 모집단에 넣어 다시 받는다");
        }

        ShareClassDeriver.Tally tally = new ShareClassDeriver(store, new DataSourceTransactionManager(ds)).derive(UsMarket.CODE);
        System.out.printf("SWEEP 유도 줄=%d 판정=%d 숨김=%d 실패줄=%s%n", tally.lines(), tally.verdicts(), tally.hidden(),
                tally.failedLines());

        Map<Long, Stock> stocks = stocks(jdbc);
        List<String> verdictRows = new ArrayList<>();
        verdictRows.add("ticker\tfiled\taccession\tform\tkind\tstatus\tbranch\tcurrent_path\tfailure\tline_shares\tdenominator");
        store.verdictRows().forEach((stockId, rs) -> rs.forEach(r -> verdictRows.add(String.join("\t",
                stocks.get(stockId).ticker(), r.filed().toString(), r.accession(), r.form(), String.valueOf(r.kind()),
                String.valueOf(r.status()), r.branch(), String.valueOf(r.currentPath()), Optional.ofNullable(r.failure()).orElse(""),
                r.lineShares() == null ? "" : String.format(Locale.ROOT, "%.0f", r.lineShares()),
                r.denominator() == null ? "" : String.format(Locale.ROOT, "%.0f", r.denominator())))));
        Files.write(dir.resolve("verdicts.tsv"), verdictRows);

        LocalDate oldest = asOf.minusYears(SHARES_MAX_AGE_YEARS);
        StoredShareCounts offCounts = new JdbcStoredShareCounts(jdbc);
        JdbcStoredShareCounts onRows = new JdbcStoredShareCounts(jdbc, store, true);
        Map<String, ShareCount> off = byTicker(offCounts.activeByMarket(UsMarket.CODE, oldest, asOf));

        // 대조군 — 재료 없는 켬 판독기는 꺼짐과 같아야 한다.
        Map<String, ShareCount> control = byTicker(new JdbcStoredShareCounts(jdbc,
                new ShareClassFileStore(jdbc, emptyMaterials), true).activeByMarket(UsMarket.CODE, oldest, asOf));
        List<String> controlDiff = new ArrayList<>();
        new TreeSet<>(union(off.keySet(), control.keySet())).forEach(t -> {
            ShareCount a = off.get(t);
            ShareCount b = control.get(t);
            if (a == null || b == null || a.shares().compareTo(b.shares()) != 0) {
                controlDiff.add(t + "(" + (a == null ? "-" : fmt(a.shares())) + "→" + (b == null ? "-" : fmt(b.shares())) + ")");
            }
        });
        System.out.println("SWEEP 대조군 재료없는켬−꺼짐 주식수 차이=" + controlDiff.size() + " " + head(controlDiff, 30));

        Map<Long, ShareClassLine> lines = store.lines(UsMarket.CODE);
        WithMaterialOnly on = new WithMaterialOnly(onRows, lines, noRowStocks(jdbc, asOf, oldest), stocks, oldest, asOf);
        Map<String, ShareCount> onMap = byTicker(on.activeByMarket(UsMarket.CODE, oldest, asOf));

        // 앞 편입 = 기준일 직전에 열려 있던 편입(클래스 축 보류는 앞 편입에만 선다). 오늘 열린 편입으로 두면 과거 기준일 재생이
        // 그날 리밸런싱과 다른 물음에 답한다 — 관측 평면 기준일(08-31~)을 재생할 때 갈린다.
        Set<String> previous = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT s.ticker FROM universe_membership m JOIN stock s ON s.id = m.stock_id
                WHERE m.universe_code = 'US_TOP1000' AND m.effective_from < ?
                  AND (m.effective_to IS NULL OR m.effective_to >= ?)
                """, String.class, asOf, asOf));
        AlpacaUniverseAdapter listing = new AlpacaUniverseAdapter(factory, properties, alpacaLimiter, names);
        // 예탁증권 표지 판정은 운영과 같은 조회로 — 안 주면 해외 양식 후보에서 AUTH로 선다.
        DepositaryCovers covers = new SecDepositaryCovers(new SecListingFormClient(null,
                new SecFilingClient(factory, properties, secLimiter), factory, properties, secLimiter));
        UsMarketCapUniverseAdapter.Ranking rOff = new UsMarketCapUniverseAdapter(listing, offCounts, cikCache, names, factory,
                properties, alpacaLimiter, false, covers).rank(UsMarketCapUniverseAdapter.UNIVERSE_CODE, asOf, previous);
        UsMarketCapUniverseAdapter.Ranking rOn = new UsMarketCapUniverseAdapter(listing, on, cikCache, names, factory,
                properties, alpacaLimiter, true, covers).rank(UsMarketCapUniverseAdapter.UNIVERSE_CODE, asOf, previous);
        Map<String, Integer> rankOff = ranks(rOff);
        Map<String, Integer> rankOn = ranks(rOn);
        Map<String, BigDecimal> capOff = caps(rOff);
        Map<String, BigDecimal> capOn = caps(rOn);
        System.out.printf("SWEEP 꺼짐 후보=%d 문턱시총=%s 제외=%s%n", rOff.candidates(),
                fmt(rOff.deduped().get(TOP_N - 1).marketCap()), rOff.excluded());
        System.out.printf("SWEEP 켬 후보=%d 문턱시총=%s 제외=%s%n", rOn.candidates(),
                fmt(rOn.deduped().get(TOP_N - 1).marketCap()), rOn.excluded());

        Map<String, String> stateOf = new HashMap<>();
        lines.forEach((stockId, line) -> {
            Stock s = stocks.get(stockId);
            MembershipHold.State st = MembershipHold.stateAt(line.history(), asOf);
            if (s != null) {
                stateOf.put(s.ticker(), st == null ? "판정전" : st.name());
            }
        });
        Set<String> members = new LinkedHashSet<>(previous);
        Map<String, Double> imp = impliedShares(jdbc, onMap, union(top(rankOn), top(rankOff)));

        List<String> rows = new ArrayList<>();
        rows.add("ticker\tmember\tsic\tsource\toff_shares\ton_shares\ton_over_off\toff_rank\ton_rank\toff_cap\ton_cap"
                + "\toff_top\ton_top\tstate\timp_over_on");
        Set<String> all = new TreeSet<>(union(union(off.keySet(), onMap.keySet()), union(rankOff.keySet(), rankOn.keySet())));
        all.addAll(stateOf.keySet());
        Map<String, String> sicOf = new HashMap<>();
        stocks.values().forEach(s -> sicOf.put(s.ticker(), s.sic()));
        for (String t : all) {
            ShareCount a = off.get(t);
            ShareCount b = onMap.get(t);
            Double ratio = a == null || b == null || a.shares().signum() == 0 ? null
                    : b.shares().doubleValue() / a.shares().doubleValue();
            Double impRatio = imp.get(t) == null || b == null ? null : imp.get(t) / b.shares().doubleValue();
            rows.add(String.join("\t", t, members.contains(t) ? "1" : "0", Optional.ofNullable(sicOf.get(t)).orElse(""),
                    b == null ? "" : on.materialOnly(t) ? "재료만" : "행", a == null ? "" : fmt(a.shares()),
                    b == null ? "" : fmt(b.shares()), ratio == null ? "" : String.format(Locale.ROOT, "%.4f", ratio),
                    str(rankOff.get(t)), str(rankOn.get(t)), capOff.containsKey(t) ? fmt(capOff.get(t)) : "",
                    capOn.containsKey(t) ? fmt(capOn.get(t)) : "", top(rankOff, t), top(rankOn, t),
                    Optional.ofNullable(stateOf.get(t)).orElse(""),
                    impRatio == null ? "" : String.format(Locale.ROOT, "%.4f", impRatio)));
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, rows);

        List<String> entered = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (String t : new TreeSet<>(union(top(rankOff), top(rankOn)))) {
            boolean inOff = top(rankOff).contains(t);
            boolean inOn = top(rankOn).contains(t);
            String who = (members.contains(t) ? "편입" : "후보") + "," + (on.materialOnly(t) ? "재료만" : "행")
                    + "," + str(rankOff.get(t)) + "→" + str(rankOn.get(t))
                    + (stateOf.containsKey(t) ? "," + stateOf.get(t) : "");
            if (inOn && !inOff) {
                entered.add(t + "(" + who + ")");
            } else if (inOff && !inOn) {
                left.add(t + "(" + who + ")");
            }
        }
        System.out.println("SWEEP 1000위 켬−꺼짐 들어옴=" + entered.size() + " " + entered);
        System.out.println("SWEEP 1000위 꺼짐−켬 나감=" + left.size() + " " + left);

        List<String> gained = new ArrayList<>();
        int gainedNoSic = 0;
        int gainedTop = 0;
        for (String t : new TreeSet<>(onMap.keySet())) {
            if (members.contains(t) || off.containsKey(t)) {
                continue;
            }
            if (sicOf.get(t) == null) {
                gainedNoSic++;
            }
            if (top(rankOn).contains(t)) {
                gainedTop++;
            }
            gained.add(t);
        }
        System.out.printf("SWEEP 후보 주식수 꺼짐없음→켬있음=%d (SIC없음 %d · 켬 1000위 안 %d) %s%n", gained.size(), gainedNoSic,
                gainedTop, head(gained, 40));

        Map<String, Map<String, Integer>> states = new TreeMap<>();
        stateOf.forEach((t, st) -> states.computeIfAbsent(members.contains(t) ? "편입" : "후보", k -> new TreeMap<>())
                .merge(st, 1, Integer::sum));
        List<String> hiddenNearTop = new ArrayList<>();
        stateOf.forEach((t, st) -> {
            Integer r = rankOff.get(t);
            if (!"SHOWN".equals(st) && !"판정전".equals(st) && r != null && r <= TOP_N + 100) {
                hiddenNearTop.add(t + "(" + st + "," + r + ")");
            }
        });
        System.out.println("SWEEP 줄 상태(그날) " + states + " · 꺼짐 1,100위 안의 보임 아님=" + hiddenNearTop.size() + " "
                + new TreeSet<>(hiddenNearTop));

        List<String> outliers = new ArrayList<>();
        int unjudged = 0;
        for (String t : top(rankOn)) {
            ShareCount b = onMap.get(t);
            Double i = imp.get(t);
            if (i == null || b == null) {
                unjudged++;
                continue;
            }
            double x = i / b.shares().doubleValue();
            if (x >= 100 && x < 10_000) {
                outliers.add(t + " " + Math.round(x) + "배");
            }
        }
        System.out.println("SWEEP 가드 축(켬 1000위 · 순이익/EPS ÷ 주식수 ∈ [100,10000))=" + outliers.size() + " " + outliers
                + " · 판정 못 함(EPS 없음·재료만)=" + unjudged);
        System.out.println("SWEEP out=" + out.toAbsolutePath());
    }

    /** 받기 — 재료가 있으면 이어 받는다. 실패는 종목 이름으로 {@code fetch-failures.tsv}에 남긴다. */
    private static Map<String, Integer> fetch(JdbcTemplate jdbc, ShareClassFileStore store, Path dir, SecTickerCikCache cikCache,
                                              ExternalApiClientFactory factory, DataSourceProperties properties,
                                              RateLimiter secLimiter) throws Exception {
        List<Map.Entry<Long, String>> population = population(jdbc, env("CLASS_SWEEP_POP", "all"));
        Path cfDir = Optional.ofNullable(System.getenv("CLASS_SWEEP_CF_DIR")).map(Path::of).orElse(null);
        Path instDir = Optional.ofNullable(System.getenv("CLASS_SWEEP_INST_DIR")).map(Path::of).orElse(null);
        SecFilingClient client = instDir == null ? new SecFilingClient(factory, properties, secLimiter)
                : diskClient(instDir, factory, properties, secLimiter);
        ShareClassCollector collector = new ShareClassCollector(client, store);
        RestClient data = factory.create(properties.getSecEdgar(), properties.getSecEdgar().getBaseUrl(),
                SecEdgarHeaders.of(properties.getSecEdgar()));
        SecFilingClient.Backoff backoff = SecFilingClient.Backoff.standard();
        SecTickerCikCache.Snapshot sec = population.isEmpty() || cfDir != null ? null : cikCache.freshSnapshot();

        Map<String, Integer> tally = new LinkedHashMap<>();
        tally.put("대상", population.size());
        List<String> failures = new ArrayList<>();
        for (Map.Entry<Long, String> target : population) {
            long stockId = target.getKey();
            String ticker = target.getValue();
            JsonNode replay = null;
            long cik;
            if (cfDir != null) {
                Path f = cfDir.resolve(ticker + ".json");
                if (!Files.exists(f)) {
                    tally.merge("재생재료없음", 1, Integer::sum);
                    continue;
                }
                replay = MAPPER.readTree(f.toFile());
                cik = replay.path("cik").asLong();
            } else {
                Optional<Long> found = sec.findCik(ticker);
                if (found.isEmpty()) {
                    tally.merge("매핑없음", 1, Integer::sum);
                    continue;
                }
                cik = found.get();
            }
            IssuerFacts cf;
            Path saved = store.inputOf(stockId);
            if (saved != null) {
                cf = IssuerFacts.fromCompanyFacts(MAPPER.readTree(saved.toFile()));
                tally.merge("재료이어받음", 1, Integer::sum);
            } else {
                JsonNode facts;
                if (replay != null) {
                    facts = replay.path("facts");
                } else {
                    String context = "companyfacts " + ticker;
                    try {
                        ResponseEntity<String> response = SecFilingClient.fetch(secLimiter, backoff, () -> data.get()
                                        .uri("/api/xbrl/companyfacts/{cik}.json", SecTickerCikCache.padded(cik))
                                        .retrieve().toEntity(String.class),
                                received -> SecEdgarResponse.unreadableJson(received.getBody()), context);
                        facts = SecEdgarResponse.requireFacts(response.getStatusCode(), response.getBody(), context);
                    } catch (DataSourceException e) {
                        if (e.kind() == DataSourceException.Kind.AUTH || e.kind() == DataSourceException.Kind.RATE_LIMITED) {
                            throw e;
                        }
                        tally.merge(e.kind() == DataSourceException.Kind.NOT_FOUND ? "companyfacts없음" : "실패", 1, Integer::sum);
                        failures.add(ticker + "\tcompanyfacts\t" + e.kind() + "\t" + e.getMessage());
                        continue;
                    }
                }
                cf = collector.saveInput(new SecurityRef(stockId, UsMarket.CODE, ticker), cik, facts);
                tally.merge("새로받음", 1, Integer::sum);
            }
            try {
                ShareClassCollector.Outcome o = collector.fetchInstances(cik, cf, ticker);
                tally.merge("앵커후보", o.targets(), Integer::sum);
                tally.merge("인스턴스새로받음", o.fetched(), Integer::sum);
                if (!o.unresolved().isEmpty()) {
                    failures.add(ticker + "\tinstance\t" + o.unresolved() + "\t" + o.failed());
                    if (o.unresolved().contains(DataSourceException.Kind.RATE_LIMITED)) {
                        Files.write(dir.resolve("fetch-failures.tsv"), failures);
                        throw new IllegalStateException("SEC 한도에 걸렸다 — 받기를 멈춘다(재료는 폴더에 남아 이어 받는다). " + ticker);
                    }
                }
            } catch (DataSourceException e) {
                if (e.kind() == DataSourceException.Kind.AUTH) {
                    throw e;
                }
                tally.merge("실패", 1, Integer::sum);
                failures.add(ticker + "\tinstance\t" + e.kind() + "\t" + e.getMessage());
            }
        }
        Files.write(dir.resolve("fetch-failures.tsv"), failures);
        tally.put("실패목록", failures.size());
        return tally;
    }

    /** 모집단 — 후보는 순위 후보 술어 그대로({@link StockRegistrar#activeMembershipExists} · ADR-0178). */
    private static List<Map.Entry<Long, String>> population(JdbcTemplate jdbc, String pop) throws Exception {
        String base = "SELECT id, ticker FROM stock s WHERE s.market_code = 'US' AND s.is_active";
        String sql;
        Object[] args = {};
        switch (pop) {
            case "none" -> {
                return List.of();
            }
            case "all" -> sql = base;
            case "candidates" -> sql = base + " AND NOT " + StockRegistrar.activeMembershipExists("s.id");
            case "members" -> sql = base + " AND " + StockRegistrar.activeMembershipExists("s.id");
            default -> {
                sql = base + " AND s.ticker = ANY (?)";
                args = new Object[]{Files.readAllLines(Path.of(pop)).stream().map(String::trim)
                        .filter(l -> !l.isEmpty()).toArray(String[]::new)};
            }
        }
        return jdbc.query(sql + " ORDER BY s.ticker",
                (rs, n) -> Map.entry(rs.getLong("id"), rs.getString("ticker")), args);
    }

    /**
     * 저장 재료로 받기를 재생한다 — {@code <CIK>_<접수번호>.xml}이 있으면 그것이 그 공시의 인스턴스이고, 없으면 XBRL 없는 공시로 읽힌다
     * ({@code SecShareClassSweepTest}가 없는 파일을 「인스턴스 없음」으로 읽는 것과 같다).
     */
    private static SecFilingClient diskClient(Path instDir, ExternalApiClientFactory factory, DataSourceProperties properties,
                                              RateLimiter limiter) {
        return new SecFilingClient(factory, properties, limiter) {
            @Override
            public List<FolderItem> folder(long cik, String accession) throws DataSourceException {
                Path xml = instDir.resolve(cik + "_" + accession + ".xml");
                try {
                    return Files.exists(xml) ? List.of(new FolderItem(accession + ".xml", Long.toString(Files.size(xml))))
                            : List.of(new FolderItem(accession + "-index.html", ""));
                } catch (java.io.IOException e) {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED, "sweep", e.getMessage(), e);
                }
            }

            @Override
            public byte[] instance(long cik, String accession, FolderItem item) throws DataSourceException {
                try {
                    return Files.readAllBytes(instDir.resolve(cik + "_" + accession + ".xml"));
                } catch (java.io.IOException e) {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED, "sweep", e.getMessage(), e);
                }
            }
        };
    }

    record Stock(long id, String ticker, String sic, String name) {
    }

    private static Map<Long, Stock> stocks(JdbcTemplate jdbc) {
        Map<Long, Stock> out = new HashMap<>();
        jdbc.query("SELECT id, ticker, sic_code, name FROM stock WHERE market_code = 'US' AND is_active", rs -> {
            out.put(rs.getLong("id"), new Stock(rs.getLong("id"), rs.getString("ticker"), rs.getString("sic_code"),
                    rs.getString("name")));
        });
        return out;
    }

    /** 순위 판독기의 창(공표 ≤ 그날 · 기말 ≥ 2년 전) 안에 재무 행이 하나도 없는 활성 종목 — 재적재가 처음 행을 만든다. */
    private static Set<Long> noRowStocks(JdbcTemplate jdbc, LocalDate asOf, LocalDate oldest) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT s.id FROM stock s WHERE s.market_code = 'US' AND s.is_active
                  AND NOT EXISTS (SELECT 1 FROM financial_statement f
                                  WHERE f.stock_id = s.id AND f.published_at <= ? AND f.period_end >= ?)
                """, Long.class, asOf, oldest));
    }

    /**
     * 켬 판독기 + 재무 행이 없는 줄의 재료 판정. 재료 몫은 그날 서 있던 판정(공시일 순 최신)이 보임이고 N_L이 있을 때만이다 — 숨김이면
     * 판독기와 같이 아무것도 안 낸다. 주식수 = N_L × 그 공시일 뒤 그날까지의 분할(판독기와 같은 경계).
     */
    static final class WithMaterialOnly implements StoredShareCounts {

        private final StoredShareCounts delegate;
        private final Map<String, ShareCount> extra = new HashMap<>();

        WithMaterialOnly(StoredShareCounts delegate, Map<Long, ShareClassLine> lines, Set<Long> noRows, Map<Long, Stock> stocks,
                         LocalDate oldest, LocalDate asOf) {
            this.delegate = delegate;
            lines.forEach((stockId, line) -> {
                Stock s = stocks.get(stockId);
                if (s == null || !noRows.contains(stockId)) {
                    return;
                }
                ShareClassLine.Verdict v = line.standingOn(asOf);
                if (v == null || v.status() != 'O' || v.lineShares() == null || v.lineShares().signum() <= 0
                        || v.filed().isBefore(oldest)) {
                    return;
                }
                extra.put(s.ticker(), new ShareCount(s.ticker(), v.lineShares().multiply(line.factor(v.filed(), asOf)),
                        s.sic(), null, v.filed(), false, s.name(), 0L));
            });
        }

        boolean materialOnly(String ticker) {
            return extra.containsKey(ticker);
        }

        @Override
        public List<ShareCount> activeByMarket(String marketCode, LocalDate oldestPeriodEnd, LocalDate asOf) {
            List<ShareCount> out = new ArrayList<>(delegate.activeByMarket(marketCode, oldestPeriodEnd, asOf));
            Set<String> seen = new java.util.HashSet<>();
            out.forEach(c -> seen.add(c.ticker()));
            extra.values().stream().filter(c -> !seen.contains(c.ticker())).forEach(out::add);
            out.sort(java.util.Comparator.comparing(ShareCount::ticker));
            return out;
        }

        @Override
        public Map<String, ClassHold> classHolds(String marketCode, LocalDate asOf) {
            return delegate.classHolds(marketCode, asOf);
        }
    }

    /** 순위 가드의 축(순이익 ÷ EPS) — 켬 판독기가 고른 행(기말 · 공표일이 같은 행의 최신 개정)에서. */
    private static Map<String, Double> impliedShares(JdbcTemplate jdbc, Map<String, ShareCount> chosen, Set<String> tickers) {
        Map<String, Double> out = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (s.ticker, f.period_end, f.published_at) s.ticker, f.period_end, f.published_at,
                       COALESCE(f.net_income_controlling, f.net_income) / NULLIF(f.eps, 0) AS imp
                FROM financial_statement f JOIN stock s ON s.id = f.stock_id
                WHERE s.market_code = 'US' AND s.is_active AND s.ticker = ANY (?)
                ORDER BY s.ticker, f.period_end, f.published_at, f.revision_no DESC
                """, rs -> {
            ShareCount c = chosen.get(rs.getString("ticker"));
            BigDecimal imp = rs.getBigDecimal("imp");
            if (c != null && imp != null && c.periodEnd() != null
                    && c.periodEnd().equals(rs.getObject("period_end", LocalDate.class))
                    && c.publishedAt().equals(rs.getObject("published_at", LocalDate.class))) {
                out.put(c.ticker(), imp.doubleValue());
            }
        }, (Object) tickers.toArray(new String[0]));
        return out;
    }

    private static Map<String, ShareCount> byTicker(List<ShareCount> counts) {
        Map<String, ShareCount> out = new HashMap<>();
        counts.forEach(c -> out.put(c.ticker(), c));
        return out;
    }

    private static Map<String, Integer> ranks(UsMarketCapUniverseAdapter.Ranking r) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < r.deduped().size(); i++) {
            out.put(r.deduped().get(i).asset().symbol(), i + 1);
        }
        return out;
    }

    private static Map<String, BigDecimal> caps(UsMarketCapUniverseAdapter.Ranking r) {
        Map<String, BigDecimal> out = new HashMap<>();
        r.deduped().forEach(x -> out.put(x.asset().symbol(), x.marketCap()));
        return out;
    }

    private static Set<String> top(Map<String, Integer> ranks) {
        Set<String> out = new TreeSet<>();
        ranks.forEach((t, r) -> {
            if (r <= TOP_N) {
                out.add(t);
            }
        });
        return out;
    }

    private static String top(Map<String, Integer> ranks, String t) {
        Integer r = ranks.get(t);
        return r != null && r <= TOP_N ? "1" : "0";
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> head(List<String> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }

    private static String str(Integer v) {
        return v == null ? "" : v.toString();
    }

    private static String fmt(BigDecimal v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String env(String name, String fallback) {
        return Optional.ofNullable(System.getenv(name)).filter(v -> !v.isBlank()).orElse(fallback);
    }
}
