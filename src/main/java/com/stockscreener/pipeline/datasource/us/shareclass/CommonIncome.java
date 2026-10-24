package com.stockscreener.pipeline.datasource.us.shareclass;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 보통주 귀속 이익 — 주당 지표(PER · EPS 계열 · 배당성향)의 분자를 (공시, 기간)마다 원장대로 정한다(관문 뒤 · 탐색
 * {@code PERSHARE-NUM-PLAN-1024.md} v2.1).
 *
 * <p>원장의 주당순이익 정의(ASC 260 · IAS 33)는 지배주주 순이익에서 우선주 배당 · 참가증권 배분 · GP 배분을 뺀 보통주 몫이다. 지배주주
 * 순이익을 그대로 보통주 수로 나누면 그 몫이 주당 지표에 얹힌다 — 최신 FY에 NIC를 실은 334종목 중 100이 1% 넘게 갈렸고 HPE · APG ·
 * LYV · JXN은 보통주 몫이 적자다(2026-10-24 팩트 캐시).
 *
 * <p>사다리(키마다 첫 번째로 서는 것):
 * <ol>
 *   <li>{@code decl} — 선언 NIC. EPS × W가 있으면 부호와 자릿수(10배)가 맞아야 받는다(CTVA 2023 Q1 0.84 · PCVX 부호 반대).</li>
 *   <li>{@code adj} — 선언 배분(우선주 · 참가증권 · GP — 묶음마다 하나)을 NI에서 뺀 값이 EPS × W와 닫힘.</li>
 *   <li>{@code ni} — 그 키에 배분 태그가 하나도 없고 NI가 EPS × W와 닫힘 → 배분 0(값 대신 표지 · 읽는 쪽이 행 지배 NI를 쓴다 —
 *       행 값은 어댑터가 mistag까지 고친 것이다).</li>
 *   <li>{@code lp} — LP 배분이 EPS × W와 닫힘.</li>
 *   <li>{@code eps} — 클래스 축 앵커가 아니면 EPS × W(원장의 주당이익 × 같은 공시의 분모).</li>
 *   <li>{@code cls} — 클래스 축 앵커이거나 축 없는 EPS가 없으면 인스턴스의 클래스 구성원 Σ EPS_m × W_m(NI와 닫히면 {@code ni}).</li>
 *   <li>{@code none} — 못 정함(지표는 그 창을 안 낸다).</li>
 * </ol>
 *
 * <p>NI는 {@code NetIncomeLoss}, 없으면 그 키의 {@code ProfitLoss − 비지배}(CAT · MA는 키에 NetIncomeLoss가 없다). W는 그 공시 당기이고
 * 판정이 지금 경로면 판정 n, 그 밖(비교열 · 연간 · 정정 · 6-K)은 그 키의 가중평균이 표지(없으면 그 공시일의 기준 표지)와 10배 자릿수
 * 안일 때 — ±12.7%(표지 증인)는 비교열에서 주식수가 그만큼 바뀐 발행사의 정당한 W를 버렸다(481키 · 172종목).
 */
public final class CommonIncome {

    public enum Basis { T, C }

    /**
     * @param common      보통주 귀속 이익({@code ni} · {@code none}이면 null)
     * @param basis       {@code C} = 계속영업 EPS에서 지은 값(중단을 다시 빼지 않는다) · 그 밖 {@code T}(중단 포함 합)
     * @param filed       그 공시의 제출일(행 → 키를 고르는 자리가 쓴다)
     * @param tolerance   {@code eps} · {@code cls} 값의 원장 반올림 폭 — 행 지배 NI가 그 안이면 배분 0이다(키에 NI가 없는 공시 · A FY2015)
     * @param income      그 키의 지배 NI 후보(없으면 null) — 다른 판의 행에 배분(= 이것 − 보통주 몫)을 옮길 때 쓴다
     */
    public record Value(Double common, Basis basis, String branch, LocalDate filed, double tolerance, Double income) {
        public Value(Double common, Basis basis, String branch, LocalDate filed) {
            this(common, basis, branch, filed, 0, null);
        }

        public boolean sameAsControlling() {
            return "ni".equals(branch);
        }

        public boolean decided() {
            return !"none".equals(branch);
        }
    }

