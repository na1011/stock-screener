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
 * <p><b>{@code scheme}은 선택이 아니다.</b> 어느 컬럼에 담기는지를 그 값이 정하고(ADR-0092),
 * 어댑터가 안 고르면 컴파일이 안 된다. 왜 유도하지 않는지는 {@link IndustryScheme}에 있다.
 *
 * @param industryCode     업종코드. KSIC는 <b>3~5자리</b>로 섞여 오고 SIC는 <b>항상 4자리</b>다
 * @param scheme           그 코드의 분류 체계. 코드가 있으면 반드시 있다
 * @param accountingMonth  결산월(1~12). 모르면 null
 * @param corpClass        법인구분 원문. 한국은 {@code Y}(유가) {@code K}(코스닥) 등
 */
public record CompanyProfile(
        String industryCode,
        IndustryScheme scheme,
        Integer accountingMonth,
        String corpClass
) {

    public CompanyProfile {
        if (accountingMonth != null && (accountingMonth < 1 || accountingMonth > 12)) {
            throw new IllegalArgumentException("결산월이 1~12가 아니다: " + accountingMonth);
        }
        // 코드가 있는데 체계가 없으면 저장 계층이 컬럼을 고를 수 없다. 여기서 막지 않으면
        // 컬렉터가 조용히 한쪽을 고르게 되고, 그것이 이 필드를 만든 이유를 없앤다.
        if (industryCode != null && !industryCode.isBlank() && scheme == null) {
            throw new IllegalArgumentException(
                    "업종코드가 있는데 분류 체계가 없다: " + industryCode
                            + " — 어느 컬럼에 담을지 정할 수 없다(ADR-0092)");
        }
    }
}
