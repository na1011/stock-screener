package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.datasource.IdentitySource.IdentityEvent;
import com.stockscreener.pipeline.datasource.IdentitySource.RegistrantFiling;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 행 끊기의 전제(ADR-0180 · 계획 v3.1 D2 「끊기의 전제」). 되돌릴 수 없는 일이라 판 하나의 불일치로 하지 않는다 — 셋이 다 서야 끊는다.
 * <ol>
 *   <li>그 행의 저장 접수번호가 새 등록인 companyfacts에 <b>하나도 없다</b>. 같은 등록인의 표기 변경이면 들어 있다.</li>
 *   <li>끝의 증거가 있다 — Alpaca 신원 사건(개명 · 재편 · 피인수 합병의 인수 쪽) 또는 SEC 날짜 박힌 양식(앞 등록인의
 *       {@code 25-NSE}·{@code 15-12*} · 뒤 등록인의 {@code 8-K12B}).</li>
 *   <li>교체일이 정해진다 — 증거의 날짜. 둘 다 있으면 Alpaca 사건일이다(그날 증권이 바뀌었다 · SEC 양식은 그 앞뒤 며칠에 낸다).</li>
 * </ol>
 * 입출력이 없다 — 재료는 호출부가 받는다.
 */
public final class SplitEvidence {

    private SplitEvidence() {
    }

    /** SEC 끝의 증거 양식 — 앞 등록인 쪽. */
    static boolean endsOldRegistration(String form) {
        return form.equals("25-NSE") || form.equals("25") || form.startsWith("15-12");
    }

    /** SEC 끝의 증거 양식 — 뒤 등록인 쪽(승계 등록). */
    static boolean startsSuccessorRegistration(String form) {
        return form.equals("8-K12B") || form.equals("8-K12G3");
    }

    /**
     * @param storedAccessions 그 행의 저장 재무 접수번호
     * @param newAccessions    새 등록인 companyfacts의 접수번호
     * @param events           옛·새 기호의 신원 사건(창 안)
     * @param oldFilings       앞 등록인의 공시(창 안)
     * @param newFilings       뒤 등록인의 공시(창 안)
     * @return 교체일. 전제가 하나라도 안 서면 빈 값과 그 사유
     */
    public static Decision decide(Set<String> storedAccessions, Set<String> newAccessions, List<IdentityEvent> events,
                                  List<RegistrantFiling> oldFilings, List<RegistrantFiling> newFilings) {
        long shared = storedAccessions.stream().filter(newAccessions::contains).count();
        if (shared > 0) {
            return Decision.no("저장 접수번호 %d 중 %d가 새 등록인 companyfacts에 있다 — 같은 등록인의 표기 변경일 수 있다"
                    .formatted(storedAccessions.size(), shared));
        }
        Optional<IdentityEvent> event = events.stream().max(Comparator.comparing(IdentityEvent::date));
        Optional<RegistrantFiling> filing = latest(oldFilings.stream()
                .filter(f -> endsOldRegistration(f.form())).toList(), newFilings.stream()
                .filter(f -> startsSuccessorRegistration(f.form())).toList());
        String accessionBasis = "저장 접수번호 %d · 새 등록인 접수번호 %d · 공유 0".formatted(storedAccessions.size(), newAccessions.size());
        if (event.isPresent()) {
            IdentityEvent e = event.get();
            return Decision.yes(e.date(), "%s · Alpaca %s %s %s%s".formatted(accessionBasis, e.type(), e.date(), e.detail(),
                    filing.map(f -> " · SEC " + f.form() + " " + f.filed()).orElse("")));
        }
        if (filing.isPresent()) {
            RegistrantFiling f = filing.get();
            return Decision.yes(f.filed(), "%s · SEC %s %s".formatted(accessionBasis, f.form(), f.filed()));
        }
        return Decision.no(accessionBasis + " · 끝의 증거 없음(Alpaca 신원 사건 0 · SEC 25-NSE·15-12*·8-K12B 0)");
    }

    private static Optional<RegistrantFiling> latest(Collection<RegistrantFiling> a, Collection<RegistrantFiling> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).max(Comparator.comparing(RegistrantFiling::filed));
    }

    /** @param switchDate 교체일 — 앞 행의 era_to(제외) · 뒤 행의 era_from(포함) */
    public record Decision(Optional<LocalDate> switchDate, String evidence) {
        static Decision yes(LocalDate date, String evidence) {
            return new Decision(Optional.of(date), evidence);
        }

        static Decision no(String evidence) {
            return new Decision(Optional.empty(), evidence);
        }
    }
}
