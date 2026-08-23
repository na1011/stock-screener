package com.stockscreener.indicator;

import java.util.Set;

/**
 * 지표 계산에 필요한 원자료와 의존 지표의 선언.
 *
 * @param priceBarsNeeded     필요한 시세 봉 개수(거래일 기준). 시세가 필요 없으면 0.
 * @param financialYearsNeeded 필요한 재무제표 연수. 재무가 필요 없으면 0.
 * @param dependsOn           이 지표가 소비하는 <b>다른 지표</b>의 코드.
 * @param governanceYearsNeeded 필요한 거버넌스 회계연도 수. 안 쓰면 0.
 *
 * <p>재무와 갈라 선언하는 이유는 <b>{@code IndicatorRunner.inputsWereReadable}이 이것으로
 * 「값이 없는 것」과 「입력을 못 읽은 것」을 가르기 때문</b>이다(ADR-0080). 선언하지 않으면
 * 거버넌스를 한 행도 못 읽은 종목에서 <b>부모 행은 남는데 이 지표 행만 지워진다.</b>
 * 시장 지수가 선언 없이 늘 읽히는 바람에 같은 결손을 안고 있는데(ADR-0080 §결과),
 * 그것을 셋째 축으로 늘리지 않는다.
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
        Set<String> dependsOn,
        int governanceYearsNeeded
) {

    public DataRequirement {
        if (priceBarsNeeded < 0) {
            throw new IllegalArgumentException("priceBarsNeeded는 음수일 수 없다: " + priceBarsNeeded);
        }
        if (financialYearsNeeded < 0) {
            throw new IllegalArgumentException("financialYearsNeeded는 음수일 수 없다: " + financialYearsNeeded);
        }
        if (governanceYearsNeeded < 0) {
            throw new IllegalArgumentException(
                    "governanceYearsNeeded는 음수일 수 없다: " + governanceYearsNeeded);
        }
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
    }

    /** 넷째 컴포넌트가 생기기 전의 형태. 거버넌스를 안 쓰는 계산기가 그대로 쓴다. */
    public DataRequirement(int priceBarsNeeded, int financialYearsNeeded, Set<String> dependsOn) {
        this(priceBarsNeeded, financialYearsNeeded, dependsOn, 0);
    }

    /** 거버넌스를 쓰는 지표. 시세는 산출 일자를 정하는 데 필요하므로 최소 1이다. */
    public static DataRequirement governance(int priceBars, int financialYears,
                                             int governanceYears) {
        return new DataRequirement(priceBars, financialYears, Set.of(), governanceYears);
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

    public boolean needsGovernance() {
        return governanceYearsNeeded > 0;
    }
}
