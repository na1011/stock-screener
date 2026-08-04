package com.stockscreener.indicator;

import java.util.Set;

/**
 * 지표 계산에 필요한 원자료와 의존 지표의 선언.
 *
 * @param priceBarsNeeded     필요한 시세 봉 개수(거래일 기준). 시세가 필요 없으면 0.
 * @param financialYearsNeeded 필요한 재무제표 연수. 재무가 필요 없으면 0.
 * @param dependsOn           이 지표가 소비하는 <b>다른 지표</b>의 코드.
 *
 * <p>{@code dependsOn}이 핵심이다. spec이 예로 든 MACD-V는 {@code (MACD / ATR) × 100}인데,
 * 이를 선언하면 러너가 위상 정렬해 이미 계산된 시계열을 넘겨준다. 이 필드가 없으면 파생 지표마다
 * 부모의 계산을 다시 구현하게 되고, 그 순간부터 두 구현이 어긋나기 시작한다.
 *
 * <p>워밍업 기간은 손으로 적지 않는다. 러너가 의존 그래프를 따라
 * {@code 유효 워밍업(X) = X.priceBarsNeeded + max(의존 지표들의 유효 워밍업)}으로 유도한다.
 * 개발자는 자기 몫의 증분만 선언한다.
 */
public record DataRequirement(
        int priceBarsNeeded,
        int financialYearsNeeded,
        Set<String> dependsOn
) {

    public DataRequirement {
        if (priceBarsNeeded < 0) {
            throw new IllegalArgumentException("priceBarsNeeded는 음수일 수 없다: " + priceBarsNeeded);
        }
        if (financialYearsNeeded < 0) {
            throw new IllegalArgumentException("financialYearsNeeded는 음수일 수 없다: " + financialYearsNeeded);
        }
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
    }

    /** 시세만 필요한 지표. */
    public static DataRequirement priceBars(int bars) {
        return new DataRequirement(bars, 0, Set.of());
    }

    /** 재무제표만 필요한 지표. */
    public static DataRequirement financialYears(int years) {
        return new DataRequirement(0, years, Set.of());
    }

    /** 시세와 재무제표를 모두 쓰는 지표(PER처럼 가격 × 재무). */
    public static DataRequirement priceAndFinancial(int bars, int years) {
        return new DataRequirement(bars, years, Set.of());
    }

    /** 다른 지표에서 파생되는 지표. */
    public static DataRequirement derivedFrom(int additionalBars, String... indicatorCodes) {
        return new DataRequirement(additionalBars, 0, Set.of(indicatorCodes));
    }

    public boolean needsPrice() {
        return priceBarsNeeded > 0;
    }

    public boolean needsFinancial() {
        return financialYearsNeeded > 0;
    }
}
