package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.IdentitySource.BarAsOf;
import com.stockscreener.pipeline.datasource.IdentitySource.EndEvent;
import com.stockscreener.pipeline.datasource.IdentitySource.EntityBar;
import com.stockscreener.pipeline.datasource.RegistrantLookup;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 행이 없는 폐지 실체를 들인다 — 손 잡 {@code us-delisted-intake}의 계획과 쓰기(탐색 {@code US-PAST-LISTING-PLAN-1017.md} D6 ·
 * 구현 계획 U3).
 *
 * <p>과거 날짜 순위의 후보는 행이다(D4). 부트스트랩(2026-08-28) 전에 피인수 · 가치 없음 제거로 사라진 실체는 행이 없어 과거 명부에서
 * 원리적으로 빠진다 — 월말마다 27 … 0(탐색 ★8). 이 잡이 그 실체를 그날의 원장 사실로 행으로 만든다.
 *
 * <h2>차례</h2>
 * <ol>
 *   <li><b>열거</b> — 시장 전체의 끝 사건(피인수 · 가치 없음 제거 · 효력일 ∈ [창 시작, 오늘]).</li>
 *   <li><b>실체</b> — 사건 기호를 효력일 전날 · 효력일로 받은 봉. 그 봉이 이미 어떤 행(열린 · 닫힌 · 앞서 들인)의 닻 봉과 같은 실체면
 *       그 행의 몫이다(끝 판정 · 접기 · 재실행).</li>
 *   <li><b>끝</b> — {@link EndJudge}(끝 판정과 같은 함수). 사건 뒤에도 거래가 이어지면 재조직이라 안 들인다(탐색 ★3).</li>
 *   <li><b>등록인</b> — {@link RegistrantLookup}(이름 · 효력일 제거 양식 후보 → 사건 전 정기 공시 표지 확인).</li>
 *   <li><b>범주</b> — 확인된 표지 줄의 거래소가 NYSE · Nasdaq이어야 한다(NGD = NYSE American은 안 들인다). 효력일 무렵 그 등록인의
 *       보통주 제거 양식 거래소와 범주가 갈리면 원장 갈림이라 안 들이고 센다(ORLA).</li>
 * </ol>
 *
 * <p><b>등록인을 못 정한 실체는 행을 안 만든다</b> — v1.1 D6-5는 「들이되 안 묶는다」였다. 그런 행은 재무가 없어 순위 후보가 원리적으로
 * 못 되고(★7 · 오늘 순위와 같은 규칙), 표지가 없으니 그날 범주도 못 정한다. 만들면 분류 못 한 행만 는다 — 사유와 함께 센다.
 *
 * <p><b>원자료는 이 잡이 안 받는다</b> — 관문의 후보 재적재가 「보존 창과 시대가 겹치는 행」을 모집단으로 받고(U6) 복원 꼬리가 시세를
 * 채운다. v1.1 D6-7의 「잡이 행 id로 직접」은 그 모집단이 닫힌 행에 안 닿던 때의 처방이었다.
 */
@Component
public class DelistedIntake {

    private static final Logger log = LoggerFactory.getLogger(DelistedIntake.class);

    /** 사건 기호의 봉을 받을 효력일 앞 길이 — 끝 판정({@link UsIdentityTriage#WINDOW_DAYS})과 같다. */
    static final int WINDOW_DAYS = UsIdentityTriage.WINDOW_DAYS;
    /** 개명을 거슬러 볼 효력일 앞 길이 — 표지를 찾는 하한과 같다. */
    static final int RENAME_LOOKBACK_DAYS = 500;
    /**
     * 시세 소스에 물을 수 있는 기호 꼴. 끝 사건에는 CVR · 에스크로 · 권리 증서가 CUSIP꼴 기호로 섞여 오고(저장 재료 1,151 중 97 ·
     * {@code 294ESC017} · {@code 021CVR014}), 그 하나가 묶음 조회 전체를 400으로 거절시킨다 — 묻지 않고 사유와 함께 센다.
     */
    static final java.util.regex.Pattern TICKER = java.util.regex.Pattern.compile("[A-Z][A-Z.]{0,6}");
    /** 개명 조회 한 번에 넣는 기호 수. */
    static final int RENAME_SYMBOLS_PER_CALL = 100;

    private final JdbcTemplate jdbc;

