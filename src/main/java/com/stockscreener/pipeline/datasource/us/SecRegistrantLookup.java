package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.RegistrantLookup;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient.IndexLine;
import com.stockscreener.pipeline.datasource.us.listing.ListingFormReader;
import com.stockscreener.pipeline.datasource.us.listing.RegistrantFinder;
import com.stockscreener.pipeline.datasource.us.listing.RegistrantNames;
import com.stockscreener.pipeline.datasource.us.listing.Venue;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link RegistrantLookup}의 SEC 구현 — 일일 색인의 정기 공시 제출자 이름과 제거 양식, 사건 전 정기 공시 표지(탐색
 * {@code US-PAST-LISTING-IMPL-1018.md} §2.2 · 원자료 {@code docs/api-spec/sec-edgar-listing-forms.md}).
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecRegistrantLookup implements RegistrantLookup {

    /** 이름 색인을 모을 창 앞 여유 — 창 시작 직후에 사라진 실체의 마지막 정기 공시가 들어오게(분기 하나 + 늦은 제출). */
    static final int NAME_INDEX_LEAD_DAYS = 120;
    /** 제거 양식 후보의 제출일 앞뒤 — 탐색 ★17(문턱 위 35 중 34가 효력일 ±1일). */
    static final int REMOVAL_NEAR_DAYS = 1;
    /** 표지를 찾아 거슬러 볼 공시 목록 하한 — 기준일 앞 이만큼. */
    static final int COVER_LOOKBACK_DAYS = 500;

    static final Set<String> REMOVAL_FORMS = Set.of("25-NSE", "25");
    /** 이름 색인의 제출자 — 연차 · 분기 정기 공시(6-K는 표지 기호가 없어 이름 색인에도 안 쓴다 · 20-F 제출자는 20-F로 잡힌다). */
    static final Set<String> NAME_FORMS = Set.of("10-K", "10-K/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A");

    private final SecListingFormClient sec;

    public SecRegistrantLookup(SecListingFormClient sec) {
        this.sec = sec;
    }

    @Override
    public Session open(LocalDate from, LocalDate asOf) throws DataSourceException {
        Set<String> forms = new LinkedHashSet<>(NAME_FORMS);
        forms.addAll(REMOVAL_FORMS);
        List<Map.Entry<String, Long>> filers = new ArrayList<>();
        Map<Long, String> filerName = new HashMap<>();
        List<IndexLine> removals = new ArrayList<>();
        for (IndexLine line : sec.lines(from.minusDays(NAME_INDEX_LEAD_DAYS), asOf, forms)) {
            if (REMOVAL_FORMS.contains(line.form())) {
                removals.add(line);
                // 제거 양식 줄의 회사 이름도 그 발행사 이름이다 — 정기 공시 줄이 창 안에 없을 때의 행 이름.
                filerName.putIfAbsent(line.cik(), line.company());
            } else {
                filers.add(Map.entry(line.company(), line.cik()));
                filerName.put(line.cik(), line.company());
            }
        }
        Map<String, Set<Long>> nameIndex = RegistrantNames.index(filers);
        return (symbol, before, securityName, renames) -> find(symbol, before, securityName, renames, nameIndex, filerName, removals);
    }

    private Result find(String symbol, LocalDate before, Optional<String> securityName, List<Rename> renames,
                        Map<String, Set<Long>> nameIndex, Map<Long, String> filerName, List<IndexLine> removals)
            throws DataSourceException {
        List<Long> byName = securityName.map(n -> RegistrantNames.candidates(n, nameIndex)).orElse(List.of());
        List<IndexLine> near = removals.stream()
                .filter(l -> Math.abs(ChronoUnit.DAYS.between(l.filed(), before)) <= REMOVAL_NEAR_DAYS).toList();
        List<Long> byForm = near.stream().map(IndexLine::cik).distinct().toList();
        RegistrantFinder.Decision decision = RegistrantFinder.find(symbol, before, byName, byForm, renames,
                (cik, end) -> sec.lastCoverBefore(cik, end, end.minusDays(COVER_LOOKBACK_DAYS)));
        String evidence = "이름 " + securityName.orElse("(안 씀)") + " · " + String.join(" | ", decision.tried());
        if (decision.result() != RegistrantFinder.Result.BOUND) {
            return new Result(decision.result() == RegistrantFinder.Result.AMBIGUOUS ? Outcome.AMBIGUOUS : Outcome.NONE,
                    null, null, null, evidence);
        }
        long cik = decision.cik();
        String title = decision.line().title() == null ? "" : " " + decision.line().title();
        String name = (filerName.getOrDefault(cik, securityName.orElse(symbol)) + title).strip();
        if (!decision.line().common()) {
            return new Result(Outcome.NOT_COMMON, cik, null, name, "표지 줄 " + decision.line().title() + " · " + evidence);
        }
        Optional<Venue> venue = decision.line().venue();
        if (venue.isEmpty() || !venue.get().listed()) {
            return new Result(Outcome.OUTSIDE, cik, null, name, "표지 " + decision.line().venueCode() + " · " + evidence);
        }
        for (IndexLine removal : near) {
            if (removal.cik() != cik) {
                continue;
            }
            Optional<Venue> removedFrom = removalVenue(removal);
            if (removedFrom.isPresent() && removedFrom.get().listed() != venue.get().listed()) {
                return new Result(Outcome.SPLIT_LEDGER, cik, null, name, "표지 " + decision.line().venueCode() + " · 제거 양식 "
                        + removedFrom.get().name() + " " + removal.accession() + " · " + evidence);
            }
        }
        return new Result(Outcome.BOUND, cik, "Nasdaq".equals(venue.get().name()) ? "NASDAQ" : "NYSE", name, evidence);
    }

    private Optional<Venue> removalVenue(IndexLine removal) throws DataSourceException {
        Optional<byte[]> doc = sec.document(removal.cik(), removal.accession(), removal.form());
        if (doc.isEmpty()) {
            return Optional.empty();
        }
        ListingFormReader.Removal read = "25-NSE".equals(removal.form())
                ? ListingFormReader.readExchangeRemoval(doc.get(), removal.accession())
                : ListingFormReader.readIssuerRemoval(new String(doc.get(), StandardCharsets.ISO_8859_1));
        return read.venue();
    }
}
