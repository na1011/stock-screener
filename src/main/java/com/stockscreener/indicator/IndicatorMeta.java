package com.stockscreener.indicator;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 지표의 표시용 메타데이터. 계산 클래스에 붙인다.
 *
 * <p>계산 코드 바로 옆에 두는 이유는 빠뜨릴 수 없게 하기 위해서다. 별도 목록으로 관리하면
 * 지표를 추가할 때 두 곳을 고쳐야 하고, 그 순간 확장 비용이 1을 넘는다.
 *
 * <p>기동 시 {@code IndicatorCatalogSynchronizer}가 이 값을 {@code indicator} 테이블로 투영하므로,
 * API와 조건 빌더 UI는 평범한 쿼리로 읽는다.
 *
 * <p>{@code description}과 {@code interpretation}은 spec §5와 frontend.md가 요구한다 —
 * 초보 사용자는 값만 보고 그것이 좋은 것인지 알 수 없다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IndicatorMeta {

    /** 화면에 보이는 이름. */
    String name();

    /** 이 지표가 무엇을 뜻하는지. */
    String description();

    /** 어느 방향이 유리한 신호인지. description과 분리한 이유는 화면에서 다르게 렌더링하기 때문이다. */
    String interpretation() default "";

    IndicatorCategory category();

    IndicatorValueType valueType();

    /** 표시 단위. "%", "배", "원" 등. */
    String unit() default "";

    /** 화면 표시 시 반올림할 소수 자릿수. 저장은 항상 전체 정밀도로 한다. */
    int decimalScale() default 2;

    /**
     * 값이 클수록 유리한 지표인가.
     *
     * <p>{@link Direction#NEUTRAL}은 방향이 정해지지 않은 지표다(RSI처럼 중간이 좋은 것).
     * 점수 계산에서 백분위의 어느 쪽이 좋은 것인지 판단하는 근거가 된다.
     */
    Direction direction() default Direction.NEUTRAL;

    /** 결과 표에 기본 노출할 컬럼인지. */
    boolean defaultColumn() default false;

    int sortOrder() default 0;

    enum Direction {
        HIGHER_IS_BETTER,
        LOWER_IS_BETTER,
        NEUTRAL;

        /** {@code indicator.higher_is_better} 컬럼에 저장할 값. NEUTRAL은 null이다. */
        public Boolean toHigherIsBetter() {
            return switch (this) {
                case HIGHER_IS_BETTER -> Boolean.TRUE;
                case LOWER_IS_BETTER -> Boolean.FALSE;
                case NEUTRAL -> null;
            };
        }
    }
}
