package com.stockscreener.probe.backtest;

import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.IndicatorCondition;

import java.time.LocalDate;

/**
 * 격자의 <b>하한</b>을 조회 대상마다 유도한다(ADR-0108).
 *
 * <p><b>상수로 박으면 시장이 늘 때 조용히 낡는다.</b> 실측으로 재무 최초 회계연도가
 * <b>국내 FY2015 · 미국 FY2018</b>이라, 국내 바닥으로 정한 하한(2022-04-01)을 미국에 그대로
 * 쓰면 {@code PROFIT_STREAK_Y >= 7}이 <b>앞쪽 8시점에서 전 종목 판정불가</b>가 된다 —
 * 실측으로 그 시점들의 확보 종목 수가 전부 0이고, 2024-04-01에 30에서 시작해 2025-04-01에
 * 799가 된다. 그 0은 게이트가 아니라 <b>적재 범위 탓</b>이고, 격자 하한이라는 장치가 애초에
 * 막으려던 바로 그 실패다.
 *
 * <p><b>사본을 만들지 않으려고 여기 둔다</b> — 진단 축과 수익률 축이 같은 하한을 써야
 * 두 표를 나란히 읽을 수 있다.
 */
final class HarnessWindow {

    /**
     * 프리셋에서 못 읽을 때의 기본 연수. {@code IndicatorDataReader}의 연간 읽기 창이
     * 5개 회계연도라, 그보다 앞을 격자에 넣으면 재무 기반 조건이 적재 범위 탓으로 판정불가다.
     */
    private static final int FALLBACK_FISCAL_YEARS = 5;

    private HarnessWindow() {
    }

    /**
     * 그 시장에서 이 조건셋을 판정할 수 있는 <b>가장 이른 시점.</b>
     *
     * <p>4월 1일로 잡는 이유는 그 해 봄이면 <b>직전 회계연도 사업보고서가 공시돼</b> 다년
     * 조건이 성립하기 때문이다. 국내는 FY2015 + 7 = <b>2022-04-01</b>이라, 이 값을 상수로
     * 갖고 있던 시절과 같은 날짜가 나온다 — 유도가 기존 판단을 재현한다는 대조다.
     *
     * <p><b>MUST NOT: 재무가 없을 때 {@code LocalDate.MIN}을 내지 않는다.</b> 호출부가 그것을
     * {@code every(from, last, 3)}에 넘기면 <b>기원전부터 3개월씩 도는 루프</b>가 되고, 각
     * 회차가 DB 질의라 사실상 멈추지 않는다. 그 상태는 「느리다」로만 보여 원인에 닿기 어렵다.
     * 대신 {@code null}을 내고 호출부가 <b>「잴 것이 없었다」</b>로 적는다.
     *
     * @param earliestFiscalYear 그 시장 재무의 최초 회계연도. {@code null}이면 재무가 없다
     * @return 그 시점부터 격자를 만든다. {@code null}이면 이 조건셋을 그 시장에서 못 잰다
     */
    static LocalDate floorFor(Integer earliestFiscalYear, ConditionGroup resolved) {
        if (earliestFiscalYear == null) {
            return null;
        }
        return LocalDate.of(earliestFiscalYear + requiredFiscalYears(resolved), 4, 1);
    }

    /**
     * 이 조건셋이 요구하는 <b>가장 긴 다년 회계연도 수.</b> 조건에서 읽으므로 임계가 바뀌면
     * 하한도 따라간다 — 손으로 적은 수는 프리셋이 바뀔 때 조용히 낡는다.
     *
     * <p>지금 그 자리를 정하는 것은 {@code PROFIT_STREAK_Y}다(버핏 우량주에서 가장 긴 다년
     * 요구). 다른 다년 지표는 읽기 창 5년 안에 들어온다.
     */
    private static int requiredFiscalYears(ConditionGroup resolved) {
        int years = FALLBACK_FISCAL_YEARS;
        for (IndicatorCondition condition : resolved.flattenConditions()) {
            if (condition.indicatorCode().equals("PROFIT_STREAK_Y")
                    && condition.operand() instanceof ConditionOperand.Literal literal
                    && literal.value() != null) {
                years = Math.max(years, literal.value().intValue());
            }
        }
        return years;
    }
}
