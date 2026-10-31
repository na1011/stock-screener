package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * 전년 대비 증감률 계산.
 *
 * <p>매출·영업이익 등 성장률 지표가 모두 같은 형태라 한곳에 둔다. 계산기마다 다시 쓰면
 * 그중 하나만 분모 처리를 다르게 해도 성장률끼리 비교가 어긋난다.
 */
public final class YearOverYear {

    private YearOverYear() {
    }

    /**
     * 연간 재무 목록(최신 우선)에서 전년 대비 증감률을 백분율로 계산한다.
     *
     * <p>연간 재무가 2개 미만이거나 어느 한 해의 값이 비어 있으면 null이다.
     *
     * <p>분모는 <b>절댓값</b>이다. 직전 값이 음수일 때 부호가 뒤집혀 악화가 개선으로
     * 보이는 것을 막는다 — 적자가 커진 기업의 이익 성장률이 양수로 나오는 것이 그 예다.
     */
    static BigDecimal growth(List<FinancialFigures> annual,
                             Function<FinancialFigures, BigDecimal> field) {
        if (annual.size() < 2) {
            return null;
        }
        return between(field.apply(annual.get(0)), field.apply(annual.get(1)));
    }

    /**
     * 최신 두 회계연도가 실제로 이웃한 해인가. 아니면 「전년 대비」라는 이름이 거짓이 된다 — 사이가 빈 종목(연간 실적이 한 해 이상 빠짐)은 몇 년 치
     * 변화를 1년 치로 낸다(DSGX FY2026 대 FY2022 매출 71.65% · 실제 전년 대비 11.98% · ORKA 영업이익 −1,572.8%는 FY2025 대 FY2023 · 반증 2026-10-31).
     */
    static boolean consecutive(List<FinancialFigures> annual) {
        return annual.size() >= 2 && annual.get(0).fiscalYear() - annual.get(1).fiscalYear() == 1;
    }

    /**
     * 두 값 사이의 증감률(%). 어느 쪽이든 비었거나 직전이 0이면 null이다.
     *
     * <p>실적 표가 이것을 쓴다 — 지표 계산기와 <b>같은 식이어야</b> 한 화면의 두 증감률이
     * 어긋나지 않는다. 특히 <b>분모의 절댓값</b>이 그렇다: 적자가 커진 기업의 이익 증감률이
     * 한쪽에서만 양수로 나오면, 사용자는 같은 사실을 두 곳에서 반대로 읽는다.
     *
     * <p>비교 대상을 고르는 것은 호출부의 몫이다. 연간은 전년, 분기는 <b>전년 동기</b>다 —
     * 직전 분기와 비교하면 계절성 있는 업종에서 뜻을 잃는다.
     */
    public static BigDecimal between(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() == 0) {
            return null;
        }
        return FinancialMath.percentage(current.subtract(previous), previous.abs());
    }
}