    public DelistedIntake(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 들일 실체 하나.
     *
     * @param venue NYSE 또는 NASDAQ — {@code stock.listing_exchange}에 그대로 쓴다
     */
    public record Intake(String symbol, String cusip, LocalDate effective, LocalDate eraTo, long cik, String venue, String name,
                         List<RegistrantLookup.Rename> renames, String evidence) {

        public String label() {
            return SecurityRef.vacatedLabel(symbol, eraTo.minusDays(1));
        }
    }

    /** 안 들인 사건 하나와 그 사유. */
    public record Skip(String symbol, LocalDate effective, String reason, String evidence) {
    }

    public record Plan(List<Intake> intakes, List<Skip> skipped) {

        public Map<String, Integer> reasons() {
            Map<String, Integer> out = new TreeMap<>();
            skipped.forEach(s -> out.merge(s.reason(), 1, Integer::sum));
            return out;
        }
    }

    /** 기존 미국 행 하나 — 닻과 그 행이 쓴 기호들. */
    private record KnownRow(long id, String anchorSymbol, LocalDate anchorDate) {
    }

    public Plan plan(LocalDate from, LocalDate asOf, IdentitySource source, RegistrantLookup registrants) throws DataSourceException {
        Map<String, List<EndEvent>> bySymbol = new LinkedHashMap<>();
        List<Skip> notTickers = new ArrayList<>();
        for (EndEvent e : source.endEvents(from, asOf)) {
            if (e.symbol() == null || !TICKER.matcher(e.symbol()).matches()) {
                notTickers.add(new Skip(e.symbol(), e.effective(), "기호 꼴 아님", e.type() + " " + e.cusip()));
                continue;
            }
            bySymbol.computeIfAbsent(e.symbol(), k -> new ArrayList<>()).add(e);
        }
        bySymbol.values().forEach(list -> list.sort(java.util.Comparator.comparing(EndEvent::effective)));
        Set<String> listedToday = new LinkedHashSet<>();
        source.listedSecurities().forEach(l -> listedToday.add(l.symbol()));

        // 사건 기호의 효력일 전날 · 효력일 봉 — 효력일끼리 묶는다(끝 판정과 같다).
        Map<LocalDate, Set<String>> byDate = new TreeMap<>();
        bySymbol.forEach((symbol, events) -> events.forEach(e -> byDate.computeIfAbsent(e.effective(), d -> new LinkedHashSet<>())
                .add(symbol)));
        Map<LocalDate, Map<String, List<EntityBar>>> eves = new HashMap<>();
        Map<LocalDate, Map<String, List<EntityBar>>> onDay = new HashMap<>();
        for (Map.Entry<LocalDate, Set<String>> request : byDate.entrySet()) {
            LocalDate e = request.getKey();
            eves.put(e, source.bars(request.getValue(), BarAsOf.anchor(e.minusDays(1)), e.minusDays(WINDOW_DAYS), asOf));
            onDay.put(e, source.bars(request.getValue(), BarAsOf.anchor(e), e.minusDays(WINDOW_DAYS), asOf));
        }
        Map<String, List<KnownRow>> rowsBySymbol = knownRows(bySymbol.keySet());
        Map<Long, List<EntityBar>> rowAnchors = anchorSeries(source, rowsBySymbol, from.minusDays(WINDOW_DAYS), asOf);
        Set<String> listedEventSymbols = new LinkedHashSet<>(bySymbol.keySet());
        listedEventSymbols.retainAll(listedToday);
        Map<String, List<EntityBar>> todayView = listedEventSymbols.isEmpty() ? Map.of()
                : source.bars(listedEventSymbols, BarAsOf.TODAY, from.minusDays(WINDOW_DAYS), asOf);

        RegistrantLookup.Session lookup = registrants.open(from, asOf);
        Map<String, List<RegistrantLookup.Rename>> renames = renames(source, bySymbol.keySet(), from.minusDays(RENAME_LOOKBACK_DAYS), asOf);

        List<Intake> intakes = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>(notTickers);
        for (Map.Entry<String, List<EndEvent>> entry : bySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<List<EntityBar>> decidedEntities = new ArrayList<>();
            for (EndEvent event : entry.getValue()) {
                LocalDate e = event.effective();
                List<EntityBar> eve = eves.get(e).getOrDefault(symbol, List.of());
                List<EntityBar> day = onDay.get(e).getOrDefault(symbol, List.of());
                List<EntityBar> entity = eve.size() >= day.size() ? eve : day;
                if (entity.isEmpty()) {
                    skipped.add(new Skip(symbol, e, "봉 없음", event.type()));
                    continue;
                }
                if (decidedEntities.stream().anyMatch(d -> IdentityJudge.matchesAcrossSymbols(d, entity))) {
                    skipped.add(new Skip(symbol, e, "같은 실체의 뒤 사건", event.type()));
                    continue;
                }
                Optional<Long> row = sameEntityRow(symbol, entity, rowsBySymbol, rowAnchors);
                if (row.isPresent()) {
                    skipped.add(new Skip(symbol, e, "행 있음", "stock " + row.get()));
                    decidedEntities.add(entity);
                    continue;
                }
                if (listedToday.contains(symbol) && IdentityJudge.matchesAcrossSymbols(entity, todayView.getOrDefault(symbol, List.of()))) {
                    skipped.add(new Skip(symbol, e, "오늘 목록에 있음", "같은 실체가 오늘 목록에 있다"));
                    decidedEntities.add(entity);
                    continue;
                }
                Optional<EndJudge.End> end = EndJudge.decide(entity, symbol,
                        List.of(new EndJudge.Candidate(event, List.of(eve, day))));
                if (end.isEmpty()) {
                    skipped.add(new Skip(symbol, e, "끝 아님", event.type() + " 뒤에도 거래가 이어진다"));
                    continue;
                }
                decidedEntities.add(entity);
                // 재사용 기호면 시세 소스 단건이 오늘 주인을 준다(★2) — 그때는 이름 후보를 안 쓴다.
                Optional<String> name = listedToday.contains(symbol) ? Optional.empty()
                        : source.asset(event.cusip() != null && !event.cusip().isBlank() ? event.cusip() : symbol)
                        .map(IdentitySource.Listed::name);
                List<RegistrantLookup.Rename> mine = renames.getOrDefault(symbol, List.of()).stream()
                        .filter(r -> r.date().isBefore(e)).toList();
                RegistrantLookup.Result found = lookup.find(symbol, e, name, mine);
                if (found.outcome() != RegistrantLookup.Outcome.BOUND) {
                    skipped.add(new Skip(symbol, e, found.outcome().name(), found.evidence()));
                    continue;
                }
                intakes.add(new Intake(symbol, event.cusip(), e, end.get().eraTo(), found.cik(), found.exchange(), found.name(),
                        mine, end.get().evidence() + " · 등록인 " + found.evidence()));
            }
        }
        log.info("폐지 실체 인입 계획. 창={}~{} 사건 기호={} 들일 것={} 안 들인 것={}", from, asOf, bySymbol.size(), intakes.size(),
                new Plan(intakes, skipped).reasons());
        return new Plan(List.copyOf(intakes), List.copyOf(skipped));
    }

    /**
     * 사건 기호들에 걸린 기호 변경 — 한 번에 묻고 새 기호로 모은다. CUSIP이 그대로인 변경도 든다(RNA → RNAM — 신원 사건의 개명은 CUSIP
     * 변경만이라 이 자리에 안 맞는다 · R-D6-0). 두 번 바뀐 실체의 앞 변경은 옛 기호가 사건 기호가 아니면 안 잡힌다 — 표지가 그보다 앞이면
     * 확인이 못 서고 「등록인 못 정함」으로 센다.
     */
    private static Map<String, List<RegistrantLookup.Rename>> renames(IdentitySource source, Set<String> symbols, LocalDate from,
                                                                      LocalDate to) throws DataSourceException {
        Map<String, List<RegistrantLookup.Rename>> out = new HashMap<>();
        List<String> all = new ArrayList<>(new java.util.TreeSet<>(symbols));
        // 기호를 URL 하나에 붙여 묻는 조회라 나눠 묻는다(사건 기호 약 1,000).
        for (int i = 0; i < all.size(); i += RENAME_SYMBOLS_PER_CALL) {
            for (IdentitySource.SymbolChange c : source.symbolChanges(all.subList(i, Math.min(all.size(), i + RENAME_SYMBOLS_PER_CALL)),
                    from, to)) {
                out.computeIfAbsent(c.to(), k -> new ArrayList<>()).add(new RegistrantLookup.Rename(c.from(), c.to(), c.date()));
            }
        }
        return out;
    }

    /** 같은 기호를 쓴 적이 있는 기존 행 중 닻 봉이 그 실체와 같은 것. */
    private static Optional<Long> sameEntityRow(String symbol, List<EntityBar> entity, Map<String, List<KnownRow>> rowsBySymbol,
                                                Map<Long, List<EntityBar>> rowAnchors) {
        for (KnownRow row : rowsBySymbol.getOrDefault(symbol, List.of())) {
            List<EntityBar> anchor = rowAnchors.getOrDefault(row.id(), List.of());
            boolean same = symbol.equals(row.anchorSymbol())
                    ? IdentityJudge.compareSameWindow(anchor, entity) == IdentityJudge.Comparison.SAME
                    : IdentityJudge.matchesAcrossSymbols(anchor, entity);
            if (same) {
                return Optional.of(row.id());
            }
        }
        return Optional.empty();
    }

    /** 기호 → 그 기호를 표지 · 닻 · 기호 시대로 쓴 미국 행(열린 · 닫힌 전부). */
    private Map<String, List<KnownRow>> knownRows(Set<String> symbols) {
        Map<String, List<KnownRow>> out = new HashMap<>();
        Map<Long, KnownRow> byId = new HashMap<>();
        jdbc.query("""
                SELECT id, ticker, alpaca_anchor_symbol, alpaca_anchor_date FROM stock
                WHERE market_code = 'US' AND alpaca_anchor_symbol IS NOT NULL
                """, rs -> {
            KnownRow row = new KnownRow(rs.getLong("id"), rs.getString("alpaca_anchor_symbol"),
                    rs.getObject("alpaca_anchor_date", LocalDate.class));
            byId.put(row.id(), row);
            String ticker = rs.getString("ticker");
            int cut = ticker.indexOf(SecurityRef.VACATED);
            for (String s : new LinkedHashSet<>(List.of(cut < 0 ? ticker : ticker.substring(0, cut), row.anchorSymbol()))) {
                if (symbols.contains(s)) {
                    out.computeIfAbsent(s, k -> new ArrayList<>()).add(row);
                }
            }
        });
        jdbc.query("SELECT stock_id, symbol FROM stock_symbol_era", rs -> {
            KnownRow row = byId.get(rs.getLong("stock_id"));
            String s = rs.getString("symbol");
            if (row != null && symbols.contains(s) && out.getOrDefault(s, List.of()).stream().noneMatch(r -> r.id() == row.id())) {
                out.computeIfAbsent(s, k -> new ArrayList<>()).add(row);
            }
        });
        return out;
    }

    /** 닻 날짜끼리 묶어 묻는다(끝 판정과 같다). */
    private static Map<Long, List<EntityBar>> anchorSeries(IdentitySource source, Map<String, List<KnownRow>> rowsBySymbol,
                                                           LocalDate from, LocalDate to) throws DataSourceException {
        Map<LocalDate, Map<String, List<Long>>> byDate = new TreeMap<>();
        Set<Long> seen = new java.util.HashSet<>();
        rowsBySymbol.values().forEach(rows -> rows.forEach(row -> {
            if (seen.add(row.id())) {
                byDate.computeIfAbsent(row.anchorDate(), d -> new LinkedHashMap<>())
                        .computeIfAbsent(row.anchorSymbol(), s -> new ArrayList<>()).add(row.id());
            }
        }));
        Map<Long, List<EntityBar>> out = new HashMap<>();
        for (Map.Entry<LocalDate, Map<String, List<Long>>> group : byDate.entrySet()) {
            Map<String, List<EntityBar>> bars = source.bars(group.getValue().keySet(), BarAsOf.anchor(group.getKey()), from, to);
            group.getValue().forEach((symbol, ids) -> ids.forEach(id -> out.put(id, bars.getOrDefault(symbol, List.of()))));
        }
        return out;
    }

    /**
     * 들일 행을 쓴다 — 한 트랜잭션. 표지는 비운 꼴({@code T~마지막 날}), 닻은 (기호, 효력일 전날), 시대 끝 · 등록인 · 거래소와 함께.
     * 효력일 앞 개명은 기호 시대로 남긴다(배당 · 기업 행위 어댑터가 그 시절 기호로 묻는다). 같은 표지가 이미 있으면 건너뛴다.
     *
     * @return 만든 행 id
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> apply(Plan plan) {
        List<Long> ids = new ArrayList<>();
        for (Intake intake : plan.intakes()) {
            Long id = jdbc.query("""
                    INSERT INTO stock (market_code, ticker, name, listing_exchange, is_active, created_at, updated_at,
                                       alpaca_anchor_symbol, alpaca_anchor_date, sec_cik, era_to)
                    VALUES ('US', ?, ?, ?, true, now(), now(), ?, ?, ?, ?)
                    ON CONFLICT (market_code, ticker) DO NOTHING
                    RETURNING id
                    """, rs -> rs.next() ? rs.getLong(1) : null,
                    intake.label(), intake.name(), intake.venue(), intake.symbol(), intake.effective().minusDays(1), intake.cik(),
                    intake.eraTo());
            if (id == null) {
                log.info("폐지 실체 행이 이미 있다 — 건너뛴다. {}", intake.label());
                continue;
            }
            for (RegistrantLookup.Rename rename : intake.renames()) {
                if (rename.to().equals(intake.symbol()) && rename.date().isBefore(intake.effective())) {
                    jdbc.update("INSERT INTO stock_symbol_era (stock_id, symbol, valid_from, valid_to) VALUES (?, ?, NULL, ?)",
                            id, rename.from(), rename.date());
                }
            }
            ids.add(id);
        }
        log.info("폐지 실체 행을 만들었다. 계획={} 만든 행={}", plan.intakes().size(), ids.size());
        return ids;
    }

}
