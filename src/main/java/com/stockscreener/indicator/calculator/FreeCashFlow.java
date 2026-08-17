package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;

import java.math.BigDecimal;
import java.util.List;

/**
 * 잉여현금흐름 = {@code 영업활동현금흐름 − 자본적지출}. 두 계산기가 공유한다.
 *
 * <h2>MUST NOT: 이것을 「오너 어닝」이라 부르지 않는다</h2>
 *
 * <p>버핏 문서 3.3의 오너 어닝은 <b>순이익 + 감가상각·상각 − 자본적지출 − 추가 소요 운전자본</b>
 * 이며, 문서 자신이 <i>"잉여현금흐름과 유사하나 운전자본 증분까지 차감한다는 점이 다르다"</i>고
 * 적는다. 감가상각비는 소스가 주지 않으므로({@code dart-open-api.md} §7 1/4 · §12 실질 2/8)
 * 정확한 오너 어닝은 <b>열리지 않는다.</b> 문서가 지시한 <b>대체품</b>이고 그 이상이 아니다.
 *
 * <h2>연간만 낸다</h2>
 *
 * <p>{@code operating_cash_flow}와 {@code capex} 둘 다 <b>연간 행에만</b> 저장된다 —
 * 현금흐름표의 분기 금액이 3개월인지 누적인지 재지 않았기 때문이다({@code §11-E}).
 * 둘이 같은 창에서만 값을 갖는 것이 중요하다: <b>기간이 다른 두 수를 빼면 값이 그럴듯한 채로
 * 틀린다.</b>
 *
 * <p>MUST NOT: 자본적지출에 절댓값을 씌우지 않는다. 유출이 <b>양수 크기</b>로 오므로 빼는 것이
 * 맞다(실측 8종목 전부 양수 — §13). 절댓값을 씌우면 음수로 오는 회사가 하나라도 있을 때 그
 * 종목의 잉여현금흐름이 <b>두 배로 부풀고</b> 화면 어디에도 안 드러난다.
 *
 * <p>MUST NOT: 자본적지출이 없을 때 0으로 보고 영업활동현금흐름을 그대로 쓰지 않는다.
 * 그러면 <b>계정이 없는 종목이 자본 소요가 없는 회사로 읽힌다</b> — 이 지표가 걸러 내려는
 * 것과 정확히 반대다.
 */
final class FreeCashFlow {

    private FreeCashFlow() {
    }

    /** @return 가장 최근 회계연도의 잉여현금흐름. 둘 중 하나라도 없으면 null */
    static BigDecimal latestAnnual(List<FinancialFigures> annual) {
        if (annual.isEmpty()) {
            return null;
        }
        FinancialFigures latest = annual.get(0);
        BigDecimal operatingCashFlow = latest.operatingCashFlow();
        BigDecimal capex = latest.capex();
        if (operatingCashFlow == null || capex == null) {
            return null;
        }
        return operatingCashFlow.subtract(capex);
    }
}
