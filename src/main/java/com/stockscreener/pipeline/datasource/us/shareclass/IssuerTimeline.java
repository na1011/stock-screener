package com.stockscreener.pipeline.datasource.us.shareclass;

import com.stockscreener.pipeline.datasource.DataSourceException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts.AXISLESS;

/**
 * 발행사 하나의 정기 공시를 공시일 순서로 판정한다(탐색 `SHARES-CLASS-PLAN-V5-1015.md` §1d·§10 · 시뮬레이터 {@code v5bsim.py}).
 *
 * <p>공시마다 처음 서는 것: ① 원장이 참여 집합을 말하면(층 a/b) 표지 합 — 완전성은 같은 기간 구성원 분모 합 ÷ 축 없는 분모
 * ≤ 0.5%(원장 자기 합), 그 재료가 없으면 표지 합 ÷ 분모 ≤ τ_c ② 전 클래스 표지 합 T ÷ 분모 ≤ τ_c(P-c) ③ 평균 —
 * 기준(검증된 마지막 분모)과의 변화가 간격별 τ를 넘으면 숨김. 10-K의 분모는 Q4(Q3 10-Q 9개월 누적 · 없으면 3개월 셋).
 * 롤포워드는 쓰지 않는다(§10a — 안정된 적합 분포가 없고 적용 영역에서 틀린 값을 낸다).
 */
public final class IssuerTimeline {

    /**
     * 판정 규칙의 판 — 판정 표에 같이 적는다(어느 규칙이 낸 행인지). 규칙을 바꾸면 올린다. 2 — 상장 클래스 매핑이 통째로 같은 기호를
     * 먼저 보고 · 보통주 아닌 줄과 여러 클래스를 묶은 구성원을 잇지 않는다(2026-10-21).
     */
    public static final int ENGINE_VERSION = 2;

    /** 단일 클래스 연속 3개월 분모 변화 간격 120~200일의 p95(§10a · {@code single5.py}). */
    static final double TAU_TWO_QUARTERS = 0.1202;

    /** @param status 'O' 값을 받음 · 'X' 숨김 */
    public record Verdict(LocalDate filed, String accession, String kind, char status, String branch, String category,
                          Double value, Double denominator) {
    }

    /** 인스턴스를 공급한다 — 없으면 null(받지 않은 공시). */
    public interface Instances {
        ClassFacts load(String accession) throws DataSourceException;
    }

    public record Split(LocalDate date, double ratio) {
    }

    private record Quarter(LocalDate filed, ClassFacts.Period period, Double value, boolean ok, String origin, String source) {
    }

    private final double tau;
    private final double tauCover;
    private final DenominatorJudge judge;

    /** 연속 10-Q 3개월 분모 변화(간격 120일 이하)의 p95 — 단일 클래스 18,785쌍(§1f · {@code single5.py}). */
    public static final double TAU = 0.054;
    /** 10-Q 표지 ÷ 3개월 분모의 |log10| p95 — 단일 클래스 28,806(§1f). */
    public static final double TAU_COVER = 0.052;
    /** 대용 분자(NetIncomeLoss · ProfitLoss) 폐합 허용 — τ와 같게 박는다(§10 F7). */
    public static final double PROXY_TOLERANCE = 0.054;

    /** 운영 문턱(드라이런 1·2가 이 셋으로 돌았다). */
    public static IssuerTimeline standard() {
        return new IssuerTimeline(TAU, TAU_COVER, PROXY_TOLERANCE);
    }

    public IssuerTimeline(double tau, double tauCover, double proxyTolerance) {
        this.tau = tau;
        this.tauCover = tauCover;
        this.judge = new DenominatorJudge(proxyTolerance);
    }

