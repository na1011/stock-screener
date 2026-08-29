package com.stockscreener.pipeline.datasource;

/**
 * 업종코드의 분류 체계. <b>어느 컬럼에 담기는지를 이 값이 정한다</b>(ADR-0092).
 *
 * <p>포트가 코드와 함께 체계를 요구하는 이유는, 체계를 <b>어느 컬럼에 썼는가로 유도하면
 * 아무것도 막을 수 없기 때문</b>이다. {@code CompanyProfileCollector}의 UPDATE는 종목당
 * 컬럼 하나만 쓰고 미국 행의 {@code industry_code}는 NULL이라, 잘못 넣어도 "한 종목에 한
 * 체계" 제약은 통과한다. 자릿수로도 못 가른다 — <b>4자리 KSIC가 54종목 실재한다</b>
 * (2026-08-29 실측). 마지막 방어선은 {@code ck_stock_sic_is_us}·{@code ck_stock_ksic_is_not_us}이고,
 * 이 enum은 그 제약에 걸릴 값을 <b>어댑터가 명시적으로 고르게</b> 만든다.
 *
 * <p>두 체계는 <b>앞 2자리가 실제로 겹친다</b> — KSIC {@code 011}(농업)과 SIC {@code 0100}이
 * 둘 다 {@code 01}이고, KSIC {@code 65}는 보험인데 SIC {@code 65}는 부동산이다.
 * 그래서 업종 파티션 키가 이 체계를 값에 굽는다.
 */
public enum IndustryScheme {

    /** 한국표준산업분류. DART 기업개황의 {@code induty_code}이며 3~5자리로 섞여 온다. */
    KSIC,

    /**
     * 미국 SIC. SEC EDGAR {@code submissions}의 {@code sic}이며 <b>항상 4자리</b>다 —
     * 앞자리 0도 소스가 채워서 준다(실측: {@code 0100}·{@code 0200}).
     */
    SIC
}
