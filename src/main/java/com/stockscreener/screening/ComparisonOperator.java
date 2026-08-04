package com.stockscreener.screening;

import java.math.BigDecimal;

/**
 * 조건의 비교 연산자.
 *
 * <p>닫힌 소형 집합이다. 번역기와 판정기는 <b>여기에만</b> 분기하고 지표 코드에는 분기하지 않는다.
 * 지표는 계속 추가되지만 연산자는 프로젝트 수명 동안 몇 번 바뀌지 않으므로,
 * 분기의 축을 연산자로 두면 지표 추가가 코드 수정을 부르지 않는다
 * ({@code .claude/rules/backend.md}가 금지한 하드코딩 분기를 구조적으로 없앤다).
 *
 * <p>{@code indicator}와 무관하게 순수한 수치 비교만 정의한다.
 */
public enum ComparisonOperator {

    GT("초과") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal bound, BigDecimal upperBound) {
            return actual.compareTo(bound) > 0;
        }
    },
    GTE("이상") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal bound, BigDecimal upperBound) {
            return actual.compareTo(bound) >= 0;
        }
    },
    LT("미만") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal bound, BigDecimal upperBound) {
            return actual.compareTo(bound) < 0;
        }
    },
    LTE("이하") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal bound, BigDecimal upperBound) {
            return actual.compareTo(bound) <= 0;
        }
    },
    EQ("같음") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal bound, BigDecimal upperBound) {
            return actual.compareTo(bound) == 0;
        }
    },
    NEQ("다름") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal bound, BigDecimal upperBound) {
            return actual.compareTo(bound) != 0;
        }
    },
    BETWEEN("사이") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal bound, BigDecimal upperBound) {
            if (upperBound == null) {
                throw new IllegalArgumentException("BETWEEN에는 상한이 필요하다");
            }
            return actual.compareTo(bound) >= 0 && actual.compareTo(upperBound) <= 0;
        }
    };

    private final String displayName;

    ComparisonOperator(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean requiresUpperBound() {
        return this == BETWEEN;
    }

    /**
     * 값이 조건을 충족하는지 판정한다.
     *
     * <p>IMPORTANT — 이것이 조건의 판정 의미를 정의하는 <b>유일한</b> 곳이다.
     * SQL 번역, 화면에 보여주는 선정 이유, 워치리스트 알림 판정이 모두 이 의미를 따라야 하며,
     * 각자 따로 구현하면 시간이 지나며 어긋난다.
     */
    public abstract boolean test(BigDecimal actual, BigDecimal bound, BigDecimal upperBound);
}
