package com.stockscreener.indicator;

import java.util.Map;

/**
 * 한 회계연도의 거버넌스 사실 묶음. {@link IndicatorContext#governanceFacts()}의 원소다.
 *
 * <p>{@link FinancialFigures}와 나란한 자리이고 성질이 셋 다르다 — 원자료가 재무제표가 아니라
 * 정기보고서 주요정보이고, 주기가 연 1회이며, <b>값이 봉마다가 아니라 공시일에 계단으로</b> 바뀐다.
 *
 * <p>MUST: 목록은 {@code fiscalYear} <b>내림차순</b>이다({@link IndicatorContext#annualFigures()}와
 * 같은 계약). 계산기가 「가장 최근 것」을 앞에서부터 가져간다.
 */
public record GovernanceFigures(int fiscalYear, Map<GovernanceMetric, GovernanceValue> values) {

    public GovernanceFigures {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** 없으면 null이다. 판단은 호출자 몫이며, 없는 것을 0으로 접지 않는다. */
    public GovernanceValue get(GovernanceMetric metric) {
        return values.get(metric);
    }
}
