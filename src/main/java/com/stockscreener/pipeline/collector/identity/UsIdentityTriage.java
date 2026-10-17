package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.collector.identity.IdentityJudge.Ambiguous;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.OpenRow;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Rename;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Reuse;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.SplitCandidate;
import com.stockscreener.pipeline.collector.identity.IdentityJudge.Verdict;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.IdentitySource.BarAsOf;
import com.stockscreener.pipeline.datasource.IdentitySource.EntityBar;
import com.stockscreener.pipeline.datasource.IdentitySource.Listed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 미국 신원 판정 — 리밸런싱 앞에서 한 번(ADR-0180 · 계획 v3.1 D2). 오늘 목록과 저장된 닻을 대조해 개명 · 등록인 교체 · 기호 재사용을
 * 행에 반영하고, 판정과 근거를 {@code identity_triage}에 남긴다.
 *
 * <h2>왜 순위 앞인가</h2>
 * 순위({@code US_TOP1000})는 저장 주식수를 <b>표지</b>로 오늘 목록과 맞춘다. 개명을 그 뒤에서 반영하면 개명된 편입 종목이 그 주
 * 순위에서 빠지고, 개명된 실체가 {@code US_LISTED} 등록에서 새 행이 된다(★10 — 그 실행마다 중복 행). 그래서 지수 루프 앞에서 판정하고
 * 등록은 판정이 막은 기호를 건너뛴다.
 *
 * <h2>복원 경로는 이 판정을 안 탄다</h2>
 * {@code UniverseRestoreJob}의 쓰기 갈래도 {@code resolveOrCreate}를 부르지만, 미국 복원 명부는 <b>저장 주식수가 있는 행</b>의 순위라
 * 이미 있는 표지만 낸다 — 새 기호를 등록할 일이 원리적으로 없다(같은 순위 어댑터 · 후보 = 주식수 있는 행). 과거 날짜에 오늘 목록을
 * 대조하는 것은 뜻이 없다.
 *
 * <h2>실패</h2>
 * 재료를 못 받으면 아무것도 안 바꾸고 {@link Outcome#failed}를 낸다 — 호출부는 그 실행에서 <b>새 미국 기호를 등록하지 않는다</b>(판정
 * 없이 등록하면 개명된 실체가 새 행이 된다). 순위와 이미 있는 행의 명부는 그대로 돈다.
 */
@Component
public class UsIdentityTriage {

    private static final Logger log = LoggerFactory.getLogger(UsIdentityTriage.class);

    /** 봉 창 — 약 31세션. 고리 1의 겹침 20을 덮는다. */
    static final int WINDOW_DAYS = 45;
    /** 끝의 증거 창 — REAX 08-25 재편이 행(08-28)보다 앞이다. 교체가 행이 생기기 전 몇 달 안에 있었을 수 있다. */
    static final int EVIDENCE_DAYS = 180;
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;
    private final ObjectProvider<IdentitySource> sources;

    public UsIdentityTriage(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                            ObjectProvider<IdentitySource> sources) {
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
        this.sources = sources;
    }

    /**
     * @param blockedSymbols 이번 실행에 등록하지 않을 새 기호
     * @param blockAllNew    판정이 못 돌았다 — 새 미국 기호를 하나도 등록하지 않는다
     * @param summary        알림·로그용 한 줄
     */
    public record Outcome(Set<String> blockedSymbols, boolean blockAllNew, boolean ran, String summary,
                          Map<String, Integer> verdictCounts) {
        public static final Outcome NOT_RUN = new Outcome(Set.of(), false, false, "미국 대상 없음", Map.of());

        public boolean failed() {
            return blockAllNew;
        }
    }

    public Outcome run(long executionId, LocalDate asOf) {
        IdentitySource source = sources.getIfAvailable();
        if (source == null) {
            // 키 없는 환경(시험·개발) — 미국 순위 소스도 같은 키라 미국 명부 자체가 안 돈다.
            log.info("신원 판정 소스가 없어 건너뛴다(Alpaca 키 없음).");
            return new Outcome(Set.of(), false, false, "소스 없음", Map.of());
        }
        try {
            return judgeAndApply(executionId, asOf, source);
        } catch (DataSourceException e) {
            String why = "신원 판정 재료를 못 받았다(%s · %s): %s".formatted(e.kind(), e.sourceName(), e.getMessage());
            log.error("{} — 이번 실행은 새 미국 기호를 등록하지 않는다.", why, e);
            recordRun(executionId, "실패 — " + why);
            return new Outcome(Set.of(), true, true, why, Map.of());
        }
    }

