package com.stockscreener.pipeline.datasource.us.listing;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 예탁증권 줄 발행사의 정기 공시마다 「1 ADS = k주」를 정한다(관문 뒤 · ADR-0183). 순수 함수다 — 받기와 저장은 부르는 쪽이 한다.
 *
 * <p>규칙(탐색 {@code explore/universe/ADS-B2-PLAN-1022.md} §2 · 드라이런 1 — 77 CIK 2019 뒤 747공시: 자기 표지 552 · 태그 6 · 앞 표지
 * 이어받음 32 · 뒤 표지 이어받음 64 · 미정 93 · 최신 판 미정이 순위 문턱에 닿는 칸 0):
 * <ol>
 *   <li><b>자기 표지</b>: 그 공시 표지가 그 기호 줄의 비율을 읽히게 실으면 그것({@link DepositaryCover#read}).</li>
 *   <li><b>이어받기</b>: 못 읽으면(표지 없음 · 글이 잘림 · 갈림) 가장 가까운 자기 표지 공시의 k를 앞(먼저)으로 · 없으면 뒤로 잇는다 —
 *       <b>그 사이 ADS 원장 사건이 없고 보통주가 1.5배 안일 때만.</b> 1.5배를 빼면 BABA 2019(보통주 8:1 분할 · ADS 사건 없음 · 비율 1 → 8)
 *       앞 판이 뒤 표지 k = 8을 잘못 받는다. 그 조건이 실제 희석(AKTX 2024)까지 막는 것은 감수한다 — 막힌 판은 미정(숨김)이지 틀린 값이
 *       아니다.</li>
 *   <li><b>ADS 단위 이탈</b>: k ≥ 2인데 그 판의 주식수가 앞의 보통주 단위 판보다 (원장 사건 반영 뒤) 1/k ±5%로 작으면 그 판은 이미 ADS
 *       단위로 실었다 → 그 판의 k = 1(CMMB 10-Q 11.4M 대 10-K 228M · k 20). 앞 판만 본다(시점). 그 밖의 계단은 숨기지 않는다 — 실제
 *       희석까지 숨기게 된다.</li>
 * </ol>
 */
public final class DepositaryRatioJudge {

    /** 이어받기를 막는 보통주 변화 배수. */
    static final double CARRY_LIMIT = 1.5;
    /** ADS 단위 이탈로 읽는 허용. */
    static final double UNIT_TOLERANCE = 0.05;
    /**
     * 원장 사건이 있다고 볼 수 있는 첫날. 그 앞 공시 사이의 「사건 없음」은 뜻이 없다 — 소스(Alpaca)의 분할 이력이 2019 무렵부터다
     * (2026-10-22: 대조군 NFLX 2015-07 7:1까지 2015~2018 0건 · 가장 이른 값 HDB 2019-09). 그 앞 공시는 잇지 않는다(폴백 근거의 이식).
     */
    public static final LocalDate LEDGER_FROM = LocalDate.of(2019, 1, 1);

    /**
     * @param reading         그 공시 표지 읽기(표지가 없거나 이름 줄이 없으면 null)
     * @param ordinaryShares  그 공시가 실은 보통주 주식수(없으면 null)
     */
    public record Filing(String accession, LocalDate filed, DepositaryCover.Reading reading, BigDecimal ordinaryShares) {
    }

    /** @param adsFactor 그날 ADS 수에 곱해지는 배수(정방향 1 → 10이면 10 · 역방향 10 → 1이면 0.1) */
    public record Event(LocalDate exDate, double adsFactor) {
    }

    public enum How { OWN, NOT_DEPOSITARY, CARRY_FORWARD, CARRY_BACK, ADS_UNIT, UNRESOLVED }

    /** @param k 정한 k(미정이면 null) */
    public record Decision(Double k, How how) {
    }

    private DepositaryRatioJudge() {
    }

    public static Map<String, Decision> decide(List<Filing> filings, List<Event> events) {
        List<Filing> ordered = new ArrayList<>(filings);
        ordered.sort(Comparator.comparing(Filing::filed).thenComparing(Filing::accession));
        int n = ordered.size();
        Double[] k = new Double[n];
        How[] how = new How[n];
        for (int i = 0; i < n; i++) {
            DepositaryCover.Reading r = ordered.get(i).reading();
            if (r != null && r.kind() == DepositaryCover.ReadingKind.RATIO) {
                k[i] = r.sharesPerDepositary();
                how[i] = How.OWN;
            } else if (r != null && r.kind() == DepositaryCover.ReadingKind.NOT_DEPOSITARY) {
                k[i] = 1.0;
                how[i] = How.NOT_DEPOSITARY;
            }
        }
        for (int i = 0; i < n; i++) {
            if (how[i] != null) {
                continue;
            }
            Filing f = ordered.get(i);
            if (f.filed().isBefore(LEDGER_FROM)) {
                how[i] = How.UNRESOLVED;
                continue;
            }
            for (int j = i - 1; j >= 0; j--) {
                if (how[j] == How.OWN) {
                    if (!ordered.get(j).filed().isBefore(LEDGER_FROM)
                            && !eventBetween(events, ordered.get(j).filed(), f.filed()) && near(ordered.get(j), f)) {
                        k[i] = k[j];
                        how[i] = How.CARRY_FORWARD;
                    }
                    break;
                }
            }
            if (how[i] != null) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                if (how[j] == How.OWN) {
                    if (!eventBetween(events, f.filed(), ordered.get(j).filed()) && near(ordered.get(j), f)) {
                        k[i] = k[j];
                        how[i] = How.CARRY_BACK;
                    }
                    break;
                }
            }
            if (how[i] == null) {
                how[i] = How.UNRESOLVED;
            }
        }
        Map<String, Decision> out = new LinkedHashMap<>();
        Filing lastOrdinary = null;
        double lastOrdinaryAds = 0;
        for (int i = 0; i < n; i++) {
            Filing f = ordered.get(i);
            Double ki = k[i];
            How hi = how[i];
            if (ki != null && f.ordinaryShares() != null && f.ordinaryShares().signum() > 0) {
                double ads = f.ordinaryShares().doubleValue() / ki;
                if (ki >= 2 && lastOrdinary != null) {
                    double expected = lastOrdinaryAds * factorBetween(events, lastOrdinary.filed(), f.filed());
                    if (Math.abs(ads / expected * ki - 1) <= UNIT_TOLERANCE) {
                        out.put(f.accession(), new Decision(1.0, How.ADS_UNIT));
                        continue;
                    }
                }
                lastOrdinary = f;
                lastOrdinaryAds = ads;
            }
            out.put(f.accession(), new Decision(ki, hi));
        }
        return out;
    }

    /** 보통주가 둘 다 있고 1.5배 안인가. 하나라도 없으면 잇지 않는다 — 보통주 분할을 못 가른다. */
    private static boolean near(Filing a, Filing b) {
        if (a.ordinaryShares() == null || b.ordinaryShares() == null
                || a.ordinaryShares().signum() <= 0 || b.ordinaryShares().signum() <= 0) {
            return false;
        }
        BigDecimal hi = a.ordinaryShares().max(b.ordinaryShares());
        BigDecimal lo = a.ordinaryShares().min(b.ordinaryShares());
        return hi.divide(lo, MathContext.DECIMAL64).doubleValue() <= CARRY_LIMIT;
    }

    /** (from, to] 안에 사건이 있는가. */
    private static boolean eventBetween(List<Event> events, LocalDate from, LocalDate to) {
        return events.stream().anyMatch(e -> e.exDate().isAfter(from) && !e.exDate().isAfter(to));
    }

    private static double factorBetween(List<Event> events, LocalDate from, LocalDate to) {
        double f = 1;
        for (Event e : events) {
            if (e.exDate().isAfter(from) && !e.exDate().isAfter(to)) {
                f *= e.adsFactor();
            }
        }
        return f;
    }
}