    /**
     * @param anchors       인스턴스를 받은 공시(접수번호)
     * @param historyFrom   이 날 앞의 표지 없는 정기 공시는 인스턴스 없이 기준 n만 잇는다('h' · 2015-01-01부터)
     * @param instanceFrom  인스턴스를 받은 구간의 시작 — 그 앞의 표지 없는 공시가 'h'다
     */
    public List<Verdict> judge(String ticker, IssuerFacts cf, Set<String> anchors, Instances instances, List<Split> splits,
                               LocalDate historyFrom, LocalDate instanceFrom) {
        record Event(LocalDate filed, String accession, String kind) {
        }
        List<Event> events = new ArrayList<>();
        cf.filings().forEach((a, fl) -> {
            boolean periodic = Set.of("10-K", "10-Q", "20-F", "40-F").contains(fl.form());
            if (anchors.contains(a)) {
                events.add(new Event(fl.filed(), a, "T"));
            } else if (periodic && cf.coverShares().containsKey(a)) {
                events.add(new Event(fl.filed(), a, "d"));
            } else if ((fl.form().equals("10-K") || fl.form().equals("10-Q")) && !cf.coverShares().containsKey(a)
                    && fl.filed().isBefore(instanceFrom) && !fl.filed().isBefore(historyFrom)) {
                events.add(new Event(fl.filed(), a, "h"));
            }
        });
        events.sort(Comparator.comparing(Event::filed).thenComparing(Event::accession).thenComparing(Event::kind));

        Map<String, Map<String, Quarter>> qv = new LinkedHashMap<>();
        cf.filings().forEach((a, fl) -> {
            if (!fl.form().startsWith("10-Q")) {
                return;
            }
            for (String key : List.of("3m", "9m")) {
                int lo = key.equals("3m") ? 80 : 255;
                int hi = key.equals("3m") ? 100 : 285;
                ClassFacts.Period p = latestPeriod(cf.weighted(), a, lo, hi);
                if (p != null) {
                    double v = cf.weighted().get(new IssuerFacts.Key(a, p));
                    Boolean ok;
                    String source;
                    if (v == 0) {
                        ok = false;
                        source = "대용";
                    } else {
                        Object[] c = companyFactsClose(cf, a, p, v);
                        ok = (Boolean) c[0];
                        source = (String) c[1];
                    }
                    qv.computeIfAbsent(a, k -> new LinkedHashMap<>())
                            .put(key, new Quarter(fl.filed(), p, v, !Boolean.FALSE.equals(ok), "cf", source));
                }
            }
        });

        List<Verdict> out = new ArrayList<>();
        LocalDate prevDate = null;
        Double prevN = null;
        for (Event ev : events) {
            LocalDate f = ev.filed();
            String a = ev.accession();
            String form = cf.filings().get(a).form();
            if (!ev.kind().equals("T")) {
                if (ev.kind().equals("d")) {
                    out.add(new Verdict(f, a, "d", 'O', "d", null, null, currentPathDenominator(cf, a, form, f, qv, splits)));
                }
                if (form.startsWith("10-Q")) {
                    ClassFacts.Period p = latestPeriod(cf.weighted(), a, 80, 100);
                    Double v = p == null ? null : cf.weighted().get(new IssuerFacts.Key(a, p));
                    if (v != null && v != 0) {
                        prevDate = f;
                        prevN = v;
                    }
                }
                continue;
            }
            ClassFacts facts;
            try {
                facts = instances.load(a);
            } catch (DataSourceException e) {
                out.add(new Verdict(f, a, "T", 'X', "noinst", null, null, null));
                continue;
            }
            if (facts == null) {
                out.add(new Verdict(f, a, "T", 'X', "noinst", null, null, null));
                continue;
            }
            if (!facts.isClassAnchor()) {
                out.add(new Verdict(f, a, "T", 'O', "cur", null, null, currentPathDenominator(cf, a, form, f, qv, splits)));
                if (form.startsWith("10-Q")) {
                    ClassFacts.Period p = latestPeriod(cf.weighted(), a, 80, 100);
                    Double v = p == null ? null : cf.weighted().get(new IssuerFacts.Key(a, p));
                    if (v != null && v != 0) {
                        prevDate = f;
                        prevN = v;
                    }
                }
                continue;
            }
            Double coverDei = cf.coverShares().get(a);
            DenominatorJudge.Result r = judge.judge(ticker, facts, DenominatorJudge.Form.of(form), coverDei);
            boolean fpi = form.startsWith("20-F") || form.startsWith("40-F");
            Double n = r.n();
            String branch = r.branch();
            if (fpi && n != null && branch.startsWith("B")) {
                n = null;
                branch = "20F-class";
            }
            ClassFacts.Period p = r.period();
            if (n == null) {
                out.add(new Verdict(f, a, "T", 'X', branch, failureCategory(facts, p, branch, r.failureSource()), null, null));
                continue;
            }
            if (form.startsWith("10-Q")) {
                Map<String, Quarter> d = new LinkedHashMap<>();
                d.put("3m", new Quarter(f, p, n, true, "inst", "원장"));
                DenominatorJudge.Result r9 = judge.judge(ticker, facts, DenominatorJudge.Form.NINE_MONTHS, coverDei);
                if (r9.period() != null) {
                    d.put("9m", new Quarter(f, r9.period(), r9.n(), r9.n() != null, "inst",
                            r9.failureSource() == null ? "원장" : r9.failureSource()));
                }
                qv.put(a, d);
            }
            double nq = n;
            if (!form.startsWith("10-Q") && !fpi) {
                FourthQuarter q4 = fourthQuarter(qv, p, n, f, splits);
                if (q4.n() == null) {
                    out.add(new Verdict(f, a, "T", 'X', q4.failBranch(), q4.failCategory(), null, null));
                    continue;
                }
                nq = q4.n();
            }
            double nref = nq;
            Map<String, Double> em = new LinkedHashMap<>();
            DenominatorJudge.periodValues(facts, ClassFactReader.EPS_TAGS, p).forEach((m, v) -> {
                if (!AXISLESS.equals(m)) {
                    em.put(m, v);
                }
            });
            Set<String> wm = new LinkedHashSet<>();
            for (String t : ClassFactReader.WEIGHTED_TAGS) {
                facts.of(t).keySet().forEach(mp -> {
                    if (mp.period().equals(p) && !AXISLESS.equals(mp.member())) {
                        wm.add(mp.member());
                    }
                });
            }
            Map<String, Double> cv = new LinkedHashMap<>();
            facts.coverOf(ClassFactReader.SHARES_OUTSTANDING).forEach((m, v) -> {
                double x = Double.parseDouble(v);
                if (!AXISLESS.equals(m) && x > 0) {
                    cv.put(m, x);
                }
            });
            Set<String> s = em.isEmpty() ? wm : em.keySet();
            Map<String, Double> wv = DenominatorJudge.periodValues(facts, ClassFactReader.WEIGHTED_TAGS, p);
            Double point = null;
            String how = null;
            if (!fpi && !s.isEmpty() && cv.keySet().containsAll(s)) {
                if (em.isEmpty() || em.values().stream().map(v -> Math.round(v * 1e4) / 1e4).distinct().count() == 1) {
                    point = s.stream().mapToDouble(cv::get).sum();
                } else {
                    String lm = DenominatorJudge.listedMember(ticker, facts, new ArrayList<>(em.keySet()))[0];
                    if (lm != null && em.get(lm) != 0) {
                        point = s.stream().mapToDouble(m -> cv.get(m) * em.get(m)).sum() / em.get(lm);
                    }
                }
                if (point != null) {
                    String via;
                    boolean ok;
                    if (Math.abs(Math.log10(point / nref)) >= 1) {
                        via = "digit";
                        ok = false;
                    } else if (wv.containsKey(AXISLESS) && wv.keySet().containsAll(s)) {
                        via = "sumw";
                        ok = Math.abs(s.stream().mapToDouble(wv::get).sum() / wv.get(AXISLESS) - 1) <= 0.005;
                    } else {
                        via = "cover";
                        ok = Math.abs(point / nref - 1) <= tauCover;
                    }
                    if (ok) {
                        how = "P-ab/" + via;
                    } else {
                        point = null;
                    }
                }
            }
            if (point == null && !cv.isEmpty() && !fpi) {
                double t = cv.values().stream().mapToDouble(Double::doubleValue).sum();
                if (Math.abs(t / nref - 1) <= tauCover) {
                    point = t;
                    how = "P-c";
                }
            }
            if (point != null) {
                out.add(new Verdict(f, a, "T", 'O', how, null, point, nq));
                prevDate = f;
                prevN = nq;
                continue;
            }
            long gap = prevDate == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(prevDate, f);
            Double tauGap = gap <= 120 ? Double.valueOf(tau) : gap <= 200 ? Double.valueOf(TAU_TWO_QUARTERS) : null;
            if (tauGap == null) {
                out.add(new Verdict(f, a, "T", 'X', "no-prev", "대용", null, nq));
            } else if (Math.abs(nq / (prevN * splitFactor(splits, prevDate, f)) - 1) > tauGap) {
                out.add(new Verdict(f, a, "T", 'X', String.format(Locale.ROOT, "jump %.1f->%.1fM", prevN / 1e6, nq / 1e6),
                        "대용", null, nq));
            } else {
                out.add(new Verdict(f, a, "T", 'O', branch, null, nq, nq));
            }
            prevDate = f;
            prevN = nq;
        }
        return out;
    }

