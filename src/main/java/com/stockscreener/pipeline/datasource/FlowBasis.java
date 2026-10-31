package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * 한 재무 행(그 공시일의 판)의 <b>손익 칸 기저 판정</b>(국내 관문 켬 · 재고 A6) — 어댑터가 판정한 칸과 그중 기저가 대체된 칸의 대체일.
 * 저장({@code FinancialWriter.applyFlowBases})이 그 행의 {@code flow_superseded}에서 판정한 칸만 다시 짓는다 — 다음 판정이 대체를 안 내면
 * 지운다(회수). 판을 더하지 않고 속성으로 싣는다: 화면 재무 표는 회사가 낸 원값을 그대로 보인다.
 *
 * @param judged     판정한 DB 컬럼명
 * @param superseded 그중 대체된 칸 → 대체일(그날 이후 봉은 그 칸을 모른다)
 */
public record FlowBasis(int fiscalYear, String fiscalPeriod, LocalDate publishedAt, Set<String> judged,
                        Map<String, LocalDate> superseded) {

    public FlowBasis {
        judged = Set.copyOf(judged);
        superseded = Map.copyOf(superseded);
    }
}
