package com.stockscreener.pipeline.datasource.us.shareclass;

import com.stockscreener.pipeline.datasource.us.listing.ClassLines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts.AXISLESS;
import static com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader.DECLARED_NUMERATOR;
import static com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader.EPS_TAGS;
import static com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader.NUMERATOR_TAGS;
import static com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader.SECURITY_TITLE;
import static com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader.SHARES_OUTSTANDING;
import static com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader.TRADING_SYMBOL;
import static com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader.WEIGHTED_TAGS;

/**
 * 분모 n — 그 공시가 스스로 닫는 EPS 분모(탐색 `SHARES-CLASS-PLAN-V5-1015.md` §1c · 시뮬레이터 {@code rule5.py}+{@code rule7.py}).
 *
 * <p>폐합: 분모 × EPS = 분자. 허용은 하나의 오차 예산이다 — 원장이 EPS 분자로 밝힌 분자({@link ClassFactReader#DECLARED_NUMERATOR} ·
 * EPS 구성원 전원의 배분)면 선언 정밀도만, 대용 분자({@code NetIncomeLoss}·{@code ProfitLoss})면 + {@code proxyTolerance}·|분자|.
 * 실패면 단위 복원(분모 ×10^±3,±6 · EPS ×10^±2,±3 · 분자 ×10^±3,±6)을 다시 폐합하고, 같은 공시의 시점 총량 T와 자릿수가 맞는
 * 후보가 <b>하나</b>일 때만 받는다(T가 없으면 복원하지 않는다). EPS와 분자의 부호가 어긋나면 폐합으로 검사할 수 없다(값을 받는다).
 */
public final class DenominatorJudge {

    /** 판정 결과. {@code n}이 null이면 실패, {@code failureSource}는 실패일 때만(원장 · 대용). */
    public record Result(Double n, String branch, ClassFacts.Period period, String failureSource) {
    }

    public enum Form {
        QUARTER(80, 100), NINE_MONTHS(255, 285), ANNUAL(350, 380);

        final int min;
        final int max;

