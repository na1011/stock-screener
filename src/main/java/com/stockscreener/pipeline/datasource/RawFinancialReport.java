package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 소스가 준 재무제표 한 건.
 *
 * <p>IMPORTANT — 이 record에 <b>비율 필드가 없는 것이 의도</b>다. PER·ROE·부채비율 같은 값을
 * 여기 담을 수 없으므로, 외부 API가 계산해 준 지표에 의존하는 것이 타입 수준에서 불가능해진다.
 * spec §7과 {@code data-pipeline.md}의 "모든 지표는 원자료에서 자체 계산"을 주석이 아니라
 * 구조로 강제한다. 새 필드를 추가할 때 이 성질을 깨지 않는지 확인한다.
 *
 * <p>모든 금액이 nullable이다. 소스가 항목을 누락하는 일이 흔하고, null이면 그 지표를 계산하지
 * 않는 것이 옳다 — 0으로 대체하면 조용히 틀린 값이 저장된다.
 *
 * <p>MUST: 소스가 준 형태 그대로 담는다. <b>FY와 Q4를 한 행으로 합치지 않는다</b> —
 * 합치면 연간과 4분기가 구별되지 않아 TTM이 그 해를 두 번 세거나 아예 못 센다.
 *
 * <p><b>Q4는 예외이고, 유도하는 것이 규칙이다.</b> 4분기 보고서는 어느 소스에도 없다
 * (DART는 사업보고서와 분기보고서를 따로 내고, EDGAR는 10-K와 10-Q뿐이다). 그런데
 * {@code IndicatorDataReader}의 분기 TTM이 {@code fiscal_period <> 'FY'}를
 * {@code period_end} 역순으로 넷 뽑으므로, <b>Q4 행이 없으면 그 넷이 다섯 분기에 걸친
 * 구멍 난 합</b>이 된다 — 값이 그럴듯하고 에러가 없다. 그래서 어댑터가 별도 행으로 유도한다
 * ({@code DartFinancialAdapter.deriveFourthQuarter}·{@code SecEdgarFacts.deriveFourthQuarters}).
 *
 * <p>MUST: 유도는 <b>손익만</b> 차감한다. 재무상태표는 시점 잔액이라 차감하면 자산총계가
 * 음수가 된다. 그리고 <b>부분이 하나라도 없으면 유도하지 않는다</b> — 0으로 대체하면 조용히
 * 틀린 값이 저장된다.
 *
 * @param fiscalPeriod  {@code FY|Q1|Q2|Q3|Q4}. {@code financial_statement.ck_fs_period}와 같은 집합.
 * @param periodEnd     기간의 근거. 회계연도가 달력연도와 다를 수 있어({@code fiscalYear}는 라벨)
 *                      TTM 합산과 정렬은 반드시 이것을 기준으로 한다.
 * @param publishedAt   공시일. 정정공시 판별과 "그 시점에 공개되어 있던 값" 질의의 근거다.
 * @param currencyCode  행 단위 사실이다. 시세와 달리 재무제표는 상장 시장과 다른 통화로
 *                      보고될 수 있다.
 * @param currentAssets      유동자산. {@code currentLiabilities}와 짝을 이뤄 운전자본이 된다.
 * @param currentLiabilities 유동부채.
 * @param retainedEarnings   이익잉여금. <b>자본총계와 다르다</b> — 자본금·자본잉여금을 포함하지 않는다.
 * @param capex              자본적지출(유형자산의 취득). <b>유출을 양수 크기로 담는다</b> —
 *                           실측 8종목이 전부 양수로 왔다(ADR-0067). 부호를 뒤집어 담으면
 *                           잉여현금흐름이 두 배로 부풀고 값이 그럴듯해 드러나지 않는다.
 * @param inventories        재고자산. <b>저량(잔액)이라 분기 행에도 담는다</b> — 유량인
 *                           {@code capex}와 다른 자리다. 금융업은 이 계정이 원리적으로 없어
 * 비는 것이 정상이다(dart-financial.md의 「계정별 가용성」 절). 0으로 대체하면 재고가 없는 회사가 되어
 *                           값이 그럴듯하게 틀린다.
 * @param interestAndDividendIncome 이자·배당수익(은행). <b>영업이익 유도의 재료이지 지표가 아니다.</b>
 * @param noninterestIncome 비이자수익(은행).
 * @param interestExpense 이자비용.
 * @param noninterestExpense 비이자비용(은행).
 * @param benefitsLossesAndExpenses 보험금·손실·비용(보험). {@code revenue}에서 빼면 영업이익이다.
 * @param grossProfit 매출총이익. 소스가 직접 낸 값이며 {@code revenue − costOfSales}와 다를 수 있다.
 * @param operatingExpenses 영업비용. {@code grossProfit}에서 빼면 영업이익이다.
 * @param costsAndExpenses 총원가·비용. {@code revenue}에서 빼면 영업이익이다.
 * @param netIncomeControlling <b>지배기업 소유주에게 귀속되는</b> 당기순이익.
 *                           {@code netIncome}(연결 총액, 비지배지분 포함)과 <b>다른 값이고 둘 다
 *                           사실이다</b> — 어느 쪽을 쓸지는 짝이 정한다. 주당 지표는 이쪽이고
 *                           (K-IFRS 1033이 주당이익을 지배기업 보통주 귀속 이익으로 정의한다),
 *                           분모가 연결 전체인 비율 지표(ROE·ROA·순이익률)는 {@code netIncome}이다.
 *                           <b>비지배지분이 없는 회사에서는 소스가 이 행을 아예 내지 않는다</b> —
 *                           결손이 아니라 정상이고, 그때는 {@code netIncome}이 곧 이 값이다.
 *                           실측은 dart-financial.md의 「계정별 가용성」(연간 8/8 · 분기 8/8).
 */
