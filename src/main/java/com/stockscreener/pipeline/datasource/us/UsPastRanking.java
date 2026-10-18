package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.IdentitySource.BarAsOf;
import com.stockscreener.pipeline.datasource.IdentitySource.EntityBar;
import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.StoredUsRows;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.us.listing.ListedTimeline;
import com.stockscreener.pipeline.datasource.us.listing.ListingCover;
import com.stockscreener.pipeline.datasource.us.listing.ListingEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 과거 날짜의 미국 시총 순위 — 복원 격자일마다 그날 상위 N의 <b>행</b>(탐색 {@code US-PAST-LISTING-PLAN-1017.md} D4 · 구현 계획 U4).
 * 미국 관문 설정({@code stockscreener.gate.us})이 켜졌을 때 복원({@code UniverseRestoreJob})만 부른다.
 *
 * <p>오늘 순위({@link UsMarketCapUniverseAdapter#fetchConstituents})와 갈리는 셋:
 * <ul>
 *   <li><b>후보는 행이다</b> — 오늘 목록의 기호가 아니다. 그 기준일 시대 안이면 닫힌 행 · 들인 폐지 실체 행도 후보다(주식수 판독이 그
 *       기준일 시대 술어를 이미 건다). 숨은 상장(ADR-0171)은 안 쓴다 — 그것은 오늘 목록의 결손을 메우는 갈래다.</li>
 *   <li><b>상장은 그날 거래소 범주다</b>({@link ListedTimeline}) — 오늘 거래소로 읽지 않는다(LEU · BMNR). 모름(이전 구간 · 원장 모순)은 후보에서
 *       빼고 센다. 범주 걷기의 기준점은 오늘 목록에 표지가 있는 열린 행이면 오늘, 아니면 닻 날짜다(R-D5 · 닻 뒤 제거 다섯이 오늘도 활성).</li>
 *   <li><b>봉은 닻이다</b> — 닻 기호 · 닻 날짜로 격자 전 범위를 한 번에 받는다(★19 — 기호 문자열은 오늘 다른 실체일 수 있다). 종가는 그날
 *       이하 마지막 거래 봉이다 — 거래 밀도로 상장을 가르지 않는다(★5 BIO.B 64일 공백). 첫 거래 봉이 그날 뒤면 후보가 아니다.</li>
 * </ul>
 * <p>CIK 대표는 행에 묶인 등록인으로 고른다 — 오늘 SEC 파일로 비운 표지를 풀 수 없다(ADR-0092 「CIK 모르면 남긴다」는 오늘 목록의 미등재를
 * 위한 폴백이라 여기 옮기지 않는다 · 묶이지 않은 후보는 남기고 센다). 나머지 거름(통화 · SIC · 이름 · 클래스 보류 · 하한 둘)은 오늘
 * 순위와 같은 함수다.
 */
@Component
@ConditionalOnApiKey("alpaca")
public class UsPastRanking {

    private static final Logger log = LoggerFactory.getLogger(UsPastRanking.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private final StoredShareCounts shareCounts;
    private final StoredUsRows storedRows;
    private final ObjectProvider<IdentitySource> identities;
    private final ObjectProvider<ListingEventSource> listingEvents;
    private final ObjectProvider<SecListingFormClient> covers;
    private final AlpacaUniverseAdapter listing;
    private final int topN;

    @org.springframework.beans.factory.annotation.Autowired
    public UsPastRanking(StoredShareCounts shareCounts, StoredUsRows storedRows, ObjectProvider<IdentitySource> identities,
                         ObjectProvider<ListingEventSource> listingEvents, ObjectProvider<SecListingFormClient> covers,
                         AlpacaUniverseAdapter listing) {
        this(shareCounts, storedRows, identities, listingEvents, covers, listing, UsMarketCapUniverseAdapter.TOP_N);
    }

    UsPastRanking(StoredShareCounts shareCounts, StoredUsRows storedRows, ObjectProvider<IdentitySource> identities,
                  ObjectProvider<ListingEventSource> listingEvents, ObjectProvider<SecListingFormClient> covers,
                  AlpacaUniverseAdapter listing, int topN) {
        this.shareCounts = shareCounts;
        this.storedRows = storedRows;
        this.identities = identities;
        this.listingEvents = listingEvents;
        this.covers = covers;
        this.listing = listing;
        this.topN = topN;
    }

    /** 한 격자일의 명부 — 상위 N 행 id(순위 순)와 귀속 재료. */
    public record Roster(LocalDate date, List<Long> stockIds, String note) {
    }

    /**
     * 격자 전체의 명부. 재료(주식수 · 닻 봉 · 범주 사건)를 한 번에 모으고 격자일마다 순위를 낸다 — 클래스 보류의 앞 편입이 앞 격자일
     * 명부라 차례대로 돈다.
     *
     * @throws DataSourceException 소스가 실패했거나 부분 목록(후보 &lt; N · 종가 커버리지 하한 미달)이면 — 그 격자일을 빼고 이으면
     *                             없던 편입을 지어낸다(복원 잡이 유니버스째 멈춘다)
     */
    public List<Roster> rosters(List<LocalDate> grid) throws DataSourceException {
        IdentitySource identity = identities.getIfAvailable();
        ListingEventSource events = listingEvents.getIfAvailable();
        SecListingFormClient sec = covers.getIfAvailable();
        if (identity == null || events == null || sec == null) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, UsMarketCapUniverseAdapter.SOURCE_NAME,
                    "과거 날짜 순위의 재료 소스가 없다(Alpaca · SEC 키) — 오늘 기호로 과거를 고르지 않는다.");
        }
        LocalDate first = grid.get(0);
        LocalDate last = grid.get(grid.size() - 1);
        LocalDate today = LocalDate.now(MARKET_ZONE);
        Map<Long, StoredUsRows.Row> rows = new HashMap<>();
        storedRows.rows().forEach(r -> rows.put(r.id(), r));

        // 1. 기준일마다 주식수(그 기준일 시대 안 · 공시일 ≤ 기준일)와 클래스 보류 — 후보 풀은 그 합집합이다.
        Map<LocalDate, List<StoredShareCounts.ShareCount>> sharesOn = new LinkedHashMap<>();
        Map<LocalDate, List<StoredShareCounts.ClassHold>> holdsOn = new LinkedHashMap<>();
        Set<Long> pool = new LinkedHashSet<>();
        for (LocalDate d : grid) {
            List<StoredShareCounts.ShareCount> counts = shareCounts.activeByMarket(UsMarket.CODE,
                    d.minusYears(UsMarketCapUniverseAdapter.SHARES_MAX_AGE_YEARS), d);
            sharesOn.put(d, counts);
            counts.forEach(c -> pool.add(c.stockId()));
            List<StoredShareCounts.ClassHold> holds = List.copyOf(shareCounts.classHolds(UsMarket.CODE, d).values());
            holdsOn.put(d, holds);
            holds.forEach(h -> pool.add(h.stockId()));
        }
        pool.removeIf(id -> !rows.containsKey(id) || UsMarketCapUniverseAdapter.nonCommonName(rows.get(id).name()));

        // 2. 닻 봉 — 닻 날짜끼리 묶어 격자 전 범위를 한 번에.
        Map<Long, List<EntityBar>> bars = anchorBars(identity, pool, rows, first.minusDays(UsMarketCapUniverseAdapter.CLOSE_WINDOW_DAYS),
                last);

        // 3. 범주 이력 — 묶인 등록인의 사건 · 오늘 목록에 표지가 있는 열린 행은 기준점이 오늘.
        Set<String> listedToday = new HashSet<>();
        listing.fetchActiveAssets().forEach(a -> listedToday.add(a.symbol()));
        Map<Long, ListedTimeline> timelines = timelines(events, sec, pool, rows, listedToday,
                first.minusDays(ListedTimeline.PAIR_DAYS + 15), today);

        List<Roster> out = new ArrayList<>();
        Set<Long> previous = Set.of();
        for (LocalDate d : grid) {
            Roster roster = rankOn(d, sharesOn.get(d), holdsOn.get(d), previous, rows, bars, timelines);
            out.add(roster);
            previous = new HashSet<>(roster.stockIds());
        }
        return out;
    }

    private Roster rankOn(LocalDate d, List<StoredShareCounts.ShareCount> counts, List<StoredShareCounts.ClassHold> holds,
                          Set<Long> previous, Map<Long, StoredUsRows.Row> rows, Map<Long, List<EntityBar>> bars,
                          Map<Long, ListedTimeline> timelines) throws DataSourceException {
        UsMarketCapUniverseAdapter.Exclusions excluded = new UsMarketCapUniverseAdapter.Exclusions();
        Map<Long, BigDecimal> shares = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (StoredShareCounts.ShareCount count : counts) {
            if (count.currencyMismatched()) {
                excluded.currency++;
                continue;
            }
            if (count.sicCode() != null && UsMarketCapUniverseAdapter.NON_OPERATING_SIC.contains(count.sicCode())) {
                excluded.nonOperating++;
                continue;
            }
            shares.put(count.stockId(), count.shares());
            names.put(count.stockId(), count.name());
        }
        List<String> held = new ArrayList<>();
        List<String> classExcluded = new ArrayList<>();
        UsMarketCapUniverseAdapter.applyClassHolds(holds, previous, StoredShareCounts.ClassHold::stockId, shares, names, excluded,
                held, classExcluded);

        int unlisted = 0;
        List<String> unknown = new ArrayList<>();
        int notYetTrading = 0;
        List<UsMarketCapUniverseAdapter.Ranked> ranked = new ArrayList<>();
        Map<UsMarketCapUniverseAdapter.Ranked, Long> idOf = new HashMap<>();
        int listedCandidates = 0;
        for (Map.Entry<Long, BigDecimal> e : new TreeMap<>(shares).entrySet()) {
            StoredUsRows.Row row = rows.get(e.getKey());
            if (row == null || UsMarketCapUniverseAdapter.nonCommonName(row.name())) {
                excluded.name++;
                continue;
            }
            ListedTimeline.Verdict verdict = timelines.getOrDefault(row.id(), ListedTimeline.of(row.anchorDate(), true, List.of(),
                    ListedTimeline.PAIR_DAYS)).on(d);
            if (verdict == ListedTimeline.Verdict.UNLISTED) {
                unlisted++;
                continue;
            }
            if (verdict == ListedTimeline.Verdict.UNKNOWN) {
                unknown.add(row.ticker());
                continue;
            }
            List<EntityBar> series = bars.getOrDefault(row.id(), List.of());
            Optional<EntityBar> lastTrade = series.stream().filter(b -> !b.date().isAfter(d) && b.trades() > 0)
                    .max(Comparator.comparing(EntityBar::date));
            boolean anyBarBefore = series.stream().anyMatch(b -> !b.date().isAfter(d));
            if (!anyBarBefore && !series.isEmpty()) {
                notYetTrading++;
                continue;
            }
            listedCandidates++;
            if (lastTrade.isEmpty()) {
                excluded.noClose++;
                continue;
            }
            BigDecimal dollarVolume = series.stream()
                    .filter(b -> !b.date().isAfter(d) && b.date().isAfter(d.minusDays(UsMarketCapUniverseAdapter.CLOSE_WINDOW_DAYS + 1))
                            && b.trades() > 0)
                    .map(b -> b.close().multiply(BigDecimal.valueOf(b.volume()))).reduce(BigDecimal.ZERO, BigDecimal::add);
            String name = names.getOrDefault(row.id(), row.name());
            UsMarketCapUniverseAdapter.Ranked r = new UsMarketCapUniverseAdapter.Ranked(
                    new AlpacaAsset(row.ticker(), name == null ? "" : name, "", "", false),
                    e.getValue().multiply(lastTrade.get().close()), dollarVolume);
            ranked.add(r);
            idOf.put(r, row.id());
        }
        double coverage = listedCandidates == 0 ? 0 : (double) ranked.size() / listedCandidates;
        if (coverage < UsMarketCapUniverseAdapter.MIN_CLOSE_COVERAGE) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, UsMarketCapUniverseAdapter.SOURCE_NAME,
                    "과거 날짜 %s 후보 중 종가가 온 것이 %d/%d(%.1f%%)로 하한 미만이다 — 부분 목록을 반영하지 않는다."
                            .formatted(d, ranked.size(), listedCandidates, coverage * 100));
        }
        ranked.sort(Comparator.comparing(UsMarketCapUniverseAdapter.Ranked::marketCap).reversed()
                .thenComparing(r -> r.asset().symbol()));
        int unbound = (int) ranked.stream().filter(r -> rows.get(idOf.get(r)).secCik() == null).count();
        List<UsMarketCapUniverseAdapter.Ranked> deduped = UsMarketCapUniverseAdapter.dropCikDuplicates(ranked, excluded,
                r -> Optional.ofNullable(rows.get(idOf.get(r)).secCik()));
        if (deduped.size() < topN) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, UsMarketCapUniverseAdapter.SOURCE_NAME,
                    "과거 날짜 %s CIK 중복을 걷고 나니 %d행뿐이라 상위 %d를 채우지 못한다. 제외 내역: %s".formatted(d, deduped.size(), topN, excluded));
        }
        List<Long> ids = deduped.subList(0, topN).stream().map(idOf::get).toList();
        String note = "후보 %d · 범주 밖 %d · 범주 모름 %d%s · 첫 거래 전 %d · 묶이지 않은 후보 %d · 보류 %s · 발행사 제외 %s · %s".formatted(
                listedCandidates, unlisted, unknown.size(), unknown.isEmpty() ? "" : unknown, notYetTrading, unbound, held,
                classExcluded, excluded);
        log.info("과거 날짜 순위. 기준일={} 편입={} · {}", d, ids.size(), note);
        return new Roster(d, ids, note);
    }

    /** 닻 날짜끼리 묶어 닻 기호로 묻는다 — 한 요청에 한 날짜의 기호만(같은 실체의 두 기호가 한 요청에 안 든다). */
    private static Map<Long, List<EntityBar>> anchorBars(IdentitySource identity, Set<Long> pool, Map<Long, StoredUsRows.Row> rows,
                                                         LocalDate from, LocalDate to) throws DataSourceException {
        Map<LocalDate, Map<String, List<Long>>> byDate = new TreeMap<>();
        for (long id : pool) {
            StoredUsRows.Row row = rows.get(id);
            byDate.computeIfAbsent(row.anchorDate(), d -> new LinkedHashMap<>())
                    .computeIfAbsent(row.anchorSymbol(), s -> new ArrayList<>()).add(id);
        }
        Map<Long, List<EntityBar>> out = new HashMap<>();
        int calls = 0;
        for (Map.Entry<LocalDate, Map<String, List<Long>>> group : byDate.entrySet()) {
            Map<String, List<EntityBar>> got = identity.bars(group.getValue().keySet(), BarAsOf.anchor(group.getKey()), from, to);
            calls++;
            // 행의 관측 구간 밖 봉(다른 증권 · 다른 등록인 시대)은 그 행의 종가가 아니다(ADR-0181) — 닻 봉도 그 앞에 다른 실체 봉을 붙여 준다.
            group.getValue().forEach((symbol, ids) -> ids.forEach(id -> out.put(id, got.getOrDefault(symbol, List.of()).stream()
                    .filter(b -> rows.get(id).observes(b.date())).toList())));
        }
        log.info("과거 날짜 순위 닻 봉. 행={} 닻 날짜 묶음={} 범위={}~{}", pool.size(), calls, from, to);
        return out;
    }

    /** 행마다 범주 이력. 등록인이 없는 행은 사건이 없어 닻 범주 그대로다(후보 수는 순위 기록이 「묶이지 않은 후보」로 센다). */
    private static Map<Long, ListedTimeline> timelines(ListingEventSource events, SecListingFormClient sec, Set<Long> pool,
                                                       Map<Long, StoredUsRows.Row> rows, Set<String> listedToday,
                                                       LocalDate from, LocalDate today) throws DataSourceException {
        Map<Long, List<Long>> byCik = new LinkedHashMap<>();
        for (long id : pool) {
            Long cik = rows.get(id).secCik();
            if (cik != null) {
                byCik.computeIfAbsent(cik, k -> new ArrayList<>()).add(id);
            }
        }
        ListingEventSource.Result result = events.events(byCik.keySet(), from, today);
        Map<Long, ListedTimeline> out = new HashMap<>();
        int unmatched = 0;
        for (Map.Entry<Long, List<Long>> group : byCik.entrySet()) {
            List<ListingEvent> registrantEvents = result.byCik().getOrDefault(group.getKey(), List.of());
            boolean shared = group.getValue().size() > 1;
            Map<Long, Optional<String>> letters = new HashMap<>();
            if (shared && !registrantEvents.isEmpty()) {
                LocalDate firstEvent = registrantEvents.stream().map(ListingEvent::date).min(LocalDate::compareTo).orElse(today);
                Optional<SecListingFormClient.Cover> cover = sec.lastCoverBefore(group.getKey(), firstEvent.plusDays(1),
                        firstEvent.minusYears(2));
                for (long id : group.getValue()) {
                    letters.put(id, cover.flatMap(c -> ListingCover.lineFor(c.facts(), rows.get(id).symbol()))
                            .flatMap(ListingCover.Line::letter));
                }
                unmatched += ListedTimeline.unmatched(registrantEvents,
                        letters.values().stream().flatMap(Optional::stream).toList()).size();
            }
            for (long id : group.getValue()) {
                StoredUsRows.Row row = rows.get(id);
                LocalDate anchor = row.open() && listedToday.contains(row.ticker()) ? today : row.anchorDate();
                out.put(id, ListedTimeline.of(anchor, true,
                        ListedTimeline.forRow(registrantEvents, shared, letters.getOrDefault(id, Optional.empty())),
                        ListedTimeline.PAIR_DAYS));
            }
        }
        log.info("과거 날짜 순위 범주 이력. 등록인={} 사건 등록인={} 못 읽은 양식={} 못 붙인 사건={}", byCik.size(), result.byCik().size(),
                result.unreadable().size(), unmatched);
        return out;
    }
}
