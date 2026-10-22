package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.ShareBasis;
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

    /**
     * <b>{@code ROE} 계열의 분자·분모 짝.</b> 기저를 <b>한 번 정해 둘 다에</b> 적용한다.
     *
     * <p><b>왜 짝인가</b>: 분자가 <b>창 단위</b>(전부-아니면-전무)이고 분모가 <b>행 단위</b>
     * ({@code controlling ?: total})이면 <b>둘이 서로를 모른다.</b> 그 상태에서 한쪽만 채우면
     * <b>분자=연결총액 ÷ 분모=지배주주자본</b>이 된다 — 실측으로 미국
     * {@code total_equity / total_equity_controlling}이 <b>최대 616.54배</b>,
     * 국내 {@code 034730} SK가 <b>3.197배</b>다(ADR-0159).
     *
     * <p><b>3단이고, 오늘은 1순위가 원리적으로 안 켜져 값이 한 톨도 안 바뀐다</b> —
     * {@code net_income_controlling}이 미국에 <b>0행</b>이기 때문이다. 적재가 그 컬럼을 채우는
     * 순간 1순위로 옮겨 가고, 그때까지 2·3순위가 <b>지금의 동작을 그대로</b> 재현한다.
     * <b>이것이 「무해한 선행 배포」다</b>(CLAUDE.md §판단 기준).
     *
     * <ol>
     *   <li><b>지배주주 짝</b> — 창 전체의 {@code netIncomeControlling}과
     *       {@code totalEquityControlling}이 <b>둘 다</b> 있을 때만.</li>
     *   <li><b>연결 짝</b> — 지배주주 짝이 안 서면 분자·분모 <b>둘 다</b> 연결로 물러난다.</li>
     * </ol>
     *
     * <p><b>「다리」 갈래를 2026-09-21에 걷었다.</b> 그것은 <i>"{@code netIncome}이 그 시장에서
     * 이미 지배주주 기저인 동안"</i>을 전제했는데 <b>코드에 그 조건이 없었다</b> — 술어가
     * {@code controllingEquity != null} 하나뿐이라, 국내 {@code total_equity_controlling}을
     * 채우는 순간 <b>연결 TTM ÷ 지배주주 자본</b>이 된다. 실측(2026-09-21): 최신 4분기 완비 창
     * 기준 {@code nic}이 0칸인 종목이 <b>KOSPI 8 + KOSDAQ 26 = 34</b>다. ADR-0159 §규칙이
     * {@code 034730} SK <b>3.197배</b>로 금지한 짝이고, ADR-0153 §결정도 같은 것을 적는다 —
     * <i>"DART에 지배주주 자본을 매핑하는 날 국내는 <b>분자도 함께</b> 지배주주로 바꿔야 한다."</i>
     *
     * <p><b>MUST NOT: 행마다 폴백하지 않는다.</b> {@link #sumControllingContinuingIncome}의 javadoc이
     * 이미 적었다 — <i>"행마다 폴백하면 합계가 어느 쪽도 아닌 수가 되고, 값은 그럴듯하고
     * 에러도 없다."</i> 엄격 짝으로 잃는 창은 실측 <b>0.9%p</b>뿐이다(US TTM 49,240 중 93.5%가 성립).
     *
     * <p><b>MUST NOT: {@code DEBT_RATIO}·{@code ALTMAN_Z''}·{@code ROIC_PRETAX}에 이것을 쓰지
     * 않는다</b> — 그쪽은 분모가 총자산·총부채와 짝이라 <b>연결 총자본이 옳다.</b>
     * <b>{@code PBR}은 반대다</b> — 분자가 시가총액이라 <b>지배주주 자본이 옳고</b>,
     * ADR-0153이 그것을 이 묶음에 넣은 것은 틀렸다(ADR-0159 §규칙).
     */
    record EquityBasis(BigDecimal income, BigDecimal equity) {
    }

    /**
     * 최근 4개 분기(TTM)의 짝. {@code quarters}는 {@code period_end} 내림차순이다.
     *
     * <p><b>분자는 계속영업이다</b>(F2b · ADR-0184) — 중단영업(매각 차익 포함)은 이어지지 않는 손익이다. 지배 계속영업을 원장이
     * 못 정해 주는 창(연결 중단영업은 있는데 지배 몫이 없다)은 <b>연결 짝</b>(연결 계속영업 ↔ 총자본)으로 간다 — 분자와 분모가
     * 같은 기저라 정당하다. 지배 분자에 연결 분모를 섞지 않는다(ADR-0153).
     */
    static EquityBasis roeBasis(List<FinancialFigures> quarters) {
        if (quarters.isEmpty()) {
            return null;
        }
        FinancialFigures latest = quarters.get(0);
        BigDecimal controllingEquity = latest.totalEquityControlling();
        BigDecimal controllingIncome =
                sumFourQuarters(quarters, FinancialFigures::netIncomeControlling, 0);
        if (controllingIncome != null && controllingEquity != null) {
            BigDecimal continuing =
                    sumFourQuarters(quarters, FinancialFigures::continuingIncomeControlling, 0);
            if (continuing != null) {
                return new EquityBasis(continuing, controllingEquity);
            }
        }
        BigDecimal income = sumTrailingFourQuarters(quarters, FinancialFigures::continuingIncome);
        return new EquityBasis(income, latest.totalEquity());
    }

    /** 연간 한 행의 짝. {@code ROE_MIN_3Y}가 해마다 부른다. 계속영업 · 연결 짝 폴백은 분기 짝과 같다. */
    static EquityBasis roeBasis(FinancialFigures figures) {
        BigDecimal controllingEquity = figures.totalEquityControlling();
        BigDecimal controllingIncome = figures.continuingIncomeControlling();
        if (figures.netIncomeControlling() != null && controllingIncome != null && controllingEquity != null) {
            return new EquityBasis(controllingIncome, controllingEquity);
        }
        return new EquityBasis(figures.continuingIncome(), figures.totalEquity());
    }

    /**
     * <b>상장주식 보유자에게 귀속되는 자본.</b> 지배주주 자본이 있으면 그것, 없으면 총자본이다.
     *
     * <p><b>{@code PBR}의 분모가 이것이어야 한다</b> — 분자인 <b>시가총액은 상장주식에만</b>
     * 값을 매기므로, 총자본을 쓰면 <b>비지배지분이 분모에만 들어간다.</b> 시가총액과 연결자본
     * 사이에는 <b>어떤 회계 항등식도 없다</b>(ADR-0159).
     *
     * <p>실측(2026-09-15 · 미국 최신 분기 962종목): 지배주주자본 &lt; 총자본 × 0.9 인 종목 <b>76</b> ·
     * × 0.5 미만 <b>21</b>. {@code IBKR} {@code PBR} <b>1.83 → 6.91</b> · {@code TPG} 4.94 → 14.96 ·
     * {@code KKR} 1.19 → 2.96. {@code MAS}는 지배주주자본이 <b>음수</b>인데 총자본이 양수라
     * <b>{@code PBR} 518.61</b>을 내고 있었다. <b>{@code 저평가 가치주}가 {@code PBR}·
     * {@code PBR_PCTL}을 읽는다.</b>
     *
     * <p><b>MUST NOT: {@code DEBT_RATIO}·{@code ALTMAN_Z''}·{@code ROIC_PRETAX}에 쓰지 않는다</b> —
     * 그쪽은 <b>양변이 다 연결 재무상태표</b>라 항등식이 서고, 총자본이 옳다.
     */
    static BigDecimal equityAttributableToShareholders(FinancialFigures figures) {
        BigDecimal controlling = figures.totalEquityControlling();
        return controlling != null ? controlling : figures.totalEquity();
    }

    /**
     * <b>이 창 전체에서 지배주주 자본이 서는가.</b> 집계값(중앙값·평균)은 <b>기저를 한 번 정해
     * 창 전체에 적용해야 한다.</b>
     *
     * <p><b>왜</b>: {@code total_equity_controlling}의 유무는 <b>회사의 성질이 아니라 그 판의
     * 성질</b>이라 분기마다 뒤집힌다. 실측(2026-09-15 · {@code US_TOP1000} 활성 982종목 ·
     * 최근 20분기): <b>유무가 섞인 종목 74</b> · 전 분기 있음 884 · 전 분기 없음 24.
     * 그중 {@code teqc/te} 괴리가 5%를 넘는 것이 <b>12종목</b>이고
     * {@code LNG}는 <b>7,690.91%</b>다. 행마다 고르면 <b>한 중앙값 안에서 두 기저가 섞이고
     * 그 값은 그럴듯하다.</b>
     *
     * <p>적재가 캐스케이드로 {@code teqc}를 거의 전 행에 채우면 이 갈래는 저절로 닫힌다
     * (ADR-0159). 그때까지 <b>섞느니 연결로 통일한다.</b>
     */
    static boolean controllingEquityThroughout(List<FinancialFigures> quarters) {
        if (quarters.isEmpty()) {
            return false;
        }
        for (FinancialFigures figures : quarters) {
            if (figures.totalEquityControlling() == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * <b>분자와 기저가 맞는 자본 하나.</b> {@link #roeBasis}의 2·3순위가 고르는 것과 같다 —
     * 짝을 함께 고를 수 없는 자리에서만 쓴다.
     */
    public static BigDecimal equityMatchingNetIncome(FinancialFigures figures) {
        return equityAttributableToShareholders(figures);
    }

    static final int SCALE = 8;
    static final int TTM_QUARTERS = 4;
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private FinancialMath() {
    }

    /**
     * 유량 12개월 창을 만드는 <b>기간 계열</b>. 둘뿐이고 <b>창 단위로 한 번</b> 고른다.
     *
     * <p>왜 둘인가는 ADR-0164 §배경에 있다 — 활성 편입 중 **US 45 · KR 1**이 최신 4분기로
     * 연속 12개월을 못 덮고, 그중 11종목은 <b>원장에 분기 손익이 0건</b>이다.
     */
    enum PeriodBasis {

        /** 연속 네 분기의 합. 기(期) 하나가 한 분기이고 12개월은 넷이다. */
        QUARTERLY,

        /** 그 회계연도 한 행. 기(期) 하나가 한 해이고 12개월은 하나다. */
        ANNUAL
    }

    /**
     * 기저와 그 계열의 행. <b>행은 {@code period_end} 내림차순</b>이고 두 기저가 같은 인덱스
     * 규약을 쓴다 — 인덱스는 <b>기(期)</b>이지 분기가 아니다.
     *
     * <p><b>MUST NOT: 두 목록을 한 리스트로 합치지 않는다.</b> {@code IndicatorContext}의 접는
     * 키가 {@code periodEnd}이고 FY와 유도 Q4는 언제나 그것을 공유하므로(ADR-0124 §검증),
     * 합치면 정상 종목에서 둘이 한 칸으로 접혀 TTM이 세 분기 합이 된다.
     *
     * <p><b>MUST NOT: 한 계산 안에서 기저를 바꾸지 않는다.</b> {@code EPS_CAGR_5Y}처럼 두 시점을
     * 견주는 지표가 한쪽만 다른 기저로 채우면 <b>증감률이 기저 차이를 재게 되고 값은 그럴듯하다.</b>
     * 그래서 {@link #ttm}은 요청한 기(期)를 이 기저로 못 내면 {@code null}이다.
     */
    record PeriodSeries(PeriodBasis basis, List<FinancialFigures> rows,
                       List<FinancialFigures> pointRows) {

        /**
         * <b>그 시점 잔액·주식수를 읽을 판들</b> — 두 목록을 {@code period_end} 내림차순으로
         * 합친 것이고 <b>유량 기저와 무관하다.</b>
         *
         * <p>IMPORTANT — 유량과 갈라 두는 이유가 실측에 있다. 2025년 신규 상장처럼 분기가
         * 비연속인 종목은 유량 기저가 연간이 되는데, 그 FY 행은 <b>최신 분기보다 오래됐다.</b>
         * 잔액까지 연간으로 읽으면 {@code MARKET_CAP}·{@code PBR}·{@code TURNOVER_RATIO}가
         * <b>있던 자리에서 사라진다</b> — 0콜 스윕에서 {@code HNGE}·{@code CHYM} 두 종목이
         * 70칸을 잃었다(2026-09-28). 잔액은 그 시점의 값이라 앞뒤가 이어지지 않아도 참이므로,
         * 여기서는 <b>기저를 묻지 않고 가장 최근 판</b>을 쓴다.
         *
         * <p>합쳐도 안전한 이유: FY와 그 유도 Q4는 {@code period_end}를 공유하는데 유도가
         * <b>잔액·주식수를 FY에서 그대로 복사</b>하므로(ADR-0151) 어느 쪽을 골라도 같은 값이다.
         * <b>MUST NOT: 이 합친 목록으로 유량을 더하지 않는다</b> — 그때는 FY와 Q4가 이중 계산된다.
         */
        @Override
        public List<FinancialFigures> pointRows() {
            return pointRows;
        }

        /** 12개월이 몇 기(期)인가. */
        int periodsPerYear() {
            return basis == PeriodBasis.QUARTERLY ? TTM_QUARTERS : 1;
        }

        /**
         * {@code offset}기(期)부터의 <b>12개월 유량 합</b>. 재료가 모자라면 null이다.
         *
         * <p>분기 기저는 {@link FinancialMath#sumFourQuarters}가 연속까지 본다. 연간 기저는
         * <b>그 해와 요청 지점 사이가 연속인지</b>를 본다 — 안 보면 「5년 전」이 실제로 6년 전이
         * 되고 {@code EPS_CAGR_5Y}가 조용히 다른 기간을 잰다.
         */
        BigDecimal ttm(Function<FinancialFigures, BigDecimal> field, int offset) {
            if (basis == PeriodBasis.QUARTERLY) {
                return sumFourQuarters(rows, field, offset);
            }
            return isConsecutiveAnnual(rows, offset + 1) ? field.apply(rows.get(offset)) : null;
        }

        /**
         * {@code offset}기(期)의 <b>잔액을 읽을 한 행</b>. 없으면 null이다.
         *
         * <p>연속을 안 묻는다 — 잔액은 그 시점의 값이라 앞뒤가 이어지지 않아도 참이다.
         * 유량과 갈라 두는 이유가 그것이고, 합치면 저량 호출부가 <b>잔액을 네 번 더한다.</b>
         */
        FinancialFigures balanceAt(int offset) {
            return offset >= 0 && offset < rows.size() ? rows.get(offset) : null;
        }

        /** 잔액·주식수를 읽을 <b>가장 최근 판</b>. 없으면 null이다. */
        FinancialFigures latestPoint() {
            return pointRows.isEmpty() ? null : pointRows.get(0);
        }

        /**
         * 그 기(期)의 <b>지배주주 귀속 12개월 계속영업 순이익</b>. 주당 지표(PER · EPS 계열)의 분자다. 창 단위 폴백은 기저마다
         * 다르다 — {@link #sumControllingContinuingIncome} · {@link #controllingContinuingIncomeOf}.
         */
        BigDecimal controllingTtm(int offset) {
            if (basis == PeriodBasis.QUARTERLY) {
                return sumControllingContinuingIncome(rows, offset);
            }
            FinancialFigures row = isConsecutiveAnnual(rows, offset + 1) ? rows.get(offset) : null;
            return row == null ? null : controllingContinuingIncomeOf(row);
        }

        boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /**
     * 이 종목의 기간 계열. <b>연속 네 분기가 서면 분기, 아니면 FY 계열이다.</b>
     *
     * <p><b>MUST: 방아쇠를 「분기가 0행일 때」로 두지 않는다.</b> 적재가 분기를 한두 행 만들면
     * 그 방아쇠는 꺼지는데 네 분기 조건도 못 채워 <b>값이 오히려 사라진다</b> — 실측으로
     * {@code 6-K}를 받으면 {@code AER BNT CAMT DSGX TSEM} 다섯이 정확히 그 자리다(ADR-0164).
     *
     * <p><b>MUST NOT: null을 내지 않는다.</b> 재무가 아예 없어도 빈 분기 계열을 돌려준다 —
     * 호출부 19곳에 null 검사를 퍼뜨리면 하나만 빠뜨려도 {@code NullPointerException}이
     * 「값이 없는 지표」가 아니라 <b>배치 실패</b>로 나타난다. 빈 계열의
     * {@link PeriodSeries#ttm}은 null이고 {@link PeriodSeries#balanceAt}도 null이라
     * 각 계산기의 기존 null 검사가 그대로 답한다.
     */
    static PeriodSeries seriesOf(IndicatorContext context) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        List<FinancialFigures> annual = context.annualFigures();
        List<FinancialFigures> points = mergeByPeriodEndDesc(quarters, annual);
        if (isConsecutiveQuarterly(quarters, 0)) {
            return new PeriodSeries(PeriodBasis.QUARTERLY, quarters, points);
        }
        if (!annual.isEmpty()) {
            return new PeriodSeries(PeriodBasis.ANNUAL, annual, points);
        }
        return new PeriodSeries(PeriodBasis.QUARTERLY, quarters, points);
    }

    /**
     * 두 목록을 {@code period_end} 내림차순으로 합친다. 같은 기말은 <b>분기 쪽을 앞에</b> 둔다.
     *
     * <p>순서만 만든다 — 중복을 지우지 않는 이유는 {@link #sharesOf}가 목록을 내려가며
     * <b>공시일과 주식수 유무를 판마다 다시 보기</b> 때문이고, 한쪽이 그 봉에 못 쓰이면
     * 다른 쪽이 답이 되는 것이 옳다.
     */
    private static List<FinancialFigures> mergeByPeriodEndDesc(
            List<FinancialFigures> quarters, List<FinancialFigures> annual) {
        if (annual.isEmpty()) {
            return quarters;
        }
        if (quarters.isEmpty()) {
            return annual;
        }
        List<FinancialFigures> merged = new java.util.ArrayList<>(quarters.size() + annual.size());
        merged.addAll(quarters);
        merged.addAll(annual);
        merged.sort(java.util.Comparator.comparing(
                FinancialFigures::periodEnd,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return java.util.Collections.unmodifiableList(merged);
    }

    /**
     * 기간 계열에 맞는 <b>순이익·자본의 짝</b>. 기저마다 다른 판을 고른다.
     *
     * <p>분기 기저는 네 분기 합과 최신 분기 잔액을, 연간 기저는 <b>그 FY 한 행의 둘</b>을 쓴다 —
     * 둘을 섞으면 분자와 분모의 기간이 갈리고 그 비율은 그럴듯하다.
     */
    static EquityBasis roeBasis(PeriodSeries series) {
        if (series.isEmpty()) {
            return null;
        }
        if (series.basis() == PeriodBasis.QUARTERLY) {
            return roeBasis(series.rows());
        }
        FinancialFigures annual = series.balanceAt(0);
        return annual == null ? null : roeBasis(annual);
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
     * <p><b>IMPORTANT — 그 넷이 연속인지 여기서 검사한다</b>({@link #isConsecutiveQuarterly}).
     * 세는 것이 <b>저장된 행 수</b>이지 달력상 1년이 아니어서, 예전에는 공시가 빠진 종목에서
     * <b>구멍 난 합이 그럴듯한 값으로 나왔다.</b> 2026-09-27 실측: 현 편입 미국 12종목이 그
     * 상태였고 {@code DOX}는 최신 분기 행이 <b>매년 Q4 하나씩(2019~2024)</b>이라 서로 다른 네
     * 해의 Q4를 더해 {@code PSR} 1.37을 냈다. 최신일에 그런 칸이 <b>153개</b>였다.
     *
     * <p>MUST NOT: 이 검사를 호출부로 되돌리지 않는다. 호출부가 11곳인데 <b>어느 하나도 하지
     * 않았고</b>, 하나만 빠뜨려도 그 지표가 조용히 틀린다. 값이 나오지 않는 것으로 바뀌는 것이
     * 이 변경의 의도다 — 「없는 값」은 사용자가 사유를 물을 수 있지만 틀린 값은 모르고 쓴다.
     */
    static BigDecimal sumFourQuarters(List<FinancialFigures> quarters,
                                      Function<FinancialFigures, BigDecimal> field, int offset) {
        if (!isConsecutiveQuarterly(quarters, offset)) {
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
     * 주당 지표가 쓰는 <b>지배주주 귀속 계속영업</b> 순이익의 4분기 합(TTM · F2b · ADR-0184).
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
     * <b>넷 다</b> 연결 계속영업({@code continuingIncome})으로 합한다.
     *
     * <p>폴백이 옳은 근거는 <b>비지배지분이 없는 회사에서는 소스가 그 행을 아예 내지
     * 않는다</b>는 것이다(실측: 삼성바이오로직스의 비지배지분이 정확히 0). 그때는 연결 총액이
     * 곧 지배주주 귀속분이다. 다만 <b>「원래 없다」와 「못 받았다」를 NULL 하나로는 가를 수
     * 없으므로</b>, 그 수를 세는 것이 유일한 방어다(ADR §검증의 premise).
     *
     * <p><b>[F2b] 지배 계열인데 한 분기라도 지배 계속영업이 미정이면 {@code null}</b>(미표시)이다.
     *
     * <p><b>MUST NOT: 그 창을 연결 계속영업으로 떨어뜨리지 않는다.</b> 지배 몫이 미정인 창은 비지배가 있는 회사에서만 생기므로
     * (비지배가 없으면 어댑터의 「비지배 없음」 갈래가 이미 정한다) 「연결 = 지배」라는 연결 폴백의 근거가 그 집합에 안 선다 —
     * 반증 실측으로 SK H1'26 3.1배 · 두산 +64% · QSR PER 분자 +30%였다(탐색 {@code F2B-PLAN-1023.md} 1c). 「지금 값(중단 포함)
     * 유지」도 아니다 — 그 오차가 연결 중단영업 안이라는 보장이 2,996칸 중 89.5%에서만 선다.
     */
    static BigDecimal sumControllingContinuingIncome(List<FinancialFigures> quarters, int offset) {
        BigDecimal controlling =
                sumFourQuarters(quarters, FinancialFigures::netIncomeControlling, offset);
        if (controlling != null) {
            return sumFourQuarters(quarters, FinancialFigures::continuingIncomeControlling, offset);
        }
        return sumFourQuarters(quarters, FinancialFigures::continuingIncome, offset);
    }

    /** 연간 한 행의 <b>지배주주 귀속 계속영업</b> 순이익. 갈래는 {@link #sumControllingContinuingIncome}과 같다. */
    static BigDecimal controllingContinuingIncomeOf(FinancialFigures annual) {
        if (annual == null) {
            return null;
        }
        return annual.netIncomeControlling() != null
                ? annual.continuingIncomeControlling()
                : annual.continuingIncome();
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
    /**
     * <b>[2026-09-21] ADR-0159 §결정은 이 폴백을 지표 계층에서 걷으라고 한다 — 아직 안 걷었다.</b>
     * <i>"MUST: 기저 판정을 적재 계층에 둔다. 지표 계층에 폴백을 남기지 않는다."</i>
     *
     * <p><b>걷기 전에 배제되는 집합을 열었고, 지금 걷으면 안 된다는 답이 나왔다.</b>
     * {@code net_income_controlling}이 비어 이 폴백이 도는 행(최신 판 기준):
     * <b>KOSDAQ 분기 69.6% · FY 51.6% · KOSPI 분기 55.7% · FY 26.3%</b>(미국은 94~97%이지만
     * 그쪽은 적재가 거의 다 채운다 — 스윕 85,441칸). 지금 걷으면 국내 분기의 절반 넘는 창에서
     * {@code PER}·{@code EPS_TTM}·{@code DIVIDEND_PAYOUT}·{@code EXEC_PAY_TO_NET_INCOME}이
     * <b>통째로 죽는다.</b>
     *
     * <p><b>[2026-09-22] 적재 뒤에 다시 쟀고, 「걷지 않는다」로 판정했다.</b>
     * 모집단은 <b>편입 1,350 · {@code period_end >= 2024-01-01} · {@code is_latest}</b>다
     * (앞 문단의 수는 이 좁힘이 없어 미국에 {@code US_LISTED} 5,128종목이 섞여 있었다 —
     * 그 분모로는 미국이 72.8%로 보인다).
     *
     * <table><caption>폴백이 도는 비율</caption>
     * <tr><th>시장</th><th>FY</th><th>분기</th></tr>
     * <tr><td>US</td><td>0.0%</td><td>0.5%</td></tr>
     * <tr><td>KOSPI</td><td>6.0%</td><td>8.2%</td></tr>
     * <tr><td>KOSDAQ</td><td>24.0%</td><td>25.7%</td></tr>
     * </table>
     *
     * <p><b>기준(한 자릿수)에 KOSDAQ만 걸린다. 그런데 걷으면 순손실이다</b> — 배제되는 집합을
     * 열었더니 폴백이 도는 <b>678행</b>이 셋으로 갈린다.
     * <ul>
     *   <li><b>비지배 자본이 0인 256행</b> — 연결 총액 = 지배주주다. 폴백이 <b>정확한 값</b>을
     *       낸다. 걷으면 맞는 값이 사라진다.</li>
     *   <li><b>{@code total_equity_controlling}도 없는 337행</b> — 판정할 재료가 없다.
     *       원장이 그 개념을 안 주는 쪽(단독 재무제표)일 가능성이 크지만 <b>안 물어봤다.</b></li>
     *   <li><b>비지배가 실재하는 85행</b> — 여기서만 폴백이 틀린 값을 낸다.</li>
     * </ul>
     *
     * <p><b>MUST: 폴백을 걷기 전에 국내 적재에 비지배 전무 게이트를 넣는다.</b> 미국
     * {@code SecEdgarFacts.consolidatedIncome}이 이미 그 모양이고(ADR-0159 §결정은 두 시장
     * 공통이다), {@code DartFullAccountMapper}에 {@code ID_NET_INCOME_NONCONTROLLING}이 이미
     * 있다. 게이트가 256행의 {@code net_income_controlling}을 <b>실제로 채우면</b> 폴백이 그
     * 자리에서 안 돌고, 그때 남는 것으로 다시 판정한다.
     *
     * <p><b>MUST NOT: 「한 자릿수」만 보고 걷지 않는다 — 그 수는 어느 집합이 죽는지를 안 말한다.</b>
     * 2026-09-21에 내가 그 기준만 적어 뒀고, 열어 보니 걷는 쪽이 더 나빴다.
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
    /**
     * 기간 계열로 주식수를 고른다. <b>계열의 행 순서와 공시일 규약이 두 기저에서 같으므로</b>
     * 리스트 판을 그대로 위임한다 — 연간 기저에서도 「그 봉 시점에 공시돼 있던 판」이 답이다.
     */
    static BigDecimal sharesOf(IndicatorContext context, PriceBar bar, PeriodSeries series) {
        return series == null ? null : sharesOf(context, bar, series.pointRows());
    }

    static BigDecimal sharesOf(IndicatorContext context, PriceBar bar,
                               List<FinancialFigures> quarters) {
        if (bar != null && bar.listedShares() != null && bar.listedShares().signum() > 0) {
            return context.sharesOn(bar.tradeDate(), bar.listedShares());
        }
        return fromFinancials(context, bar, quarters, FinancialFigures::sharesOutstanding);
    }

    /**
     * 주식수 비 지표 셋({@code SHARES_GROWTH_3Y} · {@code EPS_GROWTH_TTM_YOY} · {@code EPS_CAGR_5Y})의 한 끝.
     *
     * <p>관문이 켜진 미국 행(클래스 축이 읽은 행 — {@link FinancialFigures#shareClassState()}가 있다)이면 <b>그 공시의 EPS 분모
     * n</b>이다 — 발행사 전부가 두 끝 다 같은 개념이라, 공시 형식(축 없는 표지 대 클래스 표지)에 따라 기저가 바뀌지 않는다
     * (탐색 {@code SHARES-CLASS-PLAN-V5-1015.md} §10b-6). n이 없는 끝은 시점 주식수로 <b>안 내려간다</b> — 내려가면 두 끝의
     * 기저가 섞인다. 그 밖(국내 · 관문 꺼짐)은 {@link #sharesOf}와 같다.
     */
    static BigDecimal endpointShares(IndicatorContext context, PriceBar bar, PeriodSeries series) {
        if (series == null) {
            return null;
        }
        List<FinancialFigures> rows = series.pointRows();
        if (!rows.isEmpty() && rows.get(0).shareClassState() != null) {
            return fromFinancials(context, bar, rows, FinancialFigures::weightedShares);
        }
        return sharesOf(context, bar, rows);
    }

    private static BigDecimal fromFinancials(IndicatorContext context, PriceBar bar, List<FinancialFigures> quarters,
                                             java.util.function.Function<FinancialFigures, BigDecimal> count) {
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
            if (asOf.shareClassState() == com.stockscreener.pipeline.datasource.us.shareclass.ShareClassLine.State.HIDDEN) {
                // MUST: 아래 「주식수가 없으면 앞 판으로」보다 먼저 본다(§10b-3). 원장이 이 판의 주식수를 확인 못 한 것이라
                // 앞 판의 값을 내면 숨긴 이유를 조용히 무시하는 것이다.
                return null;
            }
            BigDecimal fromFinancials = count.apply(asOf);
            if (fromFinancials == null || fromFinancials.signum() <= 0) {
                continue;
            }
            // MUST: 기저 판정을 **고른 판에서** 본다(ADR-0162). 이 수가 시세와 다른 기저이면
            // 곱해서 맞추거나, 크기를 모르면 값을 안 낸다.
            ShareBasis basis = asOf.shareBasis();
            if (basis == ShareBasis.UNRESOLVED) {
                // MUST NOT: continue로 옛 판에 내려가지 않는다. 기저가 어긋난 것은 **종목의
                // 성질**이라 옛 판도 같은 기저이고, 내려가면 「같은 값을 다른 날짜로」 내는 것뿐이다.
                // 값을 안 내는 것이 판정이다 — 사유는 IndicatorUnavailabilityConfig가 말한다.
                return null;
            }
            if (basis.multiplies() && asOf.shareBasisMultiple() != null) {
                // 배수는 **판마다** 다르다. 사건 전에 공시된 판은 유도가 배수를 안 달므로
                // 여기서 1이 되고, 그 비대칭이 SharesGrowth3yCalculator처럼 분자·분모에 같은
                // 수를 곱하는 지표를 실제로 고친다(상수로 두면 약분돼 안 고쳐진다).
                fromFinancials = fromFinancials.multiply(asOf.shareBasisMultiple());
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

    /** 기간 계열 판. {@link #sharesOf(IndicatorContext, PriceBar, PeriodSeries)}를 탄다. */
    static BigDecimal trailingEpsOn(IndicatorContext context, PriceBar bar,
                                    BigDecimal ttmNetIncome, PeriodSeries series) {
        return perShareOn(context, bar, ttmNetIncome, series);
    }

    /** 기간 계열 판. 유량·저량 구분은 {@code amount}를 만든 호출부가 이미 했다. */
    static BigDecimal perShareOn(IndicatorContext context, PriceBar bar,
                                 BigDecimal amount, PeriodSeries series) {
        if (amount == null || series == null) {
            return null;
        }
        BigDecimal shares = sharesOf(context, bar, series);
        return shares == null ? null : amount.divide(shares, SCALE, RoundingMode.HALF_UP);
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
        // 계속영업이다(F2b) — 세전이익(미국 태그 · 국내 ProfitLossBeforeTax)과 법인세가 계속영업이라 `세전 − 계속영업 순이익 = 법인세`가
        // 항등식이다. 중단 포함 순이익으로 부호를 가르면 중단영업 손익이 클 때 판정이 뒤집힌다.
        BigDecimal net = figures.continuingIncome();
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
        BigDecimal net = figures.continuingIncome();
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
     * 연속한 분기로 인정하는 {@code period_end} 간격.
     *
     * <p><b>임계는 실측으로 골랐다</b>(2026-09-27 · 활성 편입 전부의 최신 분기 넷, 간격 3,975개).
     * 정상 쪽 최대는 <b>112일</b>이고 그것은 4-5-4 소매력의 16주 분기다
     * ({@code ACI}·{@code CAVA}·{@code DPZ}·{@code KR}·{@code PEP} 5건 — 그 종목들의 계열에는
     * 구멍이 없다). 결함 쪽 최소는 <b>151일</b>로 {@code FERG}의 빠진 2026 Q2다. 그 사이를
     * 가르되 양쪽에 여유를 뒀다. 관측된 최소는 84일이다.
     *
     * <p>MUST NOT: 상한을 180 근처로 넓히지 않는다. 한 분기 건너뛴 구멍이 <b>151~189일</b>이라
     * 정확히 그 구간에 들어온다 — 넓히면 이 검사가 있으나 마나가 된다.
     */
    private static final long MIN_QUARTER_GAP_DAYS = 75;
    private static final long MAX_QUARTER_GAP_DAYS = 125;

    /**
     * {@code offset}부터 4개 분기가 <b>실제로 이어지는 네 분기</b>인가.
     *
     * <p>{@link #isConsecutiveAnnual}과 같은 이유로 {@code fiscalYear} 라벨이 아니라
     * {@code periodEnd} 간격으로 잰다 — 라벨은 결산월이 12월이 아닌 회사에서 실제 기간과
     * 어긋나고, 미국은 라벨 자체를 못 믿는다(ADR-0124).
     *
     * <p>행 수만 세면 안 되는 이유는 {@link #sumFourQuarters}의 javadoc에 수와 함께 있다.
     */
    static boolean isConsecutiveQuarterly(List<FinancialFigures> quarters, int offset) {
        if (quarters.size() < offset + TTM_QUARTERS) {
            return false;
        }
        for (int i = offset + 1; i < offset + TTM_QUARTERS; i++) {
            // 읽기가 period_end 내림차순이므로 앞이 더 최근이다.
            LocalDate newer = quarters.get(i - 1).periodEnd();
            LocalDate older = quarters.get(i).periodEnd();
            if (newer == null || older == null) {
                return false;
            }
            long gap = ChronoUnit.DAYS.between(older, newer);
            if (gap < MIN_QUARTER_GAP_DAYS || gap > MAX_QUARTER_GAP_DAYS) {
                return false;
            }
        }
        return true;
    }

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
