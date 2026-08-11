package com.stockscreener.pipeline.datasource;

/**
 * 소스가 준 기업 개황. {@code stock}의 업종·결산월 컬럼을 채우는 데 쓴다.
 *
 * <p>{@link SecurityDescriptor}와 나눈 이유는 <b>갱신 주기와 소스가 다르기</b> 때문이다.
 * 저쪽은 유니버스에 새로 편입된 종목의 행을 만들 때 시세 소스에서 함께 얻는 것이고,
 * 이쪽은 거의 바뀌지 않는 속성을 별도 소스(DART)에서 종목당 1콜로 받는다. 합치면 종목 하나를
 * 등록할 때마다 두 벤더를 부르게 되고, 한쪽이 죽으면 종목 등록 자체가 막힌다.
 *
 * <p>{@code industryCode}는 <b>소스가 준 원문 그대로</b>다. 자릿수를 맞추거나 앞자리를 잘라
 * 넘기지 않는다 — 그 파생은 저장 계층의 생성 컬럼이 하며(ADR-0044), 두 곳에서 자르면
 * 어느 쪽이 참인지 알 수 없게 된다.
 *
 * @param industryCode     업종코드. 한국은 KSIC이며 <b>3자리 또는 5자리</b>로 섞여 온다
 * @param accountingMonth  결산월(1~12). 모르면 null
 * @param corpClass        법인구분 원문. 한국은 {@code Y}(유가) {@code K}(코스닥) 등
 */
public record CompanyProfile(
        String industryCode,
        Integer accountingMonth,
        String corpClass
) {

    public CompanyProfile {
        if (accountingMonth != null && (accountingMonth < 1 || accountingMonth > 12)) {
            throw new IllegalArgumentException("결산월이 1~12가 아니다: " + accountingMonth);
        }
    }
}
