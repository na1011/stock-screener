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
 * 적는다. 이 클래스는 그 <b>대체품</b>이다.
 *
 * <h2>MUST: 「오너 어닝은 열리지 않는다」를 시장 없이 적지 않는다 — 미국에서 거짓이다</h2>
 * <p>여기 있던 그 문장의 근거는 <b>DART 표본 8종목 중 3</b>({@code dart-financial.md}의
 * 「계정별 가용성」 절)이었고 <b>시장을 안 밝혔다.</b> 2026-09-19에 팩트 캐시 1,214종목으로
 * 미국을 직접 재니 갈렸다:
 * <ul>
 *   <li><b>감가상각·상각</b> — 현재 편입 1,000 중 <b>991</b>이 D&amp;A 계열 태그를 갖는다
 *       ({@code DepreciationDepletionAndAmortization} 912 · {@code Depreciation} 893 ·
 *       {@code DepreciationAndAmortization} 496 · {@code DepreciationAmortizationAndAccretionNet} 140).
 *       무작위 표본 <b>12/12</b>가 10-K의 FY 12개월 값을 <b>7~17개년</b> 갖는다.
 *       <b>안 갖는 아홉은 BDC·보험사다</b>(ARCC·OBDC·LNC·EG 등).</li>
 *   <li><b>운전자본 증분</b> — <b>이미 저장돼 있다.</b> {@code current_assets}·
 *       {@code current_liabilities}가 미국 편입 <b>862/1,000</b>에 있다.</li>
 *   <li><b>자본적지출·순이익</b> — 이미 저장돼 있다.</li>
 * </ul>
 * <p>즉 미국에서 빠진 것은 <b>D&amp;A 컬럼 하나뿐이고 소스는 준다 — 가짜 한계다.</b>
 * 국내는 그 3/8이 아직 유일한 근거이고 <b>다시 재려면 DART 콜이 든다</b>
 * ({@code fnlttSinglAcntAll} 350종목 = 350콜). <b>재기 전에 「국내도 안 준다」를 적지 않는다.</b>
 *
 * <h2>연간만 낸다</h2>
 *
 * <p>{@code operating_cash_flow}와 {@code capex} 둘 다 <b>연간 행에만</b> 저장된다 —
 * 현금흐름표의 분기 금액이 3개월인지 누적인지 재지 않았기 때문이다({@code dart-financial.md의 「전체 재무제표」 절}).
 * 둘이 같은 창에서만 값을 갖는 것이 중요하다: <b>기간이 다른 두 수를 빼면 값이 그럴듯한 채로
 * 틀린다.</b>
 *
 * <h2>MUST: 자본적지출을 크기로 읽는다 — 부호 규약이 회사마다 갈린다</h2>
 *
 * <p>탐침 8종목은 전부 양수였지만 <b>350종목 재적재에서 22행(1.5%)이 음수로 왔다</b> —
 * LG화학·S-Oil·롯데쇼핑과 소형주 열 곳이다. 크기는 맞고 부호만 다르다(LG화학 FY2023이
 * −12.96조이고 그 해 실제 설비투자가 그 규모다). <b>계정이 「유형자산의 취득」이라 음수일 수
 * 없으므로</b> 그것은 값이 아니라 표기 관례다 — 회사가 투자활동 유출을 음수로 적었을 뿐이다.
 *
 * <p>그래서 여기서 크기를 취한다. <b>부호를 그대로 빼면 그 22행에서 뺄셈이 덧셈이 되어</b>
 * 잉여현금흐름이 영업활동현금흐름보다 커지고, 값이 그럴듯해 화면 어디에도 안 드러난다.
 *
 * <p>MUST NOT: 이 정규화를 적재 계층으로 내리지 않는다. {@code financial_statement}는
 * <b>원자료만 담고</b> 판단은 지표 계층이 한다 — 소스가 뭐라고 했는지를 지우면 나중에 부호
 * 규약이 실제로 바뀌었을 때 그것을 알아볼 방법이 없다.
 *
 * <p>MUST NOT: 이 크기 취하기를 다른 계정에 복사하지 않는다. 여기서 안전한 이유는
 * <b>「취득」이 한 방향 항목</b>이기 때문이다 — 순증감으로 오는 계정(예: 단기금융상품의
 * 순감소(증가))에 같은 짓을 하면 방향 정보가 통째로 사라진다.
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
        // 크기를 뺀다. 부호 규약이 회사마다 갈리는 것이 실측으로 확인됐다 — 위 javadoc.
        return operatingCashFlow.subtract(capex.abs());
    }
}