public record RawFinancialReport(
        int fiscalYear,
        String fiscalPeriod,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate publishedAt,
        String reportCode,
        String currencyCode,
        BigDecimal revenue,
        BigDecimal operatingIncome,
        BigDecimal netIncome,
        BigDecimal totalAssets,
        BigDecimal totalEquity,
        BigDecimal totalLiabilities,
        BigDecimal currentAssets,
        BigDecimal currentLiabilities,
        BigDecimal retainedEarnings,
        BigDecimal operatingCashFlow,
        BigDecimal sharesOutstanding,
        BigDecimal eps,
        BigDecimal bps,
        BigDecimal costOfSales,
        BigDecimal cashAndEquivalents,
        BigDecimal capex,
        BigDecimal inventories,
        BigDecimal netIncomeControlling,
        BigDecimal interestAndDividendIncome,
        BigDecimal noninterestIncome,
        BigDecimal interestExpense,
        BigDecimal noninterestExpense,
        BigDecimal benefitsLossesAndExpenses,
        BigDecimal grossProfit,
        BigDecimal operatingExpenses,
        BigDecimal costsAndExpenses
) {

    public RawFinancialReport {
        if (fiscalPeriod == null || fiscalPeriod.isBlank()) {
            throw new IllegalArgumentException("fiscalPeriod가 비어 있다. fiscalYear=" + fiscalYear);
        }
        if (periodStart == null || periodEnd == null) {
            throw new IllegalArgumentException(
                    "기간이 비어 있다. fiscalYear=" + fiscalYear + " period=" + fiscalPeriod);
        }
        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException(
                    "periodEnd가 periodStart보다 뒤가 아니다: " + periodStart + " ~ " + periodEnd);
        }
        if (publishedAt == null) {
            throw new IllegalArgumentException(
                    "publishedAt이 null이다. 정정공시 판별의 근거이므로 생략할 수 없다.");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalArgumentException("currencyCode가 비어 있다.");
        }
    }
}
