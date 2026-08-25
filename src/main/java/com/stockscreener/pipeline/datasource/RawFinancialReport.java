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
 * <p>MUST: 소스가 준 형태 그대로 담는다. FY와 Q4를 합치거나 Q4를 {@code FY-(Q1+Q2+Q3)}로
 * 유도하지 않는다. DART는 사업보고서와 분기보고서를 따로 내며, 그 판단은 지표 계층의 몫이다.
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
        BigDecimal inventories
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
