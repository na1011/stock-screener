package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Fact;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Judged;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Multiple;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Period;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Register;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.RowIncome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 종목의 국내 재무 행(판 전부)에 보통주 몫을 붙인다(ADR-0192). 판정은 {@link KrCommonIncome}이고, 여기는 행과 판의 짝 · Q4 · 누계
 * 갈래를 맡는다. 외부 호출이 없는 순수 함수다.
 *
 * <ul>
 *   <li>Q1~Q3 · FY 행은 그 판(접수번호)의 재료로 판정한다 — 재료가 없으면 {@code nokey}(미표시).</li>
 *   <li>Q4 = FY 몫 − Q1~Q3 몫 — 각 행은 {@code published} ≤ Q4 행 {@code published}인 마지막 판이다. 하나라도 못 정하면 미표시 ·
 *       넷의 지배 NI가 FY − ΣQ = Q4로 안 닫히면(다른 판을 섞었다) 미표시.</li>
 *   <li>3개월 W가 없는 Q2 · Q3 판은 누계 몫(사업연도 시작 ~ 행 끝) − 앞 분기 몫들의 합(같은 규칙의 마지막 판).</li>
 * </ul>
 */
public final class KrCommonIncomeRows {

    private KrCommonIncomeRows() {
    }

    /** 재무 행 하나와 그 판의 재료. {@code facts}가 {@code null}이면 그 판의 XBRL 재료가 없다. */
    public record Row(long id, int fiscalYear, String fiscalPeriod, LocalDate start, LocalDate end, LocalDate published,
                      BigDecimal netIncomeControlling, BigDecimal netIncome, List<Fact> facts) {

        RowIncome income() {
            return new RowIncome(netIncomeControlling, netIncome);
        }

        Period period() {
            return new Period(start, end);
        }
    }

    /**
     * 붙일 값 — {@link Judged}와 같은 뜻에 붙일 때의 행 스냅숏 · 보통주 자본 재료({@link KrCommonEquity} · ADR-0199)를 더한다.
     *
     * @param visibleFrom       보통주 몫이 보이기 시작하는 날(국내 관문 켬 · {@link KrCommonIncome.Rule#PIT} · {@link KrCommonIncome.Rule#CMP}) —
     *                          {@code null}이면 행 공시일. 자본 재료는 안 탄다(대차대조표 판정은 따로다)
     * @param payoutVisibleFrom 배당성향 분모가 보이기 시작하는 날 — 분모가 지배 NI 그대로면 행 공시일({@code null})
     */
    public record Resolved(Row row, String branch, BigDecimal common, char basis, char statement, BigDecimal payoutBase,
                           KrCommonEquity.Equity equity, LocalDate visibleFrom, LocalDate payoutVisibleFrom) {

        Resolved(Row row, String branch, BigDecimal common, char basis, char statement, BigDecimal payoutBase) {
            this(row, branch, common, basis, statement, payoutBase, null, null, null);
        }

        public BigDecimal incomeSnapshot() {
            return row.income().snapshot();
        }

        Resolved withEquity(KrCommonEquity.Equity equity) {
            return new Resolved(row, branch, common, basis, statement, payoutBase, equity, visibleFrom, payoutVisibleFrom);
        }

        /** 가시일을 단다 — 행 공시일보다 늦을 때만(같거나 이르면 {@code null} · 행 공시일이 이미 그날이다). */
        Resolved visible(LocalDate common, LocalDate payout) {
            return new Resolved(row, branch, this.common, basis, statement, payoutBase, equity, later(common), later(payout));
        }

        /** 보통주 몫이 보이는 첫날. */
        public LocalDate commonVisible() {
            return visibleFrom == null ? row.published() : visibleFrom;
        }

        private LocalDate later(LocalDate day) {
            return day == null || !day.isAfter(row.published()) ? null : day;
        }
    }

    /** 그 해 사업보고서가 아직 없는 분기 행이 그 해 명부를 쓴 값 — 안 보인다({@link KrCommonIncome.Rule#PIT} · 사업보고서가 오면 다시 유도한다). */
    public static final LocalDate NEVER = LocalDate.of(9999, 12, 31);

