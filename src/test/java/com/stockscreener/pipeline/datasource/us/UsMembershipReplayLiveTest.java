package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.collector.JdbcStoredShareCounts;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.us.shareclass.IssuerFacts;
import com.stockscreener.pipeline.datasource.us.shareclass.MembershipHold;
import com.stockscreener.pipeline.datasource.us.shareclass.MembershipHold.Candidate;
import com.stockscreener.pipeline.datasource.us.shareclass.MembershipHold.State;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 클래스 축 드라이런 2 — 명부 재생(탐색 {@code SHARES-CLASS-PLAN-V5-1015.md} §4-2 · 수용 기준 9). <b>쓰기 없음.</b>
 *
 * <p>운영 {@code US_TOP1000} 관측 편입이 바뀐 날짜마다 실제 순위 경로({@link UsMarketCapUniverseAdapter#rank})를 다시
 * 돌린다 — 주식수는 운영 DB 읽기 전용, 종가·상장 목록은 Alpaca(날짜당 약 30콜), CIK는 오늘의 SEC 판. 그 순위에 클래스 축
 * 판정 이력(드라이런 1 스윕 TSV)을 얹어 나란히 낸다:
 * <ol>
 *   <li><b>운영</b> — 그날 관측 편입(DB)</li>
 *   <li><b>꺼짐</b> — 오늘 코드·오늘 목록의 앞 1,000. 운영과의 차이가 재생 도구 자체의 오차다(상장 목록이 오늘 것 ·
 *       10-02 숨은 상장 · 10-05 CIK 대표 규칙 · 그 뒤 적재된 재무)</li>
 *   <li><b>보류 없음</b> — 숨김·제외·첫 판 숨김을 순위에서 뺀 앞 1,000</li>
 *   <li><b>A</b>(v5.1 계획) — 보류된 앞 편입이 순위 없이 자리를 지키고 나머지 정원을 보이는 순위로 채운다</li>
 *   <li><b>B</b>({@link MembershipHold#select}) — 보류된 앞 편입이 마지막으로 보인 판의 N_L × 그날 종가로 순위를 다툰다</li>
 * </ol>
 * A·B는 각자 사슬이다(앞 편입 = 그 규칙의 앞 날짜 결과 · 첫 날짜는 그날 운영 명부 — 관문 날 켜질 때의 앞 편입). 보류 없음과의
 * 차이가 보류 함수가 만든 편입·편출이고 전부 「보류 · 보류 정원 · 보류 끝 · 제외」로 귀속돼야 한다.
 *
 * <p>N_L로 보이는 판의 순위 값이 바뀌는 몫(수용 기준 7)은 여기서 안 잰다 — 보이는 판의 순위 값은 꺼짐과 같다.
 *
 * <pre>
 * MEMBERSHIP_REPLAY_DB=stock_screener SHARE_CLASS_DIR=… SHARE_CLASS_TODO=a.json,b.json MEMBERSHIP_REPLAY_VERDICTS=v.tsv \
 *   ./gradlew liveTest --tests "*UsMembershipReplayLiveTest*" -PskipFrontend
 * </pre>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "MEMBERSHIP_REPLAY_DB", matches = "stock_screener.*")
class UsMembershipReplayLiveTest {

    private static final int TOP_N = 1_000;

    /** {@code UsMarketCapUniverseAdapter.SHARES_MAX_AGE_YEARS}와 같아야 한다. */
    private static final int SHARES_MAX_AGE_YEARS = 2;

    @Test
    void replay() throws Exception {
        String db = System.getenv("MEMBERSHIP_REPLAY_DB");
        Path dir = Path.of(System.getenv("SHARE_CLASS_DIR"));
        Path out = Path.of(Optional.ofNullable(System.getenv("MEMBERSHIP_REPLAY_OUT")).orElse("build/membership-replay.tsv"));
        String user = Optional.ofNullable(System.getenv("DB_USERNAME")).orElse(System.getProperty("user.name"));
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/" + db + "?readOnlyMode=always", user, ""));
        StoredShareCounts shareCounts = new JdbcStoredShareCounts(jdbc);

        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setBaseUrl("https://paper-api.alpaca.markets");
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        properties.getAlpaca().setReadTimeout(Duration.ofSeconds(60));
        properties.getSecEdgar().setApiKey(System.getenv("SEC_EDGAR_USER_AGENT"));
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        SecurityNameCache names = new SecurityNameCache();
        TokenBucketRateLimiter alpacaLimiter = new TokenBucketRateLimiter("alpaca", 150, 0);
        SecTickerCikCache cikCache = new SecTickerCikCache(factory, properties, new TokenBucketRateLimiter("sec-edgar", 300, 0));
        UsMarketCapUniverseAdapter adapter = new UsMarketCapUniverseAdapter(
                new AlpacaUniverseAdapter(factory, properties, alpacaLimiter, names),
                shareCounts, cikCache, names, factory, properties, alpacaLimiter, false);

        // 판정 이력: 스윕 TSV(티커 · 공시일 · 접수번호 · 종류 · 상태 · 갈래 · 범주 · N_L · n) → CIK. 티커 → CIK는 재료 목록(JSON)이
        // 정본이다. 앵커가 아닌 보인 판('d')의 N_L은 지금 경로의 값 — companyfacts 표지 주식수다.
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Long> tsvCik = new HashMap<>();
        for (String name : System.getenv("SHARE_CLASS_TODO").split(",")) {
            for (JsonNode x : mapper.readTree(dir.resolve(name).toFile())) {
                tsvCik.put(x.get(1).asString(), Long.parseLong(x.get(2).asString()));
            }
        }
        Map<String, IssuerFacts> facts = new HashMap<>();
        Map<Long, List<MembershipHold.Verdict>> history = new HashMap<>();
        for (String file : System.getenv("MEMBERSHIP_REPLAY_VERDICTS").split(",")) {
            for (String line : Files.readAllLines(Path.of(file))) {
                String[] p = line.split("\t", -1);
                if (p[0].equals("ticker")) {
                    continue;
                }
                Long cik = tsvCik.get(p[0]);
                if (cik == null) {
                    throw new IllegalStateException("판정 TSV의 티커에 CIK가 없다: " + p[0]);
                }
                boolean hidden = p[4].equals("X");
                Double shares = null;
                if (!hidden) {
                    if (!p[7].isEmpty()) {
                        shares = Double.parseDouble(p[7]);
                    } else {
                        IssuerFacts cf = facts.computeIfAbsent(p[0], t -> read(mapper, dir.resolve("tcf").resolve(t + ".json")));
                        shares = cf.coverShares().get(p[2]);
                    }
                }
                history.computeIfAbsent(cik, k -> new ArrayList<>()).add(new MembershipHold.Verdict(
                        LocalDate.parse(p[1]), hidden, p[6].equals("어긋남"), shares));
            }
        }
        Map<String, List<double[]>> splits = new HashMap<>();
        for (String line : Files.readAllLines(dir.resolve("splits.tsv"))) {
            String[] p = line.split("\t");
            splits.computeIfAbsent(p[0], k -> new ArrayList<>())
                    .add(new double[]{LocalDate.parse(p[1]).toEpochDay(), Double.parseDouble(p[2])});
        }

        List<LocalDate> dates = jdbc.queryForList("""
                SELECT DISTINCT effective_from FROM universe_membership
                WHERE universe_code = 'US_TOP1000' AND origin = 'OBSERVED' ORDER BY 1
                """, LocalDate.class);
        SecTickerCikCache.Snapshot sec = cikCache.freshSnapshot();

        List<String> rows = new ArrayList<>();
        rows.add("date\tticker\trank\tstate\tprod\toff\tnohold\tA\tB\tprev_A\tprev_B\tcap\tcap_last_shown\treason_A\treason_B");
        // 첫 날짜의 앞 편입은 그날 운영 명부 — 관문 날 켜질 때 앞 편입은 꺼진 상태가 만든 명부다. 빈 집합으로 두면 첫 날 숨김인
        // 편입이 전부 「앞 편입 아님」으로 빠져 보류가 원리적으로 0이 된다(첫 실행에서 그렇게 나왔다).
        Set<String> prevA = production(jdbc, dates.get(0));
        Set<String> prevB = prevA;
        for (LocalDate d : dates) {
            Set<String> prod = production(jdbc, d);
            List<UsMarketCapUniverseAdapter.Ranked> rankedRows = adapter.rank(UsMarketCapUniverseAdapter.UNIVERSE_CODE, d).deduped();
            Map<String, Double> sharesNow = new HashMap<>();
            for (StoredShareCounts.ShareCount c : shareCounts.activeByMarket("US", d.minusYears(SHARES_MAX_AGE_YEARS), d)) {
                sharesNow.put(c.ticker(), c.shares().doubleValue());
            }
            List<Candidate> ranked = new ArrayList<>();
            Map<String, Integer> rankOf = new HashMap<>();
            Map<String, Double> capOf = new HashMap<>();
            for (UsMarketCapUniverseAdapter.Ranked r : rankedRows) {
                String t = r.asset().symbol();
                ranked.add(new Candidate(t, r.marketCap().doubleValue()));
                rankOf.put(t, ranked.size());
                capOf.put(t, r.marketCap().doubleValue());
            }
            Map<String, State> state = new HashMap<>();
            Map<String, Double> lastShownCap = new HashMap<>();
            for (Candidate c : ranked) {
                Long cik = sec.findCik(c.ticker()).orElse(null);
                List<MembershipHold.Verdict> h = cik == null ? null : history.get(cik);
                State s = h == null ? null : MembershipHold.stateAt(h, d);
                state.put(c.ticker(), s);
                if (s == State.HELD) {
                    MembershipHold.Verdict v = MembershipHold.lastShown(h, d);
                    Double now = sharesNow.get(c.ticker());
                    if (v != null && v.lineShares() != null && now != null && now > 0) {
                        double factor = 1;
                        for (double[] sp : splits.getOrDefault(c.ticker(), List.of())) {
                            if (sp[0] > v.filed().toEpochDay() && sp[0] <= d.toEpochDay()) {
                                factor *= sp[1];
                            }
                        }
                        lastShownCap.put(c.ticker(), c.marketCap() / now * v.lineShares() * factor);
                    }
                }
            }
            Set<String> off = new LinkedHashSet<>();
            ranked.subList(0, TOP_N).forEach(c -> off.add(c.ticker()));
            Set<String> noHold = MembershipHold.select(ranked, Set.of(), state::get, t -> null, TOP_N).members();
            Set<String>[] a = selectA(ranked, prevA, state);
            MembershipHold.Selection b = MembershipHold.select(ranked, prevB, state::get, lastShownCap::get, TOP_N);

            Map<String, String> reasonA = attribute(a[0], a[1], noHold, prevA, state, rankOf, off);
            Map<String, String> reasonB = attribute(b.members(), b.held(), noHold, prevB, state, rankOf, off);
            Set<String> touched = new LinkedHashSet<>(reasonA.keySet());
            touched.addAll(reasonB.keySet());
            for (Candidate c : ranked) {
                State s = state.get(c.ticker());
                if ((s != null && s != State.SHOWN && rankOf.get(c.ticker()) <= TOP_N + 50)
                        || prod.contains(c.ticker()) != off.contains(c.ticker())) {
                    touched.add(c.ticker());
                }
            }
            prod.stream().filter(t -> !rankOf.containsKey(t)).forEach(touched::add);
            for (String t : touched) {
                rows.add(String.join("\t", d.toString(), t, String.valueOf(rankOf.getOrDefault(t, 0)),
                        String.valueOf(state.get(t)), flag(prod, t), flag(off, t), flag(noHold, t), flag(a[0], t),
                        flag(b.members(), t), flag(prevA, t), flag(prevB, t), fmt(capOf.get(t)), fmt(lastShownCap.get(t)),
                        reasonA.getOrDefault(t, ""), reasonB.getOrDefault(t, "")));
            }
            System.out.printf("REPLAY %s 순위=%d 운영−꺼짐=%d 꺼짐−운영=%d 보류없음−꺼짐=%d | A 보류=%d A−보류없음=%d | B 보류=%d B−보류없음=%d B−꺼짐=%d%n",
                    d, ranked.size(), diff(prod, off), diff(off, prod), diff(off, noHold),
                    a[1].size(), diff(a[0], noHold), b.held().size(), diff(b.members(), noHold), diff(b.members(), off));
            System.out.println("REPLAY " + d + " A " + group(reasonA));
            System.out.println("REPLAY " + d + " B " + group(reasonB));
            prevA = a[0];
            prevB = b.members();
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, rows);
        System.out.println("REPLAY out=" + out.toAbsolutePath() + " 판정 발행사=" + history.size());
    }

    /** v5.1 계획의 보류: 보류된 앞 편입이 순위 없이 자리를 지키고, 정원 1,000 − 보류를 보이는 순위로 채운다. */
    @SuppressWarnings("unchecked")
    private static Set<String>[] selectA(List<Candidate> ranked, Set<String> previous, Map<String, State> state) {
        Set<String> held = new LinkedHashSet<>();
        for (Candidate c : ranked) {
            if (previous.contains(c.ticker()) && state.get(c.ticker()) == State.HELD) {
                held.add(c.ticker());
            }
        }
        Set<String> members = new LinkedHashSet<>(held);
        for (Candidate c : ranked) {
            if (members.size() == TOP_N) {
                break;
            }
            State s = state.get(c.ticker());
            if (s == null || s == State.SHOWN) {
                members.add(c.ticker());
            }
        }
        return new Set[]{members, held};
    }

    /** 보류 없음과의 차이와 앞 편입 대비 이동을 갈래로 귀속한다. 어느 갈래에도 안 들면 「귀속안됨」. */
    private static Map<String, String> attribute(Set<String> on, Set<String> held, Set<String> noHold, Set<String> prev,
                                                 Map<String, State> state, Map<String, Integer> rankOf, Set<String> off) {
        Map<String, String> reason = new TreeMap<>();
        for (String t : on) {
            if (!noHold.contains(t)) {
                reason.put(t, held.contains(t) ? "보류" : "귀속안됨");
            }
        }
        for (String t : noHold) {
            if (!on.contains(t)) {
                reason.put(t, "보류정원");
            }
        }
        for (String t : prev) {
            if (!on.contains(t) && !reason.containsKey(t)) {
                State s = state.get(t);
                reason.put(t, !rankOf.containsKey(t) ? "편출:거름"
                        : s == State.CONSECUTIVE || s == State.EXPIRED ? "편출:보류끝"
                        : s == State.EXCLUDED ? "편출:제외"
                        : s == State.FIRST_HIDDEN ? "편출:첫판숨김"
                        : s == State.HELD ? "편출:보류순위밖"
                        : "편출:순위");
            }
        }
        for (String t : on) {
            if (!prev.contains(t) && !reason.containsKey(t)) {
                reason.put(t, off.contains(t) ? "편입:순위" : "편입:숨김자리");
            }
        }
        return reason;
    }

    private static Map<String, List<String>> group(Map<String, String> reason) {
        Map<String, List<String>> byReason = new TreeMap<>();
        reason.forEach((t, r) -> byReason.computeIfAbsent(r, k -> new ArrayList<>()).add(t));
        return byReason;
    }

    private static IssuerFacts read(ObjectMapper mapper, Path companyFacts) {
        return IssuerFacts.fromCompanyFacts(mapper.readTree(companyFacts.toFile()));
    }

    private static Set<String> production(JdbcTemplate jdbc, LocalDate d) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT s.ticker FROM universe_membership m JOIN stock s ON s.id = m.stock_id
                WHERE m.universe_code = 'US_TOP1000' AND m.effective_from <= ? AND (m.effective_to IS NULL OR m.effective_to > ?)
                """, String.class, d, d));
    }

    private static String flag(Set<String> set, String t) {
        return set.contains(t) ? "1" : "0";
    }

    private static String fmt(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.0f", v);
    }

    private static long diff(Set<String> a, Set<String> b) {
        return a.stream().filter(t -> !b.contains(t)).count();
    }
}