    private Outcome judgeAndApply(long executionId, LocalDate asOf, IdentitySource source) throws DataSourceException {
        LocalDate from = asOf.minusDays(WINDOW_DAYS);
        List<Listed> listedAssets = source.listedSecurities();
        Map<String, Listed> listed = new LinkedHashMap<>();
        listedAssets.forEach(a -> listed.put(a.symbol(), a));
        IdentitySource.RegistrantFile file = source.registrantFile();
        List<StoredRow> stored = openRows();
        Map<Long, List<EntityBar>> anchorSeries = anchorSeries(source, stored, from, asOf);
        Map<String, List<EntityBar>> todaySeries = source.bars(listed.keySet(), BarAsOf.TODAY, from, asOf);
        List<OpenRow> rows = stored.stream().map(StoredRow::row).toList();

        IdentityJudge.Result result = IdentityJudge.judge(rows, listed.keySet(), anchorSeries, todaySeries, Map.of(),
                file::cikOf);
        if (!result.needsLiteral().isEmpty()) {
            Map<String, List<EntityBar>> literal = new HashMap<>(
                    source.bars(result.needsLiteral(), BarAsOf.LITERAL, from, asOf));
            result.needsLiteral().forEach(symbol -> literal.putIfAbsent(symbol, List.of()));
            result = IdentityJudge.judge(rows, listed.keySet(), anchorSeries, todaySeries, literal, file::cikOf);
        }

        // 끊기 후보는 전제 셋을 원장에 묻는다 — 서면 끊고, 안 서면 그 기호만 멈춘다.
        Map<Long, StoredRow> byId = new HashMap<>();
        stored.forEach(r -> byId.put(r.row().id(), r));
        List<Verdict> verdicts = new ArrayList<>();
        Set<String> blocked = new LinkedHashSet<>(result.blockedSymbols());
        List<Split> splits = new ArrayList<>();
        for (Verdict verdict : result.verdicts()) {
            if (verdict instanceof SplitCandidate c) {
                SplitEvidence.Decision decision = splitEvidence(source, c, byId.get(c.stockId()), asOf);
                if (decision.switchDate().isPresent()) {
                    splits.add(new Split(c, decision.switchDate().get(), c.evidence() + " · " + decision.evidence()));
                } else {
                    verdicts.add(new Ambiguous(c.stockId(), c.newSymbol(), c.evidence() + " · 끊기 전제 안 섬: " + decision.evidence()));
                    if (!c.sameSymbol()) {
                        blocked.add(c.newSymbol());
                    }
                }
            } else {
                verdicts.add(verdict);
            }
        }

        Map<String, Integer> counts = new TreeMap<>();
        transaction.executeWithoutResult(status -> apply(executionId, verdicts, splits, listed, counts));
        IdentityJudge.Counts c = result.counts();
        String summary = "행 %d · 목록 %d · 같은 실체 %d · 봉 없음 %d · 끊긴 행 %d · 주인 없는 기호 %d · 판정 %s · 등록 보류 %s"
                .formatted(c.rows(), c.listed(), c.sameEntity(), c.noBars(), c.orphans(), c.unclaimed(), counts, blocked);
        recordRun(executionId, summary);
        log.info("미국 신원 판정. {}", summary);
        return new Outcome(Set.copyOf(blocked), false, true, summary, Map.copyOf(counts));
    }

    private record StoredRow(OpenRow row, String anchorSymbol, LocalDate anchorDate) {
    }

    private record Split(SplitCandidate candidate, LocalDate switchDate, String evidence) {
    }

    /** 시대가 열린 미국 행. 닻이 없는 행(마이그레이션 뒤 등록 경로 밖에서 생긴 행)은 판정에서 빠진다 — 펜스가 센다. */
    private List<StoredRow> openRows() {
        Set<Long> withFinancials = new HashSet<>(jdbcTemplate.queryForList("""
                SELECT DISTINCT f.stock_id FROM financial_statement f JOIN stock s ON s.id = f.stock_id
                WHERE s.market_code = 'US'
                """, Long.class));
        return jdbcTemplate.query("""
                SELECT id, ticker, sec_cik, alpaca_anchor_symbol, alpaca_anchor_date FROM stock
                WHERE market_code = 'US' AND era_to IS NULL AND alpaca_anchor_symbol IS NOT NULL
                ORDER BY id
                """, (rs, i) -> new StoredRow(new OpenRow(rs.getLong("id"), rs.getString("ticker"),
                        rs.getObject("sec_cik", Long.class), withFinancials.contains(rs.getLong("id"))),
                rs.getString("alpaca_anchor_symbol"), rs.getObject("alpaca_anchor_date", LocalDate.class)));
    }

