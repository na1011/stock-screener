package com.stockscreener.pipeline.datasource.us.listing;

import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거래소 양식 판독 — 고정물은 실제 공시를 그대로 받았다(2026-10-18 · SEC 16콜). 표지 인스턴스 둘은 dei 사실과 그 맥락만 잘랐다.
 * 탐색 {@code US-PAST-LISTING-IMPL-1018.md} U1.
 */
class ListingFormReaderTest {

    private static final String DIR = "/fixtures/sec-edgar/listing/";

    @Test
    void exchangeRemovalOfCommonStockFromNyse() throws Exception {
        ListingFormReader.Removal two = ListingFormReader.readExchangeRemoval(bytes("two-25nse-2026-08-25.xml"), "t");

        assertThat(two.unreadable()).isNull();
        assertThat(two.venue()).contains(new Venue("NYSE", true));
        assertThat(two.lines()).containsExactly("Common Stock");
        assertThat(two.lines()).allMatch(ClassLines::isCommon);
    }

    @Test
    void exchangeRemovalsThatAreNotCommonStock() throws Exception {
        ListingFormReader.Removal hwm = ListingFormReader.readExchangeRemoval(bytes("hwm-25nse-2025-12-17.xml"), "t");
        ListingFormReader.Removal lmnd = ListingFormReader.readExchangeRemoval(bytes("lmnd-25nse-2026-02-09.xml"), "t");

        assertThat(hwm.lines()).containsExactly("$3.75 Preferred Stock");
        assertThat(lmnd.lines()).containsExactly("Warrants to purchase Common Stock");
        assertThat(hwm.lines()).noneMatch(ClassLines::isCommon);
        assertThat(lmnd.lines()).noneMatch(ClassLines::isCommon);
        assertThat(hwm.venue()).contains(new Venue("NYSE American", false));
    }

    @Test
    void exchangeRemovalFromNasdaqAndFromNyseAmerican() throws Exception {
        assertThat(ListingFormReader.readExchangeRemoval(bytes("ea-25nse-2026-08-04.xml"), "t").venue())
                .contains(new Venue("Nasdaq", true));
        ListingFormReader.Removal ngd = ListingFormReader.readExchangeRemoval(bytes("ngd-25nse-2026-03-20.xml"), "t");
        assertThat(ngd.venue()).contains(new Venue("NYSE American", false));
        assertThat(ngd.lines()).allMatch(ClassLines::isCommon);
    }

    /** LEU — 한 글에 보통주와 권리가 함께 있다. 가르지 않으면 「권리가 들었다」로 보통주 제거가 사라진다(대조군 · 탐색 ☆ 안 6). */
    @Test
    void issuerRemovalSplitsTheClassSentence() throws Exception {
        ListingFormReader.Removal leu = ListingFormReader.readIssuerRemoval(html("leu-25-2025-12-11.htm"));

        assertThat(leu.unreadable()).isNull();
        assertThat(leu.venue()).contains(new Venue("NYSE American", false));
        assertThat(leu.lines()).hasSize(2);
        assertThat(leu.lines().get(0)).startsWith("Class A Common Stock");
        assertThat(ClassLines.isCommon(leu.lines().get(0))).isTrue();
        assertThat(ClassLines.classLetter(leu.lines().get(0))).contains("A");
        assertThat(ClassLines.isCommon(leu.lines().get(1))).isFalse();
    }

    @Test
    void issuerRemovalOfNotesIsNotCommon() throws Exception {
        ListingFormReader.Removal twod = ListingFormReader.readIssuerRemoval(html("twod-25-2026-09-24.htm"));

        assertThat(twod.venue()).contains(new Venue("NYSE", true));
        assertThat(twod.lines()).containsExactly("9.375% Senior Notes due 2030");
        assertThat(twod.lines()).noneMatch(ClassLines::isCommon);
    }

    @Test
    void issuerRemovalWithCommonAndNotesInOneSentence() throws Exception {
        ListingFormReader.Removal abl = ListingFormReader.readIssuerRemoval(html("abl-25-2025-12-29.htm"));

        assertThat(abl.venue()).contains(new Venue("Nasdaq", true));
        assertThat(abl.lines()).hasSize(2);
        assertThat(abl.lines().stream().filter(ClassLines::isCommon).toList())
                .containsExactly("Common stock, par value $0.0001 per share");
    }

    @Test
    void masterLimitedPartnershipCommonUnitsAreCommon() throws Exception {
        ListingFormReader.Removal sun = ListingFormReader.readIssuerRemoval(html("sun-25-2026-09-28.htm"));

        assertThat(sun.venue()).contains(new Venue("NYSE", true));
        assertThat(sun.lines()).containsExactly("Common Units Representing Limited Partner Interests");
        assertThat(sun.lines()).allMatch(ClassLines::isCommon);
    }

    @Test
    void issuerRemovalFromNyseAmericanForUplisting() throws Exception {
        ListingFormReader.Removal bmnr = ListingFormReader.readIssuerRemoval(html("bmnr-25-2026-04-16.htm"));

        assertThat(bmnr.venue()).contains(new Venue("NYSE American", false));
        assertThat(bmnr.lines()).anyMatch(ClassLines::isCommon);
    }

