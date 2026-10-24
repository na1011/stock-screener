package com.stockscreener.pipeline.datasource.us.shareclass;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 재무 행마다 보통주 몫을 붙인다 — 키 값({@link CommonIncome})을 행의 판에 맞춰 고른다(탐색 {@code PERSHARE-NUM-PLAN-1024.md} v2.1).
 *
 * <ul>
 *   <li><b>행 → 키</b>: 행의 {@code report_code} 접수번호 · 행 기간의 키. 그 공시가 그 기간 손익을 안 실었으면(잔액만 다시 실은 판 — ACA
 *       2025 FY 행 = 2026 Q1 10-Q) 같은 기간 키 중 제출일 ≤ 행 공시일인 최신 공시의 키.</li>
 *   <li><b>Q4 행</b>: 그 10-K에 Q4 3개월 키가 있으면 그 키, 없으면 FY 행 몫 − 어댑터가 쓴 세 분기 판(제출일 ≤ FY 공시일인 최신 판 —
 *       {@code SecEdgarFacts.buildQ4}의 {@code vintageOf})의 몫. 몫은 {@code ni}면 그 행의 지배 NI다.</li>
 * </ul>
 */
public final class CommonIncomeRows {

    /** 재무 행 하나(판 하나). */
    public record Row(int fiscalYear, String fiscalPeriod, int revision, LocalDate start, LocalDate end, LocalDate published,
                      String reportCode, BigDecimal netIncomeControlling) {
    }

    /**
     * @param common 보통주 몫({@code ni}면 null — 읽는 쪽이 행 지배 NI) · {@code none}이면 null
     */
    public record Resolved(Row row, BigDecimal common, CommonIncome.Basis basis, String branch) {
    }

    private static final int SAME_DAYS = 3;

    private CommonIncomeRows() {
    }

