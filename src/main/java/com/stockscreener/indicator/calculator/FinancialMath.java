package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 재무 지표 계산의 공통 부분.
 *
 * <p>TTM 합산과 "값 하나를 시세 기간 전체에 펼치기"는 재무 지표 대부분이 똑같이 하는 일이다.
 * 계산기마다 다시 쓰면 그중 하나만 null 처리를 빠뜨려도 조용히 0이 저장된다.
 *
 * <p>MUST — 항목이 비어 있으면 {@code null}을 돌려 계산을 포기한다. 0으로 대체하지 않는다.
 * "부채가 0인 회사"와 "부채를 모르는 회사"는 다른 것이고, 후자를 전자로 만들면
 * 부채비율 조건에 그 종목이 최상위로 올라온다.
 */
final class FinancialMath {

    static final int SCALE = 8;
    static final int TTM_QUARTERS = 4;
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private FinancialMath() {
    }

    /**
     * 최근 4개 분기의 항목을 합한다. 분기가 모자라거나 한 분기라도 값이 비면 null이다.
     *
     * <p>유량(flow) 항목에만 쓴다 — 매출·이익·현금흐름처럼 기간에 걸쳐 쌓이는 값이다.
     * 자산·부채 같은 저량(stock) 항목에 쓰면 4배가 된다.
     */
    static BigDecimal sumTrailingFourQuarters(List<FinancialFigures> quarters,
                                              Function<FinancialFigures, BigDecimal> field) {
        return sumFourQuarters(quarters, field, 0);
    }

