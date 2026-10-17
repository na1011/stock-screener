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
 *       있어 원장이 가르지 않는다 — <b>범주를 안 바꾸고 센다</b>(앞의 이탈을 닫을 때만 재진입이다). 상장 전 날짜는 D4의 「첫 거래 봉 ≤
 *       그날」이 따로 거른다.</li>
 *   <li><b>교체</b> — 닻 앞의 이탈인데 그 뒤 닻까지 다시 등록이 없는 것({@link #settleExits}). 범주를 안 바꾼다.</li>
 * </ul>
 * <p>짝의 창({@link #pairDays})은 <b>재지 않은 값</b>이다 — LEU · BMNR 모두 등록 뒤 18일(발행사 25 효력일)에 제거가 났다. R-D5가 정한다.
 *
 * <h2>모순은 지어내지 않는다</h2>
 * <p>걷다가 사건이 그 앞 상태와 안 맞으면(안인데 또 진입 · 밖인데 또 이탈) 그 날짜는 {@link Verdict#UNKNOWN}이다. 원장이 이랬다
 * 저랬다 하는 행을 특수 갈래로 잇지 않는다(사용자 2026-10-14) — 부르는 쪽이 후보에서 빼고 사유와 함께 센다.
 */
public final class ListedTimeline {

    /**
     * 등록과 제거를 한 이전으로 묶는 창(일 · 효력일 사이). R-D5(2026-10-18)에서 이전 0~19일 · 상향 10~20일이었고 짝 없는 등록 108 중
     * 120일 안에 제거가 붙는 것이 0이라 이 값에 결과가 민감하지 않다.
     */
    public static final int PAIR_DAYS = 30;

    public enum Kind { ENTRY, EXIT, TRANSFER, UNRESOLVED, REPLACED, OFF_VENUE }

    public enum Verdict { LISTED, UNLISTED, UNKNOWN }

    /**
     * @param uncertainUntil 진입의 이전일을 원장이 특정하지 않을 때 그 구간의 끝(제외) — 등록 제출일 ≤ 이전일 &lt; 이것(짝인 밖 거래소
     *                       제거의 효력일). 설명문이 이전일을 적었으면 사건 날짜가 그날이고 이것은 {@code null}이다
     */
    public record Classified(ListingEvent event, Kind kind, LocalDate uncertainUntil) {

        Classified(ListingEvent event, Kind kind) {
            this(event, kind, null);
        }
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
        return new ListedTimeline(anchor, listedAtAnchor, settleExits(classify(events, pairDays), anchor));
    }

    /**
     * 닻 앞의 이탈을 가린다 — 그 뒤 닻까지 다시 등록(진입 · 미정)이 있어야 상장이 끊긴 것이고, 없으면 <b>증권 교체</b>다
     * ({@link Kind#REPLACED} · 범주 그대로). 닻 날짜에 상장이었다는 것이 그 제거가 상장 끝이 아니었다고 말한다 — R-D5(2026-10-18)에서
     * 짝 없는 보통주 제거 14가 전부 그 꼴이었다(FLG 은행 승계 · QGEN · CWK · GTES 본사 이전 · FWONA 재분류 · SOS · DEC 예탁증권 전환).
     * 이탈을 닫는 등록이 미정이면 그 등록이 재진입이다(장외에서 다시 올라옴). 닻 뒤의 이탈은 그대로 이탈이다(그 뒤는 원장이 말한다).
     */
    static List<Classified> settleExits(List<Classified> ordered, LocalDate anchor) {
        List<Classified> out = new ArrayList<>(ordered);
        for (int i = 0; i < out.size(); i++) {
            Classified exit = out.get(i);
            if (exit.kind() != Kind.EXIT || exit.event().date().isAfter(anchor)) {
                continue;
            }
            int closer = -1;
            for (int j = i + 1; j < out.size(); j++) {
                Classified next = out.get(j);
                if (next.event().date().isAfter(anchor) || next.kind() == Kind.EXIT) {
                    break;
                }
                if (next.kind() == Kind.ENTRY || next.kind() == Kind.UNRESOLVED) {
                    closer = j;
                    break;
                }
            }
            if (closer < 0) {
                out.set(i, new Classified(exit.event(), Kind.REPLACED));
            } else if (out.get(closer).kind() == Kind.UNRESOLVED) {
                out.set(closer, new Classified(out.get(closer).event(), Kind.ENTRY, out.get(closer).uncertainUntil()));
            }
        }
        return List.copyOf(out);
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
                    out.add(entry(events, e, pairDays));
                    continue;
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

    /**
     * 진입의 날짜 — 짝인 밖 거래소 제거의 설명문이 이전일을 적었으면 그날, 아니면 등록 제출일부터 그 제거의 효력일까지가 모르는 구간이다
     * (R-D5 2026-10-18: 설명문이 있던 LEU · UAMY에서 이전일이 등록 제출일보다 1일 · 5일 늦었다 — 제출일을 이전일로 쓰면 UAMY는 격자일
     * 03-06을 틀린다).
     */
    private static Classified entry(List<ListingEvent> events, ListingEvent registration, int pairDays) {
        ListingEvent partner = null;
        for (ListingEvent other : events) {
            if (!other.registration() && !other.venue().listed()
                    && Math.abs(ChronoUnit.DAYS.between(registration.date(), other.date())) <= pairDays
                    && (partner == null || Math.abs(ChronoUnit.DAYS.between(registration.date(), other.date()))
                    < Math.abs(ChronoUnit.DAYS.between(registration.date(), partner.date())))) {
                partner = other;
            }
        }
        if (partner.transferredOn().isPresent()) {
            ListingEvent dated = new ListingEvent(partner.transferredOn().get(), registration.venue(), true, registration.line(),
                    registration.letter(), registration.form(), registration.accession(), partner.transferredOn());
            return new Classified(dated, Kind.ENTRY);
        }
        return new Classified(registration, Kind.ENTRY, partner.date().isAfter(registration.date()) ? partner.date() : null);
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
        for (Classified c : events) {
            if (c.uncertainUntil() != null && !date.isBefore(c.event().date()) && date.isBefore(c.uncertainUntil())) {
                return Verdict.UNKNOWN;
            }
        }
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
