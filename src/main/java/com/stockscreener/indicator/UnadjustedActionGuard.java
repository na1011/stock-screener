package com.stockscreener.indicator;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 계수에 반영되지 않은 기업 행위가 <b>읽기 창 안에 있는</b> 산출 일자를 고른다(ADR-0055).
 *
 * <p>보정되지 않은 분할·인적분할은 시세에 <b>축척이 다른 두 구간을 이어붙인다.</b> 그 이음매를
 * 넘는 창으로 낸 값은 무엇이든 틀린데, 결측이 아니라 <b>그럴듯한 숫자</b>로 나온다 — 007340은
 * 5.32배 계단을 그대로 둔 채 주봉 일목 Tier 2를 냈고, 207940은 1.465배 계단 위에서
 * {@code SMA_200}·{@code PCT_FROM_52W_*}를 내 스윙 필수 게이트를 통과했다.
 *
 * <h2>판정하는 것은 "오염"이 아니라 "창이 사건을 삼키는가"다</h2>
 * <p>이름을 정확히 쓴다. 여기서 걸리는 것 중에는 <b>축척에 불변인 지표도 있다</b> —
 * {@code TRADING_DAYS_750}은 봉 개수만 세고 {@code TURNOVER_60}은 거래소가 집계한
 * {@code trade_value}를 쓴다. 둘은 분할이 있어도 값이 맞는다. 그래도 함께 빼는 이유는
 * <b>지표마다 "이 지표가 축척에 민감한가"를 선언하게 하면 그 선언이 빠진 지표가 조용히
 * 통과하기 때문</b>이고, 빠진 쪽의 증상은 값이 나오는 것이라 아무도 못 본다. 방향이 안전한
 * 쪽이므로 초과 억제를 승인된 한계로 둔다(ADR-0055 §승인된 한계).
 *
 * <h2>경계</h2>
 * <pre>
 *   산출 일자 t의 창 = bars[max(0, i-N+1) .. i],  N = effectiveLookback(code)
 *   억제 조건        = windowStart.date &lt; e &lt;= t
 * </pre>
 * <p>IMPORTANT — {@code windowStart.date == e}는 억제하지 <b>않는다.</b> 그때는 창 안의 모든
 * 봉이 사건 당일 이후라 축척이 하나뿐이다. 부등호를 {@code <=}로 바꾸면 지표마다 하루씩 더
 * 지워지는데, 값이 사라지는 방향이라 <b>테스트도 화면도 조용하다.</b>
 *
 * <p>인덱스가 아니라 <b>날짜</b>로 비교하는 것도 계약이다. 007340은 2024-09-25~10-07이
 * 매매거래정지라 봉이 통째로 비는데, 인덱스 산술로 재면 그 공백만큼 창이 어긋난다.
 */
final class UnadjustedActionGuard {

    private UnadjustedActionGuard() {
    }

    /**
     * 값을 내지 말아야 할 산출 일자.
     *
     * @param bars              날짜 오름차순 시세({@link IndicatorContext#priceBars()} 계약)
     * @param unadjustedActions 계수에 반영되지 않은 사건의 효력일. 비면 결과도 빈 집합이다 —
     *                          <b>전 종목의 99%가 이 경로</b>라 여기서 일찍 빠지는 것이 중요하다
     * @param lookbackBars      그 지표가 실제로 읽는 봉 수
     *                          ({@link IndicatorRegistry#effectiveLookback(String)})
     * @return 억제할 거래일. 입력 순서(오름차순)를 유지한다
     */
    static Set<LocalDate> suppressedDates(List<PriceBar> bars,
                                          List<LocalDate> unadjustedActions,
                                          int lookbackBars) {
        if (bars.isEmpty() || unadjustedActions.isEmpty() || lookbackBars <= 0) {
            return Set.of();
        }

        Set<LocalDate> suppressed = new LinkedHashSet<>();
        for (int i = 0; i < bars.size(); i++) {
            LocalDate tradeDate = bars.get(i).tradeDate();
            LocalDate windowStart = bars.get(Math.max(0, i - lookbackBars + 1)).tradeDate();
            for (LocalDate effectiveDate : unadjustedActions) {
                if (effectiveDate.isAfter(windowStart) && !effectiveDate.isAfter(tradeDate)) {
                    suppressed.add(tradeDate);
                    break;
                }
            }
        }
        return suppressed;
    }
}
