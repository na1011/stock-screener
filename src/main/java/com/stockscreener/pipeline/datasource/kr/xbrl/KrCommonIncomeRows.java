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

    /** 붙일 값 — {@link Judged}와 같은 뜻에 붙일 때의 행 스냅숏을 더한다. */
    public record Resolved(Row row, String branch, BigDecimal common, char basis, char statement, BigDecimal payoutBase) {

        public BigDecimal incomeSnapshot() {
            return row.income().snapshot();
        }
    }

    /**
     * @param registers 사업연도 → 명부(없는 해는 빠진다)
     */
    public static List<Resolved> resolve(List<Row> rows, Map<Integer, Register> registers, Multiple multiple) {
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
            Judged j = KrCommonIncome.judge(row.period(), row.income(), row.facts(),
                    registers.get(row.fiscalYear() - 1), registers.get(row.fiscalYear()), multiple);
            if (j.wantsCumulative() && ("Q2".equals(row.fiscalPeriod()) || "Q3".equals(row.fiscalPeriod()))) {
                out.put(row.id(), cumulative(row, j, ordered, out));
                continue;
            }
            out.put(row.id(), new Resolved(row, j.branch(), j.common(), j.basis(), j.statement(), j.payoutBase()));
        }
        for (Row row : ordered) {
            if ("Q4".equals(row.fiscalPeriod())) {
                out.put(row.id(), fourth(row, ordered, out));
            }
        }
        List<Resolved> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(out.get(row.id()));
        }
        return result;
    }

    private static Resolved cumulative(Row row, Judged j, List<Row> ordered, Map<Long, Resolved> out) {
        Row first = vintage(ordered, row.fiscalYear(), "Q1", row.published());
        if (first == null) {
            return new Resolved(row, "none:cum", null, 'T', j.statement(), null);
        }
        BigDecimal cum = KrCommonIncome.cumulative(new Period(first.start(), row.end()), row.period(), row.income(), row.facts());
        if (cum == null) {
            return new Resolved(row, j.branch(), null, 'T', j.statement(), null);
        }
        BigDecimal earlier = BigDecimal.ZERO;
        List<String> before = "Q2".equals(row.fiscalPeriod()) ? List.of("Q1") : List.of("Q1", "Q2");
        for (String period : before) {
            Row prev = vintage(ordered, row.fiscalYear(), period, row.published());
            Resolved r = prev == null ? null : out.get(prev.id());
            if (r == null || r.common() == null || r.basis() != 'T' || r.statement() != j.statement()) {
                return new Resolved(row, "none:cum", null, 'T', j.statement(), null);
            }
            earlier = earlier.add(r.common());
        }
        return new Resolved(row, "cum", cum.subtract(earlier), 'T', j.statement(), null);
    }

    private static Resolved fourth(Row q4, List<Row> ordered, Map<Long, Resolved> out) {
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
                return new Resolved(q4, "none:q4", null, 'T', annual.statement(), null);
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
