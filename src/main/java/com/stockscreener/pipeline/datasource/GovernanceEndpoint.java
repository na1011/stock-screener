package com.stockscreener.pipeline.datasource;

/**
 * 정기보고서 주요정보에서 받는 표 하나. <b>도메인 이름이고 벤더 경로가 아니다</b>(ADR-0081).
 *
 * <p>DART의 실제 경로(`alotMatter` 등)는 {@code datasource/kr}의 어댑터 안에만 있다.
 * ADR-0006이 {@code collector}·{@code batch} 패키지의 코드에 벤더 이름이 등장하는 것을
 * MUST NOT으로 걸고, 이 상수는 잡의 인자·CHECK 제약·로그에 그대로 나가는 값이다.
 *
 * <p>여덟에 포트를 여덟 만들지 않는 이유는 <b>요청 인자가 같기 때문</b>이다
 * ({@code corp_code}·{@code bsns_year}·{@code reprt_code}). 엔드포인트는 타입이 아니라 인자다.
 *
 * <p>MUST NOT: 상수 이름을 바꾸지 않는다. {@code governance_report_row.endpoint}의 CHECK와
 * 값이 일치해야 하고(V33), 이름을 바꾸면 이미 적재된 행이 조회에서 통째로 빠진다.
 */
public enum GovernanceEndpoint {

    /** 주식의 총수 현황 — 발행주식총수·자기주식수·이익소각. 요청한 해 하나만 온다. */
    SHARE_TOTALS,

    /**
     * 배당에 관한 사항 — 주당 현금배당금·현금배당금총액.
     *
     * <p><b>3개년이 열로 온다</b>(당기·전기·전전기). 여덟 중 이것 하나뿐이다(§16-C).
     */
    DIVIDEND,

    /**
     * 이사·감사 전체의 보수(지급금액).
     *
     * <p>개발가이드가 {@code fscl_year}로 3개년을 준다고 적었으나 <b>실측은 1행이고 그 값이
     * {@code -}였다</b>(§16-C). 요청한 해 하나만 온다.
     */
    EXEC_PAY,

    /** 독립(사외)이사 및 그 변동현황. 회사당 1행이다(§16-C). */
    OUTSIDE_DIRECTOR,

    /**
     * 최대주주 현황.
     *
     * <p>주주별 행 뒤에 <b>{@code nm}이 {@code 계}인 합계 행</b>이 주식종류마다 붙는다(§16-C).
     */
    LARGEST_HOLDER,

    /** 소액주주 현황. 회사당 1행이고 <b>분자·분모가 한 행에 함께 온다</b>(§16-C). */
    MINORITY_HOLDER,

    /**
     * 최대주주 변동현황.
     *
     * <p><b>요청 연도까지의 누적 이력</b>이다(§16-C) — 최신 해 1콜이면 여러 해가 오고 창은
     * 우리가 자른다. 사건이 없는 회사는 {@code 013}이 아니라 <b>전 필드가 {@code -}인 1행</b>을
     * 받는다(표본 여덟 중 넷).
     */
    CONTROL_CHANGE,

    /**
     * 회계감사인의 명칭 및 감사의견.
     *
     * <p>1콜에 3개년이 오고 <b>행이 정확히 2배</b>다(연결·별도로 보이나 필드로 구분되지 않는다).
     */
    AUDIT_OPINION
}