        Form(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public static Form of(String secForm) {
            return secForm.startsWith("10-Q") ? QUARTER : ANNUAL;
        }
    }

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]");
    private static final Pattern SYMBOL_SPLIT = Pattern.compile("[,\\s/]+");
    private static final Pattern CLASS_LETTER = Pattern.compile("Class([A-Z]\\d?)");
    /** 구성원 이름에 든 클래스 글자(「ClassAAndClassB」 → A · B) — 소문자가 이으면 낱말이다(「Classes」). */
    private static final Pattern CLASS_LETTERS = Pattern.compile("Class([A-Z]\\d?)(?![a-z])");
    /** 「ClassAAndB」 · 「ClassAOrB」 — 둘째 글자에 {@code Class}가 안 붙은 묶음. */
    private static final Pattern CLASS_PAIR = Pattern.compile("Class[A-Z]\\d?(?:And|Or)[A-Z]\\d?(?![a-z])");
    private static final Pattern TITLE_CLASS = Pattern.compile("Class\\s+([A-Z])(?:-?(\\d))?\\b");
    private static final Pattern COMMON = Pattern.compile("common", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TOKEN_STOP = Set.of("member", "stock", "shares", "share", "par", "value", "per", "the",
            "of", "and", "us", "gaap", "ordinary");

    private final double proxyTolerance;

    public DenominatorJudge(double proxyTolerance) {
        this.proxyTolerance = proxyTolerance;
    }

    double proxyTolerance() {
        return proxyTolerance;
    }

    public Result judge(String ticker, ClassFacts f, Form form, Double coverTotal) {
        Result base = closure(ticker, f, form);
        if (base.period() == null || !base.branch().contains("FAIL")) {
            return base;
        }
        ClassFacts.Period p = base.period();
        Map<String, Double> w = periodValues(f, WEIGHTED_TAGS, p);
        Map<String, Double> e = periodValues(f, EPS_TAGS, p);
        Double num = axisless(f, NUMERATOR_TAGS, p);
        List<Double> ev = e.values().stream().filter(v -> v != 0).toList();
        if (num != null && !ev.isEmpty() && ev.stream().allMatch(v -> v * num < 0)) {
            Double nn = w.get(AXISLESS);
            if (nn == null || nn == 0) {
                double s = w.entrySet().stream().filter(x -> !AXISLESS.equals(x.getKey())).mapToDouble(Map.Entry::getValue).sum();
                nn = s == 0 ? null : s;
            }
            return new Result(nn, base.branch().replace("FAIL", "unverifiable-sign"), p, null);
        }
        Double t = coverTotal;
        if (t == null) {
            double s = 0;
            boolean any = false;
            for (String v : f.coverOf(SHARES_OUTSTANDING).values()) {
                double d = Double.parseDouble(v);
                if (d > 0) {
                    s += d;
                    any = true;
                }
            }
            t = any ? s : null;
        }
        List<Object[]> hits = new ArrayList<>();
        restore(ticker, f, form, p, WEIGHTED_TAGS, "w", new int[]{3, 6, -3, -6}, hits);
        restore(ticker, f, form, p, EPS_TAGS, "eps", new int[]{2, 3, -2, -3}, hits);
        restore(ticker, f, form, p, NUMERATOR_TAGS, "num", new int[]{3, 6, -3, -6}, hits);
        if (t == null) {
            hits.clear();
        } else {
            double tt = t;
            hits.removeIf(h -> Math.abs(Math.log10((Double) h[1] / tt)) >= 1);
        }
        if (hits.size() == 1) {
            Object[] h = hits.get(0);
            return new Result((Double) h[1], h[2] + "+" + h[0], p, null);
        }
        String source = declared(f, p) ? "원장" : "대용";
        return new Result(null, base.branch() + "/" + source, p, source);
    }

    private void restore(String ticker, ClassFacts f, Form form, ClassFacts.Period p, List<String> tags, String label,
                         int[] steps, List<Object[]> hits) {
        for (int s : steps) {
            Result r = closure(ticker, scaled(f, tags, p, Math.pow(10, s)), form);
            if (r.n() != null && !r.branch().contains("FAIL") && !r.branch().contains("unverif")) {
                hits.add(new Object[]{label + (s > 0 ? "+" : "") + s, r.n(), r.branch()});
            }
        }
    }

    private static ClassFacts scaled(ClassFacts f, List<String> tags, ClassFacts.Period p, double k) {
        Map<String, LinkedHashMap<ClassFacts.MemberPeriod, ClassFacts.Measured>> d = new LinkedHashMap<>(f.durations());
        for (String t : tags) {
            if (!d.containsKey(t)) {
                continue;
            }
            LinkedHashMap<ClassFacts.MemberPeriod, ClassFacts.Measured> copy = new LinkedHashMap<>();
            d.get(t).forEach((mp, m) -> copy.put(mp, mp.period().equals(p)
                    ? new ClassFacts.Measured(m.value() * k, m.halfWidth() * k) : m));
            d.put(t, copy);
        }
        return new ClassFacts(d, f.cover(), f.documentType());
    }

    /** 원장이 EPS 분자를 밝혔는가 — 축 없는 「보통주 귀속 순이익」 또는 EPS 구성원 전원의 배분. */
    static boolean declared(ClassFacts f, ClassFacts.Period p) {
        if (f.of(DECLARED_NUMERATOR).containsKey(new ClassFacts.MemberPeriod(AXISLESS, p))) {
            return true;
        }
        List<String> em = periodValues(f, EPS_TAGS, p).keySet().stream().filter(m -> !AXISLESS.equals(m)).toList();
        return !em.isEmpty() && NUMERATOR_TAGS.subList(0, 2).stream()
                .anyMatch(t -> em.stream().allMatch(m -> f.of(t).containsKey(new ClassFacts.MemberPeriod(m, p))));
    }

    /** 그 기간의 구성원 → 값(태그 순서대로 덮어씀 — 뒤 태그가 이긴다 · 시뮬레이터 {@code _period_facts}). */
    static Map<String, Double> periodValues(ClassFacts f, List<String> tags, ClassFacts.Period p) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String t : tags) {
            f.of(t).forEach((mp, m) -> {
                if (mp.period().equals(p)) {
                    out.put(mp.member(), m.value());
                }
            });
        }
        return out;
    }

    private static Double axisless(ClassFacts f, List<String> tags, ClassFacts.Period p) {
        for (String t : tags) {
            ClassFacts.Measured m = f.of(t).get(new ClassFacts.MemberPeriod(AXISLESS, p));
            if (m != null) {
                return m.value();
            }
        }
        return null;
    }

    // ---- rule5 ---------------------------------------------------------------------------------------------------

    private Result closure(String ticker, ClassFacts f, Form form) {
        Set<ClassFacts.Period> periods = new LinkedHashSet<>();
        for (String t : WEIGHTED_TAGS) {
            f.of(t).keySet().forEach(mp -> periods.add(mp.period()));
        }
        if (periods.isEmpty()) {
            return new Result(null, "no-wavg", null, null);
        }
        Optional<ClassFacts.Period> own = periods.stream().filter(p -> form.min < p.days() && p.days() < form.max)
                .max(Comparator.comparing(ClassFacts.Period::end).thenComparing(ClassFacts.Period::start));
        if (own.isEmpty()) {
            return new Result(null, "no-own-period", null, null);
        }
        ClassFacts.Period p = own.get();
        Map<String, Double> w = firstPerMember(f, WEIGHTED_TAGS, p);
        Map<String, Double> e = firstPerMember(f, EPS_TAGS, p);
        Double num = null;
        double hnum = 0;
        for (String t : NUMERATOR_TAGS) {
            ClassFacts.Measured m = f.of(t).get(new ClassFacts.MemberPeriod(AXISLESS, p));
            if (m != null) {
                num = m.value();
                hnum = m.halfWidth();
                if (!DECLARED_NUMERATOR.equals(t)) {
                    hnum += proxyTolerance * Math.abs(m.value());
                }
                break;
            }
        }
        List<String> edm = e.keySet().stream().filter(m -> !AXISLESS.equals(m)).toList();
        Double alloc = null;
        double halloc = 0;
        for (String t : NUMERATOR_TAGS.subList(0, 2)) {
            if (!edm.isEmpty() && edm.stream().allMatch(m -> f.of(t).containsKey(new ClassFacts.MemberPeriod(m, p)))) {
                double s = 0;
                double h = 0;
                for (String m : edm) {
                    ClassFacts.Measured x = f.of(t).get(new ClassFacts.MemberPeriod(m, p));
                    s += x.value();
                    h += x.halfWidth();
                }
                alloc = s;
                halloc = h;
                break;
            }
        }
        boolean wAxisless = w.containsKey(AXISLESS);
        boolean eAxisless = e.containsKey(AXISLESS);
        // A0: 축 없는 분모 · EPS는 구성원 하나에만
        if (wAxisless && !eAxisless && edm.size() == 1) {
            double n = w.get(AXISLESS);
            Double na = alloc != null ? alloc : num;
            double ha = alloc != null ? halloc : hnum;
            Boolean ok = closes(n, e.get(edm.get(0)), na, hw(f, EPS_TAGS, edm.get(0), p), ha, hw(f, WEIGHTED_TAGS, AXISLESS, p));
            return ok == null ? new Result(n, "A0-unverifiable", p, null)
                    : ok ? new Result(n, "A0-ok", p, null) : new Result(null, "A0-FAIL", p, null);
        }
        // M: 구성원 분모만 · 축 없는 EPS
        if (!wAxisless && eAxisless && edm.isEmpty() && !w.isEmpty()) {
            double n = w.values().stream().mapToDouble(Double::doubleValue).sum();
            double hw = w.keySet().stream().mapToDouble(m -> hw(f, WEIGHTED_TAGS, m, p)).sum();
            Boolean ok = closes(n, e.get(AXISLESS), num, hw(f, EPS_TAGS, AXISLESS, p), hnum, hw);
            return ok == null ? new Result(n, "M-unverifiable", p, null)
                    : ok ? new Result(n, "M-ok", p, null) : new Result(null, "M-FAIL", p, null);
        }
        // A: 축 없는 분모
        if (wAxisless && (eAxisless || edm.isEmpty())) {
            return axislessCase("A", f, w, e, num, hnum, p);
        }
        List<String> dims = w.keySet().stream().filter(m -> !AXISLESS.equals(m)).toList();
        Map<String, Double> eds = new LinkedHashMap<>();
        for (String m : dims) {
            if (e.containsKey(m)) {
                eds.put(m, e.get(m));
            }
        }
        if (!dims.isEmpty() && !eds.isEmpty()) {
            Double numB = alloc != null ? alloc : num;
            double hb = alloc != null ? halloc : hnum;
            double lhs = eds.entrySet().stream().mapToDouble(x -> w.get(x.getKey()) * x.getValue()).sum();
            Set<Double> vals = new LinkedHashSet<>();
            eds.values().forEach(v -> vals.add(round4(v)));
            if (vals.size() == 1) {
                double n = eds.keySet().stream().mapToDouble(w::get).sum();
                if (Boolean.TRUE.equals(closesB(lhs, numB, tolB(f, w, eds, eds.keySet(), p, hb)))) {
                    return new Result(n, "B-equal-ok", p, null);
                }
            }
            String[] mapped = listedMember(ticker, f, new ArrayList<>(eds.keySet()));
            String lm = mapped[0];
            if (lm != null && eds.get(lm) != 0) {
                double n = lhs / eds.get(lm);
                if (Boolean.TRUE.equals(closesB(lhs, numB, tolB(f, w, eds, eds.keySet(), p, hb)))) {
                    return new Result(n, "B-sum-ok/" + mapped[1], p, null);
                }
                if (Boolean.TRUE.equals(closesB(w.get(lm) * eds.get(lm), num, tolB(f, w, eds, Set.of(lm), p, hnum)))) {
                    return new Result(w.get(lm), "B-member-ok/" + mapped[1], p, null);
                }
                return new Result(null, "B-FAIL/" + mapped[1], p, null);
            }
            if (wAxisless) {
                double n = w.get(AXISLESS);
                Boolean ok = closes(n, e.get(AXISLESS), num, hw(f, EPS_TAGS, AXISLESS, p), hnum, hw(f, WEIGHTED_TAGS, AXISLESS, p));
                if (Boolean.TRUE.equals(ok)) {
                    return new Result(n, "A2-ok", p, null);
                }
            }
            return new Result(null, "B-unmapped", p, null);
        }
        if (!wAxisless && e.isEmpty() && !w.isEmpty()) {
            return new Result(w.values().stream().mapToDouble(Double::doubleValue).sum(), "M0-unverifiable", p, null);
        }
        if (wAxisless) {
            return axislessCase("A3", f, w, e, num, hnum, p);
        }
        return new Result(null, "no-rule", p, null);
    }

    private static Result axislessCase(String name, ClassFacts f, Map<String, Double> w, Map<String, Double> e, Double num,
                                       double hnum, ClassFacts.Period p) {
        double n = w.get(AXISLESS);
        Boolean ok = closes(n, e.get(AXISLESS), num, hw(f, EPS_TAGS, AXISLESS, p), hnum, hw(f, WEIGHTED_TAGS, AXISLESS, p));
        if (ok == null) {
            return new Result(n, name + "-unverifiable", p, null);
        }
        return ok ? new Result(n, name + "-ok", p, null) : new Result(null, name + "-FAIL", p, null);
    }

    /** 그 기간에 처음 나온 구성원 값(태그 순서 · 파일 순서 — 시뮬레이터 {@code rule5}의 {@code m not in w}). */
    private static Map<String, Double> firstPerMember(ClassFacts f, List<String> tags, ClassFacts.Period p) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String t : tags) {
            f.of(t).forEach((mp, m) -> {
                if (mp.period().equals(p)) {
                    out.putIfAbsent(mp.member(), m.value());
                }
            });
        }
        return out;
    }

    private static double hw(ClassFacts f, List<String> tags, String member, ClassFacts.Period p) {
        for (String t : tags) {
            ClassFacts.Measured m = f.of(t).get(new ClassFacts.MemberPeriod(member, p));
            if (m != null) {
                return m.halfWidth();
            }
        }
        return 0.005;
    }

    private static double tolB(ClassFacts f, Map<String, Double> w, Map<String, Double> e, Set<String> ms,
                               ClassFacts.Period p, double hNum) {
        double s = 0;
        for (String m : ms) {
            s += w.get(m) * hw(f, EPS_TAGS, m, p) + Math.abs(e.get(m)) * hw(f, WEIGHTED_TAGS, m, p);
        }
        return s + hNum;
    }

    /** |n·e − 분자| ≤ n·he + |e|·hw + h분자 (+ 상대 1e-9). EPS가 0이거나 없거나 분자가 없으면 검사 불가(null). */
    static Boolean closes(double n, Double e, Double num, double he, double hn, double hw) {
        if (e == null || e == 0 || num == null) {
            return null;
        }
        return Math.abs(n * e - num) <= n * he + Math.abs(e) * hw + hn + 1e-9 * Math.abs(num);
    }

    private static Boolean closesB(double lhs, Double num, double tol) {
        if (num == null || lhs == 0) {
            return null;
        }
        return Math.abs(lhs - num) <= tol + 1e-9 * Math.abs(num);
    }

    private static double round4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }

    // ---- 상장 클래스 매핑 -----------------------------------------------------------------------------------------

    /** 줄의 기호가 가리키는 구성원과 그 근거. 못 잇으면 {null, "unmapped"}. */
    static String[] listedMember(String ticker, ClassFacts f, List<String> members) {
        String[] m = listedMemberByClass(ticker, f, members);
        if (m[0] != null) {
            return m;
        }
        Map<String, String> sym = f.coverOf(TRADING_SYMBOL);
        Map<String, String> ttl = f.coverOf(SECURITY_TITLE);
        List<String> own = sym.entrySet().stream().filter(x -> nk(x.getValue()).equals(nk(ticker))).map(Map.Entry::getKey).toList();
        if (own.size() == 1 && ttl.get(own.get(0)) != null && !ClassLines.isNotCommon(ttl.get(own.get(0)))) {
            Set<String> want = tokens(ttl.get(own.get(0)));
            List<String> c = members.stream().filter(k -> tokens(ClassFacts.local(k)).equals(want)).toList();
            if (c.size() == 1) {
                return new String[]{c.get(0), "title-tokens"};
            }
        }
        return m;
    }

    private static String[] listedMemberByClass(String ticker, ClassFacts f, List<String> members) {
        Map<String, String> sym = f.coverOf(TRADING_SYMBOL);
        Map<String, String> ttl = f.coverOf(SECURITY_TITLE);
        String key = nk(ticker);
        // 기호가 통째로 같은 줄이 먼저다 — 표지는 워런트 · 유닛 · 우선주 · 사채 줄도 같은 기호로 시작해 싣는다(「HIMS WS」 · 「TWO PRA」 ·
        // 「JEF 27A」). 쪼개 담기만 보면 그 줄들이 다 맞아 「하나가 아님」이 된다. 통째로 같은 줄이 없을 때만 한 사실에 든 여러
        // 기호(「GOOGL, GOOG」)를 쪼개 본다 — 상장 판정({@code ListingCover.lineFor})의 「통째로 같은 줄 먼저」와 같다(거래소 · 보통주로
        // 고르는 것은 그쪽만 한다).
        List<String> hits = sym.entrySet().stream()
                .filter(x -> !AXISLESS.equals(x.getKey()) && nk(x.getValue()).equals(key))
                .map(Map.Entry::getKey).toList();
        if (hits.isEmpty()) {
            hits = sym.entrySet().stream()
                    .filter(x -> !AXISLESS.equals(x.getKey()) && splitSymbols(x.getValue()).contains(key))
                    .map(Map.Entry::getKey).toList();
        }
        String title = null;
        if (!hits.isEmpty()) {
            if (hits.size() == 1) {
                String m = hits.get(0);
                if (ttl.get(m) != null && ClassLines.isNotCommon(ttl.get(m))) {
                    // 그 기호의 줄이 워런트 · 권리 · 유닛(「…and one-half of one redeemable warrant」) · 우선주다 — 그 줄 이름에 든
                    // 「Class A」는 그것이 바뀌는 보통주를 말할 뿐, 그 기호가 보통주인 것이 아니다(SPAC 유닛·워런트 티커 · 클래스 스윕 2026-10-21).
                    return new String[]{null, "not-common"};
                }
                if (members.contains(m)) {
                    return new String[]{m, "direct"};
                }
                title = ttl.get(m);
                Matcher l = CLASS_LETTER.matcher(ClassFacts.local(m));
                if (l.find()) {
                    List<String> c = byLetter(members, l.group(1));
                    if (c.size() == 1) {
                        return new String[]{c.get(0), "direct-letter"};
                    }
                }
            }
        } else if (splitSymbols(sym.getOrDefault(AXISLESS, "")).contains(key)) {
            title = ttl.get(AXISLESS);
            if (title != null && ClassLines.isNotCommon(title)) {
                return new String[]{null, "not-common"};
            }
        }
        if (title != null) {
            Matcher t = TITLE_CLASS.matcher(title);
            if (t.find()) {
                String letter = t.group(1) + (t.group(2) == null ? "" : t.group(2));
                List<String> c = byLetter(members, letter);
                if (c.size() == 1) {
                    return new String[]{c.get(0), "title"};
                }
            } else if (COMMON.matcher(title).find()) {
                List<String> c = members.stream().filter(k -> ClassFacts.local(k).equals("CommonStockMember")).toList();
                if (c.size() == 1) {
                    return new String[]{c.get(0), "title-common"};
                }
            }
        }
        return new String[]{null, "unmapped"};
    }

    private static boolean namesSeveralClasses(String local) {
        return CLASS_PAIR.matcher(local).find()
                || CLASS_LETTERS.matcher(local).results().map(r -> r.group(1)).distinct().count() > 1;
    }

    private static List<String> byLetter(List<String> members, String letter) {
        // 글자는 대소문자를 안 가린다 — 구성원 이름을 「CommonClassaSubjectToRedemptionMember」로 짓는 발행사가 있다(ALDF · 클래스 스윕
        // 2026-10-21). 뒤에 소문자가 이으면 낱말이라(「Classes」) 글자가 아니다.
        String l = "(?:" + Pattern.quote(letter.toUpperCase(Locale.ROOT)) + "|" + Pattern.quote(letter.toLowerCase(Locale.ROOT)) + ")";
        Pattern a = Pattern.compile("Class" + l + "(?![a-z0-9])");
        Pattern b = Pattern.compile("Class" + l + "(Member|Common|NonVoting|Ordinary|Shares|Stock)");
        // 여러 클래스를 묶은 구성원은 어느 한 클래스의 줄이 아니다 — 글자로 잇지 않는다. SPAC은 상장된 상환 Class A 옆에
        // 「NonredeemableClassAAndClassB」를 싣고, 그 기간에 묶음만 있으면 상장 클래스가 비상환 묶음으로 갔다(클래스 스윕 2026-10-21:
        // 그렇게 이어진 행 156 · 같은 꼴의 DELL 「CommonClassA…CAndCommonClassD」 한 기간도 함께 빠진다).
        return members.stream().filter(k -> a.matcher(ClassFacts.local(k)).find() || b.matcher(ClassFacts.local(k)).find())
                .filter(k -> !namesSeveralClasses(ClassFacts.local(k)))
                .toList();
    }

    /** 표지 기호 사실 하나에 든 기호들(「GOOGL, GOOG」 꼴) — 각각 {@link #nk}로 맞춘다. 상장 판정({@code listing})도 같은 규칙을 쓴다. */
    public static List<String> splitSymbols(String s) {
        List<String> out = new ArrayList<>();
        for (String x : SYMBOL_SPLIT.split(s)) {
            out.add(nk(x));
        }
        return out;
    }

    /** 기호 꼴 맞추기 — 대문자 영숫자만(표지 {@code BFA} = 시세 {@code BF.A}). */
    public static String nk(String s) {
        return s == null ? "" : NON_ALNUM.matcher(s.toUpperCase()).replaceAll("");
    }

    /** 제목·구성원 이름의 낱말 집합: 액면가 문구와 일반어를 뺀다(「Voting Common Shares」 = {@code VotingCommonStockMember}). */
    static Set<String> tokens(String x) {
        String s = x.replaceAll(",.*$", "").replaceAll("([a-z])([A-Z])", "$1 $2");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("[A-Za-z]+").matcher(s);
        while (m.find()) {
            String w = m.group().toLowerCase();
            if (!TOKEN_STOP.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }
}
