package com.stockscreener.indicator;

import java.math.BigDecimal;

/**
 * {@code indicator} 카탈로그 한 행. 화면이 지표를 이해하는 데 필요한 전부다.
 *
 * <p>정본은 계산 클래스의 {@link IndicatorMeta}이고 이 record는 테이블에 투영된 사본을 읽은
 * 것이다. 화면이 애너테이션을 직접 읽지 않는 이유는 백분위 지표에는 계산기가 없기 때문이다 —
 * 테이블이 둘을 같은 모양으로 합쳐준다.
 *
 * @param higherIsBetter        null이면 방향이 정해지지 않은 지표(RSI처럼 중간이 좋은 것)
 * @param defaultColumn         결과 표에 기본 노출할 컬럼인가
 * @param sourceIndicatorCode   백분위 지표의 원본. 백분위가 아니면 null.
 */
public record IndicatorDescriptor(
        String code,
        String name,
        String description,
        String interpretation,
        IndicatorCategory category,
        IndicatorValueType valueType,
        String unit,
        int decimalScale,
        Boolean higherIsBetter,
        boolean defaultColumn,
        int sortOrder,
        String sourceIndicatorCode
) {

    /** 이 지표가 다른 지표의 백분위인가. */
    public boolean isPercentile() {
        return sourceIndicatorCode != null;
    }

    /**
     * 백분위에서 <b>유리한 쪽 끝</b>이 어디인가.
     *
     * <p>IMPORTANT — 백분위 값은 예외 없이 오름차순이지만, "상위 20%"의 부등호는 원본 지표의
     * 유불리에 따라 뒤집힌다. 이 판단을 화면이 하려면 지표 코드를 알아야 하고, 그것은 프론트로
     * 옮겨간 하드코딩 분기다. 여기서 계산하는 것은 지표 코드에 대한 분기가 아니라 원본의
     * {@code higher_is_better}에 대한 순수 함수다.
     *
     * @param source 이 백분위의 원본 지표
     */
    public static FavorableEnd favorableEndOf(IndicatorDescriptor source) {
        if (source == null || source.higherIsBetter() == null) {
            return FavorableEnd.UNKNOWN;
        }
        return source.higherIsBetter() ? FavorableEnd.HIGH : FavorableEnd.LOW;
    }

    /** 백분위에서 어느 쪽 끝이 유리한가. */
    public enum FavorableEnd {

        /** 값이 클수록 유리하다. "상위 20%"는 {@code >= 0.8}. */
        HIGH(ComparisonOperatorHint.GTE, new BigDecimal("0.8")),

        /** 값이 작을수록 유리하다. "상위 20%"는 {@code <= 0.2}. */
        LOW(ComparisonOperatorHint.LTE, new BigDecimal("0.2")),

        /** 원본 지표에 유불리 방향이 없다. 화면은 "상위/하위"를 제시하지 않는다. */
        UNKNOWN(null, null);

        private final ComparisonOperatorHint operator;
        private final BigDecimal threshold;

        FavorableEnd(ComparisonOperatorHint operator, BigDecimal threshold) {
            this.operator = operator;
            this.threshold = threshold;
        }

        /** "상위 20%"를 표현하는 연산자. 방향을 모르면 null. */
        public ComparisonOperatorHint topQuintileOperator() {
            return operator;
        }

        /** "상위 20%"의 경계값. 방향을 모르면 null. */
        public BigDecimal topQuintileThreshold() {
            return threshold;
        }
    }

    /**
     * 힌트에 쓰는 연산자 이름.
     *
     * <p>{@code ComparisonOperator}를 직접 참조하지 않는 이유는 {@code indicator} 패키지가
     * {@code screening}에 의존하지 않게 하기 위해서다. 지표 계층은 조건 모델을 몰라야 한다.
     */
    public enum ComparisonOperatorHint {
        GTE,
        LTE
    }
}
