package com.stockscreener.indicator;

/**
 * "이 지표의 유니버스 내 백분위도 함께 저장한다"는 선언.
 *
 * <p>백분위는 계산기가 될 수 없다. {@link IndicatorCalculator}는 한 종목의 원자료만 보는데
 * 백분위는 같은 날 <b>모든 종목</b>의 값을 봐야 정해지기 때문이다. 그래서 별도 계층
 * ({@code PercentileCalculator})이 개별 지표 계산이 모두 끝난 뒤 한 번에 산출한다.
 *
 * <p>존재 이유는 spec이 요구하는 상대 조건("PER 하위 20%")이다. 조회 시점에 윈도 함수로
 * 순위를 매기면 스크리닝이 매 요청 전 종목을 정렬해야 한다. 배치에서 미리 계산해 평범한 행으로
 * 저장하면 상대 조건이 <b>스칼라 비교</b>로 내려가, 조건 번역기가 이것을 특별하게 다루지
 * 않아도 된다(ADR-0003의 "번역기는 지표를 모른다"). 읽기가 쓰기보다 압도적으로 많은
 * 워크로드라 이 교환이 성립한다.
 *
 * @param sourceCode 백분위를 낼 원본 지표 코드
 * @param name       화면에 보일 이름
 * @param description 이 백분위가 무엇인지
 * @param interpretation 어느 방향이 유리한지
 */
public record PercentileSpec(
        String sourceCode,
        String name,
        String description,
        String interpretation
) {

    /** 백분위 지표 코드의 접미사. 원본 코드에 붙여 만든다. */
    public static final String SUFFIX = "_PCTL";

    public PercentileSpec {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("sourceCode가 비어 있다.");
        }
    }

    /** 저장될 지표 코드. 예: {@code PER} → {@code PER_PCTL}. */
    public String code() {
        return sourceCode + SUFFIX;
    }
}