    private static final int SAME_END_DAYS = 3;
    private static final Set<String> PERIODIC = Set.of("10-K", "10-Q", "20-F", "40-F");

    private CommonIncome() {
    }

    /**
     * @param cf        단위 확인을 지난 재료({@code IssuerTimeline.prepare})
     * @param refs      정기 공시마다 기준 표지(그 공시 + 앞 넷 중앙값)
     * @param instances 받아 둔 인스턴스(접수번호 → 팩트 · 보충 · 앵커)
     * @param verdicts  그 발행사의 판정
     */
    static Map<IssuerFacts.Key, Value> judge(IssuerFacts cf, Map<String, Double> refs, Map<String, ClassFacts> instances,
                                             List<IssuerTimeline.Verdict> verdicts, List<IssuerTimeline.Split> splits) {
        Map<String, IssuerTimeline.Verdict> byAccession = new LinkedHashMap<>();
        verdicts.forEach(v -> byAccession.put(v.accession(), v));
        IssuerFacts.Numerators n = cf.numerators();
        Set<IssuerFacts.Key> keys = new LinkedHashSet<>();
        for (Map<IssuerFacts.Key, Double> m : List.of(cf.declaredNumerator(), n.netIncome(), n.profitLoss(), n.totalEps(),
                n.continuingEps(), n.limitedPartners())) {
            keys.addAll(m.keySet());
        }
        Map<IssuerFacts.Key, Value> out = new LinkedHashMap<>();
        for (IssuerFacts.Key k : keys) {
            if (!cf.filings().containsKey(k.accession())) {
                continue;
            }
            ClassFacts facts = instances.get(k.accession());
            Material m = materialOf(cf, k);
            out.put(k, judgeKey(k, facts == null ? m : m.orElse(materialOf(facts, k.period())), cf, refs, facts,
                    byAccession.get(k.accession()), splits));
        }
        // companyfacts에 없는 공시(보충) · 기간 — 인스턴스의 축 없는 값이 유일한 재료다.
        instances.forEach((a, facts) -> {
            if (facts == null || !cf.filings().containsKey(a)) {
                return;
            }
            for (ClassFacts.Period p : instancePeriods(facts)) {
                IssuerFacts.Key k = new IssuerFacts.Key(a, p);
                if (!out.containsKey(k)) {
                    out.put(k, judgeKey(k, materialOf(facts, p), cf, refs, facts, byAccession.get(a), splits));
                }
            }
        });
        return out;
    }

    /** 한 키의 재료. 값은 없으면 null. */
    record Material(Double nic, Double netIncome, Double profitLoss, Double noncontrolling, Double totalEps, Double continuingEps,
                    Double weighted, Double preferred, Double participating, Double generalPartner, Double limitedPartners) {
        Double controllingIncome() {
            if (netIncome != null) {
                return netIncome;
            }
            return profitLoss != null && noncontrolling != null ? profitLoss - noncontrolling : null;
        }

        boolean allocationTagged() {
            return preferred != null || participating != null || generalPartner != null;
        }

        /** 빈 재료를 같은 공시 인스턴스의 값으로 채운다(companyfacts가 축 없이 안 실은 NIC · EPS — GLXY · PAA). */
        Material orElse(Material o) {
            return new Material(or(nic, o.nic), or(netIncome, o.netIncome), or(profitLoss, o.profitLoss),
                    or(noncontrolling, o.noncontrolling), or(totalEps, o.totalEps), or(continuingEps, o.continuingEps),
                    or(weighted, o.weighted), or(preferred, o.preferred), or(participating, o.participating),
                    or(generalPartner, o.generalPartner), or(limitedPartners, o.limitedPartners));
        }

        private static Double or(Double a, Double b) {
            return a != null ? a : b;
        }
    }

    private static Material materialOf(IssuerFacts cf, IssuerFacts.Key k) {
        IssuerFacts.Numerators n = cf.numerators();
        Double w = cf.weighted().get(k);
        return new Material(cf.declaredNumerator().get(k), n.netIncome().get(k), n.profitLoss().get(k), n.noncontrolling().get(k),
                n.totalEps().get(k), n.continuingEps().get(k), w == null || w <= 0 ? null : w, n.preferred().get(k),
                n.participating().get(k), n.generalPartner().get(k), n.limitedPartners().get(k));
    }