    /** 닻 날짜끼리 묶어 묻는다 — 한 요청에 한 날짜의 기호만 들어 같은 실체의 두 기호가 한 요청에 안 든다. */
    private static Map<Long, List<EntityBar>> anchorSeries(IdentitySource source, List<StoredRow> rows, LocalDate from,
                                                           LocalDate to) throws DataSourceException {
        Map<LocalDate, Map<String, List<Long>>> byDate = new TreeMap<>();
        for (StoredRow row : rows) {
            byDate.computeIfAbsent(row.anchorDate(), d -> new LinkedHashMap<>())
                    .computeIfAbsent(row.anchorSymbol(), s -> new ArrayList<>()).add(row.row().id());
        }
        Map<Long, List<EntityBar>> out = new HashMap<>();
        for (Map.Entry<LocalDate, Map<String, List<Long>>> group : byDate.entrySet()) {
            Map<String, List<EntityBar>> bars = source.bars(group.getValue().keySet(), BarAsOf.anchor(group.getKey()), from, to);
            group.getValue().forEach((symbol, ids) -> ids.forEach(id -> out.put(id, bars.getOrDefault(symbol, List.of()))));
        }
        return out;
    }

    private SplitEvidence.Decision splitEvidence(IdentitySource source, SplitCandidate c, StoredRow row, LocalDate asOf)
            throws DataSourceException {
        LocalDate since = asOf.minusDays(EVIDENCE_DAYS);
        Set<String> stored = new HashSet<>();
        for (String code : jdbcTemplate.queryForList(
                "SELECT DISTINCT report_code FROM financial_statement WHERE stock_id = ? AND report_code IS NOT NULL",
                String.class, c.stockId())) {
            stored.add(code.substring(code.indexOf(':') + 1));
        }
        Set<String> symbols = new LinkedHashSet<>(List.of(c.oldTicker(), c.newSymbol()));
        return SplitEvidence.decide(stored, source.registrantAccessions(c.newCik()),
                source.identityEvents(symbols, since, asOf),
                source.registrantFilings(row.row().secCik(), since), source.registrantFilings(c.newCik(), since));
    }

