package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 유도가 <b>원문 밖에서</b> 필요로 하는 것. {@link GovernanceFactMapper#derive}의 인자다.
 *
 * <p>이 자리가 생긴 이유는 미국 배당이다. 국내는 사업보고서 한 장에 회계연도·공시일·주식수·
 * 배당액이 <b>함께</b> 오지만, 미국은 소스가 <b>사건 스트림</b>({@code cash_dividends})만 주고
 * 나머지 셋이 재무제표와 기업행위 원장에 흩어져 있다. 매퍼가 DB를 읽게 하면 벤더 경계가
 * 무너지므로(ADR-0081), <b>읽는 것은 {@code GovernanceFactDeriver}가 하고 값만 넘긴다.</b>
 *
 * <p>국내 매퍼는 이것을 <b>쓰지 않는다.</b> 인자를 default로 숨기지 않은 이유는, 숨기면 새
 * 매퍼가 빈 문맥을 받고도 조용히 값을 내기 때문이다 — 그때 나오는 것은 결손이 아니라
 * <b>그럴듯하게 틀린 값</b>이다.
 *
 * @param collectedOn      그 종목의 원문을 <b>마지막으로 받은 날</b>. 「그 해에 사건이 없었다」를
 *                         말하려면 그 해를 끝까지 봤어야 하는데, 소스의 조회 창이 <b>처리 시각</b>
 *                         기준이라 공시일만으로는 그것을 보증하지 못한다
 *                         (`alpaca-corporate-action.md` 「연간 재무 공시일이 배당 처리 지연을
 *                         안 덮는다」). 없으면 null이고 그때는 완결 판정을 할 수 없다
 * @param annualPeriods    그 종목의 <b>연간</b> 재무가 말하는 회계연도들. 회계연도 내림차순이다
 * @param shareMultiples   기업행위가 만든 주식수 배수. {@code IndicatorContext.corporateActionMultiple}과
 *                         <b>같은 경계</b>다({@code from} 제외 · {@code to} 포함) — 두 곳이 다른
 *                         경계를 쓰면 권리락 당일 사건이 한 번 더 붙거나 빠진다
 */
public record GovernanceDerivation(
        LocalDate collectedOn,
        List<AnnualPeriod> annualPeriods,
        ShareMultiples shareMultiples) {

    public GovernanceDerivation {
        annualPeriods = annualPeriods == null ? List.of() : List.copyOf(annualPeriods);
        if (shareMultiples == null) {
            // 배수를 모르는 채로 곱하면 분할이 낀 해의 합이 배수만큼 틀리고 값은 그럴듯하다.
            throw new IllegalArgumentException("shareMultiples가 null이다.");
        }
    }

    /** 국내처럼 원문 밖 재료가 필요 없는 매퍼에 넘기는 값. <b>완결 판정은 못 한다.</b> */
    public static GovernanceDerivation none() {
        return new GovernanceDerivation(null, List.of(), (from, to) -> BigDecimal.ONE);
    }

    /**
     * 회계연도 하나.
     *
     * @param start            <b>{@code period_start}를 쓴다.</b> {@code period_end}의 차분으로
     *                         만들면 결측 회계연도에서 창이 두 해로 벌어지고 최초 회계연도가
     *                         미정의가 된다(실측: 미국 최신 FY 1,000행의 창 길이 중앙값 364일 ·
     *                         350~380일 밖 0행)
     * @param firstPublishedAt 그 회계연도 연간 재무가 <b>처음</b> 공시된 날. 개정판이 있는
     *                         (종목,회계연도) 그룹이 24.0%이고 그중 97%가 최신판과 90일 넘게
     *                         벌어지므로, {@code is_latest} 행만 읽으면 계단이 뒤로 밀린다
     * @param sharesOutstanding 그 회계연도의 유통주식수. 없으면 null이고 총액을 못 낸다
     */
    public record AnnualPeriod(int fiscalYear, LocalDate start, LocalDate end,
                               LocalDate firstPublishedAt, BigDecimal sharesOutstanding) {

        public boolean contains(LocalDate date) {
            return date != null && !date.isBefore(start) && !date.isAfter(end);
        }
    }

    /** {@code from}(제외)부터 {@code to}(포함)까지의 주식수 배수. 사건이 없으면 1이다. */
    @FunctionalInterface
    public interface ShareMultiples {
        BigDecimal between(LocalDate from, LocalDate to);
    }
}