    private static Material materialOf(ClassFacts f, ClassFacts.Period p) {
        Double nic = axisless(f, List.of(ClassFactReader.DECLARED_NUMERATOR), p);
        if (nic == null) {
            // 보통주 몫을 클래스 구성원에만 실은 공시(GLXY Up-C — Class A NIC −17.9M · 축 없는 NI −85.3M은 LP 단위 몫 포함).
            Map<String, Double> byClass = members(f, List.of(ClassFactReader.DECLARED_NUMERATOR), p);
            nic = byClass.isEmpty() ? null : byClass.values().stream().mapToDouble(Double::doubleValue).sum();
        }
        return new Material(nic, axisless(f, List.of("NetIncomeLoss"), p),
                axisless(f, List.of("ProfitLoss"), p), axisless(f, List.of(ClassFactReader.NONCONTROLLING), p),
                axisless(f, ClassFactReader.TOTAL_EPS, p), axisless(f, ClassFactReader.CONTINUING_EPS, p),
                positive(axisless(f, ClassFactReader.WEIGHTED_TAGS, p)), axisless(f, ClassFactReader.PREFERRED_DIVIDENDS, p),
                axisless(f, ClassFactReader.PARTICIPATING, p), axisless(f, List.of(ClassFactReader.GENERAL_PARTNER), p),
                axisless(f, List.of(ClassFactReader.LIMITED_PARTNERS), p));
    }

    private static Value judgeKey(IssuerFacts.Key k, Material m, IssuerFacts cf, Map<String, Double> refs, ClassFacts facts,
                                  IssuerTimeline.Verdict verdict, List<IssuerTimeline.Split> splits) {
        LocalDate filed = cf.filings().get(k.accession()).filed();
        // 축 없는 EPS가 한 클래스의 것일 수 있는 앵커만 값 갈래를 막는다 — 클래스 EPS가 서로 다르면(BRK A · B) 축 없는 EPS × 전체 W가
        // 틀린다. 클래스 EPS가 다 같거나 없으면(MA · UPS — 축 없는 EPS 하나) 그대로 원장 주당이익이다.
        boolean classAnchor = facts != null && facts.isClassAnchor() && !uniformClassEps(facts, k.period());
        Double[] weighted = weightedOf(k, m, cf, refs, facts, verdict, splits);
        Double w = weighted[0];
        boolean verified = weighted[1] != null;
        Double eps = m.totalEps() != null ? m.totalEps() : m.continuingEps();
        Basis basis = m.totalEps() != null ? Basis.T : Basis.C;
        Double ni = m.controllingIncome();
        // 단위를 잘못 실은 가중평균(TEM 179,917 · ECHO · DDS — 천 단위)은 고쳐 쓰지 않는다 — 원장이 뒤틀린 자리를 종목 특정 수리로 메우지
        // 않는다(2026-10-25 사용자 기준 · 정합성 훼손 → 그 키는 못 정함). 그 W는 표지 · 기준 표지와 10배 자릿수로 걸러져 값 갈래에 안 든다.
        // 폐합(갈래 1 · 2 · 3 · 4)은 확인 안 된 W로도 증인이 된다 — 단위가 틀린 W는 안 닫힌다. 클래스 축 앵커도 축 없는 EPS · W로 닫히면
        // 증인이다(DDS — 두 클래스 EPS가 같아 축 없이 싣는다). 값을 내는 갈래 5만 확인된 W와 앵커 아님을 요구한다.
        boolean witness = eps != null && w != null;

        if (m.nic() != null && (!witness || agrees(m.nic(), eps * w, eps, w))) {
            return new Value(m.nic(), Basis.T, "decl", filed, 0, ni);
        }
        if (witness && basis == Basis.T && ni != null && m.allocationTagged()) {
            double c = ni - zero(m.preferred()) - zero(m.participating()) - zero(m.generalPartner());
            if (closes(c, eps, w)) {
                return new Value(c, Basis.T, "adj", filed, 0, ni);
            }
        }
        if (witness && ni != null && !m.allocationTagged() && closes(ni, eps, w)) {
            return new Value(null, Basis.T, "ni", filed, 0, ni);
        }
        if (eps == null && !m.allocationTagged()) {
            // 기본 EPS 없이 희석만 실은 공시(POR 2025 Q1 10-Q) — 희석 EPS × 희석 가중평균이 지배 NI와 닫히면 배분 0이다(희석은 몫을
            // 낮추기만 한다). 값으로는 안 쓴다.
            Double dEps = cf.witnesses().epsDiluted().get(k);
            Double dW = cf.witnesses().diluted().get(k);
            if ((dEps == null || dW == null) && facts != null) {
                dEps = axisless(facts, List.of(ClassFactReader.DILUTED_EPS), k.period());
                dW = axisless(facts, List.of(ClassFactReader.DILUTED_WEIGHTED), k.period());
            }
            if (dEps != null && dW != null && dW > 0) {
                if (ni != null && closes(ni, dEps, dW)) {
                    return new Value(null, Basis.T, "ni", filed, 0, ni);
                }
                if (ni == null) {
                    // 키에 지배 NI가 없다(POR — ProfitLoss만) — 행 지배 NI가 닫을지는 행 붙이기가 본다(증인 전용).
                    return new Value(dEps * dW, Basis.T, "epsw", filed, tolerance(dEps, dW, null), null);
                }
            }
        }
        if (witness && basis == Basis.T && m.limitedPartners() != null && closes(m.limitedPartners(), eps, w)) {
            return new Value(m.limitedPartners(), Basis.T, "lp", filed, 0, ni);
        }
        if (witness && !classAnchor) {
            // 확인 안 된 W의 EPS × W는 값으로 안 쓴다 — 행 지배 NI가 그 안에서 닫힐 때만(배분 0의 증인 · 행 붙이기가 정한다).
            return new Value(eps * w, basis, verified ? "eps" : "epsw", filed, tolerance(eps, w, ni), ni);
        }
        if (facts != null) {
            // 클래스 축 앵커 · 또는 EPS와 가중평균을 클래스 구성원에만 실은 공시(BKR 10-Q — 표지 · NI는 축 없이, EPS · W는 Class A에만).
            Value sum = classSum(facts, k.period(), ni, filed);
            if (sum != null) {
                return sum;
            }
        }
        return new Value(null, Basis.T, "none", filed);
    }

