package com.stockscreener.stock.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 실적 원수치 — 종목 상세와 <b>별도 엔드포인트</b>다.
 *
 * <p>{@link StockDetailResponse}가 지지·저항을 뺀 이유가 그대로 걸린다 — 오류 격리와
 * "지표가 아님"의 구조적 표현이다. 여기 오는 값은 계산된 지표가 아니라 재무제표 행이고,
 * 이 요청이 실패해도 상세와 지표 카드는 살아 있어야 한다.
 *
 * <p>IMPORTANT — <b>이 응답의 위험은 "소스가 준 값처럼 보이는 것"이다.</b> 표에 뜨는 수치 중
 * 둘은 우리가 만든 것이다.
 * <ul>
 *   <li><b>4분기 손익은 유도값이다.</b> DART에 4분기 보고서가 없어 사업보고서에서 3분기
 *       누적을 뺀다. {@code derived}가 그 사실을 나른다 — 없으면 사용자는 공시된 실적으로 읽고,
 *       그것은 ADR-0039의 <i>"뜻이 다른 값을 같은 이름으로 부르지 않는다"</i>에 걸린다.
 *   <li><b>기간은 회계연도·분기로만 말한다.</b> {@code period_start}·{@code period_end}는
 *       합성 규약이라(ADR-0056) 결산월이 12월이 아닌 종목에서 아직 오지 않은 기간을 가리킨다.
 *       그래서 이 응답에 그 두 필드가 <b>없다.</b>
 * </ul>
 *
 * <p>증감률을 서버가 계산해 싣는 것도 의도다. {@code .claude/rules/frontend.md}가
 * <i>"서버가 이미 아는 사실을 화면이 다른 요청으로 재계산하지 않는다"</i>와
 * <i>"지표를 값만 나열하지 않는다"</i>를 함께 걸기 때문이다.
 */
public record StockFinancialsResponse(
        List<Period> annual,
        List<Period> quarterly,
        Notice notice) {

    /**
     * 한 기(期)의 원수치.
     *
     * @param fiscalYear   회계연도 라벨
     * @param fiscalPeriod {@code FY}·{@code Q1}~{@code Q4}
     * @param publishedAt  공시일. 유도된 4분기는 사업보고서의 공시일이라 FY와 같다
     * @param revised      정정본인가({@code revision_no > 0}). {@code is_latest}만으로는 알 수 없다
     * @param derived      우리가 유도한 행인가. 4분기 손익이 그것이다
     * @param revenueYoy   전년 <b>동기</b> 대비 증감률(%). 분기를 직전 분기와 비교하면
     *                     계절성 있는 업종에서 뜻이 없다
     */
    public record Period(
            int fiscalYear,
            String fiscalPeriod,
            LocalDate publishedAt,
            boolean revised,
            boolean derived,
            BigDecimal revenue,
            BigDecimal operatingIncome,
            BigDecimal netIncome,
            BigDecimal operatingCashFlow,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal totalEquity,
            BigDecimal revenueYoy,
            BigDecimal operatingIncomeYoy,
            BigDecimal netIncomeYoy) {
    }

    /**
     * 화면이 반드시 함께 그려야 하는 고지. <b>서버가 문구를 소유하고 항상 실린다.</b>
     *
     * <p>{@code PriceReference#disclaimer}·{@code UnadjustedActions#notice}와 같은 형태다.
     * 프론트가 자기 문구를 들면 두 곳이 갈리는데, 갈린 쪽은 화면에서 드러나지 않는다.
     */
    public record Notice(String quarterlyCashFlow, String derivedFourthQuarter) {

        /**
         * 둘 다 <b>지금 데이터로 참인 고지</b>다.
         *
         * <p>분기 영업활동현금흐름은 실측으로 네 분기 전부 0행이다 — ADR-0056이 3개월인지
         * 누적인지 미확인이라 저장을 금지했다. 고지 없이 {@code —}만 그리면 수집 결손으로 읽힌다.
         */
        static Notice standard() {
            return new Notice(
                    "분기 영업활동현금흐름은 저장하지 않습니다. 원본이 3개월치인지 누적인지 확인되지 "
                            + "않아, 잘못 더하면 값이 조용히 틀리기 때문입니다. 비어 있는 것이 정상입니다.",
                    "4분기 손익은 공시된 값이 아니라 사업보고서에서 3분기 누적을 뺀 값입니다. "
                            + "DART에 4분기 보고서가 없습니다. 자산·부채·자본은 시점 잔액이라 "
                            + "차감하지 않으므로 연간과 같습니다.");
        }
    }
}
