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
     * 먼저 보고 · 보통주 아닌 줄과 여러 클래스를 묶은 구성원을 잇지 않는다(2026-10-21). 3 — 20-F · 40-F 클래스 앵커를 10-K와 같게
     * 판정한다(B 갈래 n · 시점 합) — 막던 근거 「ADS 비율을 원장이 안 준다」가 거짓이 됐고(공시 표지가 판마다 준다 · ADR-0183) ADS가
     * 아닌 20-F · 40-F 보통주(TRI · NU)에는 처음부터 안 서던 규칙이다. Q4 유도만 안 한다(연간 공시라 분기 n이 없다). 4 — 지금 경로
     * 분모(ADR-0189): 당기 기간만 · 인스턴스 축 맥락 · 원장 선언 대체 · 단위 확인 · 폐합 실패 입력 Q4의 출력 확인 · 16주 분기(2026-10-24).
     */
    public static final int ENGINE_VERSION = 5;

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

    /** @param closed 입력 폐합이 「참」으로 섰다(불명 · 실패는 거짓 — Q4가 출력 확인을 탄다) */
    private record Quarter(LocalDate filed, ClassFacts.Period period, Double value, boolean ok, String origin, String source,
                           boolean closed) {
        Quarter(LocalDate filed, ClassFacts.Period period, Double value, boolean ok, String origin, String source) {
            this(filed, period, value, ok, origin, source, ok);
        }
    }

    private final double tau;
    private final double tauCover;
    private final DenominatorJudge judge;

    /** 연속 10-Q 3개월 분모 변화(간격 120일 이하)의 p95 — 단일 클래스 18,785쌍(§1f · {@code single5.py}). */
    public static final double TAU = 0.054;
    /** 10-Q 표지 ÷ 3개월 분모의 비율 p95(|비 − 1| — |log10|로는 0.022) — 단일 클래스 28,806(§1f). */
    public static final double TAU_COVER = 0.052;
    /**
     * 표지 증인 문턱 — |log10(n ÷ 표지)| ≤ 0.052(비율로 약 12.7%). 지금 경로 n이 「그 줄의 주식 수」인지 표지로 확인하는 자리(넓힌 태그 ·
     * 인스턴스 분모 · 폐합 실패 입력의 Q4)에 쓴다. 정직한 쌍(2020 뒤 d 10-Q 18,488 · 2026-10-24 0콜 스윕)의 p97.8이고, 막을 실패(다른
     * 단위 REG 0.005배 · 클래스 일부 CQP 0.86배 · MGY 0.78배 · 천 단위)는 모두 그 밖이다. ADR-0187이 이 값을 τ_c라 적고 log10에 썼다 —
     * 값은 그대로이고 이름이 갈렸다.
     */
    public static final double COVER_WITNESS_LOG = 0.052;
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
    public List<Verdict> judge(String ticker, IssuerFacts given, Set<String> anchors, Instances instances, List<Split> splits,
                               LocalDate historyFrom, LocalDate instanceFrom) {
        Prepared prepared = prepare(given, instances, splits, anchors);
        IssuerFacts cf = prepared.facts();
        Map<String, Double> refs = prepared.references();
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
                ClassFacts.Period p = key.equals("3m") ? quarterPeriod(cf, cf.weighted(), a) : currentPeriod(cf, cf.weighted(), a, 255, 285);
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
                            // 「섰다」는 폐합 참 + 그 10-Q 자기 표지와 자릿수 — n과 EPS가 서로 반대로 틀리면 폐합만 맞는다(COHR 2020-05
                            // 9개월 82,615 · EPS −1430 → FY2020 Q4 340.9M · 표지 103.7M). 안 서면 Q4가 출력 확인을 탄다.
                            .put(key, new Quarter(fl.filed(), p, v, !Boolean.FALSE.equals(ok), "cf", source,
                                    Boolean.TRUE.equals(ok) && digit(v, cf.coverShares().get(a))));
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
                    out.add(new Verdict(f, a, "d", 'O', prepared.noInstance().contains(a) ? "d-noinst" : "d", null, null,
                            currentPathDenominator(cf, a, form, f, qv, splits, refs.get(a))));
                }
                if (form.startsWith("10-Q")) {
                    ClassFacts.Period p = quarterPeriod(cf, cf.weighted(), a);
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
                out.add(new Verdict(f, a, "T", 'O', "cur", null, null, currentPathDenominator(cf, a, form, f, qv, splits, refs.get(a))));
                if (form.startsWith("10-Q")) {
                    ClassFacts.Period p = quarterPeriod(cf, cf.weighted(), a);
                    Double v = p == null ? null : cf.weighted().get(new IssuerFacts.Key(a, p));
                    if (v != null && v != 0) {
                        prevDate = f;
                        prevN = v;
                    }
                }
                continue;
            }
            DenominatorJudge.Result r = judge.judge(ticker, facts, DenominatorJudge.Form.of(form));
            boolean fpi = form.startsWith("20-F") || form.startsWith("40-F");
            Double n = r.n();
            String branch = r.branch();
            ClassFacts.Period p = r.period();
            if (n == null) {
                out.add(new Verdict(f, a, "T", 'X', branch, failureCategory(facts, p, branch, r.failureSource()), null, null));
                continue;
            }
            if (form.startsWith("10-Q")) {
                Map<String, Quarter> d = new LinkedHashMap<>();
                d.put("3m", new Quarter(f, p, n, true, "inst", "원장"));
                DenominatorJudge.Result r9 = judge.judge(ticker, facts, DenominatorJudge.Form.NINE_MONTHS);
                if (r9.period() != null) {
                    d.put("9m", new Quarter(f, r9.period(), r9.n(), r9.n() != null, "inst",
                            r9.failureSource() == null ? "원장" : r9.failureSource()));
                }
                qv.put(a, d);
            }
            double nq = n;
            if (!form.startsWith("10-Q") && !fpi) {
                FourthQuarter q4 = fourthQuarter(qv, p, n, f, splits, refs.get(a));
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
            if (!s.isEmpty() && cv.keySet().containsAll(s)) {
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
            if (point == null && !cv.isEmpty()) {
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

    private FourthQuarter fourthQuarter(Map<String, Map<String, Quarter>> qv, ClassFacts.Period p, double n, LocalDate f,
                                        List<Split> splits, Double cover) {
        LocalDate lo = p.start().minusDays(7);
        LocalDate hi = p.start().plusDays(7);
        LocalDate cut = p.end().minusDays(20);
        Quarter q9 = null;
        Map<ClassFacts.Period, Quarter> byPeriod = new LinkedHashMap<>();
        for (Map<String, Quarter> dd : qv.values()) {
            Quarter x = dd.get("9m");
            // 값 0 · null은 「없음」이다(단위 확인이 지운 값 · 원래 0) — 합에 0으로 들어가면 Q4가 약 2n으로 부푼다.
            if (x != null && x.value() != null && x.value() > 0 && x.filed().isBefore(f) && !x.period().start().isBefore(lo)
                    && !x.period().start().isAfter(hi)
                    && x.period().end().isBefore(cut) && (q9 == null || x.period().end().isAfter(q9.period().end()))) {
                q9 = x;
            }
            Quarter y = dd.get("3m");
            if (y != null && y.value() != null && y.value() > 0 && y.filed().isBefore(f) && !y.period().start().isBefore(lo)
                    && y.period().end().isBefore(cut)) {
                byPeriod.putIfAbsent(y.period(), y);
            }
        }
        long tot = p.days() + 1;
        double nq;
        boolean confirmOutput = false;
        if (q9 != null && q9.value() != null && q9.value() != 0) {
            if (!q9.ok() && !"cf".equals(q9.origin())) {
                return new FourthQuarter(null, "q4-input-FAIL", q9.source().equals("원장") ? "어긋남" : "대용");
            }
            confirmOutput = !q9.closed();
            long d9 = q9.period().days() + 1;
            nq = (n * tot - q9.value() * splitFactor(splits, q9.filed(), f) * d9) / (tot - d9);
        } else if (byPeriod.size() == 3) {
            if (byPeriod.values().stream().anyMatch(x -> !x.ok() && !"cf".equals(x.origin()))) {
                boolean ledger = byPeriod.values().stream().filter(x -> !x.ok()).allMatch(x -> x.source().equals("원장"));
                return new FourthQuarter(null, "q4-input-FAIL", ledger ? "어긋남" : "대용");
            }
            confirmOutput = byPeriod.values().stream().anyMatch(x -> !x.closed());
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
        if (!digit(nq, cover)) {
            return new FourthQuarter(null, "q4-digit", "어긋남");
        }
        if (confirmOutput && !outputConfirmed(qv, nq, p, f, splits, cover)) {
            return new FourthQuarter(null, "q4-input-FAIL", "어긋남");
        }
        return new FourthQuarter(nq, null, null);
    }

    /**
     * 입력 폐합이 실패한 companyfacts 입력으로 낸 Q4를 원장의 다른 수로 확인한다(ADR-0189) — 같은 회계연도 마지막 3개월 분모와의 변화가
     * 간격별 τ 안이거나, 그 10-K의 축 없는 표지와 τ_c 안.
     */
    private boolean outputConfirmed(Map<String, Map<String, Quarter>> qv, double nq, ClassFacts.Period p, LocalDate f,
                                    List<Split> splits, Double cover) {
        if (coverOk(nq, cover)) {
            return true;
        }
        Quarter last = null;
        for (Map<String, Quarter> dd : qv.values()) {
            Quarter y = dd.get("3m");
            if (y != null && y.value() != null && y.value() > 0 && y.filed().isBefore(f) && !y.period().start().isBefore(p.start().minusDays(7))
                    && y.period().end().isBefore(p.end().minusDays(20)) && (last == null || y.period().end().isAfter(last.period().end()))) {
                last = y;
            }
        }
        if (last == null) {
            return false;
        }
        long gap = ChronoUnit.DAYS.between(last.filed(), f);
        double t = gap <= 120 ? tau : gap <= 200 ? TAU_TWO_QUARTERS : -1;
        return t > 0 && Math.abs(nq / (last.value() * splitFactor(splits, last.filed(), f)) - 1) <= t;
    }

    /**
     * 지금 경로 판(d · 앵커 아닌 T)의 분모 — companyfacts의 축 없는 분모로: 10-Q 3개월 · 10-K Q4 · 20-F/40-F 연간. 비 지표 셋의 두
     * 끝이다(§10b-6). 못 내면 {@code null} — 판은 그대로 보인다(값은 지금 경로가 낸다).
     */
    private Double currentPathDenominator(IssuerFacts cf, String a, String form, LocalDate f,
                                          Map<String, Map<String, Quarter>> qv, List<Split> splits, Double ref) {
        if (form.startsWith("10-Q")) {
            ClassFacts.Period p = quarterPeriod(cf, cf.weighted(), a);
            Double v = p == null ? null : cf.weighted().get(new IssuerFacts.Key(a, p));
            return v == null || v <= 0 ? null : v;
        }
        ClassFacts.Period p = currentPeriod(cf, cf.weighted(), a, 350, 380);
        Double v = p == null ? null : cf.weighted().get(new IssuerFacts.Key(a, p));
        if (v == null || v <= 0) {
            return null;
        }
        if (!form.startsWith("10-K")) {
            return v;
        }
        return fourthQuarter(qv, p, v, f, splits, ref).n();
    }

    /** 지금 경로 분모를 인스턴스로 채우는 구간의 시작(ADR-0189) — 지표 창 시작 − 5년(EPS_CAGR_5Y의 과거 끝) − 한 분기 여유. */
    static final LocalDate DENOMINATOR_FROM = LocalDate.of(2020, 1, 1);
    private static final Set<String> PERIODIC = Set.of("10-K", "10-Q", "20-F", "40-F");
    /** 당기 기말과 같은 기간으로 치는 날 수 — 같은 기말을 사실마다 하루 이틀 달리 싣는다(52/53주 회계연도). */
    private static final int SAME_END_DAYS = 3;

    /** 그 공시 당기 기말의 기간 창 안 가중평균 기간 — 당기 기말을 모르면 창 안 가장 늦은 기말. */
    private static ClassFacts.Period currentPeriod(IssuerFacts cf, Map<IssuerFacts.Key, Double> weighted, String accession,
                                                   int lo, int hi) {
        LocalDate end = cf.currentEnd(accession);
        ClassFacts.Period best = null;
        for (IssuerFacts.Key k : weighted.keySet()) {
            if (!k.accession().equals(accession) || !(lo < k.period().days() && k.period().days() < hi)) {
                continue;
            }
            if (end != null && Math.abs(ChronoUnit.DAYS.between(k.period().end(), end)) > SAME_END_DAYS) {
                continue;
            }
            if (best == null || k.period().end().isAfter(best.end())) {
                best = k.period();
            }
        }
        return best;
    }

    /** 10-Q의 분기 기간 — 3개월(80~100일)이 먼저, 없으면 16주 분기(100~115일 · KR · ACI · CAVA · PRMB 1분기). */
    private static ClassFacts.Period quarterPeriod(IssuerFacts cf, Map<IssuerFacts.Key, Double> weighted, String accession) {
        ClassFacts.Period p = currentPeriod(cf, weighted, accession, 80, 100);
        return p != null ? p : currentPeriod(cf, weighted, accession, 100, 115);
    }

    private static ClassFacts.Period latestOwnPeriod(IssuerFacts cf, Map<IssuerFacts.Key, Double> weighted, String accession,
                                                     String form) {
        return form.startsWith("10-Q") ? quarterPeriod(cf, weighted, accession) : currentPeriod(cf, weighted, accession, 350, 380);
    }

    /**
     * 기준 표지 — 그 공시와 앞 정기 공시 넷(분할 반영)의 축 없는 표지 중앙값. 표지 자체를 단위 오태깅한 공시가 있어(AA 2019-05 · 07 ×10^6
     * · CRM 2011 133.9 · HBAN 2011 ×0.001) 한 공시의 표지를 증인으로 못 쓴다 — 다수가 정한다. 미래 공시는 안 본다(판정이 그 공시의 시점
     * 사실이어야 한다). Q4 · 인스턴스 · 원장 선언 대체의 확인에만 쓴다(값마다의 단위 확인은 그 공시 자기 표지).
     */
    private static Map<String, Double> referenceCovers(IssuerFacts cf, List<Split> splits) {
        List<Map.Entry<String, IssuerFacts.Filing>> periodic = new ArrayList<>();
        cf.filings().entrySet().stream().filter(e -> PERIODIC.contains(e.getValue().form()))
                .sorted(Comparator.comparing((Map.Entry<String, IssuerFacts.Filing> e) -> e.getValue().filed()).thenComparing(Map.Entry::getKey))
                .forEach(periodic::add);
        Map<String, Double> out = new LinkedHashMap<>();
        List<Map.Entry<LocalDate, Double>> seen = new ArrayList<>();
        for (Map.Entry<String, IssuerFacts.Filing> e : periodic) {
            Double c = cf.coverShares().get(e.getKey());
            if (c == null || c <= 0) {
                continue;
            }
            LocalDate f = e.getValue().filed();
            seen.add(Map.entry(f, c));
            List<Double> window = new ArrayList<>();
            for (Map.Entry<LocalDate, Double> x : seen.subList(Math.max(0, seen.size() - 5), seen.size())) {
                window.add(x.getValue() * splitFactor(splits, x.getKey(), f));
            }
            window.sort(Comparator.naturalOrder());
            out.put(e.getKey(), window.get(window.size() / 2));
        }
        return out;
    }

    private static boolean digit(double v, Double ref) {
        return ref == null || (v > 0 && Math.abs(Math.log10(v / ref)) < 1);
    }

    private static boolean coverOk(double v, Double ref) {
        return ref != null && v > 0 && Math.abs(Math.log10(v / ref)) <= COVER_WITNESS_LOG;
    }

    /**
     * 공시 × 기간마다 보통주 귀속 이익({@link CommonIncome}) — 판정과 같은 재료(단위 확인을 지난 가중평균 · 기준 표지)와 판정의 n을 쓴다.
     * 인스턴스는 받아 둔 것(보충 · 앵커)만 — 없으면 companyfacts만으로 정한다.
     */
    public Map<IssuerFacts.Key, CommonIncome.Value> commonIncomes(IssuerFacts given, Set<String> anchors, Instances instances,
                                                                 List<Split> splits, List<Verdict> verdicts) {
        Prepared prepared = prepare(given, instances, splits, anchors);
        Map<String, ClassFacts> loaded = new LinkedHashMap<>();
        for (String a : prepared.facts().filings().keySet()) {
            ClassFacts f = load(instances, a);
            if (f != null) {
                loaded.put(a, f);
            }
        }
        return CommonIncome.judge(prepared.facts(), prepared.references(), loaded, verdicts, splits);
    }

    /** 결과 — 고친 companyfacts 재료와 인스턴스가 필요했는데 못 받은 공시. */
    private record Prepared(IssuerFacts facts, Map<String, Double> references, Set<String> noInstance) {
    }

    /**
     * 받기 후보(ADR-0189) — 지금 경로 공시 중 companyfacts만으로는 당기 분모가 안 서는 것. 받기({@code ShareClassCollector})와 유도가 같은
     * 함수를 부른다(인스턴스 없이 지은 결과에서 당기 값이 0도 아닌 「없음」인 공시).
     */
    /**
     * 보통주 귀속 이익 후보 — {@link IssuerFacts#INSTANCE_FROM}(지표 창 − 5년 여유는 {@link #DENOMINATOR_FROM}) 뒤 공시 중 companyfacts만으로
     * 당기 보통주 몫을 못 정하는 것(축 없는 NIC도 총 EPS도 없다 — PAA는 LP 단위 EPS를 LP 클래스 축에만, BKR은 Class A에만 싣는다). 받기가
     * 이 공시의 인스턴스를 더 받는다(탐색 {@code PERSHARE-NUM-PLAN-1024.md} v2.1 · 앵커 · 분모 후보는 이미 받는다).
     */
    public static Set<String> numeratorCandidates(IssuerFacts cf) {
        Prepared p = standard().prepare(cf, accession -> null, List.of(), Set.of());
        Map<IssuerFacts.Key, CommonIncome.Value> keys = CommonIncome.judge(p.facts(), p.references(), Map.of(), List.of(), List.of());
        Map<String, Boolean> undecided = new LinkedHashMap<>();
        keys.forEach((k, v) -> {
            IssuerFacts.Filing f = cf.filings().get(k.accession());
            LocalDate end = cf.currentEnd(k.accession());
            if (f == null || f.filed().isBefore(DENOMINATOR_FROM) || end == null
                    || Math.abs(ChronoUnit.DAYS.between(k.period().end(), end)) > SAME_END_DAYS) {
                return;
            }
            undecided.merge(k.accession(), !v.decided(), Boolean::logicalAnd);
        });
        Set<String> out = new LinkedHashSet<>();
        undecided.forEach((a, none) -> {
            if (none) {
                out.add(a);
            }
        });
        // 당기 손익 · EPS 키가 하나도 없는 행 폼 공시 — companyfacts가 그 공시의 손익을 안 실었다(POR 2025 Q1 10-Q는 가중평균만).
        cf.filings().forEach((a, f) -> {
            if (ROW_FORMS.contains(f.form()) && !f.filed().isBefore(DENOMINATOR_FROM) && !undecided.containsKey(a)) {
                out.add(a);
            }
        });
        return out;
    }

    /** 재무 행의 {@code report_code}가 될 수 있는 폼 — 정기 · 정정 · 6-K. */
    private static final Set<String> ROW_FORMS = Set.of("10-K", "10-Q", "20-F", "40-F", "10-K/A", "10-Q/A", "20-F/A", "40-F/A",
            "6-K", "6-K/A", "10-KT");

    public static Set<String> denominatorCandidates(IssuerFacts cf) {
        // 분할 원장 없이 정해진다 — 후보를 가르는 확인(값마다의 단위 · 원장 선언 대체)은 그 공시 자기 표지만 본다.
        return standard().prepare(cf, accession -> null, List.of(), Set.of()).noInstance();
    }

    /**
     * 지금 경로(축 없는 표지가 있는 정기 공시 · 표지가 클래스에 없는 보충 공시)의 분모 재료를 원장대로 고친다(ADR-0189).
     * <ol>
     *   <li>값마다(전 기간): 기준 표지와 자릿수(10배)가 맞는 첫 태그 값. 없으면 0(값 없음 — 기간 열쇠는 남긴다). 배율을 찾아 고쳐 쓰지
     *       않는다(2026-10-25 사용자 기준).</li>
     *   <li>당기 값이 없으면 원장이 선언한 대체: 기본 = 희석을 선언한 기간의 희석 가중평균, 기본 EPS와 엄격히 닫히는 ProForma.</li>
     *   <li>그래도 없으면(2020 뒤) 인스턴스: 축 없는 값 또는 뜻이 같은 축 하나짜리의 0 아닌 값 하나(폐합 또는 표지 비율), 그 밖 축은
     *       선언 정밀도의 엄격 폐합 — 모두 자릿수.</li>
     * </ol>
     */
    private Prepared prepare(IssuerFacts cf, Instances instances, List<Split> splits, Set<String> anchors) {
        Map<IssuerFacts.Key, Double> w = new LinkedHashMap<>(cf.weighted());
        Map<String, Double> refs = new LinkedHashMap<>(referenceCovers(cf, splits));
        Set<String> noInstance = new LinkedHashSet<>();
        for (Map.Entry<String, IssuerFacts.Filing> e : cf.filings().entrySet()) {
            String a = e.getKey();
            IssuerFacts.Filing fl = e.getValue();
            if (!PERIODIC.contains(fl.form())) {
                continue;
            }
            Double ref = refs.get(a);
            Double ownCover = cf.coverShares().get(a);
            ClassFacts facts = null;
            if (ref == null) {
                // 표지가 클래스에 없는 보충 공시(companyfacts가 빠뜨린 정기 공시 · 「cur」 갈래) — 받은 인스턴스의 축 없는 표지로 선다.
                if (!anchors.contains(a) || cf.coverShares().containsKey(a)) {
                    uncoveredChecked(cf, w, a);
                    continue;
                }
                facts = load(instances, a);
                if (facts == null || facts.isClassAnchor()) {
                    // 클래스 축 앵커는 자기 판정(폐합)이 n을 정한다 — 여기서 비우면 Q4 입력의 원장 어긋남이 「안줌」으로 바뀐다(ECHO).
                    continue;
                }
                String axisless = facts.coverOf(ClassFactReader.SHARES_OUTSTANDING).get(AXISLESS);
                ref = axisless == null ? null : Double.parseDouble(axisless);
                if (ref == null || ref <= 0) {
                    uncoveredChecked(cf, w, a);
                    continue;
                }
                refs.put(a, ref);
                ownCover = ref;
            }
            for (IssuerFacts.Key k : new ArrayList<>(w.keySet())) {
                if (k.accession().equals(a)) {
                    w.put(k, unitChecked(cf, k, w.get(k), ownCover));
                }
            }
            ClassFacts.Period own = latestOwnPeriod(cf, w, a, fl.form());
            if (own != null && w.get(new IssuerFacts.Key(a, own)) > 0) {
                continue;
            }
            if (declaredSubstitute(cf, w, a, fl.form(), ownCover) || own != null) {
                // 당기 값이 있는데 단위 확인이 지웠으면 인스턴스는 같은 값을 준다(companyfacts가 인스턴스에서 온다) — 묻지 않는다.
                continue;
            }
            if (fl.filed().isBefore(DENOMINATOR_FROM)) {
                continue;
            }
            if (facts == null) {
                facts = load(instances, a);
            }
            if (facts == null) {
                noInstance.add(a);
                continue;
            }
            boolean quarterly = fl.form().startsWith("10-Q");
            Object[] latest = quarterly ? instanceValue(facts, 80, 100, cf.currentEnd(a)) : instanceValue(facts, 350, 380, cf.currentEnd(a));
            if (latest == null && quarterly) {
                latest = instanceValue(facts, 100, 115, cf.currentEnd(a));
            }
            if (latest != null && acceptInstance(cf, facts, a, latest, ref, true)) {
                w.put(new IssuerFacts.Key(a, (ClassFacts.Period) latest[0]), (Double) latest[1]);
                if (quarterly) {
                    Object[] nine = instanceValue(facts, 255, 285, ((ClassFacts.Period) latest[0]).end());
                    if (nine != null && acceptInstance(cf, facts, a, nine, ref, false)) {
                        w.putIfAbsent(new IssuerFacts.Key(a, (ClassFacts.Period) nine[0]), (Double) nine[1]);
                    }
                }
            }
        }
        return new Prepared(cf.withWeighted(w), refs, noInstance);
    }

    private static ClassFacts load(Instances instances, String accession) {
        try {
            return instances.load(accession);
        } catch (DataSourceException ex) {
            // 받기 실패 · 못 읽음은 「인스턴스 없음」과 같이 noInstance 갈래로 판정에 남는다(앵커는 같은 실패가 noinst 숨김이다).
            return null;
        }
    }

    /** 원장이 선언한 대체 분모 — 기본 = 희석을 선언한 기간의 희석 가중평균, 또는 기본 EPS와 엄격히 닫히는 ProForma(자릿수는 자기 표지). */
    private boolean declaredSubstitute(IssuerFacts cf, Map<IssuerFacts.Key, Double> w, String a, String form, Double ref) {
        LocalDate end = cf.currentEnd(a);
        if (end == null) {
            return false;
        }
        int lo = form.startsWith("10-Q") ? 80 : 350;
        int hi = form.startsWith("10-Q") ? 115 : 380;
        IssuerFacts.Witnesses wit = cf.witnesses();
        for (Map<IssuerFacts.Key, Double> source : List.of(wit.diluted(), wit.proForma())) {
            for (Map.Entry<IssuerFacts.Key, Double> x : source.entrySet()) {
                IssuerFacts.Key k = x.getKey();
                if (!k.accession().equals(a) || !(lo < k.period().days() && k.period().days() < hi)
                        || Math.abs(ChronoUnit.DAYS.between(k.period().end(), end)) > SAME_END_DAYS) {
                    continue;
                }
                double v = x.getValue();
                boolean declared = source == wit.proForma() || basicEqualsDiluted(cf, k);
                if (declared && digit(v, ref) && Boolean.TRUE.equals(strictCloses(cf, k, v))) {
                    w.put(k, v);
                    return true;
                }
            }
        }
        return false;
    }

    /** 원장이 그 기간 기본 = 희석을 선언했는가 — EPS를 「기본 및 희석」 태그로만 실었거나, 기본 · 희석 EPS가 같고 두 분자가 다르지 않다. */
    private static boolean basicEqualsDiluted(IssuerFacts cf, IssuerFacts.Key k) {
        IssuerFacts.Witnesses wit = cf.witnesses();
        if (wit.basicAndDilutedEps().contains(k)) {
            return true;
        }
        Double basic = cf.eps().get(k);
        Double diluted = wit.epsDiluted().get(k);
        if (basic == null || diluted == null || Double.compare(basic, diluted) != 0) {
            return false;
        }
        Double nb = cf.declaredNumerator().get(k);
        Double nd = wit.dilutedNumerator().get(k);
        return nb == null || nd == null || Double.compare(nb, nd) == 0;
    }

    /**
     * 값 하나의 단위 확인 — 그 공시 자기 표지와 EPS 폐합. 폐합이 참이거나 불명이면 둔다(표지가 틀린 공시가 있어 어느 쪽이 틀렸는지 못 가른다
     * — CRM 2011 · PNR 2011). 폐합이 거짓이고 표지와 자릿수(10배)도 어긋나면: 같은 기간의 다음 태그 값(AMCR — 원장이 같은 공시에 실은
     * 값) → 없으면 0. 배율을 찾아 고쳐 쓰지 않는다 — 원장이 뒤틀린 자리를 수리로 메우는 것이라 정합성 훼손이다(2026-10-25 사용자 기준).
     */
    private Double unitChecked(IssuerFacts cf, IssuerFacts.Key k, Double v, Double cover) {
        if (v == null || v <= 0 || cover == null) {
            return v;
        }
        Boolean closes = witnessCloses(cf, k, v);
        if (closes == null || closes || digit(v, cover)) {
            return v;
        }
        for (double x : cf.witnesses().laterTags().getOrDefault(k, List.of())) {
            if (digit(x, cover) && !Boolean.FALSE.equals(witnessCloses(cf, k, x))) {
                return x;
            }
        }
        // MUST NOT: 열쇠를 지우지 않는다 — 0은 지금 경로 분모가 「값 없음」으로, Q4 입력이 「없음」으로 읽는 값이다.
        return 0.0;
    }

    /**
     * 기준 표지가 없는 공시의 값 확인 — 자릿수를 물을 표지가 없어도, EPS × n이 분자 × 10^k(k = ±2 · ±3 · ±6)로만 닫히면 n이나 EPS 중
     * 하나를 단위를 틀리게 실은 것이다(NVMI 2026 20-F 30,108 — 천 단위). 어느 쪽인지 원장이 가려 주지 않으니 그 n은 0(값 없음)이다 —
     * 고쳐 쓰지 않는다(2026-10-25 사용자 기준). 폐합이 참 · 불명 · 거듭제곱이 아닌 어긋남(배분)이면 둔다.
     */
    private void uncoveredChecked(IssuerFacts cf, Map<IssuerFacts.Key, Double> w, String a) {
        for (IssuerFacts.Key k : new ArrayList<>(w.keySet())) {
            if (!k.accession().equals(a)) {
                continue;
            }
            Double v = w.get(k);
            if (v == null || v <= 0 || !Boolean.FALSE.equals(witnessCloses(cf, k, v))) {
                continue;
            }
            for (int s : UNIT_STEPS) {
                double x = v * Math.pow(10, s);
                if (Boolean.TRUE.equals(witnessCloses(cf, k, x)) && Math.abs(numeratorOf(cf, k)) > x * 0.005) {
                    // 분자가 반 센트 × n보다 커야 지문이다 — 아니면 n을 줄이기만 해도 자명하게 닫힌다.
                    w.put(k, 0.0);
                    break;
                }
            }
        }
    }

    /** 단위 오태깅의 지문 — 원장 값이 다른 원장 값과 이 거듭제곱만큼 어긋나 닫힌다(센트 · 천 · 백만). */
    static final int[] UNIT_STEPS = {-6, -3, -2, 2, 3, 6};

    /** 폐합이 읽는 분자 — 원장이 밝힌 분자, 없으면 대용({@link #witnessCloses}가 null이 아닐 때만 부른다). */
    private static double numeratorOf(IssuerFacts cf, IssuerFacts.Key k) {
        return cf.declaredNumerator().containsKey(k) ? cf.declaredNumerator().get(k) : cf.proxyNumerator().get(k);
    }

    /** EPS × n이 분자와 닫히는가 — companyfacts에는 선언 정밀도가 없어 대용 허용(τ · |분자|)과 EPS 반 센트. */
    private Boolean witnessCloses(IssuerFacts cf, IssuerFacts.Key k, double v) {
        Double e = cf.eps().get(k);
        Double num = cf.declaredNumerator().containsKey(k) ? cf.declaredNumerator().get(k) : cf.proxyNumerator().get(k);
        if (e == null || e == 0 || num == null) {
            return null;
        }
        return Math.abs(v * e - num) <= v * 0.005 + judge.proxyTolerance() * Math.abs(num) + 1e-9 * Math.abs(num);
    }

    /** 엄격 폐합 — EPS 반 센트와 분자 끝자리 0이 말하는 반올림 반폭(companyfacts에 선언 정밀도가 없다)만. */
    private static Boolean strictCloses(IssuerFacts cf, IssuerFacts.Key k, double v) {
        Double e = cf.eps().get(k);
        Double num = cf.declaredNumerator().containsKey(k) ? cf.declaredNumerator().get(k) : cf.proxyNumerator().get(k);
        if (e == null || e == 0 || num == null) {
            return null;
        }
        return Math.abs(v * e - num) <= v * 0.005 + impliedHalfWidth(num) + 1e-9 * Math.abs(num);
    }

    private static double impliedHalfWidth(double value) {
        long x = Math.abs(Math.round(value));
        long p = 1;
        while (x != 0 && p < 1_000_000_000L && x % (p * 10) == 0) {
            p *= 10;
        }
        return x == 0 ? 0 : p / 2.0;
    }

    /**
     * 인스턴스 값을 받는가. 뜻이 같은 축(또는 축 없음)은 폐합(companyfacts 대용 허용 또는 인스턴스 선언 정밀도) 또는 표지 비율 τ_c(최신
     * 기간만), 그 밖 축은 인스턴스 선언 정밀도의 엄격 폐합 — 모두 기준 표지와 자릿수가 맞아야 한다.
     */
    private boolean acceptInstance(IssuerFacts cf, ClassFacts facts, String a, Object[] picked, double ref, boolean latest) {
        ClassFacts.Period p = (ClassFacts.Period) picked[0];
        double v = (Double) picked[1];
        boolean sameMeaning = (Boolean) picked[2];
        if (!digit(v, ref)) {
            return false;
        }
        Boolean strict = instanceCloses(facts, p, v);
        if (!sameMeaning) {
            return Boolean.TRUE.equals(strict);
        }
        IssuerFacts.Key k = new IssuerFacts.Key(a, p);
        if (Boolean.TRUE.equals(strict) || Boolean.TRUE.equals(witnessCloses(cf, k, v))) {
            return true;
        }
        return latest && coverOk(v, ref);
    }

    /** 인스턴스의 축 없는 EPS · 분자로 선언 정밀도 안에서 닫히는가 — 물을 수 없으면 null. */
    private static Boolean instanceCloses(ClassFacts facts, ClassFacts.Period p, double v) {
        ClassFacts.Measured e = null;
        for (String t : ClassFactReader.EPS_TAGS) {
            e = facts.of(t).get(new ClassFacts.MemberPeriod(AXISLESS, p));
            if (e != null) {
                break;
            }
        }
        ClassFacts.Measured num = null;
        for (String t : ClassFactReader.NUMERATOR_TAGS) {
            num = facts.of(t).get(new ClassFacts.MemberPeriod(AXISLESS, p));
            if (num != null) {
                break;
            }
        }
        if (e == null || e.value() == 0 || num == null) {
            return null;
        }
        return DenominatorJudge.closes(v, e.value(), num.value(), e.halfWidth(), num.halfWidth(), 0);
    }

    /**
     * 인스턴스에서 기간 창의 가중평균 — 태그 순서대로 처음 서는 것. 축 없는 값이 먼저, 없으면 축 하나짜리의 0 아닌 값이 하나일 때(뜻이 같은
     * 축을 먼저 · 다른 축은 따로). {@code end}가 있으면 그 기말(±3일)만. 돌려주는 것: {기간, 값, 뜻이 같은 축인가}.
     */
    private static Object[] instanceValue(ClassFacts facts, int lo, int hi, LocalDate end) {
        for (String tag : ClassFactReader.WEIGHTED_TAGS) {
            List<Map.Entry<ClassFacts.MemberPeriod, ClassFacts.Measured>> c = new ArrayList<>();
            for (Map.Entry<ClassFacts.MemberPeriod, ClassFacts.Measured> x : facts.of(tag).entrySet()) {
                if (AXISLESS.equals(x.getKey().member())) {
                    c.add(x);
                }
            }
            c.addAll(facts.of(ClassFactReader.DIMENSIONED + tag).entrySet());
            c.removeIf(x -> !(lo < x.getKey().period().days() && x.getKey().period().days() < hi)
                    || end != null && Math.abs(ChronoUnit.DAYS.between(x.getKey().period().end(), end)) > SAME_END_DAYS);
            if (c.isEmpty()) {
                continue;
            }
            LocalDate last = c.stream().map(x -> x.getKey().period().end()).max(Comparator.naturalOrder()).get();
            c.removeIf(x -> !x.getKey().period().end().equals(last));
            for (Map.Entry<ClassFacts.MemberPeriod, ClassFacts.Measured> x : c) {
                if (AXISLESS.equals(x.getKey().member())) {
                    return new Object[]{x.getKey().period(), x.getValue().value(), true};
                }
            }
            for (boolean same : new boolean[]{true, false}) {
                Set<Double> values = new LinkedHashSet<>();
                ClassFacts.Period period = null;
                for (Map.Entry<ClassFacts.MemberPeriod, ClassFacts.Measured> x : c) {
                    String axis = x.getKey().member().substring(0, x.getKey().member().indexOf('='));
                    if (ClassFactReader.SAME_DENOMINATOR_AXES.contains(axis) == same && x.getValue().value() != 0) {
                        values.add(x.getValue().value());
                        period = x.getKey().period();
                    }
                }
                if (values.size() == 1) {
                    return new Object[]{period, values.iterator().next(), same};
                }
                if (values.size() > 1) {
                    return null;
                }
            }
            return null;
        }
        return null;
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
