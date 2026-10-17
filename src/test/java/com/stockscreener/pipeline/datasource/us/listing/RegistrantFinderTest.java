package com.stockscreener.pipeline.datasource.us.listing;

import com.stockscreener.pipeline.datasource.RegistrantLookup;
import com.stockscreener.pipeline.datasource.us.SecListingFormClient.Cover;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 등록인 찾기 — 후보 둘 · 확인 하나(탐색 {@code US-PAST-LISTING-IMPL-1018.md} §2.2). */
class RegistrantFinderTest {

    private static final LocalDate E = LocalDate.of(2026, 3, 2);

    @Test
    void nameCandidateConfirmedByTheCoverSymbolIsBound() throws Exception {
        Map<Long, Cover> covers = Map.of(712515L, cover("10-Q", LocalDate.of(2026, 2, 3), Map.of("EA", "NASDAQ")));

        RegistrantFinder.Decision d = RegistrantFinder.find("EA", E, List.of(712515L), List.of(), List.of(), lookup(covers));

        assertThat(d.result()).isEqualTo(RegistrantFinder.Result.BOUND);
        assertThat(d.cik()).isEqualTo(712515L);
        assertThat(d.line().venue()).contains(new Venue("Nasdaq", true));
    }

    /** 이름만 맞고 다른 회사(ARIS → Aris Mining꼴) — 표지에 그 기호 줄이 없어 안 묶는다. */
    @Test
    void nameMatchWithoutTheSymbolOnTheCoverIsNotBound() throws Exception {
        Map<Long, Cover> covers = Map.of(1654760L, cover("40-F", LocalDate.of(2026, 1, 20), Map.of("ARMN", "NYSEAMER")));

        RegistrantFinder.Decision d = RegistrantFinder.find("ARIS", E, List.of(1654760L), List.of(), List.of(), lookup(covers));

        assertThat(d.result()).isEqualTo(RegistrantFinder.Result.NONE);
        assertThat(d.tried()).singleElement().asString().contains("ARIS 줄 없음");
    }

    /** 재사용 기호 — 이름 후보가 없어도 효력일 제거 양식을 낸 등록인이 표지로 확인되면 묶는다(PINC꼴). */
    @Test
    void removalFormCandidateBindsWhenThereIsNoName() throws Exception {
        Map<Long, Cover> covers = Map.of(1577916L, cover("10-K", LocalDate.of(2025, 8, 19), Map.of("PINC", "NASDAQ")),
                999L, cover("10-Q", LocalDate.of(2026, 2, 1), Map.of("XYZ", "NYSE")));

        RegistrantFinder.Decision d = RegistrantFinder.find("PINC", E, List.of(), List.of(999L, 1577916L), List.of(), lookup(covers));

        assertThat(d.result()).isEqualTo(RegistrantFinder.Result.BOUND);
        assertThat(d.cik()).isEqualTo(1577916L);
    }

    /** 합병 전날 개명(RNA → RNAM 2026-02-26) — 그 전 표지는 옛 기호 RNA를 싣는다(탐색 ★6). */
    @Test
    void coverIsMatchedWithTheSymbolOfItsFilingDate() throws Exception {
        Map<Long, Cover> covers = Map.of(1423824L, cover("10-K", LocalDate.of(2026, 2, 25), Map.of("RNA", "NASDAQ")));
        List<RegistrantLookup.Rename> renames = List.of(new RegistrantLookup.Rename("RNA", "RNAM", LocalDate.of(2026, 2, 26)));

        RegistrantFinder.Decision d = RegistrantFinder.find("RNAM", E, List.of(1423824L), List.of(), renames, lookup(covers));

        assertThat(d.result()).isEqualTo(RegistrantFinder.Result.BOUND);
        assertThat(RegistrantFinder.symbolOn("RNAM", LocalDate.of(2026, 2, 27), renames)).isEqualTo("RNAM");
        assertThat(RegistrantFinder.symbolOn("RNAM", LocalDate.of(2026, 2, 25), renames)).isEqualTo("RNA");
    }

    /** 모회사 + 운영 파트너십이 둘 다 그 기호를 싣는다 — 안 묶고 센다. */
    @Test
    void twoConfirmedRegistrantsAreAmbiguous() throws Exception {
        Map<Long, Cover> covers = Map.of(924901L, cover("10-K", LocalDate.of(2026, 2, 20), Map.of("VRE", "NYSE")),
                1067063L, cover("10-K", LocalDate.of(2026, 2, 20), Map.of("VRE", "NYSE")));

        RegistrantFinder.Decision d = RegistrantFinder.find("VRE", E, List.of(924901L, 1067063L), List.of(), List.of(), lookup(covers));

        assertThat(d.result()).isEqualTo(RegistrantFinder.Result.AMBIGUOUS);
        assertThat(d.cik()).isNull();
    }

    @Test
    void dottedSymbolMatchesTheCoverForm() throws Exception {
        Map<Long, Cover> covers = Map.of(14693L, cover("10-Q", LocalDate.of(2026, 1, 5), Map.of("BFB", "NYSE")));

        assertThat(RegistrantFinder.find("BF.B", E, List.of(14693L), List.of(), List.of(), lookup(covers)).result())
                .isEqualTo(RegistrantFinder.Result.BOUND);
    }

    @Test
    void noCoverIsNotBound() throws Exception {
        assertThat(RegistrantFinder.find("CADE", E, List.of(1L), List.of(), List.of(), lookup(Map.of())).result())
                .isEqualTo(RegistrantFinder.Result.NONE);
    }

    private static RegistrantFinder.Covers lookup(Map<Long, Cover> covers) {
        return (cik, before) -> Optional.ofNullable(covers.get(cik));
    }

    private static Cover cover(String form, LocalDate filed, Map<String, String> symbolToExchange) {
        Map<String, LinkedHashMap<String, String>> cover = new LinkedHashMap<>();
        LinkedHashMap<String, String> symbols = new LinkedHashMap<>();
        LinkedHashMap<String, String> exchanges = new LinkedHashMap<>();
        LinkedHashMap<String, String> titles = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, String> e : symbolToExchange.entrySet()) {
            String member = symbolToExchange.size() == 1 ? ClassFacts.AXISLESS : "us-gaap:Class" + (char) ('A' + i++) + "Member";
            symbols.put(member, e.getKey());
            exchanges.put(member, e.getValue());
            titles.put(member, "Common Stock");
        }
        cover.put(ClassFactReader.TRADING_SYMBOL, symbols);
        cover.put(ClassFactReader.SECURITY_EXCHANGE, exchanges);
        cover.put(ClassFactReader.SECURITY_TITLE, titles);
        return new Cover("acc", form, filed, new ClassFacts(new LinkedHashMap<>(), cover, form));
    }
}