    /** Q4 분모 — 연간 분모에서 Q3 10-Q의 9개월 누적(없으면 3개월 셋)을 빼 남는 날로 나눈다. 못 내면 실패 갈래와 출처. */
    private record FourthQuarter(Double n, String failBranch, String failCategory) {
    }

    private static FourthQuarter fourthQuarter(Map<String, Map<String, Quarter>> qv, ClassFacts.Period p, double n, LocalDate f,
                                               List<Split> splits) {
        LocalDate lo = p.start().minusDays(7);
        LocalDate hi = p.start().plusDays(7);
        LocalDate cut = p.end().minusDays(20);
        Quarter q9 = null;
        Map<ClassFacts.Period, Quarter> byPeriod = new LinkedHashMap<>();
        for (Map<String, Quarter> dd : qv.values()) {
            Quarter x = dd.get("9m");
            if (x != null && x.filed().isBefore(f) && !x.period().start().isBefore(lo) && !x.period().start().isAfter(hi)
                    && x.period().end().isBefore(cut) && (q9 == null || x.period().end().isAfter(q9.period().end()))) {
                q9 = x;
            }
            Quarter y = dd.get("3m");
            if (y != null && y.filed().isBefore(f) && !y.period().start().isBefore(lo) && y.period().end().isBefore(cut)) {
                byPeriod.putIfAbsent(y.period(), y);
            }
        }
        long tot = p.days() + 1;
        double nq;
        if (q9 != null && q9.value() != null && q9.value() != 0) {
            if (!q9.ok()) {
                return new FourthQuarter(null, "q4-input-FAIL", q9.source().equals("원장") ? "어긋남" : "대용");
            }
            long d9 = q9.period().days() + 1;
            nq = (n * tot - q9.value() * splitFactor(splits, q9.filed(), f) * d9) / (tot - d9);
        } else if (byPeriod.size() == 3) {
            if (byPeriod.values().stream().anyMatch(x -> !x.ok())) {
                boolean ledger = byPeriod.values().stream().filter(x -> !x.ok()).allMatch(x -> x.source().equals("원장"));
                return new FourthQuarter(null, "q4-input-FAIL", ledger ? "어긋남" : "대용");
            }
            double sum = 0;
            long days = 0;
            for (Map.Entry<ClassFacts.Period, Quarter> x : byPeriod.entrySet()) {
                long dd = x.getKey().days() + 1;
                sum += x.getValue().value() * splitFactor(splits, x.getValue().filed(), f) * dd;
                days += dd;
            }
            nq = (n * tot - sum) / (tot - days);
        } else {
            return new FourthQuarter(null, "no-q4", "안줌");
        }
        if (nq <= 0) {
            return new FourthQuarter(null, "q4<=0", "어긋남");
        }
        return new FourthQuarter(nq, null, null);
    }

