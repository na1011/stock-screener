package com.stockscreener.indicator;

import java.util.Set;

/**
 * 지표 계산에 필요한 원자료와 의존 지표의 선언.
 *
 * @param priceBarsNeeded     필요한 시세 봉 개수(거래일 기준). 시세가 필요 없으면 0.
 *
 * <p>IMPORTANT — <b>1은 미보정 기업 행위 억제를 켜지 않는다.</b> {@code UnadjustedActionGuard}가
 * 창을 {@code (windowStart, tradeDate]}로 잡는데 봉이 하나면 {@code windowStart == tradeDate}가
 * 되어 판정이 항상 거짓이다. {@code PER}·{@code PSR}·{@code PBR}과 거버넌스 지표 전부가 이
 * 성질을 갖는다 — <b>그 지표들은 억제의 보호를 안 받으므로 기업 행위 보정을 계산기가 명시로
 * 한다</b>({@code IndicatorContext.corporateActionMultiple}). 0으로 두는 것이 더 위험하다는
 * ADR-0066의 경고를 「1이면 안전하다」로 읽지 않는다.
 * @param financialYearsNeeded 필요한 재무제표 연수. 재무가 필요 없으면 0.
 * @param dependsOn           이 지표가 소비하는 <b>다른 지표</b>의 코드.
 * @param governanceYearsNeeded 필요한 거버넌스 회계연도 수. 안 쓰면 0.
 *
 * <p>재무와 갈라 선언하는 이유는 <b>{@code IndicatorRunner.inputsWereReadable}이 이것으로
 * 「값이 없는 것」과 「입력을 못 읽은 것」을 가르기 때문</b>이다(ADR-0080). 선언하지 않으면
 * 거버넌스를 한 행도 못 읽은 종목에서 <b>부모 행은 남는데 이 지표 행만 지워진다.</b>
 * @param needsMarketIndex   시장 지수 시계열을 쓰는가. 같은 이유로 선언한다(ADR-0085) —
 * 선언이 없던 동안 지수가 통째로 비면 {@code BETA_104W}·{@code EXCESS_RETURN_*}가
 * 「값이 사라졌다」로 판정되어 걷혔다(ADR-0080 §결과가 적어 둔 구멍이다).
 *
 * <p>IMPORTANT — <b>이 축은 「통째로 없는가」만 가른다.</b> {@code IndicatorContext}가
 * {@code null}을 빈 맵으로 접으므로 판정이 결국 「맵이 비었는가」다. <b>날짜별 결손은 못 가른다</b> —
 * 그쪽은 {@code IndicatorRunner}가 꼬리 후보에서 날짜 단위로 뺀다.
 *
 * <p>ADR-0045 §버린 대안이 기각한 「계산기가 <b>적게 낸다</b>고 선언한다」와 다른 것이다.
 * 그쪽은 <b>산출량</b> 선언이라 잊으면 조용한 삭제가 되고, 이쪽은 <b>입력 축</b> 선언이라
 * 잊으면 지금과 같은 상태로 돌아갈 뿐이다.
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
        int governanceYearsNeeded,
        boolean needsMarketIndex
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
        this(priceBarsNeeded, financialYearsNeeded, dependsOn, 0, false);
    }

    /** 다섯째 컴포넌트가 생기기 전의 형태. 지수를 안 쓰는 계산기가 그대로 쓴다. */
    public DataRequirement(int priceBarsNeeded, int financialYearsNeeded, Set<String> dependsOn,
                           int governanceYearsNeeded) {
        this(priceBarsNeeded, financialYearsNeeded, dependsOn, governanceYearsNeeded, false);
    }

    /**
     * 시세와 <b>시장 지수</b>를 쓰는 지표.
     *
     * <p>이 축을 쓰는 계산기는 셋뿐이고({@code BETA_104W}·{@code EXCESS_RETURN_26W}·
     * {@code EXCESS_RETURN_52W}) 그 셋에 의존하는 파생 지표는 러너 안에 없다.
     * <b>주봉 일목은 지수를 안 쓴다</b> — {@code IchimokuAnalyzer}가 그렇게 못 박는다.
     */
    public static DataRequirement priceAndMarketIndex(int bars) {
        return new DataRequirement(bars, 0, Set.of(), 0, true);
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
