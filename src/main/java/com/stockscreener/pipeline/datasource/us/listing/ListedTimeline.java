package com.stockscreener.pipeline.datasource.us.listing;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 한 행 증권의 거래소 범주 이력 — 그날 NYSE·Nasdaq에 상장돼 있었나(탐색 {@code US-PAST-LISTING-PLAN-1017.md} D5 · 구현 계획 U2).
 * 순수 함수다.
 *
 * <h2>닻 날짜에서 걷는다</h2>
 * <p>끝 상태는 행의 닻 날짜 범주다(구현 계획 §2.1) — 기존 행은 NYSE·NASDAQ 목록이나 단건 확인에서 만들어졌으므로 닻 날짜에 「안」이다(★A),
 * 폐지 실체 행은 사건 전 정기 공시 표지의 거래소다. 그 날짜에서 앞뒤로 사건을 걸어 그날의 범주를 낸다.
 *
 * <h2>사건을 넷으로 가른다 — 범주를 바꾸는 것은 둘뿐이다</h2>
 * <ul>
 *   <li><b>진입</b> — 안 거래소 등록(8-A12B) 중 같은 줄의 <b>밖 거래소 제거</b>가 짝으로 붙은 것(상향 이전 · LEU · BMNR).</li>
 *   <li><b>이탈</b> — 안 거래소 제거 중 같은 줄의 안 거래소 등록이 짝으로 안 붙은 것(장외 이전 · 폐지).</li>
 *   <li><b>이전</b> — 안 ↔ 안(NYSE ↔ Nasdaq). 범주가 안 바뀐다.</li>
 *   <li><b>미정</b> — 안 거래소 등록인데 어느 제거도 짝으로 안 붙은 것. 장외(OTC)에서 올라온 것일 수도, 승계 등록인의 재등록일 수도
 *       있어 원장이 가르지 않는다 — <b>범주를 안 바꾸고 센다.</b> 상장 전 날짜는 D4의 「첫 거래 봉 ≤ 그날」이 따로 거른다.</li>
 * </ul>
 * <p>짝의 창({@link #pairDays})은 <b>재지 않은 값</b>이다 — LEU · BMNR 모두 등록 뒤 18일(발행사 25 효력일)에 제거가 났다. R-D5가 정한다.
 *
 * <h2>모순은 지어내지 않는다</h2>
 * <p>걷다가 사건이 그 앞 상태와 안 맞으면(안인데 또 진입 · 밖인데 또 이탈) 그 날짜는 {@link Verdict#UNKNOWN}이다. 원장이 이랬다
 * 저랬다 하는 행을 특수 갈래로 잇지 않는다(사용자 2026-10-14) — 부르는 쪽이 후보에서 빼고 사유와 함께 센다.
 */
public final class ListedTimeline {

    public enum Kind { ENTRY, EXIT, TRANSFER, UNRESOLVED, OFF_VENUE }

    public enum Verdict { LISTED, UNLISTED, UNKNOWN }

    public record Classified(ListingEvent event, Kind kind) {
    }

    private final LocalDate anchor;
    private final boolean listedAtAnchor;
    private final List<Classified> events;

    private ListedTimeline(LocalDate anchor, boolean listedAtAnchor, List<Classified> events) {
        this.anchor = anchor;
        this.listedAtAnchor = listedAtAnchor;
        this.events = events;
    }

    /**
     * @param events   그 행 증권 줄의 사건(등록인 · 클래스 글자로 이미 거른 것)
     * @param pairDays 등록과 제거를 한 이전으로 묶는 창(일 · 효력일 사이)
     */
    public static ListedTimeline of(LocalDate anchor, boolean listedAtAnchor, List<ListingEvent> events, int pairDays) {
        return new ListedTimeline(anchor, listedAtAnchor, classify(events, pairDays));
    }

    static List<Classified> classify(List<ListingEvent> events, int pairDays) {
        List<Classified> out = new ArrayList<>();
        for (ListingEvent e : events) {
            Kind kind;
            if (!e.venue().listed()) {
                kind = Kind.OFF_VENUE;
            } else if (e.registration()) {
                if (anyNear(events, e, pairDays, false, true)) {
                    kind = Kind.TRANSFER;
                } else if (anyNear(events, e, pairDays, false, false)) {
                    kind = Kind.ENTRY;
                } else {
                    kind = Kind.UNRESOLVED;
                }
            } else {
                kind = anyNear(events, e, pairDays, true, true) ? Kind.TRANSFER : Kind.EXIT;
            }
            out.add(new Classified(e, kind));
        }
        out.sort(Comparator.comparing((Classified c) -> c.event().date()));
        return List.copyOf(out);
    }

    /** {@code e}와 효력일이 {@code pairDays} 안이고 등록 여부 · 거래소 범주가 주어진 대로인 다른 사건이 있나. */
    private static boolean anyNear(List<ListingEvent> events, ListingEvent e, int pairDays, boolean registration, boolean listed) {
        for (ListingEvent other : events) {
            if (other != e && other.registration() == registration && other.venue().listed() == listed
                    && Math.abs(ChronoUnit.DAYS.between(e.date(), other.date())) <= pairDays) {
                return true;
            }
        }
        return false;
    }

    /**
     * 그날의 범주. 사건은 효력일에 바뀐다 — 그날부터 새 상태다.
     *
     * <p>모순을 만나면 <b>빠진 사건이 어디였는지 원장이 안 말한다</b> — 닻 쪽에서 마지막으로 확인된 날(닻 또는 그 직전 뒤집힘)보다
     * 먼 날짜는 전부 {@link Verdict#UNKNOWN}이다. 모순 지점까지만 모르는 것이 아니다: 「이탈인데 닻에서 안」이면 재진입이 이탈과 닻
     * 사이 어디였는지 모른다.
     */
    public Verdict on(LocalDate date) {
        if (date.isBefore(anchor)) {
            return backward(date);
        }
        return forward(date);
    }

    private Verdict backward(LocalDate date) {
        // 닻에서 거꾸로 — 모순이면 빠진 사건이 그 사건과 직전 뒤집힘(또는 닻) 사이 어딘가다. 이 날짜는 그 직전 뒤집힘보다 앞이다.
        boolean state = listedAtAnchor;
        for (int i = events.size() - 1; i >= 0; i--) {
            Classified c = events.get(i);
            LocalDate t = c.event().date();
            if (t.isAfter(anchor) || !flips(c.kind())) {
                continue;
            }
            boolean consistent = c.kind() == Kind.ENTRY ? state : !state;
            if (!consistent) {
                return Verdict.UNKNOWN;
            }
            if (!t.isAfter(date)) {
                break;
            }
            state = !state;
        }
        return verdict(state);
    }

    private Verdict forward(LocalDate date) {
        // 닻에서 앞으로 — 모순이면 빠진 사건이 직전 뒤집힘(또는 닻)과 그 사건 사이 어딘가다. 그 직전 뒤집힘 날만 확실하다.
        boolean state = listedAtAnchor;
        LocalDate lastFlip = anchor;
        for (Classified c : events) {
            LocalDate t = c.event().date();
            if (!t.isAfter(anchor) || !flips(c.kind())) {
                continue;
            }
            boolean consistent = c.kind() == Kind.ENTRY ? !state : state;
            if (!consistent) {
                return date.equals(lastFlip) ? verdict(state) : Verdict.UNKNOWN;
            }
            if (t.isAfter(date)) {
                break;
            }
            state = !state;
            lastFlip = t;
        }
        return verdict(state);
    }

    private static Verdict verdict(boolean listed) {
        return listed ? Verdict.LISTED : Verdict.UNLISTED;
    }

    private static boolean flips(Kind kind) {
        return kind == Kind.ENTRY || kind == Kind.EXIT;
    }

    /**
     * 등록인의 사건 중 그 행 증권의 것. 등록인에 보통주 행이 하나면 전부이고, 둘 이상이면 클래스 글자가 행의 글자와 같은 것만이다 —
     * 글자가 없는 사건은 어느 행의 것인지 원장이 안 말하므로 안 쓴다(부르는 쪽이 {@link #unmatched}로 센다).
     *
     * @param rowLetter 그 행의 클래스 글자(표지의 그 기호 줄 증권 이름에서) — 모르면 비었다
     */
    public static List<ListingEvent> forRow(List<ListingEvent> registrantEvents, boolean sharedRegistrant,
                                            java.util.Optional<String> rowLetter) {
        if (!sharedRegistrant) {
            return registrantEvents;
        }
        return registrantEvents.stream()
                .filter(e -> rowLetter.isPresent() && e.letter().isPresent() && e.letter().get().equals(rowLetter.get()))
                .toList();
    }

    /** {@link #forRow}가 어느 행에도 못 붙인 사건 — 보통주 행이 둘 이상인 등록인의 것만 해당한다. */
    public static List<ListingEvent> unmatched(List<ListingEvent> registrantEvents, java.util.Collection<String> rowLetters) {
        return registrantEvents.stream()
                .filter(e -> e.letter().isEmpty() || !rowLetters.contains(e.letter().get()))
                .toList();
    }

    /** 판정에 쓴 사건과 그 갈래 — 실행 기록의 귀속 재료다. */
    public List<Classified> events() {
        return events;
    }
}
