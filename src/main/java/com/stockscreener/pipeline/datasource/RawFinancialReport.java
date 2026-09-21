package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

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
 * @param incomeTaxExpense 법인세비용(계속영업 기준). <b>음수가 두 뜻이다</b> —
 *                         진짜 환입과 차감 항목 표기이며 {@code 세전이익 − 순이익}과
 *                         부호를 대조해야 갈린다. 연간 행에만 있다
 * @param profitBeforeTax  세전이익(총액). 분자가 계속영업 기준이라 중단영업이 있는
 *                         해에는 기저가 어긋난다. 연간 행에만 있다
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

        /**
         * <b>지배주주 자본.</b> {@code totalEquity}는 비지배지분을 포함하고 {@code netIncome}은
         * 미국에서 지배주주 귀속이라, {@code ROE}가 기저가 다른 채로 나간다 — 그것을 맞추려고
         * 갈라 담는다(실측: {@code LNG} +50.61%p · {@code KKR} +4.57%p).
         *
         * <p><b>국내는 아직 비운다 — DB 전수 0 / 20,154행</b>(2026-09-20). DART가
         * {@code ifrs-full_EquityAttributableToOwnersOfParent}로 주는데 매핑을 안 했다.
         *
         * <p><b>MUST NOT: 「국내는 분자도 비지배 포함이라 기저가 맞으므로 급하지 않다」로 읽지
         * 않는다 — 그 면제는 {@code ROE}에만 서고 {@code PBR}에는 안 선다.</b> 여기 있던 그
         * 문장이 그것이었고 틀렸다. {@code PBR}의 분자는 <b>시가총액</b>이고 그것은 국내에서도
         * <b>상장주식에만</b> 값을 매기므로, 분모가 연결자본이면 <b>비지배지분이 분모에만
         * 들어간다</b>({@link com.stockscreener.indicator.calculator.FinancialMath}의
         * {@code equityAttributableToShareholders} javadoc · ADR-0159). 지금 국내 전 종목의
         * {@code PBR}이 그 폴백으로 돌고 있다.
         *
         * <p><b>크기는 두 시장 다 쟀다.</b> 국내는 DART 전수 305종목에서 비지배 비중이
         * <b>1% 초과 169 · 10% 초과 82 · 30% 초과 34</b>이고, {@code 000150} 두산의 저장
         * {@code PBR}이 <b>1.461</b>인데 지배주주 기저로는 <b>11.73</b>이다(<b>8.03배</b>) ·
         * {@code 034730} SK 0.351 대 1.122. 미국은 최신 FY 987티커 중 괴리가 1% 초과 208 ·
         * 20% 초과 54이고 {@code IBKR} 1.83 → 6.91 · {@code MAS} <b>518.61</b>(ADR-0159 §측정).
         *
         * <p><b>지금 닿는다</b> — {@code PBR_PCTL <= 0.30} 버킷에 KOSPI 60 + KOSDAQ 45 =
         * <b>105종목</b>이 있고, {@code PBR}이 8배 낮게 잡히는 지주회사가 그 버킷을 점거한다.
         */
        BigDecimal totalEquityControlling,
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
        BigDecimal costsAndExpenses,
        BigDecimal incomeTaxExpense,
        BigDecimal profitBeforeTax,

        /**
         * <b>우리가 만든 행인가.</b> 소스가 준 것이면 {@code false}, {@code FY − Q1 − Q2 − Q3}처럼
         * <b>우리가 유도한 것</b>이면 {@code true}다.
         *
         * <p>이 구분이 없으면 <b>유도 규칙을 고쳐도 옛 유도값을 영영 못 지운다.</b>
         * {@code FinancialWriter}의 재수집 경로는 NULL만 채우고 값을 NULL로 덮지 않으므로,
         * 새 규칙이 「이 컬럼은 못 만든다」고 판정해도 저장에는 옛 값이 그대로 남는다 —
         * 실측(2026-09-14)으로 미국 4분기 음수 매출 45행·매출원가 41행이 재적재 뒤에도
         * 한 행도 안 줄었다.
         *
         * <p><b>MUST: 유도한 행에는 반드시 {@code true}를 준다.</b> 기본값 {@code false}를 주는
         * 호환 생성자가 있으므로 <b>컴파일러가 이것을 묻지 않는다.</b>
         *
         * <p><b>MUST NOT: 「유도 자리는 둘이고 둘 다 {@code deriveFourthQuarter…}다」로 읽지
         * 않는다 — 2026-09-15에 거짓이 됐다.</b> 그날 {@code SecEdgarFacts.toReport} 안에
         * <b>컬럼 유도 일곱</b>이 들어왔다(매출·순이익·총자본·지배주주 자본·총부채·
         * 영업활동현금흐름·세전이익 — ADR-0153~0157). 그 경로는 FY·Q1~Q3를 만들므로
         * {@code derived == false}다.
         *
         * <p><b>그래서 그 일곱은 되돌릴 수 없다.</b> {@code FinancialWriter.fillMissing}의 값
         * 지우기 갈래가 이 필드를 요구하는데 일곱은 {@code false}라, <b>유도 규칙을 고쳐
         * {@code null}을 내면 저장된 옛 유도값이 영영 안 지워진다.</b> 커밋 {@code cf474f0}이
         * 닫은 결함이 새 축에서 다시 열린 것이다.
         *
         * <p><b>그 결손은 {@link #assertedAbsent}가 닫았다(2026-09-22).</b> 컬럼 단위 출처를
         * 어댑터가 싣고 {@code fillMissing}이 그것을 본다.
         *
         * <p><b>MUST NOT: 이 자리의 옛 서술 「ADR-0157 §결과가 컬럼 단위 출처를 요구한다」를
         * 근거로 쓰지 않는다 — 2026-09-22에 재보니 거짓이다.</b> 그 ADR 전문에
         * <i>"컬럼 단위"</i>도 {@code derived}도 <b>한 번도 안 나온다</b>. 요구를 만든 것은
         * ADR이 아니라 <b>이 javadoc 자신</b>이었고, 근거는 {@code withoutDerived}의 컬럼 목록이다.
         */
        boolean derived,

        /**
         * <b>어댑터가 「이 기간에는 그 값이 없다」고 판정한 컬럼</b>의 DB 컬럼명.
         *
         * <p>{@link #derived}가 <b>행 단위</b> 출처라면 이것은 <b>컬럼 단위</b> 출처다.
         * {@code derived}의 javadoc이 적은 <i>"그래서 그 일곱은 되돌릴 수 없다"</i>가 가리키는
         * 결손을 닫는다.
         *
         * <p><b>왜 필요한가</b>: {@code FinancialWriter.fillMissing}은 소스 행({@code derived == false})에서
         * {@code null}을 만나면 <b>안 지운다</b>. 그 규칙은 정당하다 — 소스가 준 판에서 컬럼이
         * 빠진 것은 「그 판이 그 컬럼을 안 실었다」이지 「그 값이 없다」가 아니고, 지우면 정정
         * 하나가 그 기간의 손익을 통째로 날린다.
         *
         * <p><b>그런데 유도 컬럼은 그 규칙의 전제가 안 선다.</b> {@code SecEdgarFacts}는
         * {@code carryForward}로 <b>판을 가로질러</b> 값을 이어받은 뒤에 유도하므로, 그 결과가
         * {@code null}이면 「이 판이 안 실었다」가 아니라 <b>「지금까지 본 모든 판을 합쳐도
         * 재료가 없다」</b>이다. 그것은 판정이고, 저장에 반영돼야 한다.
         *
         * <p><b>실측(2026-09-22)</b>: ADR-0159로 {@code net_income}이 연결 총액이 되면서
         * 어댑터가 <b>5,366칸 · 260종목</b>({@code is_latest} 2,578)에 「못 만든다」를 냈는데
         * 저장에는 <b>옛 지배주주 값</b>이 그대로 남았다. 같은 대조를 <b>컬럼 열한 개에 따로</b>
         * 돌리면 나머지는 0~2칸이라, 이 축은 <b>{@code net_income} 하나</b>가 지배한다.
         *
         * <p><b>MUST: 소스가 직접 준 컬럼을 여기 담지 않는다.</b> 담으면 그 소스의
         * 「안 실었다」가 「없다」로 읽혀 멀쩡한 값이 지워진다 — 국내 {@code net_income}이
         * {@code null}인 행이 <b>298개</b>(2026-09-22 실측) 있고, 그 소스에는 {@code carryForward}에
         * 해당하는 기제가 없다. 담을 자격은 <b>어댑터가 여러 판을 합쳐 판정했는가</b>이다.
         *
         * <p>{@code null}이면 빈 집합으로 정규화한다 — 어댑터가 안 채우면 <b>지금까지와 똑같이</b>
         * 동작한다.
         */
        Set<String> assertedAbsent
) {

    /**
     * {@code net_income}의 DB 컬럼명. {@link #assertedAbsent}에 담는 값과
     * {@code FinancialWriter}의 {@code Amount} 컬럼명이 <b>문자열로 만나므로</b> 한 곳에 둔다.
     * 오타는 조용히 0건이 되어 <b>결손을 고친 것처럼 보이게</b> 한다.
     */
    public static final String NET_INCOME = "net_income";

    /**
     * <b>지배주주 자본을 안 받는 옛 형태 — 시험 픽스처 전용이다.</b>
     *
     * <p><b>MUST NOT: 적재 어댑터에서 쓰지 않는다.</b> 이 생성자가 그 자리에 {@code null}을
     * 박고 국내 네 생성 지점이 전부 이것을 쓰고 있었던 탓에, 국내 {@code total_equity_controlling}이
     * <b>20,154행 전부 비어</b> {@code PBR}의 분모가 전 종목에서 연결자본으로 폴백했다.
     * 적재 경로는 아래 {@code totalEquityControlling}을 받는 생성자를 쓴다 —
     * <b>컴파일러가 그 자리를 강제로 드러내는 것이 이 분리의 목적이다.</b>
     */
    public RawFinancialReport(int fiscalYear, String fiscalPeriod, LocalDate periodStart,
                              LocalDate periodEnd, LocalDate publishedAt, String reportCode,
                              String currencyCode, BigDecimal revenue, BigDecimal operatingIncome,
                              BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalEquity,
                              BigDecimal totalLiabilities, BigDecimal currentAssets,
                              BigDecimal currentLiabilities, BigDecimal retainedEarnings,
                              BigDecimal operatingCashFlow, BigDecimal sharesOutstanding,
                              BigDecimal eps, BigDecimal bps, BigDecimal costOfSales,
                              BigDecimal cashAndEquivalents, BigDecimal capex,
                              BigDecimal inventories, BigDecimal netIncomeControlling,
                              BigDecimal interestAndDividendIncome, BigDecimal noninterestIncome,
                              BigDecimal interestExpense, BigDecimal noninterestExpense,
                              BigDecimal benefitsLossesAndExpenses, BigDecimal grossProfit,
                              BigDecimal operatingExpenses, BigDecimal costsAndExpenses,
                              BigDecimal incomeTaxExpense, BigDecimal profitBeforeTax) {
        this(fiscalYear, fiscalPeriod, periodStart, periodEnd, publishedAt, reportCode,
                currencyCode, revenue, operatingIncome, netIncome, totalAssets, totalEquity,
                null, totalLiabilities, currentAssets, currentLiabilities, retainedEarnings,
                operatingCashFlow, sharesOutstanding, eps, bps, costOfSales, cashAndEquivalents,
                capex, inventories, netIncomeControlling, interestAndDividendIncome,
                noninterestIncome, interestExpense, noninterestExpense, benefitsLossesAndExpenses,
                grossProfit, operatingExpenses, costsAndExpenses, incomeTaxExpense,
                profitBeforeTax, false, Set.of());
    }

    /**
     * 소스가 준 행. <b>유도한 행에는 쓰지 않는다</b> — 위 {@code derived} javadoc.
     *
     * <p><b>MUST: {@code totalEquityControlling}을 인자로 받는다 — 2026-09-21에 더했다.</b>
     * 그 전에는 이 생성자가 그 자리에 <b>상수 {@code null}</b>을 박았고, 국내 네 생성 지점이
     * 전부 이것을 쓰므로 <b>국내는 지배주주 자본을 담을 자리가 원리적으로 없었다.</b>
     * 특히 {@code toFourthQuarterBalance}가 만드는 Q4가 국내 재무의 <b>18.86%</b>라,
     * 그 자리를 안 고치면 커버리지 상한이 81.14%이고 {@code PBR_MEDIAN_5Y}는 5년 창에 Q4가
     * 매년 들어가 <b>연결 기저에 남는다.</b>
     */
    public RawFinancialReport(int fiscalYear, String fiscalPeriod, LocalDate periodStart,
                              LocalDate periodEnd, LocalDate publishedAt, String reportCode,
                              String currencyCode, BigDecimal revenue, BigDecimal operatingIncome,
                              BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalEquity,
                              BigDecimal totalEquityControlling,
                              BigDecimal totalLiabilities, BigDecimal currentAssets,
                              BigDecimal currentLiabilities, BigDecimal retainedEarnings,
                              BigDecimal operatingCashFlow, BigDecimal sharesOutstanding,
                              BigDecimal eps, BigDecimal bps, BigDecimal costOfSales,
                              BigDecimal cashAndEquivalents, BigDecimal capex,
                              BigDecimal inventories, BigDecimal netIncomeControlling,
                              BigDecimal interestAndDividendIncome, BigDecimal noninterestIncome,
                              BigDecimal interestExpense, BigDecimal noninterestExpense,
                              BigDecimal benefitsLossesAndExpenses, BigDecimal grossProfit,
                              BigDecimal operatingExpenses, BigDecimal costsAndExpenses,
                              BigDecimal incomeTaxExpense, BigDecimal profitBeforeTax) {
        this(fiscalYear, fiscalPeriod, periodStart, periodEnd, publishedAt, reportCode,
                currencyCode, revenue, operatingIncome, netIncome, totalAssets, totalEquity,
                totalEquityControlling, totalLiabilities, currentAssets, currentLiabilities,
                retainedEarnings,
                operatingCashFlow, sharesOutstanding, eps, bps, costOfSales, cashAndEquivalents,
                capex, inventories, netIncomeControlling, interestAndDividendIncome,
                noninterestIncome, interestExpense, noninterestExpense, benefitsLossesAndExpenses,
                grossProfit, operatingExpenses, costsAndExpenses, incomeTaxExpense,
                profitBeforeTax, false, Set.of());
    }

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
        // 안 채운 어댑터는 지금까지와 똑같이 동작한다. 복사본을 두는 것은 호출부가 뒤에
        // 집합을 바꿔도 저장 판정이 안 흔들리게 하기 위해서다.
        assertedAbsent = assertedAbsent == null ? Set.of() : Set.copyOf(assertedAbsent);
    }
}