    /**
     * 판정을 행에 반영한다 — 한 트랜잭션. 표지를 먼저 다 비운 뒤 새 표지를 단다: 개명이 다른 행이 비울 기호로 갈 수 있고(재사용된
     * 기호의 옛 주인이 다른 기호로 개명) 표지는 {@code uq_stock_market_ticker}다.
     */
    private void apply(long executionId, List<Verdict> verdicts, List<Split> splits, Map<String, Listed> listed,
                       Map<String, Integer> counts) {
        LocalDate today = LocalDate.now(MARKET_ZONE);
        // 1) 표지 비우기
        for (Verdict verdict : verdicts) {
            if (verdict instanceof Reuse r) {
                jdbcTemplate.update("UPDATE stock SET ticker = ?, era_to = ?, updated_at = now() WHERE id = ?",
                        vacated(r.ticker(), r.lastBar()), r.lastBar().plusDays(1), r.stockId());
            } else if (verdict instanceof Rename r) {
                jdbcTemplate.update("UPDATE stock SET ticker = ?, updated_at = now() WHERE id = ?", "~" + r.stockId(), r.stockId());
            }
        }
        for (Split s : splits) {
            SplitCandidate c = s.candidate();
            if (c.sameSymbol()) {
                jdbcTemplate.update("UPDATE stock SET ticker = ?, era_to = ?, updated_at = now() WHERE id = ?",
                        vacated(c.oldTicker(), s.switchDate().minusDays(1)), s.switchDate(), c.stockId());
            } else {
                jdbcTemplate.update("UPDATE stock SET era_to = ?, updated_at = now() WHERE id = ?", s.switchDate(), c.stockId());
            }
        }
        // 2) 새 표지 · 새 행 · 기호 시대
        for (Verdict verdict : verdicts) {
            if (verdict instanceof Rename r) {
                Listed asset = listed.get(r.newSymbol());
                jdbcTemplate.update("""
                        UPDATE stock SET ticker = ?, name = coalesce(nullif(?, ''), name),
                               listing_exchange = coalesce(nullif(?, ''), listing_exchange), updated_at = now()
                        WHERE id = ?""", r.newSymbol(), asset.name(), asset.exchange(), r.stockId());
                insertEra(r.stockId(), r.oldTicker(), r.renamedOn());
            }
        }
        for (Split s : splits) {
            SplitCandidate c = s.candidate();
            Listed asset = listed.get(c.newSymbol());
            long created = jdbcTemplate.queryForObject("""
                    INSERT INTO stock (market_code, ticker, name, listing_exchange, is_active, created_at, updated_at,
                                       alpaca_anchor_symbol, alpaca_anchor_date, sec_cik, era_from)
                    VALUES ('US', ?, ?, ?, true, now(), now(), ?, ?, ?, ?) RETURNING id
                    """, Long.class, c.newSymbol(), asset.name().isBlank() ? c.newSymbol() : asset.name(),
                    asset.exchange(), c.newSymbol(), today, c.newCik(), s.switchDate());
            // 가격과 수정계수는 증권의 것이다 — 새 등록인 행이 앞 행의 기호 시대를 물려받는다(경계 앞 기업 행위가 새 행 원장에 실린다).
            jdbcTemplate.update("""
                    INSERT INTO stock_symbol_era (stock_id, symbol, valid_from, valid_to)
                    SELECT ?, symbol, valid_from, valid_to FROM stock_symbol_era WHERE stock_id = ?
                    """, created, c.stockId());
            if (!c.sameSymbol()) {
                insertEra(created, c.oldTicker(), c.renamedOn());
            }
            record(executionId, created, c.newSymbol(), "SPLIT", "새 행 %d(%s · CIK %d · 시대 %s~) ← 앞 행 %d(%s · 시대 ~%s) · %s"
                    .formatted(created, c.newSymbol(), c.newCik(), s.switchDate(), c.stockId(),
                            c.sameSymbol() ? vacated(c.oldTicker(), s.switchDate().minusDays(1)) : c.oldTicker(),
                            s.switchDate(), s.evidence()));
            counts.merge("SPLIT", 1, Integer::sum);
        }
        for (Verdict verdict : verdicts) {
            switch (verdict) {
                case Rename r -> record(executionId, r.stockId(), r.newSymbol(), "RENAME",
                        "%s → %s(%s부터) · %s".formatted(r.oldTicker(), r.newSymbol(), r.renamedOn(), r.evidence()));
                case Reuse r -> record(executionId, r.stockId(), r.ticker(), "REUSE",
                        "표지 %s · 시대 ~%s · %s".formatted(vacated(r.ticker(), r.lastBar()), r.lastBar().plusDays(1), r.evidence()));
                case Ambiguous a -> record(executionId, a.stockId(), a.symbol(), "AMBIGUOUS", a.evidence());
                case SplitCandidate c -> throw new IllegalStateException("끊기 후보가 판정 없이 남았다: " + c);
            }
            String key = switch (verdict) {
                case Rename r -> "RENAME";
                case Reuse r -> "REUSE";
                case Ambiguous a -> "AMBIGUOUS";
                case SplitCandidate c -> "SPLIT";
            };
            counts.merge(key, 1, Integer::sum);
        }
    }

    /** 비운 표지 — 그 기호를 그날까지 쓴 행. 기호 + 마지막 날이라 다시 비워도 겹치지 않는다(varchar 20 안 · 최장 기호 6 + 11). */
    static String vacated(String ticker, LocalDate lastDay) {
        return ticker + "~" + lastDay;
    }

    private void insertEra(long stockId, String symbol, LocalDate validTo) {
        LocalDate validFrom = jdbcTemplate.queryForObject(
                "SELECT max(valid_to) FROM stock_symbol_era WHERE stock_id = ?", LocalDate.class, stockId);
        jdbcTemplate.update("INSERT INTO stock_symbol_era (stock_id, symbol, valid_from, valid_to) VALUES (?, ?, ?, ?)",
                stockId, symbol, validFrom, validTo);
    }

    private void record(long executionId, Long stockId, String symbol, String verdict, String evidence) {
        jdbcTemplate.update("INSERT INTO identity_triage (execution_id, stock_id, symbol, verdict, evidence) VALUES (?, ?, ?, ?, ?)",
                executionId, stockId, symbol, verdict, evidence);
    }

    private void recordRun(long executionId, String summary) {
        record(executionId, null, null, "RUN", summary);
    }
}