    /** {@link KrCommonIncome.Rule#CMP}의 재료 — 행마다 그 판보다 뒤에 접수된 판들(접수번호 순). 없으면 빈 목록. */
    @FunctionalInterface
    public interface Later {
        List<KrCommonIncome.LaterFiling> after(Row row);

        Later NONE = row -> List.of();
    }

    /** 발행사 재료를 행에 묶인 판들로만 짓는다(스윕 · 시험). */
    public static List<Resolved> resolve(List<Row> rows, Map<Integer, Register> registers, Multiple multiple) {
        List<Map.Entry<Integer, List<Fact>>> filings = new ArrayList<>();
        for (Row row : rows) {
            filings.add(new java.util.AbstractMap.SimpleEntry<>(row.fiscalYear(), row.facts()));
        }
        return resolve(rows, registers, multiple, KrCommonEquity.Issuer.of(filings));
    }

    /**
     * @param registers 사업연도 → 명부(없는 해는 빠진다)
     * @param issuer    발행사 판 전부에서 지은 보통주 자본 재료
     */
    public static List<Resolved> resolve(List<Row> rows, Map<Integer, Register> registers, Multiple multiple,
                                         KrCommonEquity.Issuer issuer) {
        return resolve(rows, registers, multiple, issuer, KrCommonIncome.Rules.NONE);
    }

    /** @param rules 엔진 4판 규칙(국내 관문 켬 · {@link KrCommonIncome.Rules#V4}) */
    public static List<Resolved> resolve(List<Row> rows, Map<Integer, Register> registers, Multiple multiple,
                                         KrCommonEquity.Issuer issuer, KrCommonIncome.Rules rules) {
        return resolve(rows, registers, multiple, issuer, rules, Later.NONE);
    }

    /** @param later 행마다 뒤 판들({@link KrCommonIncome.Rule#CMP}) */
    public static List<Resolved> resolve(List<Row> rows, Map<Integer, Register> registers, Multiple multiple,
                                         KrCommonEquity.Issuer issuer, KrCommonIncome.Rules rules, Later later) {
        return resolve(rows, registers, multiple, issuer, rules, later, KrShareTimeline.NONE);
    }

