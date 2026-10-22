package com.stockscreener.pipeline.datasource.us;

import tools.jackson.databind.node.ObjectNode;

/**
 * companyfacts 트리에 빠진 공시를 보충하는 자리(ADR-0173). 운영 구현은 {@link SecFilingSupplement} 하나다.
 *
 * <p>인터페이스로 둔 이유는 어댑터 시험이다 — companyfacts 응답만 꾸며 기간 재구성을 보는 시험이 공시 목록·색인·인스턴스
 * 응답까지 꾸미게 되면 그 시험이 묻는 것이 흐려진다. 보충의 계약은 {@code SecFilingSupplementTest}가 따로 묻는다.
 */
@FunctionalInterface
interface FilingSupplementer {

    /** 보충하지 않는다 — companyfacts만 쓰는 시험용. */
    FilingSupplementer NONE = (cik, facts, fromFiscalYear, context) -> SecFilingSupplement.Outcome.none();

    SecFilingSupplement.Outcome supplement(long cik, ObjectNode facts, int fromFiscalYear, String context);

    /** 관문 설정을 잇는 자리(ADR-0182) — 켜지면 IFRS로 실린 공시도 「덮였다」다. 기본은 꺼짐과 같다. */
    default SecFilingSupplement.Outcome supplement(long cik, ObjectNode facts, int fromFiscalYear, String context,
                                                   boolean usGate) {
        return supplement(cik, facts, fromFiscalYear, context);
    }
}