    /**
     * 지금 경로 판(d · 앵커 아닌 T)의 분모 — companyfacts의 축 없는 분모로: 10-Q 3개월 · 10-K Q4 · 20-F/40-F 연간. 비 지표 셋의 두
     * 끝이다(§10b-6). 못 내면 {@code null} — 판은 그대로 보인다(값은 지금 경로가 낸다).
     */
    private static Double currentPathDenominator(IssuerFacts cf, String a, String form, LocalDate f,
                                                 Map<String, Map<String, Quarter>> qv, List<Split> splits) {
        if (form.startsWith("10-Q")) {
            ClassFacts.Period p = latestPeriod(cf.weighted(), a, 80, 100);
            Double v = p == null ? null : cf.weighted().get(new IssuerFacts.Key(a, p));
            return v == null || v <= 0 ? null : v;
        }
        ClassFacts.Period p = latestPeriod(cf.weighted(), a, 350, 380);
        Double v = p == null ? null : cf.weighted().get(new IssuerFacts.Key(a, p));
        if (v == null || v <= 0) {
            return null;
        }
        if (!form.startsWith("10-K")) {
            return v;
        }
        return fourthQuarter(qv, p, v, f, splits).n();
    }

    /** 실패의 출처: 원장이 밝힌 분자로 실패 · 축척 꼴(n·e/분자 ≥ 10^0.9) → 원장 어긋남, 대용 분자 실패 → 대용, 그 밖 → 안 줌. */
    private static String failureCategory(ClassFacts f, ClassFacts.Period p, String branch, String source) {
        boolean fail = branch.contains("FAIL");
        boolean scale = false;
        if (p != null) {
            Map<String, Double> w = DenominatorJudge.periodValues(f, ClassFactReader.WEIGHTED_TAGS, p);
            Map<String, Double> e = DenominatorJudge.periodValues(f, ClassFactReader.EPS_TAGS, p);
            Double num = null;
            for (String t : ClassFactReader.NUMERATOR_TAGS) {
                ClassFacts.Measured m = f.of(t).get(new ClassFacts.MemberPeriod(AXISLESS, p));
                if (m != null) {
                    num = m.value();
                    break;
                }
            }
            if (num != null && num != 0 && !w.isEmpty() && !e.isEmpty()) {
                Double nn = w.get(AXISLESS);
                if (nn == null || nn == 0) {
                    nn = w.entrySet().stream().filter(x -> !AXISLESS.equals(x.getKey())).mapToDouble(Map.Entry::getValue).sum();
                }
                Double ee = e.get(AXISLESS);
                if (ee == null || ee == 0) {
                    ee = e.values().iterator().next();
                }
                if (nn != 0 && ee != 0 && Math.abs(Math.log10(Math.abs(nn * ee / num))) >= 0.9) {
                    scale = true;
                }
            }
        }
        if (fail && ("원장".equals(source) || scale)) {
            return "어긋남";
        }
        return fail ? "대용" : "안줌";
    }