    public static List<Resolved> resolve(List<Row> rows, Map<IssuerFacts.Key, CommonIncome.Value> keys) {
        List<Resolved> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            out.add("Q4".equals(r.fiscalPeriod()) ? fourth(r, rows, keys) : own(r, rows, keys));
        }
        return out;
    }

    private static Resolved own(Row r, List<Row> rows, Map<IssuerFacts.Key, CommonIncome.Value> keys) {
        CommonIncome.Value v = keyFor(r, keys);
        if (v == null) {
            return new Resolved(r, null, CommonIncome.Basis.T, "nokey");
        }
        if (!v.decided()) {
            // 자기 공시가 그 기간을 실었지만 못 정했다(10-K가 다시 실은 분기 표 — 가중평균이 없다 · HON · KMB 2025 Q3) — 같은 기간을
            // 정한 앞 공시의 배분을 옮긴다.
            Map.Entry<IssuerFacts.Key, CommonIncome.Value> earlier = earlierDecided(r, keys);
            return earlier == null ? new Resolved(r, null, CommonIncome.Basis.T, v.branch())
                    : transferred(r, earlier.getValue(), incomeOf(earlier.getKey(), rows));
        }
        return resolvedOwn(r, v);
    }

    private static Resolved resolvedOwn(Row r, CommonIncome.Value v) {
        boolean witnessOnly = "epsw".equals(v.branch());
        // 계속영업 EPS에서 지은 값(C)도 — 행 지배 NI(중단 포함)가 그 반올림 안이면 중단 · 배분 둘 다 0이다(VRSN FY2025).
        if (("eps".equals(v.branch()) || "cls".equals(v.branch()) || witnessOnly) && r.netIncomeControlling() != null
                && Math.abs(r.netIncomeControlling().doubleValue() - v.common()) <= v.tolerance()) {
            // 원장 주당이익이 행 지배 NI를 반올림 안에서 확인한다 — 배분 0(키에 NI가 없는 공시 · A FY2015 10-K).
            return new Resolved(r, null, CommonIncome.Basis.T, "ni");
        }
        if (witnessOnly) {
            return new Resolved(r, null, CommonIncome.Basis.T, "epsw-open");
        }
        return new Resolved(r, v.common() == null ? null : BigDecimal.valueOf(v.common()), v.basis(), v.branch());
    }

    /** 다른 판의 키 값을 이 행에 — 배분 0은 그대로, 값은 배분(그 키 NI − 보통주 몫)을 행 지배 NI에서 뺀다. */
    /** 그 키의 지배 NI — 키에 없으면 그 공시가 실은 같은 기간 재무 행의 지배 NI(어댑터가 지은 값 · FTV · GPC 2020 Q4). */
    private static Double incomeOf(IssuerFacts.Key k, List<Row> rows) {
        for (Row x : rows) {
            if (accessionOf(x.reportCode()).equals(k.accession()) && samePeriod(k.period(), x.start(), x.end())
                    && x.netIncomeControlling() != null) {
                return x.netIncomeControlling().doubleValue();
            }
        }
        return null;
    }

    private static Resolved transferred(Row r, CommonIncome.Value v, Double rowIncome) {
        if (v.sameAsControlling()) {
            return new Resolved(r, null, CommonIncome.Basis.T, "ni");
        }
        if ("epsw".equals(v.branch()) || "eps".equals(v.branch()) || "cls".equals(v.branch())) {
            Resolved closed = resolvedOwn(r, v);
            if ("ni".equals(closed.branch())) {
                return closed;
            }
            if ("epsw".equals(v.branch())) {
                return new Resolved(r, null, CommonIncome.Basis.T, "xfer-none");
            }
        }
        Double income = v.income() != null ? v.income() : rowIncome;
        if (v.basis() != CommonIncome.Basis.T || v.common() == null || income == null || r.netIncomeControlling() == null) {
            return new Resolved(r, null, CommonIncome.Basis.T, "xfer-none");
        }
        BigDecimal allocation = BigDecimal.valueOf(income - v.common());
        return new Resolved(r, r.netIncomeControlling().subtract(allocation), CommonIncome.Basis.T, "xfer");
    }

    /** 같은 기간을 정한 다른 공시 중 제출일 ≤ 행 공시일의 최신. */
    private static Map.Entry<IssuerFacts.Key, CommonIncome.Value> earlierDecided(Row r,
                                                                                Map<IssuerFacts.Key, CommonIncome.Value> keys) {
        String accession = accessionOf(r.reportCode());
        Map.Entry<IssuerFacts.Key, CommonIncome.Value> latest = null;
        for (Map.Entry<IssuerFacts.Key, CommonIncome.Value> e : keys.entrySet()) {
            IssuerFacts.Key k = e.getKey();
            CommonIncome.Value v = e.getValue();
            if (k.accession().equals(accession) || !v.decided() || !samePeriod(k.period(), r.start(), r.end())) {
                continue;
            }
            if (r.published() != null && v.filed().isAfter(r.published())) {
                continue;
            }
            if (latest == null || v.filed().isAfter(latest.getValue().filed())) {
                latest = e;
            }
        }
        return latest;
    }

    /** 행의 키 — 자기 공시 · 행 기간, 없으면 같은 기간을 실은 공시 중 제출일 ≤ 행 공시일의 최신. */
    static CommonIncome.Value keyFor(Row r, Map<IssuerFacts.Key, CommonIncome.Value> keys) {
        String accession = accessionOf(r.reportCode());
        CommonIncome.Value own = null;
        CommonIncome.Value latest = null;
        for (Map.Entry<IssuerFacts.Key, CommonIncome.Value> e : keys.entrySet()) {
            IssuerFacts.Key k = e.getKey();
            if (!samePeriod(k.period(), r.start(), r.end())) {
                continue;
            }
            if (k.accession().equals(accession)) {
                // 같은 공시가 한 기간을 시작일만 다른 키 둘로 실을 수 있다(LSCC 2025 Q3 — 06-29 · 06-30) — 정한 쪽이 이긴다.
                if (own == null || !own.decided() && e.getValue().decided()) {
                    own = e.getValue();
                }
                continue;
            }
            LocalDate filed = e.getValue().filed();
            if (r.published() != null && filed.isAfter(r.published())) {
                continue;
            }
            if (latest == null || filed.isAfter(latest.filed())) {
                latest = e.getValue();
            }
        }
        return own != null ? own : latest;
    }

    private static Resolved fourth(Row q4, List<Row> rows, Map<IssuerFacts.Key, CommonIncome.Value> keys) {
        CommonIncome.Value source = null;
        String accession = accessionOf(q4.reportCode());
        for (Map.Entry<IssuerFacts.Key, CommonIncome.Value> e : keys.entrySet()) {
            if (e.getKey().accession().equals(accession) && samePeriod(e.getKey().period(), q4.start(), q4.end())) {
                source = e.getValue();
            }
        }
        if (source != null && source.decided()) {
            return resolvedOwn(q4, source);
        }
        Row fy = rows.stream().filter(x -> x.fiscalYear() == q4.fiscalYear() && "FY".equals(x.fiscalPeriod())
                        && x.reportCode() != null && x.reportCode().equals(q4.reportCode()))
                .max(Comparator.comparing(Row::revision)).orElse(null);
        if (fy == null) {
            return new Resolved(q4, null, CommonIncome.Basis.T, "q4-nofy");
        }
        Resolved fyCommon = own(fy, rows, keys);
        BigDecimal total = amount(fyCommon);
        boolean allSame = fyCommon.branch().equals("ni");
        if (total == null) {
            return new Resolved(q4, null, CommonIncome.Basis.T, "q4-" + fyCommon.branch());
        }
        for (String p : List.of("Q1", "Q2", "Q3")) {
            Row q = vintageOf(rows, q4.fiscalYear(), p, fy.published());
            if (q == null) {
                // 세 분기 판이 없다(상장 첫해 · INGM · RKT) — FY 배분이 0이면 분기 배분도 0이다(우선주 배당 · 참가증권 배분은 이익이 양수인
                // 해에 음수가 아니다 — 합이 0이면 각각 0). 그 밖은 못 정한다.
                return "ni".equals(fyCommon.branch()) ? new Resolved(q4, null, CommonIncome.Basis.T, "ni")
                        : new Resolved(q4, null, CommonIncome.Basis.T, "q4-no" + p);
            }
            Resolved qc = own(q, rows, keys);
            BigDecimal a = amount(qc);
            if (a == null) {
                return new Resolved(q4, null, CommonIncome.Basis.T, "q4-" + qc.branch());
            }
            allSame &= qc.branch().equals("ni");
            total = total.subtract(a, MathContext.DECIMAL64);
        }
        return allSame ? new Resolved(q4, null, CommonIncome.Basis.T, "ni")
                : new Resolved(q4, total, CommonIncome.Basis.T, "q4");
    }

    /** 그 몫의 금액 — {@code ni}면 행 지배 NI. 계속영업 기저(C)는 Q4 차감에 못 섞는다(중단 갈래가 다르다). */
    private static BigDecimal amount(Resolved r) {
        if (r.basis() != CommonIncome.Basis.T) {
            return null;
        }
        if ("ni".equals(r.branch())) {
            return r.row().netIncomeControlling();
        }
        return r.common();
    }

    /** 공시일 ≤ {@code asOf}인 그 기간의 최신 판(어댑터의 {@code vintageOf}). */
    private static Row vintageOf(List<Row> rows, int year, String period, LocalDate asOf) {
        Row best = null;
        for (Row x : rows) {
            if (x.fiscalYear() != year || !period.equals(x.fiscalPeriod())) {
                continue;
            }
            if (asOf != null && x.published() != null && x.published().isAfter(asOf)) {
                continue;
            }
            if (best == null || x.published().isAfter(best.published())
                    || x.published().equals(best.published()) && x.revision() > best.revision()) {
                best = x;
            }
        }
        return best;
    }

    private static boolean samePeriod(ClassFacts.Period p, LocalDate start, LocalDate end) {
        return start != null && end != null && Math.abs(ChronoUnit.DAYS.between(p.start(), start)) <= SAME_DAYS
                && Math.abs(ChronoUnit.DAYS.between(p.end(), end)) <= SAME_DAYS;
    }

    private static String accessionOf(String reportCode) {
        int colon = reportCode == null ? -1 : reportCode.indexOf(':');
        return colon < 0 ? "" : reportCode.substring(colon + 1);
    }
}
