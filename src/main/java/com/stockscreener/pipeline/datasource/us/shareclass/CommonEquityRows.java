package com.stockscreener.pipeline.datasource.us.shareclass;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 재무 행마다 보통주 자본 재료를 붙인다 — (공시, 날짜) 값({@link CommonEquity})을 행의 판에 맞춰 고른다(탐색 {@code PERSHARE-DEN-PLAN-1026.md}
 * v1.1 4절).
 *
 * <ul>
 *   <li>행의 {@code report_code} 공시 · 행 끝 날짜의 값.</li>
 *   <li>그 공시가 그 날짜 대차대조표를 안 실었으면 같은 날짜를 정한 공시 중 제출일 ≤ 행 공시일인 최신 공시의 값. Q4 행은 FY 끝 날짜라 FY와 같다.</li>
 *   <li>값이 없을 때 — 신호 없는 발행사이고 그 공시가 companyfacts에 있으면 {@code none}(뺄 것 0). 그 밖(신호 발행사 · companyfacts가 덮지 않는
 *       보충 공시)은 {@code nokey}(못 정함) — 「신호 없음」은 companyfacts가 그 공시를 덮을 때만 증거다.</li>
 * </ul>
 */
public final class CommonEquityRows {

    public record Resolved(CommonIncomeRows.Row row, Double nonCommon, Double ncavNonCommon, String branch) {
    }

    private CommonEquityRows() {
    }

    /**
     * @param filings              공시 목록(보충 공시 포함 · 제출일)
     * @param companyFactsFilings  companyfacts가 실은 공시 — 「신호 없음」이 증거가 되는 범위
     */
    public static List<Resolved> resolve(List<CommonIncomeRows.Row> rows, Map<CommonEquity.Key, CommonEquity.Value> judged,
                                         Map<String, IssuerFacts.Filing> filings, Set<String> companyFactsFilings, boolean signal) {
        List<Resolved> out = new ArrayList<>(rows.size());
        for (CommonIncomeRows.Row r : rows) {
            CommonEquity.Value v = r.end() == null ? null : judged.get(new CommonEquity.Key(r.reportCode(), r.end()));
            if (v == null && r.end() != null) {
                v = earlier(r, judged, filings);
            }
            if (v != null) {
                out.add(new Resolved(r, v.nonCommon(), v.ncavNonCommon(), v.branch()));
            } else if (!signal && r.reportCode() != null && companyFactsFilings.contains(r.reportCode())) {
                out.add(new Resolved(r, 0.0, 0.0, "none"));
            } else {
                out.add(new Resolved(r, null, null, "nokey"));
            }
        }
        return out;
    }

    private static CommonEquity.Value earlier(CommonIncomeRows.Row r, Map<CommonEquity.Key, CommonEquity.Value> judged,
                                              Map<String, IssuerFacts.Filing> filings) {
        CommonEquity.Value best = null;
        LocalDate bestFiled = null;
        for (Map.Entry<CommonEquity.Key, CommonEquity.Value> e : judged.entrySet()) {
            if (!e.getKey().date().equals(r.end())) {
                continue;
            }
            IssuerFacts.Filing f = filings.get(e.getKey().accession());
            if (f == null || r.published() != null && f.filed().isAfter(r.published())) {
                continue;
            }
            if (bestFiled == null || f.filed().isAfter(bestFiled)) {
                bestFiled = f.filed();
                best = e.getValue();
            }
        }
        return best;
    }
}
