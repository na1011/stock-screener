package com.stockscreener.pipeline.datasource.us.listing;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.stockscreener.pipeline.datasource.us.listing.ListedTimeline.Verdict.LISTED;
import static com.stockscreener.pipeline.datasource.us.listing.ListedTimeline.Verdict.UNKNOWN;
import static com.stockscreener.pipeline.datasource.us.listing.ListedTimeline.Verdict.UNLISTED;
import static org.assertj.core.api.Assertions.assertThat;

/** 거래소 범주 이력 — 탐색 {@code US-PAST-LISTING-IMPL-1018.md} U2 · v1.1 §3 D5. */
class ListedTimelineTest {

    private static final Venue NYSE = new Venue("NYSE", true);
    private static final Venue NASDAQ = new Venue("Nasdaq", true);
    private static final Venue AMERICAN = new Venue("NYSE American", false);
    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 28);
    private static final int PAIR = 30;

    /** LEU — 8-A12B NYSE 2025-12-03 · 발행사 25 NYSE American(효력 12-21). 복원은 2025-09-24부터 편입했었다(탐색 ★10 · 틀린 편입). */
    @Test
    void uplistingFromNyseAmericanIsAnEntry() {
        ListedTimeline leu = ListedTimeline.of(ANCHOR, true, List.of(
                register(LocalDate.of(2025, 12, 3), NYSE, "Class A Common Stock, par value $0.10 per share"),
                remove(LocalDate.of(2025, 12, 21), AMERICAN, "Class A Common Stock - par value $0.10 per share")), PAIR);

        assertThat(leu.on(LocalDate.of(2025, 9, 24))).isEqualTo(UNLISTED);
        assertThat(leu.on(LocalDate.of(2025, 12, 2))).isEqualTo(UNLISTED);
        assertThat(leu.on(LocalDate.of(2025, 12, 3))).isEqualTo(LISTED);
        assertThat(leu.on(LocalDate.of(2026, 3, 6))).isEqualTo(LISTED);
        assertThat(leu.events()).extracting(ListedTimeline.Classified::kind)
                .containsExactly(ListedTimeline.Kind.ENTRY, ListedTimeline.Kind.OFF_VENUE);
    }

    /** 대조군: BMNR은 4월 상향 전 NYSE American이었다(탐색 ★10 — 복원 편입 2025-11-21부터가 틀렸다). */
    @Test
    void bmnrWasOutsideBeforeItsUplisting() {
        ListedTimeline bmnr = ListedTimeline.of(ANCHOR, true, List.of(
                register(LocalDate.of(2026, 4, 8), NYSE, "Common Stock, par value $0.0001 per share"),
                remove(LocalDate.of(2026, 4, 26), AMERICAN, "Common Stock, par value $0.0001 per share")), PAIR);

        assertThat(bmnr.on(LocalDate.of(2025, 11, 21))).isEqualTo(UNLISTED);
        assertThat(bmnr.on(LocalDate.of(2026, 4, 7))).isEqualTo(UNLISTED);
        assertThat(bmnr.on(LocalDate.of(2026, 4, 10))).isEqualTo(LISTED);
    }

    @Test
    void transferBetweenNyseAndNasdaqIsNotAnEvent() {
        ListedTimeline moved = ListedTimeline.of(ANCHOR, true, List.of(
                register(LocalDate.of(2026, 2, 2), NASDAQ, "Common Stock"),
                remove(LocalDate.of(2026, 2, 20), NYSE, "Common Stock")), PAIR);

        assertThat(moved.on(LocalDate.of(2025, 10, 1))).isEqualTo(LISTED);
        assertThat(moved.on(LocalDate.of(2026, 2, 10))).isEqualTo(LISTED);
        assertThat(moved.events()).extracting(ListedTimeline.Classified::kind)
                .containsOnly(ListedTimeline.Kind.TRANSFER);
    }

    /** 짝 없는 안 거래소 등록 — 장외에서 올라왔는지 승계 등록인지 원장이 안 가른다. 범주를 안 바꾼다. */
    @Test
    void unpairedRegistrationDoesNotFlip() {
        ListedTimeline t = ListedTimeline.of(ANCHOR, true, List.of(register(LocalDate.of(2026, 1, 5), NYSE, "Common Stock")), PAIR);

        assertThat(t.on(LocalDate.of(2025, 10, 1))).isEqualTo(LISTED);
        assertThat(t.events()).extracting(ListedTimeline.Classified::kind).containsExactly(ListedTimeline.Kind.UNRESOLVED);
    }

    /** 짝이 창 밖이면 이전이 아니다 — 등록은 미정, 제거는 이탈이다. */
    @Test
    void pairOutsideTheWindowIsNotATransfer() {
        ListedTimeline t = ListedTimeline.of(ANCHOR, true, List.of(
                register(LocalDate.of(2026, 1, 5), NASDAQ, "Common Stock"),
                remove(LocalDate.of(2026, 3, 1), NYSE, "Common Stock")), PAIR);

        assertThat(t.events()).extracting(ListedTimeline.Classified::kind)
                .containsExactly(ListedTimeline.Kind.UNRESOLVED, ListedTimeline.Kind.EXIT);
        assertThat(t.on(LocalDate.of(2026, 3, 2))).as("이탈 뒤 재진입이 언제였는지 원장이 안 말한다").isEqualTo(UNKNOWN);
        assertThat(t.on(LocalDate.of(2026, 2, 1))).isEqualTo(UNKNOWN);
        assertThat(t.on(ANCHOR)).isEqualTo(LISTED);
    }

    /** 이탈 뒤 진입 근거 없이 닻에서 안이다 — 원장이 모순이라 그 앞 날짜는 모른다(지어내지 않는다). */
    @Test
    void contradictionBeforeTheAnchorIsUnknown() {
        ListedTimeline t = ListedTimeline.of(ANCHOR, true, List.of(remove(LocalDate.of(2025, 11, 1), NASDAQ, "Common Stock")), PAIR);

        assertThat(t.on(LocalDate.of(2025, 12, 1))).as("재진입이 11-01과 닻 사이 어딘가다").isEqualTo(UNKNOWN);
        assertThat(t.on(LocalDate.of(2025, 10, 1))).isEqualTo(UNKNOWN);
        assertThat(t.on(ANCHOR)).isEqualTo(LISTED);
    }

    /** 폐지 실체 행 — 닻은 효력일 전날이고 그날의 제거가 그 뒤를 밖으로 만든다(앞으로 걷기). */
    @Test
    void removalAfterTheAnchorEndsTheListing() {
        LocalDate eve = LocalDate.of(2026, 8, 3);
        ListedTimeline ea = ListedTimeline.of(eve, true, List.of(remove(LocalDate.of(2026, 8, 4), NASDAQ, "Common Stock")), PAIR);

        assertThat(ea.on(eve)).isEqualTo(LISTED);
        assertThat(ea.on(LocalDate.of(2026, 8, 4))).isEqualTo(UNLISTED);
        assertThat(ea.on(LocalDate.of(2025, 10, 1))).isEqualTo(LISTED);
    }

    @Test
    void entryAfterTheAnchorWhileListedIsUnknown() {
        LocalDate eve = LocalDate.of(2026, 3, 1);
        ListedTimeline t = ListedTimeline.of(eve, true, List.of(
                register(LocalDate.of(2026, 3, 10), NYSE, "Common Stock"),
                remove(LocalDate.of(2026, 3, 20), AMERICAN, "Common Stock")), PAIR);

        assertThat(t.on(LocalDate.of(2026, 3, 15))).isEqualTo(UNKNOWN);
        assertThat(t.on(LocalDate.of(2026, 3, 5))).as("빠진 이탈이 닻과 3-10 사이 어딘가다").isEqualTo(UNKNOWN);
        assertThat(t.on(eve)).isEqualTo(LISTED);
    }

    /** 확인된 뒤집힘보다 닻 쪽 날짜는 그 뒤의 모순에 안 흔들린다. */
    @Test
    void contradictionFurtherAwayDoesNotPoisonConfirmedDates() {
        ListedTimeline t = ListedTimeline.of(ANCHOR, true, List.of(
                remove(LocalDate.of(2025, 7, 1), NASDAQ, "Common Stock"),
                remove(LocalDate.of(2025, 10, 1), NASDAQ, "Common Stock"),
                register(LocalDate.of(2026, 1, 5), NYSE, "Common Stock"),
                remove(LocalDate.of(2026, 1, 15), AMERICAN, "Common Stock")), PAIR);

        assertThat(t.on(LocalDate.of(2026, 2, 1))).isEqualTo(LISTED);
        assertThat(t.on(LocalDate.of(2025, 12, 1))).as("진입 앞 = 밖 · 10-01 이탈과 맞다").isEqualTo(UNLISTED);
        assertThat(t.on(LocalDate.of(2025, 9, 1))).as("10-01 이탈 앞은 안이어야 하는데 07-01이 또 이탈이다 — 빠진 진입이 그 사이").isEqualTo(UNKNOWN);
    }

    /** 보통주 행이 둘인 등록인 — 글자가 맞는 사건만 그 행의 것이고, 글자 없는 사건은 어느 행에도 안 붙는다. */
    @Test
    void sharedRegistrantMatchesEventsByClassLetter() {
        List<ListingEvent> events = List.of(
                remove(LocalDate.of(2026, 1, 5), NASDAQ, "Series A Common Stock"),
                remove(LocalDate.of(2026, 1, 5), NASDAQ, "Series C Common Stock"),
                remove(LocalDate.of(2026, 1, 5), NASDAQ, "Common Stock"));

        assertThat(ListedTimeline.forRow(events, true, Optional.of("A"))).extracting(ListingEvent::line)
                .containsExactly("Series A Common Stock");
        assertThat(ListedTimeline.forRow(events, true, Optional.empty())).isEmpty();
        assertThat(ListedTimeline.forRow(events, false, Optional.empty())).hasSize(3);
        assertThat(ListedTimeline.unmatched(events, List.of("A", "C"))).extracting(ListingEvent::line)
                .containsExactly("Common Stock");
    }

    @Test
    void issuerRemovalTakesEffectTenDaysAfterFiling() {
        ListingFormReader.Removal removal = new ListingFormReader.Removal(Optional.of(AMERICAN), "NYSE American LLC",
                List.of("Class A Common Stock - par value $0.10 per share", "Rights to purchase Series A Preferred Stock"), null);

        List<ListingEvent> events = ListingEvent.ofRemoval("25", LocalDate.of(2025, 12, 11), "a", removal);
        List<ListingEvent> nse = ListingEvent.ofRemoval("25-NSE", LocalDate.of(2025, 12, 11), "a", removal);

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.date()).isEqualTo(LocalDate.of(2025, 12, 21));
            assertThat(e.letter()).contains("A");
        });
        assertThat(nse).singleElement().satisfies(e -> assertThat(e.date()).isEqualTo(LocalDate.of(2025, 12, 11)));
    }

    private static ListingEvent register(LocalDate date, Venue venue, String line) {
        return new ListingEvent(date, venue, true, line, ClassLines.classLetter(line), "8-A12B", "r" + date);
    }

    private static ListingEvent remove(LocalDate date, Venue venue, String line) {
        return new ListingEvent(date, venue, false, line, ClassLines.classLetter(line), "25", "x" + date);
    }
}
