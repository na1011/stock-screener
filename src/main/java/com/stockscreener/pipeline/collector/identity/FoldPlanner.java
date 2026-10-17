package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.IdentitySource.BarAsOf;
import com.stockscreener.pipeline.datasource.IdentitySource.EntityBar;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 접기 잡의 짝 찾기와 짝마다의 판정(ADR-0180). 짝은 「{@code since} 뒤에 생긴 행 S의 닻 계열 = 그 전부터 있던 목록 밖 행 T의 닻 계열」이다
 * — 신원 판정의 고리 1과 같은 술어({@link IdentityJudge#matchesAcrossSymbols})다. 같은 등록인인지는 같은 포함 검사({@link
 * RegistrantContainment})로 가른다.
 *
 * <ul>
 *   <li>{@link Kind#FOLD} — T에 재무가 없거나 T의 저장 접수번호가 전부 S 기호의 오늘 등록인 것이다. 두 행을 T 하나로 접는다.</li>
 *   <li>{@link Kind#ERA} — T의 접수번호가 하나도 그 등록인 것이 아니고 끝의 증거가 있다. 두 행이 맞다(등록인 교체) — T의 시대를 닫고 S에
 *       시대 시작 · 등록인 · 물려받은 기호 시대를 단다(신원 판정의 행 끊기와 같은 결과).</li>
 *   <li>{@link Kind#STOP} — 그 밖. 무엇이 안 섰는지 적고 손대지 않는다.</li>
 * </ul>
 */
@Component
public class FoldPlanner {

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private final JdbcTemplate jdbc;
    private final ObjectProvider<IdentitySource> sources;
    private final StockFold fold;

    public FoldPlanner(JdbcTemplate jdbc, ObjectProvider<IdentitySource> sources, StockFold fold) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.fold = fold;
    }

    public enum Kind { FOLD, ERA, STOP }

    /**
     * @param switchDate {@link Kind#ERA}의 교체일
     * @param newCik     S 기호의 오늘 등록인({@link Kind#ERA}에서 S에 묶는다)
     * @param plan       {@link Kind#FOLD}의 표마다 셈
     */
    public record Pair(long target, String targetTicker, long source, String sourceTicker, Kind kind, LocalDate renamedOn,
                       LocalDate switchDate, Long newCik, StockFold.Plan plan, String evidence) {
    }

    /** @param unmatched 짝을 못 찾은 S(그대로 둔다 — 새 상장이거나 판정을 못 준다) */
    public record Result(List<Pair> pairs, List<String> unmatched, List<String> uncoveredTables) {
    }

    private record Row(long id, String ticker, String anchorSymbol, LocalDate anchorDate) {
    }

    public Result plan(Instant since, LocalDate asOf) throws DataSourceException {
        IdentitySource source = sources.getIfAvailable();
        if (source == null) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, "identity", "신원 판정 소스가 없다(Alpaca 키) — 짝을 못 찾는다.");
        }
        List<String> uncovered = fold.uncoveredChildTables();
        Set<String> listed = new HashSet<>();
        source.listedSecurities().forEach(l -> listed.add(l.symbol()));
        IdentitySource.RegistrantFile file = source.registrantFile();

        List<Row> news = rows("created_at >= ?", since);
        // 옛 행은 목록 여부와 무관하게 본다 — 개명 뒤에도 목록이 옛 기호를 활성으로 싣는다(HKIT·BDAI 2026-10-17).
        List<Row> olds = rows("created_at < ?", since);
        LocalDate from = asOf.minusDays(UsIdentityTriage.WINDOW_DAYS);
        Map<Long, List<EntityBar>> series = new HashMap<>();
        series.putAll(anchorSeries(source, news, from, asOf));
        series.putAll(anchorSeries(source, olds, from, asOf));

        Map<Long, List<Row>> matchesOfNew = new LinkedHashMap<>();
        Map<Long, Integer> matchCountOfOld = new HashMap<>();
        for (Row s : news) {
            for (Row t : olds) {
                if (IdentityJudge.matchesAcrossSymbols(series.get(t.id()), series.get(s.id()))) {
                    matchesOfNew.computeIfAbsent(s.id(), k -> new ArrayList<>()).add(t);
                    matchCountOfOld.merge(t.id(), 1, Integer::sum);
                }
            }
        }
        List<String> unmatched = new ArrayList<>();
        Map<Row, Row> pairs = new LinkedHashMap<>();
        for (Row s : news) {
            List<Row> matched = matchesOfNew.getOrDefault(s.id(), List.of());
            if (matched.size() == 1 && matchCountOfOld.get(matched.get(0).id()) == 1) {
                pairs.put(s, matched.get(0));
            } else if (!matched.isEmpty()) {
                unmatched.add(s.ticker() + "(닻 계열이 같은 옛 행 " + matched.stream().map(Row::ticker).toList()
                        + (matched.size() == 1 ? " — 그 옛 행이 다른 새 행과도 같다" : "") + ")");
            } else {
                unmatched.add(s.ticker());
            }
        }
        Set<String> needLiteral = new LinkedHashSet<>();
        pairs.keySet().forEach(s -> needLiteral.add(s.ticker()));
        Map<String, List<EntityBar>> literal = needLiteral.isEmpty() ? Map.of()
                : source.bars(needLiteral, BarAsOf.LITERAL, from, asOf);

        List<Pair> out = new ArrayList<>();
        for (Map.Entry<Row, Row> entry : pairs.entrySet()) {
            out.add(decide(source, file, entry.getValue(), entry.getKey(), series, literal, asOf, listed));
        }
        return new Result(out, unmatched, uncovered);
    }

    private Pair decide(IdentitySource source, IdentitySource.RegistrantFile file, Row t, Row s,
                        Map<Long, List<EntityBar>> series, Map<String, List<EntityBar>> literal, LocalDate asOf,
                        Set<String> listedNow) throws DataSourceException {
        int overlap = IdentityJudge.overlap(series.get(t.id()), series.get(s.id()));
        LocalDate renamedOn = IdentityJudge.renameDate(literal.getOrDefault(s.ticker(), List.of()), series.get(s.id()));
        String basis = "%s 닻 계열 = %s 닻 계열(겹침 %d) · %s 첫 봉 %s%s".formatted(t.ticker(), s.ticker(), overlap, s.ticker(), renamedOn,
                listedNow.contains(t.ticker()) ? " · 옛 기호도 목록에 활성" : "");
        if (renamedOn == null) {
            return stop(t, s, basis + " · 개명일을 못 읽는다(새 기호 문자열의 봉이 실체 계열과 안 이어진다)");
        }
        Set<String> stored = RegistrantContainment.storedAccessions(jdbc, t.id());
        if (stored.isEmpty()) {
            return foldOrStop(t, s, renamedOn, basis + " · 옛 행 재무 0행(섞일 판 없음)");
        }
        Optional<Long> cik = file.cikOf(s.ticker());
        if (cik.isEmpty()) {
            return stop(t, s, basis + " · 새 기호가 오늘 등록인 파일에 없어 옛 행 재무의 등록인을 못 가른다");
        }
        Set<String> newAccessions = source.registrantAccessions(cik.get());
        RegistrantContainment.Check check = RegistrantContainment.check(stored,
                RegistrantContainment.known(jdbc, cik.get(), newAccessions));
        String withCheck = basis + " · CIK " + cik.get() + " " + check.describe();
        return switch (check.result()) {
            case ALL, NO_STORED -> foldOrStop(t, s, renamedOn, withCheck);
            case PARTIAL -> stop(t, s, withCheck);
            case NONE -> {
                LocalDate since = asOf.minusDays(UsIdentityTriage.EVIDENCE_DAYS);
                SplitEvidence.Decision decision = SplitEvidence.decide(stored, newAccessions,
                        source.identityEvents(List.of(t.ticker(), s.ticker()), since, asOf), List.of(),
                        source.registrantFilings(cik.get(), since));
                yield decision.switchDate()
                        .map(d -> new Pair(t.id(), t.ticker(), s.id(), s.ticker(), Kind.ERA, renamedOn, d, cik.get(), null,
                                withCheck + " · " + decision.evidence()))
                        .orElseGet(() -> stop(t, s, withCheck + " · 끊기 전제 안 섬: " + decision.evidence()));
            }
        };
    }

    private Pair foldOrStop(Row t, Row s, LocalDate renamedOn, String evidence) {
        StockFold.Plan plan = fold.plan(t.id(), s.id());
        if (plan.blocked()) {
            return new Pair(t.id(), t.ticker(), s.id(), s.ticker(), Kind.STOP, renamedOn, null, null, plan,
                    evidence + " · 값이 다른 자식 행:" + plan.describe());
        }
        return new Pair(t.id(), t.ticker(), s.id(), s.ticker(), Kind.FOLD, renamedOn, null, null, plan,
                evidence + " ·" + plan.describe());
    }

    private static Pair stop(Row t, Row s, String evidence) {
        return new Pair(t.id(), t.ticker(), s.id(), s.ticker(), Kind.STOP, null, null, null, null, evidence);
    }

    private List<Row> rows(String createdPredicate, Instant since) {
        return jdbc.query("""
                SELECT id, ticker, alpaca_anchor_symbol, alpaca_anchor_date FROM stock
                WHERE market_code = 'US' AND era_to IS NULL AND alpaca_anchor_symbol IS NOT NULL AND %s ORDER BY id
                """.formatted(createdPredicate), (rs, i) -> new Row(rs.getLong("id"), rs.getString("ticker"),
                rs.getString("alpaca_anchor_symbol"), rs.getObject("alpaca_anchor_date", LocalDate.class)),
                java.sql.Timestamp.from(since));
    }

    private static Map<Long, List<EntityBar>> anchorSeries(IdentitySource source, List<Row> rows, LocalDate from, LocalDate to)
            throws DataSourceException {
        Map<LocalDate, Map<String, List<Long>>> byDate = new TreeMap<>();
        for (Row row : rows) {
            byDate.computeIfAbsent(row.anchorDate(), d -> new LinkedHashMap<>())
                    .computeIfAbsent(row.anchorSymbol(), k -> new ArrayList<>()).add(row.id());
        }
        Map<Long, List<EntityBar>> out = new HashMap<>();
        for (Map.Entry<LocalDate, Map<String, List<Long>>> group : byDate.entrySet()) {
            Map<String, List<EntityBar>> bars = source.bars(group.getValue().keySet(), BarAsOf.anchor(group.getKey()), from, to);
            group.getValue().forEach((symbol, ids) -> ids.forEach(id -> out.put(id, bars.getOrDefault(symbol, List.of()))));
        }
        return out;
    }

    /** 오늘(미국 동부) — 잡이 기준일을 안 받을 때. */
    public static LocalDate today() {
        return LocalDate.now(MARKET_ZONE);
    }
}