    /**
     * W — 그 공시 당기 기간이고 판정이 지금 경로(d · 앵커 아닌 T)면 판정 n. 그 밖은 그 키의 가중평균이 그 공시 표지(없으면 그 공시일의 기준
     * 표지)와 10배 자릿수 안일 때. 10-K 판정의 n은 Q4라 연간 키에는 안 쓴다.
     */
    private static Double[] weightedOf(IssuerFacts.Key k, Material m, IssuerFacts cf, Map<String, Double> refs, ClassFacts facts,
                                       IssuerTimeline.Verdict verdict, List<IssuerTimeline.Split> splits) {
        IssuerFacts.Filing filing = cf.filings().get(k.accession());
        String form = filing.form();
        LocalDate end = cf.currentEnd(k.accession());
        boolean current = end != null && Math.abs(ChronoUnit.DAYS.between(k.period().end(), end)) <= SAME_END_DAYS;
        long days = k.period().days();
        boolean rowPeriod = form.startsWith("10-Q") ? days > 80 && days < 115
                : (form.startsWith("20-F") || form.startsWith("40-F")) && days > 350 && days < 380;
        if (current && rowPeriod && verdict != null && verdict.status() == 'O'
                && ("d".equals(verdict.kind()) || "cur".equals(verdict.branch())) && verdict.denominator() != null
                && verdict.denominator() > 0) {
            return new Double[]{verdict.denominator(), verdict.denominator()};
        }
        Double w = m.weighted();
        if (w == null) {
            return new Double[]{null, null};
        }
        Double ref = cf.coverShares().get(k.accession());
        if ((ref == null || ref <= 0) && facts != null) {
            // 인스턴스의 표지 — 축 없는 값, 없으면 클래스 구성원 표지의 합(클래스 축 앵커 · MA).
            Map<String, String> covers = facts.coverOf(ClassFactReader.SHARES_OUTSTANDING);
            String axisless = covers.get(ClassFacts.AXISLESS);
            if (axisless != null) {
                ref = Double.parseDouble(axisless);
            } else if (!covers.isEmpty()) {
                ref = covers.values().stream().mapToDouble(Double::parseDouble).sum();
            }
        }
        if (ref == null || ref <= 0) {
            ref = referenceOn(cf, refs, filing.filed(), splits);
        }
        boolean digit = ref != null && ref > 0 && Math.abs(Math.log10(w / ref)) < 1;
        return new Double[]{w, digit ? w : null};
    }