    private Object[] companyFactsClose(IssuerFacts cf, String a, ClassFacts.Period p, double v) {
        IssuerFacts.Key k = new IssuerFacts.Key(a, p);
        Double e = cf.eps().get(k);
        if (cf.declaredNumerator().containsKey(k)) {
            return new Object[]{DenominatorJudge.closes(v, e, cf.declaredNumerator().get(k), 0.005, 0, 0), "원장"};
        }
        Double x = cf.proxyNumerator().get(k);
        return new Object[]{x == null ? null : DenominatorJudge.closes(v, e, x, 0.005, judgeProxy() * Math.abs(x), 0), "대용"};
    }

    private double judgeProxy() {
        return judge.proxyTolerance();
    }

    private static ClassFacts.Period latestPeriod(Map<IssuerFacts.Key, Double> weighted, String accession, int lo, int hi) {
        ClassFacts.Period best = null;
        for (IssuerFacts.Key k : weighted.keySet()) {
            if (k.accession().equals(accession) && lo < k.period().days() && k.period().days() < hi
                    && (best == null || k.period().end().isAfter(best.end()))) {
                best = k.period();
            }
        }
        return best;
    }

    /** 앞 공시일 뒤 · 이 공시일 이하의 분할 비율 곱(앞 공시에서 온 입력을 이 공시의 기저로 옮긴다). */
    static double splitFactor(List<Split> splits, LocalDate from, LocalDate to) {
        double f = 1.0;
        for (Split s : splits) {
            if (s.date().isAfter(from) && !s.date().isAfter(to)) {
                f *= s.ratio();
            }
        }
        return f;
    }
}