    /**
     * {@code offset}번째 분기부터 4개를 합한다. {@code offset = 4}면 <b>직전 TTM</b>이다.
     *
     * <p>읽기가 {@code period_end} 내림차순이므로 인덱스가 클수록 과거다. 전년 대비 성장률을
     * 만들 때 이것과 {@code offset = 0}을 짝지어 쓴다.
     *
     * <p>MUST NOT: 분기가 연속인지 여기서 검사한다고 믿지 않는다 — 세는 것은 <b>저장된 분기
     * 여덟 개</b>이지 달력상 2년이 아니다. 공시가 빠진 종목에서는 두 구간이 겹치지 않을 뿐
     * 값은 나온다. 그 판단은 호출부의 몫이다.
     */
    static BigDecimal sumFourQuarters(List<FinancialFigures> quarters,
                                      Function<FinancialFigures, BigDecimal> field, int offset) {
        if (quarters.size() < offset + TTM_QUARTERS) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = offset; i < offset + TTM_QUARTERS; i++) {
            BigDecimal value = field.apply(quarters.get(i));
            if (value == null) {
                return null;
            }
            sum = sum.add(value);
        }
        return sum;
    }

    /**
     * 재무에서 나온 값 하나를 시세 날짜 전체에 펼친다.
     *
     * <p>분기 공시라 재무는 하루짜리 값이지만, 조건 판정은 "최신 거래일의 지표"를 읽으므로
     * 매 거래일에 행이 있어야 한다. 다음 공시 전까지는 같은 값이 유효하다는 뜻이기도 하다.
     */
    static List<IndicatorPoint> spreadOverTradeDates(List<PriceBar> bars, BigDecimal value) {
        if (value == null || bars.isEmpty()) {
            return List.of();
        }
        List<IndicatorPoint> points = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            points.add(new IndicatorPoint(bar.tradeDate(), value));
        }
        return points;
    }

    /**
     * 그 시점의 주식수. <b>시세 쪽을 우선하고 재무를 폴백으로 쓴다.</b>
     *
     * <p>시세가 우선인 이유는 날짜별 값이기 때문이다 — 유상증자·분할로 주식수가 바뀌면 그날부터
     * 반영되는 반면, 분기 재무는 다음 공시까지 낡은 수를 들고 있다. 그 차이가 분할 당일의
     * PER을 튀게 만들고 그 값은 그럴듯해서 눈치채기 어렵다.
     *
     * <p>재무를 폴백으로 남기는 이유는 소스마다 다르기 때문이다. 국내 시세 API는
     * {@code lstgStCnt}를 주지만, 그것을 주지 않는 소스에서는 재무 쪽만 채워질 수 있다.
     *
     * <p>IMPORTANT — 시세 쪽 값은 {@link IndicatorContext#sharesOn}을 <b>거쳐서</b> 나온다.
     * 그래서 이 메서드가 컨텍스트를 받는다 — 인자로 받지 않으면 호출부마다 보정을 기억해야 하고,
     * 하나만 빠뜨려도 그 지표만 권리락 구간에서 배수만큼 틀린 채 조용히 남는다(ADR-0051).
     * <b>재무 폴백에 {@code sharesOn}을 걸지 않는다</b> — 분기 재무의 주식수는 공시 시점의 값이라
     * 권리락 구간과 무관하고, 거기 곱하면 이미 신주가 반영된 수에 한 번 더 곱하게 된다.
     *
     * <p><b>대신 재무 폴백에는 {@link IndicatorContext#corporateActionMultiple}을 건다(ADR-0096).
     * 그 둘은 다른 함수다</b> — {@code sharesOn}은 권리락~신주상장 <b>구간의 계단</b>이고,
     * 이쪽은 <b>두 시점 사이의 누적 배수</b>다. 재무 주식수는 <b>보고 종료일의 값</b>인데 봉의
     * 종가는 그 뒤 분할을 이미 반영하므로, 보정 없이 곱하면 그 사이 구간이 배수만큼 틀린다.
     *
     * <p><b>실측(NVDA)</b>: 2024-06-10에 종가가 {@code 1,208.88 → 121.79}(10:1 분할)인데 그 시점
     * 최신 재무는 {@code period_end 2024-04-28}의 <b>2,460,000,000</b>(분할 전)이다. 곱하면
     * 시가총액이 <b>정확히 1/10</b>이고 다음 보고(2024-08-28 공시)까지 <b>약 55거래일</b> 그대로다.
     * 결측이 아니라 그럴듯한 숫자라 눈으로는 안 드러난다. 보정하면
     * {@code 2.46B × 10 = 24.6B}이고 실제 다음 보고값이 24.53B다.
     *
     * <p><b>경계의 등호는 {@code corporateActionMultiple}의 것을 그대로 쓴다</b> —
     * {@code from}(보고 종료일) 당일 사건은 빼고 {@code to}(봉) 당일 사건은 넣는다. 그쪽 javadoc이
     * 그 방향을 정한 근거는 {@code sharesOn}과의 짝인데, 여기서는 {@code sharesOn}을 안 곱하므로
     * <b>그 전제가 성립하지 않는다.</b> 보고 종료일 당일에 효력이 생긴 사건은 그 보고서의
     * 주식수에 이미 반영돼 있다고 보는 것이 옳고, 그래서 같은 방향이 맞다 —
     * {@code FinancialSharesSplitTest}가 그 하루를 고정한다.
     *
     * @return 양수인 주식수. 양쪽 모두 없거나 0 이하면 null이다.
     */
    static BigDecimal sharesOf(IndicatorContext context, PriceBar bar,
                               List<FinancialFigures> quarters) {
        if (bar != null && bar.listedShares() != null && bar.listedShares().signum() > 0) {
            return context.sharesOn(bar.tradeDate(), bar.listedShares());
        }
        if (!quarters.isEmpty()) {
            FinancialFigures latest = quarters.get(0);
            BigDecimal fromFinancials = latest.sharesOutstanding();
            if (fromFinancials != null && fromFinancials.signum() > 0) {
                if (bar == null || latest.periodEnd() == null) {
                    // 보정할 두 시점 중 하나가 없다. 보정 없이 내는 것이 값을 안 내는 것보다
                    // 낫다 — 분할이 없는 구간에서는 배수가 1이라 같은 값이다.
                    return fromFinancials;
                }
                return shiftToBar(context, fromFinancials, latest.periodEnd(), bar.tradeDate());
            }
        }
        return null;
    }

    /**
     * 보고 종료일의 주식수를 <b>그 봉 시점의 주식수</b>로 옮긴다.
     *
     * <p><b>양방향이다.</b> 재무 스냅샷 하나를 읽기 창 전체에 펴는 것이 이 프로젝트의 계약이라
     * ({@code batch-load} skill: <i>"재무를 쓰는 지표는 실행 한 번이 읽기 창 전체에 값을 쓴다"</i>)
     * 봉은 보고 종료일보다 <b>앞일 수도 뒤일 수도</b> 있다. 한쪽만 보정하면 반대쪽이 배수만큼
     * 틀린 채 그럴듯하게 남는다.
     *
     * <ul>
     *   <li>봉이 <b>뒤</b>면 그 사이 분할을 <b>곱한다</b> — 보고 이후 늘어난 주식수를 따라간다.
     *   <li>봉이 <b>앞</b>이면 <b>나눈다</b> — 그때는 아직 안 늘어난 주식수여야 한다.
     *       {@link IndicatorContext#corporateActionMultiple} javadoc이 적은
     *       <i>"두 시점의 주식수를 견줄 때 나눠서 쓴다"</i>가 이 방향이다.
     * </ul>
     *
     * <p><b>보정하지 않으면 미국이 양쪽으로 틀린다.</b> NVDA 실측: 2024-06-10 10:1 분할에서
     * 뒤쪽 55거래일은 시가총액이 <b>1/10</b>, 분할 전 봉은 최신 재무(24.5B)를 쓰면 <b>10배</b>다.
     * 국내는 봉의 {@code listed_shares}가 전량 차 있어(실측 NULL 0행) 이 폴백을 <b>타지 않는다.</b>
     */
    private static BigDecimal shiftToBar(IndicatorContext context, BigDecimal reportedShares,
                                         LocalDate periodEnd, LocalDate barDate) {
        if (barDate.isAfter(periodEnd)) {
            return reportedShares.multiply(context.corporateActionMultiple(periodEnd, barDate));
        }
        BigDecimal multiple = context.corporateActionMultiple(barDate, periodEnd);
        if (multiple.signum() <= 0) {
            // 비율이 0 이하인 원장 행은 판정이 거절하지만(CorporateActionLedger.judge),
            // 여기서 나눗셈이 터지면 그 종목의 지표가 통째로 사라진다.
            return reportedShares;
        }
        return reportedShares.divide(multiple, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 그 봉 시점의 <b>주당순이익(TTM)</b>. 값이 없으면 null이다.
     *
     * <p>{@code PER}과 {@code EPS_TTM}이 같은 나눗셈을 하므로 한곳에 둔다. 갈라 쓰면 한쪽만
     * 주식수 출처나 반올림을 바꿔도 <b>두 지표가 서로 어긋난 채 둘 다 그럴듯한 값</b>을 낸다 —
     * 화면에서 EPS × PER이 주가와 안 맞는 형태로만 드러난다.
     *
     * <p>IMPORTANT — <b>부호를 여기서 거르지 않는다.</b> 적자의 뜻이 두 지표에서 다르기 때문이다.
     * 음수 PER은 "PER이 낮은 종목"을 찾는 조건에 적자 기업을 최상위로 올려 뜻을 잃지만,
     * 음수 EPS는 그 자체가 사실이다. 거르는 것은 호출부의 몫이다.
     *
     * @param ttmNetIncome {@link #sumTrailingFourQuarters}로 미리 구한 최근 4분기 순이익 합
     */
    static BigDecimal trailingEpsOn(IndicatorContext context, PriceBar bar,
                                    BigDecimal ttmNetIncome, List<FinancialFigures> quarters) {
        return perShareOn(context, bar, ttmNetIncome, quarters);
    }

    /**
     * 그 날짜에 <b>이미 공시돼 있던</b> 첫 분기의 인덱스. 없으면 {@code -1}이다.
     *
     * <p>시점 판정을 {@code period_end}가 아니라 <b>{@code published_at}</b>으로 하는 것이 요점이다.
     * 기말이 지났어도 공시 전에는 시장이 그 이익을 몰랐고, {@code period_end}로 자르면
     * <b>미래를 알고 계산한 값</b>이 된다. 실측으로 {@code published_at}은 8,796행 전부 채워져 있다.
     *
     * <p><b>합산은 여기서 하지 않는다.</b> 인덱스만 돌려주는 이유는 호출부의 항목이 유량일 수도
     * 저량일 수도 있기 때문이다 — 유량이면 이 자리부터 {@link #sumFourQuarters}로 넷을 더하고,
     * 저량이면 <b>그 한 분기의 잔액</b>을 쓴다. 합산까지 여기서 하면 저량 호출부가
     * <b>잔액을 네 번 더하게 되고 값이 그럴듯해 드러나지 않는다.</b>
     *
     * <p>분기는 {@code period_end} 내림차순으로 온다.
     */
    static int publishedIndexOn(List<FinancialFigures> quarters, LocalDate asOf) {
        for (int i = 0; i < quarters.size(); i++) {
            LocalDate published = quarters.get(i).publishedAt();
            if (published != null && !published.isAfter(asOf)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 그 봉 시점의 <b>주당 금액</b>. {@link #trailingEpsOn}의 일반형이다.
     *
     * <p>주당 배수 지표({@code PER}·{@code PBR}·{@code PSR})가 <b>같은 나눗셈</b>을 하므로
     * 한곳에 둔다. 갈라 쓰면 한쪽만 주식수 출처나 반올림을 바꿔도 <b>지표들이 서로 어긋난 채
     * 전부 그럴듯한 값</b>을 낸다 — 화면에서는 배수끼리 비교가 안 맞는 형태로만 드러난다.
     *
     * <p>IMPORTANT — <b>부호를 여기서 거르지 않는다.</b> 무엇이 음수일 때 뜻이 없는지는 지표마다
     * 다르다. 적자 순이익은 PER을 무의미하게 만들지만 EPS로는 그 자체가 사실이고, 매출은 음수가
     * 원리적으로 없으며 자기자본은 자본잠식에서 음수가 뜻을 갖는다. <b>거르는 것은 호출부의
     * 몫이다.</b>
     *
     * @param amount 유량이면 4분기 합(TTM), 저량이면 그 시점 잔액. <b>어느 쪽인지는 호출부가 안다</b>
     */
    static BigDecimal perShareOn(IndicatorContext context, PriceBar bar,
                                 BigDecimal amount, List<FinancialFigures> quarters) {
        if (amount == null) {
            return null;
        }
        BigDecimal shares = sharesOf(context, bar, quarters);
        if (shares == null) {
            return null;
        }
        return amount.divide(shares, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 백분율로 나눈다. 분모가 0이거나 어느 쪽이든 null이면 null이다.
     *
     * <p>분모가 음수인 경우는 <b>거르지 않는다.</b> 자본잠식 기업의 ROE처럼 음수 분모가
     * 의미를 갖는 경우가 있고, 그 판단은 각 계산기가 자기 맥락에서 한다.
     */
    static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.multiply(HUNDRED)
                .divide(denominator, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 연속한 회계연도로 인정하는 {@code period_end} 간격.
     *
     * <p>1년은 365일 또는 366일이다. 폭을 조금 두는 이유는 결산월 변경으로 한 회계기간이
     * 늘거나 줄 수 있기 때문이며, 폭을 넓히면 <b>한 해 건너뛴 구멍이 통과한다</b> — 그 경우
     * 간격이 730일 안팎이라 아래 상한과 멀찍이 떨어져 있다.
     */
    private static final long MIN_ANNUAL_GAP_DAYS = 300;
    private static final long MAX_ANNUAL_GAP_DAYS = 430;

    /**
     * 최근 {@code years}개 연간 재무가 <b>실제로 연속한 회계연도</b>인가.
     *
     * <p>IMPORTANT — 행 수만 세면 안 되는 이유가 여기 있다. {@code readAnnualFigures}가
     * {@code ORDER BY period_end DESC LIMIT ?}라 <b>행 수만 맞으면 통과하고</b>, FY 행을 일곱
     * 가진 종목이 267이라 중간에 한 해가 빠져도 요청한 개수는 채워진다 — <b>구멍이 보이지
     * 않는다.</b> 그래서 {@code fiscalYear} 라벨이 아니라 {@code periodEnd} 간격으로 잰다.
     * 라벨은 결산월이 12월이 아닌 회사에서 실제 기간과 어긋난다.
     */
    static boolean isConsecutiveAnnual(List<FinancialFigures> annual, int years) {
        if (annual.size() < years) {
            return false;
        }
        for (int i = 1; i < years; i++) {
            // 읽기가 period_end 내림차순이므로 앞이 더 최근이다.
            LocalDate newer = annual.get(i - 1).periodEnd();
            LocalDate older = annual.get(i).periodEnd();
            if (newer == null || older == null) {
                return false;
            }
            long gap = ChronoUnit.DAYS.between(older, newer);
            if (gap < MIN_ANNUAL_GAP_DAYS || gap > MAX_ANNUAL_GAP_DAYS) {
                return false;
            }
        }
        return true;
    }
}