    /** 그 날까지 제출된 정기 공시 중 가장 늦은 것의 기준 표지(분할 반영) — 표지 없는 공시(6-K · 보충)의 자릿수 확인. */
    private static Double referenceOn(IssuerFacts cf, Map<String, Double> refs, LocalDate day, List<IssuerTimeline.Split> splits) {
        String best = null;
        for (String a : refs.keySet()) {
            IssuerFacts.Filing f = cf.filings().get(a);
            if (f == null || !PERIODIC.contains(f.form()) || f.filed().isAfter(day)) {
                continue;
            }
            if (best == null || f.filed().isAfter(cf.filings().get(best).filed())) {
                best = a;
            }
        }
        return best == null ? null : refs.get(best) * IssuerTimeline.splitFactor(splits, cf.filings().get(best).filed(), day);
    }

    /** 클래스 합 — 그 기간 클래스 구성원마다 총 EPS × 가중평균(첫 태그). EPS 구성원이 없으면 축 없는 총 EPS × Σ 가중평균. */
    private static Value classSum(ClassFacts f, ClassFacts.Period p, Double ni, LocalDate filed) {
        Map<String, Double> eps = members(f, ClassFactReader.TOTAL_EPS, p);
        Map<String, Double> w = members(f, ClassFactReader.WEIGHTED_TAGS, p);
        if (eps.isEmpty() && axisless(f, ClassFactReader.TOTAL_EPS, p) == null) {
            // 클래스 축 밖의 같은 뜻 축 하나에만 실은 EPS · 가중평균(PAA — LP 클래스 축 보통 단위 구성원 · 표지는 축 없음).
            eps = dimensioned(f, ClassFactReader.TOTAL_EPS, p);
            w = dimensioned(f, ClassFactReader.WEIGHTED_TAGS, p);
            if (eps.isEmpty()) {
                return null;
            }
        }
        if (w.isEmpty()) {
            return null;
        }
        double sum = 0;
        double tol = 0;
        if (!eps.isEmpty()) {
            for (Map.Entry<String, Double> e : eps.entrySet()) {
                Double wm = w.get(e.getKey());
                if (wm == null) {
                    return null;
                }
                sum += e.getValue() * wm;
                tol += 0.005 * wm + Math.abs(e.getValue()) * halfWidth(wm);
            }
        } else {
            Double axisless = axisless(f, ClassFactReader.TOTAL_EPS, p);
            if (axisless == null) {
                return null;
            }
            for (double wm : w.values()) {
                sum += axisless * wm;
                tol += 0.005 * wm + Math.abs(axisless) * halfWidth(wm);
            }
        }
        if (ni != null && Math.abs(sum - ni) <= tol + halfWidth(ni)) {
            return new Value(null, Basis.T, "ni", filed);
        }
        return new Value(sum, Basis.T, "cls", filed, tol, ni);
    }