    @Test
    void registrationRowsPairEachClassWithItsExchange() throws Exception {
        ListingFormReader.Registration leu = ListingFormReader.readRegistration(html("leu-8a12b-2025-12-03.htm"));

        assertThat(leu.unreadable()).isNull();
        assertThat(leu.rows()).hasSize(2);
        assertThat(leu.rows()).allMatch(r -> r.venue().equals(java.util.Optional.of(new Venue("NYSE", true))));
        assertThat(leu.rows().get(0).line()).startsWith("Class A Common Stock");
        assertThat(ClassLines.isCommon(leu.rows().get(0).line())).isTrue();
        assertThat(ClassLines.isCommon(leu.rows().get(1).line())).isFalse();
    }

    /** BMNR — 같은 등록인의 두 등록: 4월은 보통주(상향 이전), 6월은 우선주(사건 아님 · 대조군). */
    @Test
    void registrationOfPreferredIsNotACommonListing() throws Exception {
        ListingFormReader.Registration april = ListingFormReader.readRegistration(html("bmnr-8a12b-2026-04-08.htm"));
        ListingFormReader.Registration june = ListingFormReader.readRegistration(html("bmnr-8a12b-2026-06-10.htm"));

        assertThat(april.rows()).singleElement().satisfies(r -> {
            assertThat(r.venue()).contains(new Venue("NYSE", true));
            assertThat(ClassLines.isCommon(r.line())).isTrue();
        });
        assertThat(june.rows()).singleElement().satisfies(r -> {
            assertThat(r.venue()).contains(new Venue("NYSE", true));
            assertThat(ClassLines.isCommon(r.line())).isFalse();
        });
    }

    @Test
    void listingCoverGivesSymbolExchangeAndTitle() throws Exception {
        ClassFacts ea = cover("ea-10q-2026-08-03-cover.xml", 712515);

        assertThat(ea.coverOf(ClassFactReader.TRADING_SYMBOL)).containsValue("EA");
        assertThat(ea.coverOf(ClassFactReader.SECURITY_EXCHANGE)).containsValue("NASDAQ");
        assertThat(Venue.ofDeiCode(only(ea.coverOf(ClassFactReader.SECURITY_EXCHANGE)))).contains(new Venue("Nasdaq", true));
        assertThat(only(ea.coverOf(ClassFactReader.SECURITY_TITLE))).startsWith("Common Stock");
    }

    /** ORLA — 3월 40-F 표지는 NYSE인데 7월 제거 양식은 NYSE American이다(원장 갈림 · 탐색 ★17). 둘을 각각 정직하게 읽는다. */
    @Test
    void coverAndRemovalDisagreeForOrla() throws Exception {
        ClassFacts orla = cover("orla-40f-2026-03-20-cover.xml", 1680056);
        ListingFormReader.Removal removal = ListingFormReader.readExchangeRemoval(bytes("orla-25nse-2026-07-31.xml"), "t");

        assertThat(Venue.ofDeiCode(only(orla.coverOf(ClassFactReader.SECURITY_EXCHANGE)))).contains(new Venue("NYSE", true));
        assertThat(removal.venue()).contains(new Venue("NYSE American", false));
    }

    @Test
    void coverOfAnotherRegistrantIsSkipped() throws Exception {
        ClassFacts ea = cover("ea-10q-2026-08-03-cover.xml", 1L);

        assertThat(ea.coverOf(ClassFactReader.TRADING_SYMBOL)).isEmpty();
    }

    @Test
    void unknownExchangeNameIsNotGuessed() {
        assertThat(Venue.ofName("Some Regional Exchange")).isEmpty();
        assertThat(Venue.ofDeiCode("XYZ")).isEmpty();
        assertThat(Venue.ofName("NYSE American LLC")).contains(new Venue("NYSE American", false));
        assertThat(Venue.ofName("The Nasdaq Global Select Market")).contains(new Venue("Nasdaq", true));
    }

    @Test
    void classLinesExcludeWarrantsThatNameCommonShares() {
        List<String> lines = ClassLines.split("Class A Ordinary Shares, par value $.0001 per share Redeemable warrants, each whole "
                + "warrant exercisable for one Class A Ordinary Share at an exercise price of $11.50");

        assertThat(lines).hasSize(2);
        assertThat(ClassLines.isCommon(lines.get(0))).isTrue();
        assertThat(ClassLines.isCommon(lines.get(1))).isFalse();
        assertThat(ClassLines.isCommon("American Depositary Shares, each representing one ordinary share")).isTrue();
        assertThat(ClassLines.isCommon("Depositary Shares, each representing a 1/40th interest in a share of Series A Preferred")).isFalse();
        assertThat(ClassLines.isCommon("Units, each consisting of one share of Class A common stock and one-half of one warrant")).isFalse();
    }

    private static String only(Map<String, String> byMember) {
        assertThat(byMember).hasSize(1);
        return byMember.values().iterator().next();
    }

    private ClassFacts cover(String name, long cik) throws Exception {
        try (InputStream in = stream(name)) {
            return ClassFactReader.readListingCover(in, cik, name);
        }
    }

    private byte[] bytes(String name) throws IOException {
        try (InputStream in = stream(name)) {
            return in.readAllBytes();
        }
    }

    private String html(String name) throws IOException {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    private InputStream stream(String name) {
        InputStream in = getClass().getResourceAsStream(DIR + name);
        assertThat(in).as(name).isNotNull();
        return in;
    }
}
