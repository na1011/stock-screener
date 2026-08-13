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

    /**
     * 종목 상세의 분류 구획에 기본으로 보일 것인지(ADR-0058).
     *
     * <p>{@code false}로 두어도 <b>폐기가 아니다.</b> 조건 빌더 드롭다운·결과 표·종목 상세의
     * 「내 조건」 구획에는 그대로 나온다. 감추는 것은 <b>분류 구획 하나</b>이며, 사용자가 조건에
     * 넣으면 다시 보인다.
     *
     * <p>{@link #defaultColumn}과 기본값이 반대인 것은 의도다. 대부분의 지표는 보여야 하고
     * 감출 것은 소수라, 기본을 {@code false}로 두면 거의 모든 계산기에 {@code true}를 적게 된다.
     */
    boolean detailDefault() default true;

    int sortOrder() default 0;

    /**
     * 값 구간별 자연어 라벨. 조건 빌더가 "숫자를 모르는 사용자"에게 먼저 제시하는 선택지다.
     *
     * <p>기본값이 빈 배열인 것은 <b>대부분의 지표에 붙이면 안 되기 때문</b>이다 — 근거는
     * {@link IndicatorBand}에 있다. 구간을 제시할 수 없으면 화면은 직접 입력만 보여준다.
     * 없는 것을 지어내는 것보다 없다고 말하는 편이 낫다.
     */
    Band[] bands() default {};

    /**
     * {@link IndicatorMeta#bands()}의 한 칸.
     *
     * <p>애너테이션은 null을 담을 수 없어 무한대를 "열린 쪽"의 표식으로 쓴다. 둘 다 컴파일
     * 상수라 애너테이션 기본값으로 쓸 수 있다. 투영 시 {@link IndicatorBand}의 null로 바뀐다.
     *
     * <p>{@code Target}이 비어 있는 것은 의도적이다 — 이 애너테이션은 멤버 값으로만 쓰이고
     * 클래스에 직접 붙는 일이 없다.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Band {

        /** 구간의 뜻. "매우 높음", "과매수" 등. */
        String label();

        /** 하한(포함). 생략하면 아래로 열린다. */
        double from() default Double.NEGATIVE_INFINITY;

        /** 상한(미포함). 생략하면 위로 열린다. */
        double to() default Double.POSITIVE_INFINITY;
    }

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
