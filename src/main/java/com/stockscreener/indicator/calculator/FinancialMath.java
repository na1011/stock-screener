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
            // MUST: 내려가면서 **공시일을 다시 본다**(ADR-0133). publishedIndexOn은 첫 자리만
            // 고르고 이 루프는 **기말이 더 앞선** 판으로 가는데, 그런 판이 뒤 보고서의 비교열로
            // 실려 **봉보다 늦게 공시**돼 있을 수 있다. 안 걸러내면 그 봉이 미래에 공시된
            // 주식수를 쓴다 — 실측으로 LYFT·HOOD·TWLO·UHAL 넷이 그 자리였고, 봉이 공시일보다
            // 최대 **9개월** 앞이었다.
            if (bar != null && asOf.publishedAt() != null
                    && asOf.publishedAt().isAfter(bar.tradeDate())) {
                continue;
            }
            BigDecimal fromFinancials = asOf.sharesOutstanding();
            if (fromFinancials == null || fromFinancials.signum() <= 0) {
                continue;
            }
            // MUST: 앵커는 **공시일**이다(ADR-0133). 이 수는 보고서 표지 기준이라 기말 이후
            // 공시 전에 일어난 기업행위가 **이미 반영돼 있고**, 기말에서 배수를 걸면 그
            // 구간이 한 번 더 곱해진다. 공시일이 없는 행에서만 기말로 물러선다.
            LocalDate anchor = asOf.publishedAt() != null ? asOf.publishedAt() : asOf.periodEnd();
            if (bar == null || anchor == null) {
                // 보정할 두 시점 중 하나가 없다. 보정 없이 내는 것이 값을 안 내는 것보다
                // 낫다 — 분할이 없는 구간에서는 배수가 1이라 같은 값이다.
                return fromFinancials;
            }
            return shiftToBar(context, fromFinancials, anchor, bar.tradeDate());
        }
        return null;
    }

    /**
     * <b>공시일</b>의 주식수를 그 봉 시점의 주식수로 옮긴다(ADR-0133).
     *
     * <p><b>앵커가 기말이 아니라 공시일인 것이 요점이다.</b> 이 수는 보고서 표지 기준이라
     * 기말 이후 공시 전에 일어난 기업행위가 <b>이미 반영돼 있다</b> — 기말에서 배수를 걸면
     * 그 구간이 한 번 더 곱해진다. 실측(2026-09-09)으로 활성 유니버스에서 그 자리가
     * <b>미국 6종목</b>이었고 배수가 최대 <b>25배</b>였다({@code BKNG} 시가총액 3.7조 달러).
     *
     * <p><b>한 방향뿐이다 — 봉은 앵커보다 앞일 수 없다.</b> {@link #sharesOf}가
     * {@code publishedIndexOn}으로 <b>{@code published_at <= barDate}인 판만</b> 고르고,
     * 그 뒤로 내려가며 고르는 옛 판도 ADR-0132가 컨텍스트를 봉 시점으로 좁혀 같은 성질을
     * 갖는다. <b>MUST: 그 둘 중 하나를 되돌리면 여기에 나눗셈 갈래를 되살린다</b> —
     * 안 되살리면 봉보다 뒤에 공시된 주식수가 그대로 쓰여 배수만큼 틀린다.
     *
     * <p><b>보정 자체를 빼면 미국이 양쪽으로 틀린다.</b> NVDA 실측: 2024-06-10 10:1 분할에서
     * 뒤쪽 55거래일은 시가총액이 <b>1/10</b>이 된다. 국내는 봉의 {@code listed_shares}가 전량
     * 차 있어(실측 NULL 0행) 이 폴백을 <b>타지 않는다.</b>
     */
    private static BigDecimal shiftToBar(IndicatorContext context, BigDecimal reportedShares,
                                         LocalDate anchor, LocalDate barDate) {
        if (barDate.isBefore(anchor)) {
            // 도달할 수 없는 자리다(위 javadoc). 조용히 틀린 값을 내느니 던진다 —
            // 여기 오면 판 선택이 시점성을 잃은 것이고, 그 증상은 값이 배수만큼 틀린 것뿐이다.
            throw new IllegalStateException(
                    "봉(" + barDate + ")이 공시일(" + anchor + ")보다 앞이다 —"
                            + " 판 선택이 published_at <= barDate를 지키지 않았다");
        }
        return reportedShares.multiply(context.corporateActionMultiple(anchor, barDate));
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
    /**
     * 그 기(期)의 <b>유효세율</b>. 클램프하지 않은 관측값이며 {@code EFFECTIVE_TAX_RATE}가 쓴다.
     *
     * <p><b>부호를 항등식으로 판정한다.</b> 법인세비용의 음수가 두 뜻이기 때문이다 —
     * <b>진짜 환입</b>(그 해 세금을 돌려받았다. 순이익 &gt; 세전이익)과 <b>차감 항목 표기</b>
     * (비용인데 음수로 적었다. 순이익 &lt; 세전이익)다. 실측(8종목 × 5년)에서 음수 여섯 중
     * <b>넷이 환입, 둘이 표기</b>였다.
     *
     * <p><b>MUST NOT: 자본적지출처럼 크기(abs)로 읽지 않는다.</b> capex는 「취득」이라 한
     * 방향이지만 환입은 실재하므로, 크기로 읽으면 <b>진짜 환입의 부호가 뒤집힌다.</b>
     * 가르는 것은 {@code 세전이익 − 순이익}과의 부호 대조이고 둘 다 실측값이다 —
     * <b>「순이익에서 역산」이 아니라 부호 검증</b>이다.
     *
     * @return 백분율. 세전이익이 0 이하면 {@code null}이다 — 음수 분모는 부호를 뒤집어
     *         세율이 뜻을 잃는다({@code RoicMin3yCalculator}가 투하자본에서 같은 처방을 쓴다)
     */
    static BigDecimal effectiveTaxRateOf(FinancialFigures figures) {
        if (figures == null) {
            return null;
        }
        BigDecimal tax = figures.incomeTaxExpense();
        BigDecimal net = figures.netIncome();
        if (tax == null) {
            return null;
        }
        if (tax.signum() < 0 && net != null && figures.profitBeforeTax() != null
                && figures.profitBeforeTax().subtract(net).signum() > 0) {
            // 순이익이 세전보다 작은데 법인세가 음수다 — 환입이 아니라 차감 항목 표기다.
            tax = tax.negate();
        }
        BigDecimal pretax = pretaxIncomeOf(figures, tax);
        if (pretax == null || pretax.signum() <= 0) {
            return null;
        }
        return percentage(tax, pretax);
    }

    /**
     * 세전이익. <b>태그가 없으면 {@code 순이익 + 법인세비용}으로 유도한다.</b>
     *
     * <p><b>왜 유도가 필요한가</b>: 미국은 `IncomeTaxExpenseBenefit`은 오는데 세전이익 태그가
     * 안 오는 회사가 실측 <b>237건(FY2020~ 전수의 4.1%)</b>이다. 그 회사들은 세금이 있는데도
     * 세율을 못 내어 세후 지표가 통째로 빠진다.
     *
     * <p><b>유도가 얼마나 정확한가</b>: 항등식 {@code 세전 = 순이익 + 법인세}는 <b>중단영업과
     * 비지배지분이 없는 해에만</b> 정확하다(국내 표본 실측 34/38 = 89%). 그래서 <b>태그가 있으면
     * 언제나 태그가 이긴다</b> — 유도는 태그가 없을 때만 탄다.
     *
     * <p>MUST NOT: 이 유도를 근거로 세전이익 컬럼을 안 받는 쪽으로 되돌리지 않는다.
     * 그 둘은 다른 결정이고, 컬럼을 받는 근거는 ADR-0143에 있다.
     */
    static BigDecimal pretaxIncomeOf(FinancialFigures figures, BigDecimal signAdjustedTax) {
        if (figures == null) {
            return null;
        }
        if (figures.profitBeforeTax() != null) {
            return figures.profitBeforeTax();
        }
        BigDecimal net = figures.netIncome();
        if (net == null || signAdjustedTax == null) {
            return null;
        }
        return net.add(signAdjustedTax);
    }

    /**
     * <b>정규화 유효세율</b> — 최근 {@code years}개 연간에서 구할 수 있는 해의 유효세율을
     * {@code [0, 1]}로 자른 뒤 <b>중앙값</b>을 낸다. 비율이지 백분율이 아니다.
     *
     * <h2>왜 「그 해 세율」이 아니라 정규화인가 — 재설계의 중심이다</h2>
     *
     * <p>처음에는 각 해의 관측 세율을 그 해에 그대로 썼고, 세전이익이 0 이하인 해는 값을 내지
     * 않았다. <b>그 설계가 두 가지로 틀렸다</b>(2026-09-14 실측).
     *
     * <ul>
     *   <li><b>멀쩡한 기업이 원천 배제된다.</b> 미국 최신 지표일에서 세전 ROIC는 나오는데 세후가
     *       안 나오는 종목이 <b>139</b>였고 그중 <b>113이 「세전 ≤ 0」</b>이다 — <b>영업은
     *       흑자인데</b> 영업외손실로 세전이 적자인 해다. 5년 계열은 더 심해서, 값이 없는
     *       271종목 중 <b>198이 세전 적자 해를 가졌고 그중 69는 딱 한 해</b>였다.
     *       같은 저장소의 {@code OPM_MIN_5Y}는 적자 해가 있어도 값을 낸다 — <b>대칭이 깨져
     *       있었다.</b></li>
     *   <li><b>자기모순이었다.</b> {@code t < 0}과 {@code t > 1}은 클램프해서 값을 내면서
     *       {@code 세전 ≤ 0}만 배제했다. 셋 다 「그 해 t를 믿을 수 없다」인데 처리가 달랐다.</li>
     * </ul>
     *
     * <p><b>그리고 TTM이 한 해에 매달려 있었다.</b> 직전 회계연도 세율이 35% 이상인 미국 종목이
     * <b>57</b>, 0인 종목이 <b>37</b>인데, 5년 중앙값으로 바꾸면 각각 <b>35 · 20</b>이 된다.
     * 정규화는 커버리지를 늘리면서 <b>왜곡을 함께 줄인다</b>(실측 회복: KOSPI 104 → 162 ·
     * KOSDAQ 60 → 116 · US 476 → 639).
     *
     * <p><b>중앙값인 이유</b>는 {@code PerMedian5yCalculator}가 적은 것과 같다 — 평균은 한 점에
     * 끌려가고, 세율에는 일회성 환입·소급 과세 같은 한 점이 실제로 있다.
     *
     * <p><b>MUST NOT: 이것을 「없는 값을 지어내는 것」으로 읽지 않는다.</b> 전부 <b>그 회사의
     * 관측값</b>이고, 정규화가 고르는 것은 <b>어느 해의 관측을 쓰느냐</b>다. NOPAT이 재는 것이
     * 「이 영업이 지속되면 세후로 얼마가 남는가」이므로 <b>지속되는 세율</b>을 쓰는 것이 정의에
     * 더 맞다. 한 해도 못 구하면 {@code null}이고 그때는 값을 내지 않는다.
     */
    static BigDecimal normalizedTaxRate(List<FinancialFigures> annual, int years) {
        if (annual == null || annual.isEmpty()) {
            return null;
        }
        List<BigDecimal> rates = new ArrayList<>();
        int limit = Math.min(years, annual.size());
        for (int i = 0; i < limit; i++) {
            BigDecimal ratio = clampedTaxRateOf(annual.get(i));
            if (ratio != null) {
                rates.add(ratio);
            }
        }
        if (rates.isEmpty()) {
            return null;
        }
        rates.sort(BigDecimal::compareTo);
        int mid = rates.size() / 2;
        if (rates.size() % 2 == 1) {
            return rates.get(mid);
        }
        return rates.get(mid - 1).add(rates.get(mid))
                .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
    }

    /** 한 해의 유효세율을 비율로, {@code [0, 1]}로 잘라서. 못 구하면 {@code null}. */
    private static BigDecimal clampedTaxRateOf(FinancialFigures figures) {
        BigDecimal percent = effectiveTaxRateOf(figures);
        if (percent == null) {
            return null;
        }
        BigDecimal ratio = percent.divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        if (ratio.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return ratio.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : ratio;
    }

    /**
     * NOPAT에 쓰는 유효세율 <b>비율</b>(백분율이 아니다). <b>정의역을 {@code [0, 1]}로 자른다.</b>
     *
     * <p>둘을 자르는 이유가 다르다.
     * <ul>
     *   <li><b>{@code t > 1} → 1</b> — {@code (1 − t) < 0}이면 <b>영업흑자가 세후 적자로
     *       뒤집힌다.</b> 부호 보존이다.
     *   <li><b>{@code t < 0} → 0</b> — 뜻의 문제다. NOPAT이 재는 것은 <b>「이 영업이 지속되면
     *       세후로 얼마가 남는가」</b>인데, 환입의 원인(이월결손금 인식·평가충당금 환입·
     *       세무조사 종결)은 <b>과거 사건의 회계적 인식이고 영업과 무관하며 지속되지 않는다.</b>
     *       그대로 두면 같은 지표가 어떤 해는 영업의 수익성을, 어떤 해는 그것에 과거 환입을
     *       더한 것을 재게 되어 <b>값의 뜻이 해마다 흔들린다.</b>
     * </ul>
     *
     * <p><b>MUST NOT: 이 클램프를 「없는 값을 만드는 것」으로 읽지 않는다.</b>
     * {@code t ∈ [0, 1]}은 NOPAT 공식의 <b>전제</b>다 — 「세금은 이익의 일부를 가져간다」.
     * 관측값 자체는 {@link #effectiveTaxRateOf}가 클램프 없이 그대로 낸다.
     */
    static BigDecimal taxRateForNopat(FinancialFigures figures) {
        return clampedTaxRateOf(figures);
    }

    /**
     * 세후 영업이익(NOPAT) = {@code 영업이익 × (1 − 유효세율)}.
     *
     * @return 영업이익이나 세율을 못 내면 {@code null}
     */
    static BigDecimal nopatOf(FinancialFigures figures) {
        return nopatOf(figures, taxRateForNopat(figures));
    }

    /**
     * 세후 영업이익(NOPAT) = {@code 영업이익 × (1 − 세율)}. <b>세율을 밖에서 받는다.</b>
     *
     * <p>호출부가 {@link #normalizedTaxRate}를 넘기는 것이 기본이다 — 그래야 <b>세전이익이
     * 0 이하인 해에도 그 회사의 통상 세율로 세후 영업이익이 나온다.</b> 그 해에 없는 것은
     * 세율이지 영업이익이 아니다.
     */
    static BigDecimal nopatOf(FinancialFigures figures, BigDecimal rate) {
        BigDecimal operatingIncome = operatingIncomeOf(figures);
        if (operatingIncome == null || rate == null) {
            return null;
        }
        return operatingIncome.multiply(BigDecimal.ONE.subtract(rate));
    }

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