    /** @param timeline 정기보고서 주식수 표({@link KrCommonIncome.Rule#SW}) */
    public static List<Resolved> resolve(List<Row> rows, Map<Integer, Register> registers, Multiple multiple,
                                         KrCommonEquity.Issuer issuer, KrCommonIncome.Rules rules, Later later, KrShareTimeline timeline) {
        Map<Long, Resolved> out = new HashMap<>();
        List<Row> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(Row::fiscalYear).thenComparing(r -> order(r.fiscalPeriod()))
                .thenComparing(Row::published));
        for (Row row : ordered) {
            if ("Q4".equals(row.fiscalPeriod())) {
                continue;
            }
            if (row.facts() == null) {
                out.put(row.id(), new Resolved(row, "nokey", null, 'T', 'N', null));
                continue;
            }
            KrCommonIncome.NcContext nc = ncContext(row, ordered);
            Register prior = registers.get(row.fiscalYear() - 1);
            Register same = registers.get(row.fiscalYear());
            LocalDate visible = row.published();
            boolean quarter = !"FY".equals(row.fiscalPeriod());
            if (rules.has(KrCommonIncome.Rule.PIT) && quarter) {
                // 분기 공시 때 그 해 명부는 아직 없다 — 앞 해 명부가 없을 때만 그 해 명부를 쓰고, 그 해 사업보고서 공시일부터 보인다.
                if (prior != null) {
                    same = null;
                } else if (same != null) {
                    LocalDate annual = firstAnnual(ordered, row.fiscalYear());
                    visible = annual == null ? NEVER : later(visible, annual);
                }
            }
            KrShareTimeline.Window window = rules.has(KrCommonIncome.Rule.SW) ? timeline.window(row, ordered) : null;
            Judged j;
            if (rules.has(KrCommonIncome.Rule.CMP)) {
                // 비교열 W는 그 판 재료로 값이 안 설 때만 — 값이 서던 행을 뒤 판 공개 전 날들까지 바꾸지 않는다.
                j = KrCommonIncome.judge(row.period(), row.income(), row.facts(), prior, same, multiple,
                        rules.without(KrCommonIncome.Rule.CMP), nc, List.of(), window);
                // 누계 갈래(반기 · 3분기 누계 − 앞 분기)로 서는 값도 그 판 재료다 — 그것까지 안 설 때만 뒤 판을 본다.
                // R1+(원장 비보통 몫 후보로 닫힌 값)도 그 판 재료다.
                boolean own = j.common() != null || j.fallback() != null
                        || j.wantsCumulative() && ("Q2".equals(row.fiscalPeriod()) || "Q3".equals(row.fiscalPeriod()))
                        && cumulative(row, j, ordered, out, rules.without(KrCommonIncome.Rule.CMP), nc).common() != null;
                if (!own) {
                    j = KrCommonIncome.judge(row.period(), row.income(), row.facts(), prior, same, multiple, rules, nc, later.after(row), window);
                }
            } else {
                j = KrCommonIncome.judge(row.period(), row.income(), row.facts(), prior, same, multiple, rules, nc, List.of(), window);
            }
            if (j.laterReceipt() != null) {
                visible = later(visible, j.laterReceipt());
            }
            Resolved resolved = null;
            if (j.wantsCumulative() && ("Q2".equals(row.fiscalPeriod()) || "Q3".equals(row.fiscalPeriod()))) {
                Resolved cumulative = cumulative(row, j, ordered, out, rules, nc);
                if (cumulative.common() != null || j.fallback() == null) {
                    resolved = cumulative;
                    // 누계 몫 − 앞 분기 몫 — 앞 분기 몫이 늦게 보이면 이 값도 그날부터다.
                    visible = later(visible, partsVisible(ordered, row, "Q2".equals(row.fiscalPeriod()) ? List.of("Q1") : List.of("Q1", "Q2"), out));
                }
            }
            if (resolved == null) {
                // 누계로 안 선 반기 · 3분기와 1분기 · 연간 — 그 판 판정(값이 없고 R1+가 섰으면 그것).
                j = j.common() == null && j.fallback() != null ? j.fallback() : j;
                resolved = new Resolved(row, j.branch(), j.common(), j.basis(), j.statement(), j.payoutBase());
            }
            out.put(row.id(), resolved.visible(visible, resolved.payoutBase() != null && j.payoutFollowsCommon() ? visible : null));
        }
        for (Row row : ordered) {
            if ("Q4".equals(row.fiscalPeriod())) {
                LocalDate visible = later(row.published(), partsVisible(ordered, row, List.of("FY", "Q1", "Q2", "Q3"), out));
                out.put(row.id(), fourth(row, ordered, out, rules).visible(visible, null));
            }
        }
        // 보통주 자본은 손익과 따로 — 대차대조표 날짜의 판으로 정한다. Q4는 같은 기말의 FY 판(Q4 published 이하 마지막)이다.
        Map<Long, KrCommonEquity.Equity> equity = new HashMap<>();
        for (Row row : ordered) {
            if (!"Q4".equals(row.fiscalPeriod())) {
                equity.put(row.id(), equity(row, issuer, registers, rules, timeline));
            }
        }
        for (Row row : ordered) {
            if ("Q4".equals(row.fiscalPeriod())) {
                Row fy = vintage(ordered, row.fiscalYear(), "FY", row.published());
                equity.put(row.id(), fy == null ? KrCommonEquity.Equity.OPEN : equity.get(fy.id()));
            }
        }
        List<Resolved> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(out.get(row.id()).withEquity(equity.get(row.id())));
        }
        return result;
    }

    /** 보통주 자본 판정 — {@link KrCommonIncome.Rule#QREG}면 첫해 분기 행이 그 해(미래) 명부 대신 그 시점 분기 명부를 쓴다. */
    private static KrCommonEquity.Equity equity(Row row, KrCommonEquity.Issuer issuer, Map<Integer, Register> registers,
                                                KrCommonIncome.Rules rules, KrShareTimeline timeline) {
        Register same = registers.get(row.fiscalYear());
        if (!rules.has(KrCommonIncome.Rule.QREG) || "FY".equals(row.fiscalPeriod()) || registers.get(row.fiscalYear() - 1) != null
                || same == null) {
            return KrCommonEquity.judge(row.end(), row.fiscalYear(), row.facts(), issuer, registers);
        }
        Map<Integer, Register> pointInTime = new HashMap<>(registers);
        pointInTime.remove(row.fiscalYear());
        Register quarter = timeline.quarterRegister(row);
        if (quarter != null) {
            pointInTime.put(row.fiscalYear(), quarter);
        }
        KrCommonEquity.Equity judged = KrCommonEquity.judge(row.end(), row.fiscalYear(), row.facts(), issuer, pointInTime);
        // 그 시점 명부가 없는데 그 해 명부가 보통주 밖 종류를 싣는다 — 그 시점 몫을 모른다(그 판에 우선주 증거가 없어 1이 나와도 받지 않는다).
        return quarter == null && same.multiClass() && judged.fraction() != null ? KrCommonEquity.Equity.OPEN_FRACTION : judged;
    }

    /** NC-a 재료 — Q2 · Q3 행에서 같은 해 1분기 시작과 직전 판(Q2면 Q1 · Q3면 Q2 · 그 행 공시일 이하 마지막 판)의 끝 · 사실. */
    private static KrCommonIncome.NcContext ncContext(Row row, List<Row> ordered) {
        if (!"Q2".equals(row.fiscalPeriod()) && !"Q3".equals(row.fiscalPeriod())) {
            return null;
        }
        Row first = vintage(ordered, row.fiscalYear(), "Q1", row.published());
        Row previous = vintage(ordered, row.fiscalYear(), "Q2".equals(row.fiscalPeriod()) ? "Q1" : "Q2", row.published());
        if (first == null || previous == null || previous.facts() == null) {
            return null;
        }
        return new KrCommonIncome.NcContext(first.start(), previous.end(), previous.facts());
    }

    private static Resolved cumulative(Row row, Judged j, List<Row> ordered, Map<Long, Resolved> out,
                                       KrCommonIncome.Rules rules, KrCommonIncome.NcContext nc) {
        // 분모가 분자와 따로 정해졌으면(PAY · 보통주 몫에 안 기댐) 누계 갈래가 값을 못 내도 그 분모는 그 판 판정 그대로다.
        BigDecimal payout = j.payoutFollowsCommon() ? null : j.payoutBase();
        Row first = vintage(ordered, row.fiscalYear(), "Q1", row.published());
        if (first == null) {
            return new Resolved(row, "none:cum", null, 'T', j.statement(), payout);
        }
        BigDecimal cum = KrCommonIncome.cumulative(new Period(first.start(), row.end()), row.period(), row.income(), row.facts(), rules, nc);
        if (cum == null) {
            return new Resolved(row, j.branch(), null, 'T', j.statement(), payout);
        }
        BigDecimal earlier = BigDecimal.ZERO;
        List<String> before = "Q2".equals(row.fiscalPeriod()) ? List.of("Q1") : List.of("Q1", "Q2");
        for (String period : before) {
            Row prev = vintage(ordered, row.fiscalYear(), period, row.published());
            Resolved r = prev == null ? null : out.get(prev.id());
            if (r == null || r.common() == null || r.basis() != 'T' || r.statement() != j.statement()) {
                return new Resolved(row, "none:cum", null, 'T', j.statement(), payout);
            }
            earlier = earlier.add(r.common());
        }
        return new Resolved(row, "cum", cum.subtract(earlier), 'T', j.statement(), payout);
    }

    private static Resolved fourth(Row q4, List<Row> ordered, Map<Long, Resolved> out, KrCommonIncome.Rules rules) {
        Row fy = vintage(ordered, q4.fiscalYear(), "FY", q4.published());
        List<Row> quarters = new ArrayList<>();
        for (String period : List.of("Q1", "Q2", "Q3")) {
            quarters.add(vintage(ordered, q4.fiscalYear(), period, q4.published()));
        }
        if (fy == null || quarters.contains(null)) {
            return new Resolved(q4, "none:q4", null, 'T', 'N', null);
        }
        if (q4.netIncomeControlling() != null && fy.netIncomeControlling() != null
                && quarters.stream().allMatch(r -> r.netIncomeControlling() != null)) {
            BigDecimal implied = fy.netIncomeControlling();
            for (Row r : quarters) {
                implied = implied.subtract(r.netIncomeControlling());
            }
            if (implied.subtract(q4.netIncomeControlling()).abs().compareTo(BigDecimal.ONE) > 0) {
                return new Resolved(q4, "none:q4-vintage", null, 'T', 'N', null);
            }
        }
        Resolved annual = out.get(fy.id());
        if (annual == null || annual.common() == null) {
            return new Resolved(q4, "none:q4", null, 'T', annual == null ? 'N' : annual.statement(), null);
        }
        BigDecimal common = annual.common();
        boolean allNi = "kr-ni".equals(annual.branch());
        for (Row r : quarters) {
            Resolved q = out.get(r.id());
            if (q == null || q.common() == null || q.basis() != annual.basis() || q.statement() != annual.statement()) {
                return nineMonths(q4, annual, quarters, ordered, rules);
            }
            common = common.subtract(q.common());
            allNi &= "kr-ni".equals(q.branch());
        }
        if (allNi && q4.income().snapshot() != null) {
            // 넷 다 배분 0이면 Q4도 배분 0 — 값은 Q4 행 자신의 지배 NI(위 닫힘 검사가 같은 수임을 확인했다).
            return new Resolved(q4, "kr-ni", q4.income().snapshot(), 'T', annual.statement(), null);
        }
        return new Resolved(q4, "q4", common, annual.basis(), annual.statement(), null);
    }

    /**
     * 분기 하나가 안 정해졌을 때 — 3분기 판이 9개월 누계 EPS × 누계 W를 주면 Q4 = FY 몫 − 9개월 누계 몫이다(국내 어댑터의 Q4 = FY − 3분기
     * 누계와 같은 꼴 · 1분기 판에 가중평균 태그가 없는 금융지주 2025가 그 자리다). 연간 몫이 중단 포함 합(T)이고 3분기 판이 정해졌을 때만.
     */
    private static Resolved nineMonths(Row q4, Resolved annual, List<Row> quarters, List<Row> ordered, KrCommonIncome.Rules rules) {
        Row first = quarters.get(0);
        Row third = quarters.get(2);
        if (annual.basis() != 'T' || third.facts() == null) {
            return new Resolved(q4, "none:q4", null, 'T', annual.statement(), null);
        }
        BigDecimal nine = KrCommonIncome.cumulative(new Period(first.start(), third.end()), third.period(), third.income(), third.facts(),
                rules, ncContext(third, ordered));
        if (nine == null) {
            return new Resolved(q4, "none:q4", null, 'T', annual.statement(), null);
        }
        return new Resolved(q4, "q4-cum", annual.common().subtract(nine), 'T', annual.statement(), null);
    }

    /** 그 사업연도 사업보고서(FY 행)의 첫 공시일 — 그 해 명부가 공개된 날. 없으면 {@code null}. */
    private static LocalDate firstAnnual(List<Row> ordered, int year) {
        LocalDate first = null;
        for (Row r : ordered) {
            if (r.fiscalYear() == year && "FY".equals(r.fiscalPeriod()) && (first == null || r.published().isBefore(first))) {
                first = r.published();
            }
        }
        return first;
    }

    /** 재료 행들(그 행 공시일 이하 마지막 판)의 보통주 몫 가시일 중 가장 늦은 날 — 없으면 {@code null}. */
    private static LocalDate partsVisible(List<Row> ordered, Row row, List<String> periods, Map<Long, Resolved> out) {
        LocalDate latest = null;
        for (String period : periods) {
            Row part = vintage(ordered, row.fiscalYear(), period, row.published());
            Resolved r = part == null ? null : out.get(part.id());
            if (r != null) {
                latest = later(latest, r.commonVisible());
            }
        }
        return latest;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a == null ? b : b == null || !b.isAfter(a) ? a : b;
    }

    /** 그 사업연도 · 기간 행 중 {@code published} ≤ 기준인 마지막 판. */
    private static Row vintage(List<Row> ordered, int year, String period, LocalDate notAfter) {
        Row found = null;
        for (Row r : ordered) {
            if (r.fiscalYear() == year && period.equals(r.fiscalPeriod()) && !r.published().isAfter(notAfter)) {
                found = r;
            }
        }
        return found;
    }

    private static int order(String period) {
        return switch (period) {
            case "Q1" -> 1;
            case "Q2" -> 2;
            case "Q3" -> 3;
            case "Q4" -> 4;
            default -> 5;
        };
    }
}
