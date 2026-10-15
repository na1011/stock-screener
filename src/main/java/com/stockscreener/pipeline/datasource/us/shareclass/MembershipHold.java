package com.stockscreener.pipeline.datasource.us.shareclass;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 클래스 축 숨김 중의 편입 보류(탐색 {@code SHARES-CLASS-PLAN-V5-1015.md} §10b-1 · 배선 없음).
 *
 * <p>순수 함수다 — <b>앞 편입</b>과 <b>그날 발행사마다의 판정 이력</b>만 본다. 그래서 같은 날짜를 어느 실행일에
 * 돌려도 답이 같다(수용 기준 8).
 *
 * <ul>
 *   <li>그날 최신 판이 보이면 {@link State#SHOWN} — 순위에 그대로 든다.</li>
 *   <li>최신 판이 숨김이고 그 앞 판이 보였다 → 공시일에서 {@link #CAP_DAYS}일까지 {@link State#HELD}, 넘으면
 *       {@link State#EXPIRED}. <b>보류된 앞 편입은 마지막으로 보인 판의 N_L로 순위를 다툰다</b>(값 지표는 빈 채로).
 *       자리를 순위 없이 떼어 두지 않는다 — 드라이런 2에서 그 꼴이 마지막 보인 판으로 약 1,925위인 {@code TBLA}를
 *       119일 남기고 경계의 보인 편입 4~5를 매주 밀어냈다(탐색 §10e).</li>
 *   <li>앞 판도 숨김 → {@link State#CONSECUTIVE}(보류 끝 — 편출).</li>
 *   <li>숨긴 판 하나뿐 → {@link State#FIRST_HIDDEN}(후보 불가 — 보류가 아니다).</li>
 *   <li>원장 어긋남 구간이 {@link #EXCLUSION_DAYS}일 안에 두 번 시작하면 둘째 시작부터 첫 시작 + 371일까지
 *       {@link State#EXCLUDED}(발행사 제외).</li>
 * </ul>
 *
 * <p>판정 이력이 없는 발행사는 클래스 축 앵커가 아니라 지금 경로다 — {@code null}을 내고 순위에 그대로 든다.
 */
public final class MembershipHold {

    /** 보류 상한 — 단일 클래스 정기 공시 간격의 p95(★ {@code .factcache} 38,706쌍 · 보고 주기 하나). */
    public static final int CAP_DAYS = 119;

    /** 제외 창 — 미국 보존 창 255거래일이 덮는 달력일(★ 2025-10-08~2026-10-14). */
    public static final int EXCLUSION_DAYS = 371;

    private MembershipHold() {
    }

    /**
     * 발행사의 판정 하나. {@code ledgerContradiction}은 실패 출처가 원장(어긋남)인 숨김이다. {@code lineShares}는 보인 판의
     * 상장 줄 주식수(N_L — 앵커가 아니면 지금 경로의 값)이고 숨긴 판은 {@code null}이다.
     */
    public record Verdict(LocalDate filed, boolean hidden, boolean ledgerContradiction, Double lineShares) {
    }

    public enum State { SHOWN, HELD, EXPIRED, CONSECUTIVE, FIRST_HIDDEN, EXCLUDED }

    /** {@code day}의 상태. 그날까지 판정이 하나도 없으면 {@code null}(지금 경로). */
    public static State stateAt(List<Verdict> history, LocalDate day) {
        List<Verdict> known = history.stream().filter(v -> !v.filed().isAfter(day))
                .sorted(Comparator.comparing(Verdict::filed)).toList();
        if (known.isEmpty()) {
            return null;
        }
        if (excluded(known, day)) {
            return State.EXCLUDED;
        }
        Verdict last = known.get(known.size() - 1);
        if (!last.hidden()) {
            return State.SHOWN;
        }
        if (known.size() < 2) {
            return State.FIRST_HIDDEN;
        }
        if (known.get(known.size() - 2).hidden()) {
            return State.CONSECUTIVE;
        }
        return ChronoUnit.DAYS.between(last.filed(), day) <= CAP_DAYS ? State.HELD : State.EXPIRED;
    }

    /** {@code day}까지 마지막으로 보인 판. 없으면 {@code null}. */
    public static Verdict lastShown(List<Verdict> history, LocalDate day) {
        return history.stream().filter(v -> !v.filed().isAfter(day) && !v.hidden())
                .max(Comparator.comparing(Verdict::filed)).orElse(null);
    }

    private static boolean excluded(List<Verdict> known, LocalDate day) {
        List<LocalDate> starts = new ArrayList<>();
        boolean previousHidden = false;
        for (Verdict v : known) {
            if (v.hidden() && v.ledgerContradiction() && !previousHidden) {
                starts.add(v.filed());
            }
            previousHidden = v.hidden();
        }
        for (int i = 1; i < starts.size(); i++) {
            LocalDate until = starts.get(i - 1).plusDays(EXCLUSION_DAYS);
            if (starts.get(i).isBefore(until) && !day.isBefore(starts.get(i)) && day.isBefore(until)) {
                return true;
            }
        }
        return false;
    }

    /** 순위 후보 — 티커와 그날 시총(보이는 판의 값). */
    public record Candidate(String ticker, double marketCap) {
    }

    /**
     * @param held    앞 편입이면서 {@link State#HELD}라 마지막 보인 판의 값으로 남은 것
     * @param members 그날 순위의 앞 N
     */
    public record Selection(Set<String> members, Set<String> held) {
    }

    /**
     * @param ranked   숨김 말고 모든 거름을 통과한 후보(CIK 대표만 · 시총은 그 티커의 보이는 판 값)
     * @param previous 앞 편입
     * @param stateOf  티커 → 그날 상태(이력이 없으면 {@code null} — 지금 경로)
     * @param heldCap  보류 후보의 시총 = 마지막으로 보인 판의 N_L × 그날 종가(분할 반영). 못 내면 {@code null} — 보류하지 않는다
     */
    public static Selection select(List<Candidate> ranked, Set<String> previous, Function<String, State> stateOf,
                                   Function<String, Double> heldCap, int topN) {
        List<Candidate> pool = new ArrayList<>();
        Set<String> heldPool = new LinkedHashSet<>();
        for (Candidate c : ranked) {
            State s = stateOf.apply(c.ticker());
            if (s == null || s == State.SHOWN) {
                pool.add(c);
            } else if (s == State.HELD && previous.contains(c.ticker())) {
                Double cap = heldCap.apply(c.ticker());
                if (cap != null) {
                    pool.add(new Candidate(c.ticker(), cap));
                    heldPool.add(c.ticker());
                }
            }
        }
        pool.sort(Comparator.comparingDouble(Candidate::marketCap).reversed().thenComparing(Candidate::ticker));
        Set<String> members = new LinkedHashSet<>();
        Set<String> held = new LinkedHashSet<>();
        for (Candidate c : pool.subList(0, Math.min(topN, pool.size()))) {
            members.add(c.ticker());
            if (heldPool.contains(c.ticker())) {
                held.add(c.ticker());
            }
        }
        return new Selection(members, held);
    }
}
