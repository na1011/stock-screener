package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.collector.identity.IdentityJudge.Ambiguous;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.OpenRow;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Rename;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Result;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Reuse;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.SplitCandidate;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Verdict;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.IdentitySource.BarAsOf;
import com.stockscreener.pipeline.datasource.IdentitySource.EntityBar;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.us.IdentityProbeSupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * R0' — 신원 판정 함수를 운영 모집단에 대 본다(ADR-0180 · 계획 v3.1 §4 R0). <b>운영 DB는 읽기만</b>(읽기 전용 세션) · 봉은 실시간 ·
 * SEC는 개명 짝의 포함 검사와 끊기 증거만.
 *
 * <p>운영에는 {@code V69_1}이 아직 없어 닻을 마이그레이션과 같은 식(표지 · 행이 생긴 미국 동부 날짜)으로 여기서 짓는다.
 * {@code IDENTITY_PROBE_BEFORE}(ISO 시각) 뒤에 생긴 행은 빼서 그 리밸런싱 전 상태를 재현한다.
 *
 * <p>칠 때: {@code ( set -a; . ./.env; set +a; IDENTITY_PROBE_BEFORE=2026-10-17T11:00:00Z ./gradlew liveTest --tests '*UsIdentityProbeLiveTest*' -PskipFrontend -i )}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "IDENTITY_PROBE_BEFORE", matches = ".+")
class UsIdentityProbeLiveTest {

    @Test
    void probe() throws Exception {
        Instant before = Instant.parse(System.getenv("IDENTITY_PROBE_BEFORE"));
        LocalDate asOf = LocalDate.parse(env("IDENTITY_PROBE_AS_OF", LocalDate.now().toString()));
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:5432/"
                + env("IDENTITY_PROBE_DB", "stock_screener") + "?options=-c%20default_transaction_read_only%3Don",
                env("DB_USERNAME", System.getProperty("user.name")), "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        IdentitySource source = IdentityProbeSupport.source(properties(), new ExternalApiClientFactory(),
                new TokenBucketRateLimiter("alpaca", 150, 0), new TokenBucketRateLimiter("sec-edgar", 300, 0));

        Set<Long> withFinancials = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT f.stock_id FROM financial_statement f JOIN stock s ON s.id = f.stock_id WHERE s.market_code = 'US'",
                Long.class));
        record Anchored(OpenRow row, LocalDate anchorDate) {
        }
        List<Anchored> stored = jdbc.query("""
                SELECT id, ticker, (created_at AT TIME ZONE 'America/New_York')::date AS anchor_date FROM stock
                WHERE market_code = 'US' AND created_at < ? ORDER BY id""",
                (rs, i) -> new Anchored(new OpenRow(rs.getLong("id"), rs.getString("ticker"), null,
                        withFinancials.contains(rs.getLong("id"))), rs.getObject("anchor_date", LocalDate.class)),
                java.sql.Timestamp.from(before));
        Set<String> listed = new HashSet<>();
        source.listedSecurities().forEach(l -> listed.add(l.symbol()));
        IdentitySource.RegistrantFile file = source.registrantFile();
        LocalDate from = asOf.minusDays(UsIdentityTriage.WINDOW_DAYS);

        Map<LocalDate, Map<String, List<Long>>> byDate = new TreeMap<>();
        stored.forEach(a -> byDate.computeIfAbsent(a.anchorDate(), d -> new LinkedHashMap<>())
                .computeIfAbsent(a.row().ticker(), k -> new ArrayList<>()).add(a.row().id()));
        Map<Long, List<EntityBar>> anchor = new HashMap<>();
        for (var group : byDate.entrySet()) {
            Map<String, List<EntityBar>> bars = source.bars(group.getValue().keySet(), BarAsOf.anchor(group.getKey()), from, asOf);
            group.getValue().forEach((symbol, ids) -> ids.forEach(id -> anchor.put(id, bars.getOrDefault(symbol, List.of()))));
        }
        Map<String, List<EntityBar>> today = source.bars(listed, BarAsOf.TODAY, from, asOf);
        List<OpenRow> rows = stored.stream().map(Anchored::row).toList();

        Result first = IdentityJudge.judge(rows, listed, anchor, today, Map.of(), file::cikOf);
        Map<String, List<EntityBar>> literal = new HashMap<>(source.bars(first.needsLiteral(), BarAsOf.LITERAL, from, asOf));
        first.needsLiteral().forEach(s -> literal.putIfAbsent(s, List.of()));
        Result result = IdentityJudge.judge(rows, listed, anchor, today, literal, file::cikOf);
        System.out.println("PROBE 수 " + result.counts() + " · 문자열 봉 " + first.needsLiteral().size());

        Map<String, Integer> kinds = new TreeMap<>();
        Map<Long, OpenRow> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.id(), r));
        for (Verdict v : result.verdicts()) {
            String kind = v.getClass().getSimpleName();
            if (v instanceof Ambiguous a && a.evidence().contains("등록인이 안 묶였다")) {
                kind = "Ambiguous(미결합 개명)";
            } else if (v instanceof Ambiguous a && a.evidence().contains("한쪽만")) {
                kind = "Ambiguous(한쪽만 빔)";
            }
            kinds.merge(kind, 1, Integer::sum);
            System.out.println("PROBE " + kind + " " + describe(v));
        }
        System.out.println("PROBE 판정 " + kinds);

        // 고리 1의 미결합 개명 — 등록인을 포함 검사로 갈라 「접기 29 · 끊기 5」를 재현한다(sec-cik-bind 뒤의 판정).
        Map<String, Integer> registrant = new TreeMap<>();
        for (Verdict v : result.verdicts()) {
            if (!(v instanceof Ambiguous a) || !a.evidence().contains("등록인이 안 묶였다")) {
                continue;
            }
            var cik = file.cikOf(a.symbol());
            Set<String> storedAccessions = RegistrantContainment.storedAccessions(jdbc, a.stockId());
            if (cik.isEmpty()) {
                registrant.merge("새 기호 파일에 없음", 1, Integer::sum);
                System.out.println("PROBE 등록인 " + byId.get(a.stockId()).ticker() + "→" + a.symbol() + " 파일에 없음");
                continue;
            }
            Set<String> accessions = source.registrantAccessions(cik.get());
            RegistrantContainment.Check check = RegistrantContainment.check(storedAccessions,
                    RegistrantContainment.known(jdbc, cik.get(), accessions));
            String line = byId.get(a.stockId()).ticker() + "→" + a.symbol() + " CIK " + cik.get() + " " + check.describe();
            if (check.result() == RegistrantContainment.Result.NONE) {
                LocalDate since = asOf.minusDays(UsIdentityTriage.EVIDENCE_DAYS);
                SplitEvidence.Decision d = SplitEvidence.decide(storedAccessions, accessions,
                        source.identityEvents(List.of(byId.get(a.stockId()).ticker(), a.symbol()), since, asOf), List.of(),
                        source.registrantFilings(cik.get(), since));
                line += " · 끊기 " + d.switchDate().map(Object::toString).orElse("전제 안 섬") + " · " + d.evidence();
            }
            registrant.merge(check.result().name(), 1, Integer::sum);
            System.out.println("PROBE 등록인 " + line);
        }
        System.out.println("PROBE 미결합 개명의 등록인 " + registrant);
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

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
