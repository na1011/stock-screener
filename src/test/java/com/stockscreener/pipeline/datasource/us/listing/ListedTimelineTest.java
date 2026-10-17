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
        assertThat(leu.on(LocalDate.of(2025, 12, 5))).as("등록 제출일 ~ 옛 거래소 제거 효력일 사이는 원장이 이전일을 안 말한다").isEqualTo(UNKNOWN);
        assertThat(leu.on(LocalDate.of(2025, 12, 21))).isEqualTo(LISTED);
        assertThat(leu.on(LocalDate.of(2026, 3, 6))).isEqualTo(LISTED);
        assertThat(leu.events()).extracting(ListedTimeline.Classified::kind)
                .containsExactly(ListedTimeline.Kind.ENTRY, ListedTimeline.Kind.OFF_VENUE);
    }

    /** 발행사 25 설명문이 이전일을 적으면 그날이 진입이다(UAMY: 8-A12B 03-06 · 설명문 03-11 · R-D5). */
    @Test
    void statedTransferDateIsTheEntryDate() {
        ListingEvent removal = new ListingEvent(LocalDate.of(2026, 3, 26), AMERICAN, false, "Common Stock", Optional.empty(), "25", "x",
                Optional.of(LocalDate.of(2026, 3, 11)));
        ListedTimeline uamy = ListedTimeline.of(ANCHOR, true, List.of(register(LocalDate.of(2026, 3, 6), NYSE, "Common Stock"), removal), PAIR);

        assertThat(uamy.on(LocalDate.of(2026, 3, 6))).as("격자일 03-06은 아직 NYSE American이었다").isEqualTo(UNLISTED);
        assertThat(uamy.on(LocalDate.of(2026, 3, 10))).isEqualTo(UNLISTED);
        assertThat(uamy.on(LocalDate.of(2026, 3, 11))).isEqualTo(LISTED);
    }

    /** 대조군: BMNR은 4월 상향 전 NYSE American이었다(탐색 ★10 — 복원 편입 2025-11-21부터가 틀렸다). */
    @Test
    void bmnrWasOutsideBeforeItsUplisting() {
        ListedTimeline bmnr = ListedTimeline.of(ANCHOR, true, List.of(
                register(LocalDate.of(2026, 4, 8), NYSE, "Common Stock, par value $0.0001 per share"),
                remove(LocalDate.of(2026, 4, 26), AMERICAN, "Common Stock, par value $0.0001 per share")), PAIR);

        assertThat(bmnr.on(LocalDate.of(2025, 11, 21))).isEqualTo(UNLISTED);
        assertThat(bmnr.on(LocalDate.of(2026, 4, 7))).isEqualTo(UNLISTED);
        assertThat(bmnr.on(LocalDate.of(2026, 4, 10))).isEqualTo(UNKNOWN);
        assertThat(bmnr.on(LocalDate.of(2026, 4, 27))).isEqualTo(LISTED);
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
                .containsExactly(ListedTimeline.Kind.UNRESOLVED, ListedTimeline.Kind.REPLACED);
        assertThat(t.on(LocalDate.of(2026, 3, 2))).isEqualTo(LISTED);
        assertThat(t.on(LocalDate.of(2026, 2, 1))).isEqualTo(LISTED);
    }

    /** 짝 없는 보통주 제거 뒤 다시 등록이 없는데 닻에서 상장 — 증권 교체다(FLG 은행 승계꼴 · R-D5). 범주를 안 바꾼다. */
    @Test
    void removalWithoutRelistingBeforeTheAnchorIsAReplacement() {
        ListedTimeline t = ListedTimeline.of(ANCHOR, true, List.of(remove(LocalDate.of(2025, 11, 1), NASDAQ, "Common Stock")), PAIR);

        assertThat(t.events()).extracting(ListedTimeline.Classified::kind).containsExactly(ListedTimeline.Kind.REPLACED);
        assertThat(t.on(LocalDate.of(2025, 12, 1))).isEqualTo(LISTED);
        assertThat(t.on(LocalDate.of(2025, 10, 1))).isEqualTo(LISTED);
    }

    /** 장외로 갔다가 다시 올라옴 — 짝 없는 등록이 앞 이탈을 닫으면 재진입이다. */
    @Test
    void relistingAfterAnExitReopensTheListing() {
        ListedTimeline t = ListedTimeline.of(ANCHOR, true, List.of(
                remove(LocalDate.of(2025, 11, 1), NASDAQ, "Common Stock"),
                register(LocalDate.of(2026, 3, 2), NASDAQ, "Common Stock")), PAIR);

        assertThat(t.events()).extracting(ListedTimeline.Classified::kind)
                .containsExactly(ListedTimeline.Kind.EXIT, ListedTimeline.Kind.ENTRY);
        assertThat(t.on(LocalDate.of(2025, 10, 1))).isEqualTo(LISTED);
        assertThat(t.on(LocalDate.of(2025, 12, 1))).isEqualTo(UNLISTED);
        assertThat(t.on(LocalDate.of(2026, 3, 2))).isEqualTo(LISTED);
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

    /** 닻 뒤에 상장 중인데 또 진입 — 빠진 이탈이 닻과 그 사이 어딘가라 모른다(지어내지 않는다). */
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

    /** 이탈 · 진입이 이어진 이력 — 이탈 둘 중 다시 등록이 닫는 것만 이탈이다. */
    @Test
    void onlyTheExitClosedByARegistrationIsAnExit() {
        ListedTimeline t = ListedTimeline.of(ANCHOR, true, List.of(
                remove(LocalDate.of(2025, 7, 1), NASDAQ, "Common Stock"),
                remove(LocalDate.of(2025, 10, 1), NASDAQ, "Common Stock"),
                register(LocalDate.of(2026, 1, 5), NYSE, "Common Stock"),
                remove(LocalDate.of(2026, 1, 15), AMERICAN, "Common Stock")), PAIR);

        assertThat(t.on(LocalDate.of(2026, 2, 1))).isEqualTo(LISTED);
        assertThat(t.on(LocalDate.of(2025, 12, 1))).as("진입 앞 = 밖 · 10-01 이탈과 맞다").isEqualTo(UNLISTED);
        assertThat(t.on(LocalDate.of(2025, 9, 1))).as("07-01 제거는 다시 등록 없이 10-01 제거가 뒤따라 교체다").isEqualTo(LISTED);
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
                List.of("Class A Common Stock - par value $0.10 per share", "Rights to purchase Series A Preferred Stock"), null,
                Optional.empty());

        List<ListingEvent> events = ListingEvent.ofRemoval("25", LocalDate.of(2025, 12, 11), "a", removal);
        List<ListingEvent> nse = ListingEvent.ofRemoval("25-NSE", LocalDate.of(2025, 12, 11), "a", removal);

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.date()).isEqualTo(LocalDate.of(2025, 12, 21));
            assertThat(e.letter()).contains("A");
        });
        assertThat(nse).singleElement().satisfies(e -> assertThat(e.date()).isEqualTo(LocalDate.of(2025, 12, 11)));
    }

    private static ListingEvent register(LocalDate date, Venue venue, String line) {
        return new ListingEvent(date, venue, true, line, ClassLines.classLetter(line), "8-A12B", "r" + date, Optional.empty());
    }

    private static ListingEvent remove(LocalDate date, Venue venue, String line) {
        return new ListingEvent(date, venue, false, line, ClassLines.classLetter(line), "25", "x" + date, Optional.empty());
    }
}