    private static Map<String, Double> members(ClassFacts f, List<String> tags, ClassFacts.Period p) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String t : tags) {
            f.of(t).forEach((mp, v) -> {
                if (mp.period().equals(p) && !ClassFacts.AXISLESS.equals(mp.member()) && !mp.member().contains("=")) {
                    out.putIfAbsent(mp.member(), v.value());
                }
            });
        }
        return out;
    }

    /**
     * 주식 · 단위의 종류를 가르는 축 — 구성원마다 EPS × W를 더하면 보통주 몫이 된다. 같은 분모 축 중 EPS 표시 · 계속/중단영업 축은 뺀다
     * (구성원이 같은 주식의 다른 몫이라 더하면 두 번 센다).
     */
    private static final Set<String> SHARE_KIND_AXES = Set.of("StatementClassOfStockAxis", "ClassesOfShareCapitalAxis",
            "LimitedPartnersCapitalAccountByClassAxis", "PartnerTypeOfPartnersCapitalAccountAxis");

    /** 그 기간 클래스 구성원 EPS가 다 같거나 없는가(소수 넷째 자리까지). */
    private static boolean uniformClassEps(ClassFacts f, ClassFacts.Period p) {
        return members(f, ClassFactReader.TOTAL_EPS, p).values().stream().map(v -> Math.round(v * 1e4)).distinct().count() <= 1;
    }

    /** 축 하나짜리 칸({@link ClassFactReader#DIMENSIONED})에서 주식 종류 축의 구성원 값 — 열쇠는 「축=구성원」. */
    private static Map<String, Double> dimensioned(ClassFacts f, List<String> tags, ClassFacts.Period p) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String t : tags) {
            f.of(ClassFactReader.DIMENSIONED + t).forEach((mp, v) -> {
                int eq = mp.member().indexOf('=');
                if (mp.period().equals(p) && eq > 0 && SHARE_KIND_AXES.contains(mp.member().substring(0, eq))) {
                    out.putIfAbsent(mp.member(), v.value());
                }
            });
        }
        return out;
    }

    private static Double axisless(ClassFacts f, List<String> tags, ClassFacts.Period p) {
        for (String t : tags) {
            ClassFacts.Measured m = f.of(t).get(new ClassFacts.MemberPeriod(ClassFacts.AXISLESS, p));
            if (m != null) {
                return m.value();
            }
        }
        return null;
    }

    /** 인스턴스의 기간 사실 중 손익 · EPS가 실린 기간(3개월 · 반기 · 9개월 · 연간). */
    private static List<ClassFacts.Period> instancePeriods(ClassFacts f) {
        Set<ClassFacts.Period> out = new LinkedHashSet<>();
        for (String t : List.of(ClassFactReader.DECLARED_NUMERATOR, "NetIncomeLoss", "ProfitLoss")) {
            f.of(t).keySet().forEach(mp -> out.add(mp.period()));
        }
        for (String t : ClassFactReader.TOTAL_EPS) {
            f.of(t).keySet().forEach(mp -> out.add(mp.period()));
        }
        List<ClassFacts.Period> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.comparing(ClassFacts.Period::end).thenComparing(ClassFacts.Period::start));
        return sorted;
    }

    /** EPS × W와 닫히는가 — EPS 반 센트 × W + W 끝자리 반폭 × |EPS| + 값 끝자리 반폭(companyfacts에 선언 정밀도가 없다). */
    static boolean closes(double c, double eps, double w) {
        return Math.abs(eps * w - c) <= tolerance(eps, w, c);
    }

    private static double tolerance(double eps, double w, Double c) {
        double v = c == null ? 0 : c;
        return 0.005 * w + Math.abs(eps) * halfWidth(w) + halfWidth(v) + 1e-9 * Math.abs(v);
    }

    /** 선언 NIC가 EPS × W와 부호 · 자릿수(10배)로 맞는가 — 둘 다 반올림 안이면(0 근처) 맞는 것으로 본다. */
    private static boolean agrees(double nic, double implied, double eps, double w) {
        double tol = 0.005 * w + Math.abs(eps) * halfWidth(w) + halfWidth(nic);
        if (Math.abs(nic) <= tol || Math.abs(implied) <= tol) {
            return Math.abs(nic - implied) <= 2 * tol + 0.1 * Math.max(Math.abs(nic), Math.abs(implied));
        }
        return Math.signum(nic) == Math.signum(implied) && Math.abs(Math.log10(Math.abs(nic) / Math.abs(implied))) < 1;
    }

    /**
     * 값 끝자리 0이 말하는 반올림 반폭 — 상한 50만(백만 단위 보고의 반폭). 끝자리 0만 보면 둥근 수(1,000,000,000 · 480,000,000)의 반폭이
     * 5억 · 500만으로 부풀어 배분이 있는 분기도 「배분 0」으로 닫힌다(반증 안 5 · 시험 {@code CommonIncomeTest}). 원장은 백만보다 큰 단위로
     * 싣지 않는다.
     */
    static double halfWidth(double value) {
        long x = Math.abs(Math.round(value));
        long p = 1;
        while (x != 0 && p < 1_000_000L && x % (p * 10) == 0) {
            p *= 10;
        }
        return x == 0 ? 0 : p / 2.0;
    }

    private static double zero(Double v) {
        return v == null ? 0 : v;
    }

    private static Double positive(Double v) {
        return v == null || v <= 0 ? null : v;
    }
}
