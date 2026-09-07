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
     * 주당 지표가 쓰는 <b>지배기업 소유주 귀속</b> 순이익의 4분기 합(TTM).
     *
     * <p>K-IFRS 1033이 주당이익을 지배기업 보통주 귀속 이익으로 정의한다. 연결 총액을 쓰면
     * <b>비지배지분의 몫까지 보통주 EPS에 얹혀</b> PER이 낮게 나오고, 그 크기가 종목마다
     * 다르다 — 배당표 전수 대조 512쌍에서 <b>96쌍이 5% 초과, 65쌍이 20% 초과</b>이며
     * 지주회사에서 최대 5.48배다. 그래서 「PER 낮은 종목」 조건의 최상위를 지주회사가 점거한다.
     *
     * <p><b>IMPORTANT — 폴백이 행 단위가 아니라 창 단위인 것이 이 메서드의 요점이다.</b>
     * 네 분기 중 일부만 {@code netIncomeControlling}을 갖고 있을 때 행마다 폴백하면 합계가
     * <b>지배주주와 연결 총액의 혼합</b>이 되어 어느 쪽도 아닌 수가 나온다. 값은 그럴듯하고
     * 에러도 없다. 그래서 <b>넷이 모두 있을 때만</b> 그것으로 합하고, 하나라도 없으면
     * <b>넷 다</b> {@code netIncome}으로 합한다.
     *
     * <p>폴백이 옳은 근거는 <b>비지배지분이 없는 회사에서는 소스가 그 행을 아예 내지
     * 않는다</b>는 것이다(실측: 삼성바이오로직스의 비지배지분이 정확히 0). 그때는 연결 총액이
     * 곧 지배주주 귀속분이다. 다만 <b>「원래 없다」와 「못 받았다」를 NULL 하나로는 가를 수
     * 없으므로</b>, 그 수를 세는 것이 유일한 방어다(ADR §검증의 premise).
     *
     * @param offset {@code 0}이면 최근 TTM, {@code 4}면 직전 TTM. {@link #sumFourQuarters}와 같다
     * @return 4분기 합. 분기가 모자라거나 {@code netIncome}조차 한 분기라도 비면 null이다
     */
    static BigDecimal sumControllingNetIncome(List<FinancialFigures> quarters, int offset) {
        BigDecimal controlling =
                sumFourQuarters(quarters, FinancialFigures::netIncomeControlling, offset);
        if (controlling != null) {
            return controlling;
        }
        return sumFourQuarters(quarters, FinancialFigures::netIncome, offset);
    }

    /** 최근 4개 분기. {@link #sumControllingNetIncome(List, int)}의 {@code offset = 0}이다. */
    static BigDecimal sumControllingNetIncome(List<FinancialFigures> quarters) {
        return sumControllingNetIncome(quarters, 0);
    }

    /**
     * 연간 한 행의 <b>지배기업 소유주 귀속</b> 순이익. 없으면 연결 총액으로 폴백한다.
     *
     * <p>{@code DIVIDEND_PAYOUT}·{@code EXEC_PAY_TO_NET_INCOME}처럼 <b>순이익이 분모</b>인
     * 연간 지표가 쓴다. 배당과 임원보수를 받는 몫이 지배주주의 것이므로 분모도 그쪽이어야
     * 한다 — 실측으로 DART가 계산해 주는 {@code (연결)현금배당성향}이 정확히 이 분모를 쓴다
     * (삼성전자 FY2025: 25.10% 대 우리 24.57%).
     *
     * <p>여기는 행이 하나라 창 단위 문제가 없다. 그래도 같은 파일에 두는 이유는
     * <b>폴백 규칙이 한 곳에 있어야</b> 나중에 한쪽만 바뀌지 않기 때문이다.
     */
    static BigDecimal controllingNetIncomeOf(FinancialFigures annual) {
        if (annual == null) {
            return null;
        }
        return annual.netIncomeControlling() != null
                ? annual.netIncomeControlling()
                : annual.netIncome();
    }

    /**
     * 재무에서 나온 값을 <b>봉마다 그 시점의 판으로</b> 계산해 시계열로 낸다(ADR-0128).
     *
     * <p>값 하나를 창 전체에 펴 바르던 자리를 대신한다. 그쪽은 <b>시간축이 없어</b> 과거 일자
     * 조회가 「그날 아직 공시되지 않은 재무」로 판정하게 만들었다 — 실측(2026-09-07)
     * as-of 2026-03-02에서 활성 1,350종목 중 <b>600(44.4%)</b>이 그 상태였다.
     *
     * <p><b>봉마다 다시 계산하지 않는다.</b> 재무에서 나온 값은 공시가 있을 때만 바뀌므로
     * {@link IndicatorContext#financialPublicationDates()}로 구간을 나눠 <b>구간당 한 번</b>만
     * 부른다. 봉이 오름차순인 것에 기대어 포인터로 훑는다 — 순서가 바뀌면 값이 앞 구간의
     * 것으로 남는데 <b>그럴듯한 숫자라 드러나지 않는다.</b>
     *
     * <p>MUST: {@code valueOf}에 넘기는 것은 {@link IndicatorContext#visibleAt}의 사본이다.
     * 원래 컨텍스트를 넘기면 <b>전 봉이 같은 값을 받아</b> 이 메서드가 이름만 바뀐
     * 「하나를 펴 바르기」가 된다. 컴파일도 되고 값도 그럴듯하다.
     *
     * @param valueOf 그 시점의 재무만 담긴 컨텍스트를 받아 값 하나를 낸다. {@code null}이면
     *                그 구간에는 행을 쓰지 않는다(ADR-0002)
     */
    static List<IndicatorPoint> byPublication(
            IndicatorContext context,
            Function<IndicatorContext, BigDecimal> valueOf) {
        List<PriceBar> bars = context.priceBars();
        if (bars.isEmpty()) {
            return List.of();
        }
        List<LocalDate> cuts = context.financialPublicationDates();
        List<IndicatorPoint> points = new ArrayList<>(bars.size());
        int cut = 0;
        BigDecimal cached = null;
        boolean computed = false;
        for (PriceBar bar : bars) {
            boolean crossed = false;
            while (cut < cuts.size() && !cuts.get(cut).isAfter(bar.tradeDate())) {
                cut++;
                crossed = true;
            }
            if (!computed || crossed) {
                cached = valueOf.apply(context.visibleAt(bar.tradeDate()));
                computed = true;
            }
            if (cached != null) {
                points.add(new IndicatorPoint(bar.tradeDate(), cached));
            }
        }
        return points;
    }

    /**
     * <b>봉 루프를 스스로 도는</b> 계산기를 공시 구간마다 다시 돌린다(ADR-0128).
     *
     * <p>{@link #byPublication}과 겨냥하는 결함은 같고 모양만 다르다. 그쪽은 값 <b>하나</b>를
     * 내는 계산기를 위한 것이고, 이쪽은 <b>이미 봉마다 값을 내지만 재무 합산만 루프 밖에서
     * 한 번</b> 하는 계산기를 위한 것이다 — {@code PER}·{@code PBR}·{@code PSR}·
     * {@code EPS_TTM}이 그 모양이었고, 주식수·종가는 봉마다 읽으면서 <b>분자만 asOf
     * 스냅샷</b>이었다.
     *
     * <p>실측(2026-09-08): {@code 005930}을 asOf 2026-09-04와 2026-06-01로 각각 계산하면
     * {@code PER}이 <b>모든 날짜에서 정확히 1.796배</b>로 갈렸다. <b>배수가 날짜에 무관하게
     * 일정한 것이 「분모가 하나」라는 증거다</b> — 봉마다 정해지면 배수가 날짜마다 달라진다.
     *
     * <p>구간마다 {@code compute}를 <b>전 봉에 대해</b> 부르고 <b>그 구간의 봉만</b> 취한다.
     * 창 안 공시일이 한 자릿수라 비용이 그만큼만 는다. 계산기 본체는 한 줄도 안 바꾼다.
     *
     * <p>MUST: 넘기는 것은 {@link IndicatorContext#visibleAt}의 사본이다. 원래 컨텍스트를
     * 넘기면 <b>이 메서드가 아무 일도 안 한다</b> — 컴파일도 되고 값도 그럴듯하다.
     */
    static List<IndicatorPoint> bySegment(
            IndicatorContext context,
            Function<IndicatorContext, List<IndicatorPoint>> compute) {
        List<PriceBar> bars = context.priceBars();
        if (bars.isEmpty()) {
            return List.of();
        }
        List<LocalDate> cuts = context.financialPublicationDates();
        List<IndicatorPoint> out = new ArrayList<>(bars.size());
        int i = 0;
        int cut = 0;
        while (i < bars.size()) {
            LocalDate from = bars.get(i).tradeDate();
            while (cut < cuts.size() && !cuts.get(cut).isAfter(from)) {
                cut++;
            }
            LocalDate next = cut < cuts.size() ? cuts.get(cut) : null;
            int end = i;
            while (end < bars.size()
                    && (next == null || bars.get(end).tradeDate().isBefore(next))) {
                end++;
            }
            LocalDate to = bars.get(end - 1).tradeDate();
            for (IndicatorPoint point : compute.apply(context.visibleAt(from))) {
                if (!point.tradeDate().isBefore(from) && !point.tradeDate().isAfter(to)) {
                    out.add(point);
                }
            }
            i = end;
        }
        return out;
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
     * <p><b>재무 폴백은 봉마다 그 시점에 공시돼 있던 분기를 고른다</b>({@link #publishedIndexOn}).
     * 「재무 스냅샷 하나를 읽기 창 전체에 편다」는 계약이 <b>주식수에는 걸리지 않는다</b> —
     * 그러면 창 전체가 같은 수를 받고, {@link #shiftToBar}의 배수가
     * {@code SharesGrowth3yCalculator}의 {@code corporateActionMultiple}과 <b>구간 가법성으로
     * 정확히 상쇄</b>되어 {@code SHARES_GROWTH_3Y}가 <b>항등 0</b>이 된다(미국 실측: 편입 1,000
     * 전부가 정확히 0이고 창 안 distinct 값이 하나뿐이었다). 같은 상쇄가
     * {@code EPS_GROWTH_TTM_YOY}·{@code EPS_CAGR_5Y}에서는 주식수를 <b>완전히 약분</b>시켜
     * 「주당」이 아닌 값을 낸다.
     *
     * <p><b>그 봉 시점에 공시된 분기가 없으면 값을 안 낸다.</b> 최신 분기를 되돌려 쓰면
     * <b>미래를 알고 계산한 수</b>가 된다. 비용을 쟀다 — 미국 편입 1,000 중 최신 봉에서 값이
     * 나는 종목은 <b>982</b>(나머지 18은 분기 주식수 자체가 없어 지금도 값이 없다)이고,
     * 3년 전 기준점에서 못 내는 종목이 <b>67</b>, 읽기 창 앞부분을 잃는 종목이 <b>169</b>다.
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
        if (quarters.isEmpty()) {
            return null;
        }
        // MUST: 봉 시점에 **공시돼 있던** 분기를 고른다. quarters.get(0)을 어느 봉에나 쓰면
        // 창 전체가 같은 수를 받고, shiftToBar의 배수가 SharesGrowth3yCalculator의
        // corporateActionMultiple과 구간 가법성으로 정확히 상쇄되어 값이 **항등 0**이 된다.
        // bar가 없으면 시점을 물을 수 없으므로 최신을 쓴다.
        int from = bar == null ? 0 : publishedIndexOn(quarters, bar.tradeDate());
        if (from < 0) {
            // 그 봉 시점에 공시된 분기가 하나도 없다. **값을 지어내지 않는다** —
            // 최신 분기를 되돌려 쓰면 미래를 알고 계산한 수가 된다.
            return null;
        }
        for (int i = from; i < quarters.size(); i++) {
            // 고른 판이 주식수를 안 실었으면 그보다 앞선 판으로 내려간다. 그 시점에 알 수
            // 있었던 가장 최근 수가 답이지, 「그 판이 안 실었으니 없다」가 아니다.
            FinancialFigures asOf = quarters.get(i);
            BigDecimal fromFinancials = asOf.sharesOutstanding();
            if (fromFinancials == null || fromFinancials.signum() <= 0) {
                continue;
            }
            if (bar == null || asOf.periodEnd() == null) {
                // 보정할 두 시점 중 하나가 없다. 보정 없이 내는 것이 값을 안 내는 것보다
                // 낫다 — 분할이 없는 구간에서는 배수가 1이라 같은 값이다.
                return fromFinancials;
            }
            return shiftToBar(context, fromFinancials, asOf.periodEnd(), bar.tradeDate());
        }
        return null;
    }

    /**
     * 보고 종료일의 주식수를 <b>그 봉 시점의 주식수</b>로 옮긴다.
     *
     * <p><b>양방향이지만 이제 한쪽이 거의 안 쓰인다.</b> {@link #sharesOf}가 봉마다 그 시점에
     * 공시돼 있던 분기를 고르므로({@code published_at <= barDate}) 고른 판의 기말은 통상 봉보다
     * <b>앞</b>이고 곱셈 갈래로 간다. 나눗셈 갈래가 남는 것은 <b>기말 전에 공시된 판</b> 때문이며
     * 리더가 그런 행을 <b>5행</b> 세어 두었다. <b>지우지 않는다</b> — 그 5행에서 배수만큼 틀린
     * 값이 조용히 나온다.
     *
     * <p>이것이 ADR-0096의 <b>§결정을 바꾼 자리</b>다. 저쪽은 「스냅샷 하나를 창 전체에 편다」를
     * 전제로 양방향을 세웠는데, 주식수는 더 이상 그 전제 위에 있지 않다.
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
     * 그 기(期)의 <b>영업이익</b>. 소스가 직접 낸 값이 있으면 그것이고, 없으면 <b>영업수익에서
     * 영업비용을 뺀다.</b>
     *
     * <p><b>왜 유도가 필요한가 — 이것은 소스의 부재가 아니라 표시 관행이다.</b>
     * K-IFRS는 은행도 영업이익을 표시해 국내 활성 유니버스의 금융업 <b>50종목이 FY 영업이익을
     * 100% 갖고</b> 그중 23종목이 {@code OPM_MIN_5Y} 값을 낸다. 반면 US-GAAP은 금융업에
     * {@code OperatingIncomeLoss}를 잘 쓰지 않아 미국 금융업 <b>170 중 119(70%)</b>가
     * 판정불가였다. 그대로 두면 <b>같은 이름의 조건이 시장마다 다른 것을 잰다</b>
     * (phase-criteria §P27 축①·②가 금지하는 모양이다).
     *
     * <p><b>유도식은 K-IFRS의 정의를 따른다 — 영업수익 − 영업비용</b>(2026-08-31 사용자 결정).
     * 우선순위가 있고 <b>앞의 것이 이긴다</b>:
     * <ol>
     *   <li>소스가 낸 {@code operatingIncome}
     *   <li><b>은행형</b> — (이자·배당수익 + 비이자수익) − (이자비용 + 비이자비용)
     *   <li><b>보험형</b> — 매출(= 영업수익) − 보험금·손실·비용
     *   <li><b>비금융</b> — 매출총이익 − 영업비용
     *   <li><b>비금융 대안</b> — 매출 − 총원가·비용
     * </ol>
     *
     * <p><b>MUST: 재료가 하나라도 없으면 그 갈래를 건너뛴다.</b> 없는 것을 0으로 두면
     * 이자비용이 0인 은행이 되어 영업이익이 부풀고, <b>그 값은 그럴듯해서 안 드러난다.</b>
     *
     * <p><b>MUST NOT: 이 유도를 적재 계층으로 내리지 않는다.</b> {@code financial_statement}는
     * 원자료만 담고 판단은 지표 계층이 한다({@code FreeCashFlow} javadoc ·
     * phase-criteria §P22 축②). 여덟 개 재료 컬럼이 원자료이고 이 메서드가 판단이다.
     *
     * <p><b>국내는 언제나 첫 갈래로 떨어진다</b> — DART 어댑터가 재료 여덟을 채우지 않으므로
     * (전부 null) 유도가 성립할 수 없고, 애초에 {@code operatingIncome}이 100% 차 있다.
     * 그 불변을 {@code OperatingIncomeDerivationTest}가 잠근다.
     */
    static BigDecimal operatingIncomeOf(FinancialFigures figures) {
        if (figures == null) {
            return null;
        }
        if (figures.operatingIncome() != null) {
            return figures.operatingIncome();
        }
        BigDecimal bank = minus(
                sum(figures.interestAndDividendIncome(), figures.noninterestIncome()),
                sum(figures.interestExpense(), figures.noninterestExpense()));
        if (bank != null) {
            return bank;
        }
        BigDecimal insurance = minus(figures.revenue(), figures.benefitsLossesAndExpenses());
        if (insurance != null) {
            return insurance;
        }
        BigDecimal fromGrossProfit = minus(figures.grossProfit(), figures.operatingExpenses());
        if (fromGrossProfit != null) {
            return fromGrossProfit;
        }
        return minus(figures.revenue(), figures.costsAndExpenses());
    }

    /**
     * 부채총계. <b>없으면 자산총계 − 자기자본으로 유도한다.</b>
     *
     * <p><b>왜 유도가 필요한가</b>: 미국은 {@code us-gaap:Liabilities}가 <b>필수 표시 항목이
     * 아니다.</b> 대조군 60종목(총부채가 비는 종목)에서 그 태그로 해당 기말 값이 잡힌 종목이
     * <b>0</b>이었고 53종목은 태그 자체가 없었다 — 즉 <b>재적재로는 영영 안 채워진다.</b>
     * DECK·NKE·AMZN·T·VZ·WMT·ORCL이 그 무리다. 국내는 DART가 100% 채우므로 이 갈래로
     * 떨어지지 않는다.
     *
     * <p><b>유도가 얼마나 정확한가</b>(대조군 60종목 실측):
     * 자기자본은 비지배지분 포함 태그를 먼저 보므로 <b>NCI 누출이 0</b>이고(그 태그로 값이
     * 잡힌 35종목 전부 저장값과 일치), 남는 오차는 <b>메자닌(임시자본)</b>뿐이다 —
     * 8/60에만 있고 유도 총부채 대비 중앙 0.43% · 최대 1.38%다. 유동+비유동 성분이 둘 다
     * 있는 15종목과 직접 견주면 13/15가 0.1% 이내이고 최대 오차 1.40%(ACN)가 그 종목의
     * 메자닌 1.38%와 일치한다. 방향이 <b>부채 과대</b>라 알트만 점수는 보수적으로 나온다.
     *
     * <p><b>MUST: 네 소비자가 전부 이것을 쓴다</b>({@code ALTMAN_Z} · {@code ALTMAN_Z''} ·
     * {@code NCAV_TO_MARKET} · {@code DEBT_RATIO}). 예전에는 {@code DebtRatioCalculator}만
     * 인라인 사본으로 이 유도를 갖고 있었고, 그래서 <b>한 종목에서 부채비율 카드는 값이
     * 나가는데 알트만 카드 셋은 비었으며 그 이유가 화면에 없었다.</b> 사본을 다시 만들지 않는다.
     */
    static BigDecimal totalLiabilitiesOf(FinancialFigures figures) {
        if (figures == null) {
            return null;
        }
        if (figures.totalLiabilities() != null) {
            return figures.totalLiabilities();
        }
        return minus(figures.totalAssets(), figures.totalEquity());
    }

    /** 하나라도 없으면 null이다. 0으로 대체하면 없는 항목이 「0원이었다」가 된다. */
    private static BigDecimal sum(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.add(b);
    }

    /** 하나라도 없으면 null이다. */
    private static BigDecimal minus(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.subtract(b);
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
