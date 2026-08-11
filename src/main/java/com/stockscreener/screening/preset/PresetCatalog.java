package com.stockscreener.screening.preset;

import com.stockscreener.indicator.PercentileSpec;
import com.stockscreener.indicator.SectorMetricSpec;
import com.stockscreener.indicator.calculator.AdjCloseCalculator;
import com.stockscreener.indicator.calculator.AltmanZCalculator;
import com.stockscreener.indicator.calculator.AtrPct14Calculator;
import com.stockscreener.indicator.calculator.DebtRatioCalculator;
import com.stockscreener.indicator.calculator.MacdVCalculator;
import com.stockscreener.indicator.calculator.MarketCapCalculator;
import com.stockscreener.indicator.calculator.OperatingIncomeGrowthCalculator;
import com.stockscreener.indicator.calculator.OperatingMarginCalculator;
import com.stockscreener.indicator.calculator.PbrCalculator;
import com.stockscreener.indicator.calculator.PctFrom52wHighCalculator;
import com.stockscreener.indicator.calculator.PctFrom52wLowCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.indicator.calculator.ProfitableQuarters4Calculator;
import com.stockscreener.indicator.calculator.RevenueGrowthCalculator;
import com.stockscreener.indicator.calculator.RoeCalculator;
import com.stockscreener.indicator.calculator.Rsi14Calculator;
import com.stockscreener.indicator.calculator.SharesGrowth3yCalculator;
import com.stockscreener.indicator.calculator.Sma200Calculator;
import com.stockscreener.indicator.calculator.Sma200Momentum21Calculator;
import com.stockscreener.indicator.calculator.Sma200Slope60Calculator;
import com.stockscreener.indicator.calculator.Sma20Calculator;
import com.stockscreener.indicator.calculator.Sma60Calculator;
import com.stockscreener.indicator.calculator.TradingDays750Calculator;
import com.stockscreener.indicator.calculator.Turnover60Calculator;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

/**
 * 사전 정의 전략 선언. spec §3이 요구하는 "Value, QVG형 전략"이 여기 있다.
 *
 * <p>{@code @Component} 하나씩이 아니라 {@code @Configuration}의 리스트 빈으로 두는 이유는
 * 프리셋이 <b>행위가 아니라 데이터</b>이기 때문이다({@code IndicatorCalculator}와 대비되고
 * {@link PercentileSpec}과 같은 성격이다). 나란히 보여야 가중치 균형을 비교하며 잡을 수 있다.
 *
 * <p>IMPORTANT — <b>백분위 조건의 부등호 방향</b>이 이 파일에서 가장 틀리기 쉬운 부분이다.
 * 백분위는 예외 없이 오름차순이므로:
 * <ul>
 *   <li>PER처럼 <b>낮은 쪽이 좋은</b> 지표의 "하위 30%"는 {@code PER_PCTL <= 0.30}
 *   <li>ROE처럼 <b>높은 쪽이 좋은</b> 지표의 "상위 30%"는 {@code ROE_PCTL >= 0.70}
 * </ul>
 * 뒤집어 적으면 정반대 종목이 최상위로 올라오는데 점수도 결과도 그럴듯해서 눈으로는
 * 드러나지 않는다. {@code PresetCatalogTest}가 원본 지표의 방향과 대조해 이것을 잡는다.
 *
 * <p>지표 코드를 문자열이 아니라 계산기의 {@code CODE} 상수로 참조한다 — 오타가 기동 시점의
 * FK 위반이 아니라 <b>컴파일 오류</b>가 되고, 지표 코드를 바꾸면 여기가 함께 깨진다.
 */
@Configuration
public class PresetCatalog {

    /** 백분위 지표 코드. 원본 코드에 접미사를 붙이는 규칙은 {@link PercentileSpec}이 정본이다. */
    private static String pctl(String sourceCode) {
        return sourceCode + PercentileSpec.SUFFIX;
    }

    /** 업종 내 백분위. 모집단이 유니버스가 아니라 업종이다(ADR-0044). */
    private static String sectorPctl(String sourceCode) {
        return sourceCode + SectorMetricSpec.PERCENTILE_SUFFIX;
    }

    /** 업종 중앙값 배수. 1.0이 업종 중앙값이며 백분위가 아니다. */
    private static String sectorRel(String sourceCode) {
        return sourceCode + SectorMetricSpec.MEDIAN_RATIO_SUFFIX;
    }

    @Bean
    public List<ScreenPreset> screenPresets() {
        return List.of(valueKr(), qvgKr(), momentumKr(), swingMidtermKr());
    }

    /**
     * 저평가 가치주 — 싼데 이유가 있는 종목을 걸러낸 밸류.
     *
     * <p>절대 PER({@code PER < 10}) 대신 백분위를 쓰는 이유: 업종마다 평균 PER이 달라
     * 절대 기준은 특정 업종만 통과시킨다. 백분위는 유니버스 내 상대 위치라 그 편향이 없다.
     *
     * <p>수익성·부채 조건이 함께 있는 이유는 밸류 지표만으로 고르면 <b>가치함정</b>(값이
     * 싼 데는 이유가 있는 기업)이 상위를 채우기 때문이다. 200일선 조건은 추세가 무너진
     * 종목을 걸러내되, 눌림목을 배제하지 않도록 10% 아래까지 허용한다.
     */
    private static ScreenPreset valueKr() {
        return new ScreenPreset(
                "VALUE_KR",
                "저평가 가치주",
                "이익·자산 대비 주가가 낮으면서 최소한의 수익성과 재무 건전성을 갖춘 종목을 점수로 줄 세운다. "
                        + "업종별 편차를 피하려 절대 PER이 아니라 유니버스 내 상대 순위를 쓴다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // 하위 30% — PER은 낮은 쪽이 좋으므로 <=
                        weighted(pctl(PerCalculator.CODE), ComparisonOperator.LTE, "0.30", "3"),
                        // 하위 30% — PBR도 낮은 쪽이 좋으므로 <=
                        weighted(pctl(PbrCalculator.CODE), ComparisonOperator.LTE, "0.30", "2"),
                        // 가치함정 방어: 최소 수익성
                        weighted(RoeCalculator.CODE, ComparisonOperator.GTE, "8", "2"),
                        // 부채로 부풀린 ROE 배제. 부채비율은 낮은 쪽이 좋으므로 <=
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "150", "2"),
                        // 추세가 완전히 무너지지는 않았을 것 — 200일선 10% 아래까지 허용
                        IndicatorCondition.of(
                                AdjCloseCalculator.CODE, ComparisonOperator.GTE,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE, "0.90"))
                                .withWeight(new BigDecimal("1")))));
    }

    /**
     * 퀄리티·밸류·성장 — spec §3이 이름을 든 QVG형 전략.
     *
     * <p>세 축을 한 화면에 담는다. 밸류 축만 부등호가 반대인 것이 요점이다 — 같은 백분위
     * 지표라도 원본의 유불리에 따라 방향이 갈린다는 사실이 여기서 드러난다.
     *
     * <p>매출 성장률만 보면 <b>이익 없는 성장</b>이 상위에 온다. 영업이익 성장률을 함께
     * 요구해 매출만 늘고 이익이 줄어드는 기업을 걸러낸다.
     */
    private static ScreenPreset qvgKr() {
        return new ScreenPreset(
                "QVG_KR",
                "퀄리티·밸류·성장(QVG)",
                "수익성(Q)·저평가(V)·성장(G) 세 축을 가중 점수로 합산한다. "
                        + "한 축만 뛰어난 종목보다 세 축이 고르게 좋은 종목이 위로 올라온다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // Q — ROE는 높은 쪽이 좋으므로 상위 30%가 >=
                        weighted(pctl(RoeCalculator.CODE), ComparisonOperator.GTE, "0.70", "3"),
                        // Q — 영업이익률도 높은 쪽이 좋으므로 >=
                        weighted(pctl(OperatingMarginCalculator.CODE), ComparisonOperator.GTE, "0.70", "2"),
                        // V — PER은 낮은 쪽이 좋으므로 <=. 성장주를 전부 배제하지 않도록 50%로 느슨하게 잡는다.
                        weighted(pctl(PerCalculator.CODE), ComparisonOperator.LTE, "0.50", "3"),
                        // G — 매출 성장률은 높은 쪽이 좋으므로 >=
                        weighted(pctl(RevenueGrowthCalculator.CODE), ComparisonOperator.GTE, "0.70", "3"),
                        // G — 이익 없는 성장 배제. 절대값이라 백분위가 아니다.
                        weighted(OperatingIncomeGrowthCalculator.CODE, ComparisonOperator.GTE, "0", "2"),
                        // 가드
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "200", "1"))));
    }

    /**
     * 추세 상승 — 유일한 FILTER 프리셋.
     *
     * <p>하나를 FILTER로 두는 이유는 "판정 방식은 조건이 아니라 실행의 속성"(ADR-0003)이
     * 시딩 계층에서도 참임을 보이기 위해서다. 프리셋이라고 반드시 점수인 것은 아니다.
     *
     * <p>RSI를 상한까지 두는 것은 과열 구간(70 초과) 진입을 걸러내기 위해서다. 하한 45는
     * 침체 구간의 반등 초기를 배제한다 — 추세가 이미 확인된 종목을 찾는 것이 목적이다.
     */
    private static ScreenPreset momentumKr() {
        return new ScreenPreset(
                "MOMENTUM_KR",
                "추세 상승",
                "장기·단기 이동평균 위에 있고 모멘텀이 살아 있으면서 과열되지는 않은 종목만 남긴다. "
                        + "점수가 아니라 충족/불충족으로 걸러낸다.",
                EvaluationMode.FILTER,
                null,
                ConditionGroup.and(List.of(
                        // 장기 추세 — 수정종가끼리 비교해야 액면분할에서 값이 어긋나지 않는다
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        // 단기 정배열
                        IndicatorCondition.of(Sma20Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma60Calculator.CODE)),
                        // 변동성 대비 상승 모멘텀
                        literal(MacdVCalculator.CODE, ComparisonOperator.GT, "0"),
                        // 침체(<45)와 과열(>70) 양쪽을 배제
                        IndicatorCondition.of(Rsi14Calculator.CODE, ComparisonOperator.BETWEEN,
                                ConditionOperand.Literal.between(
                                        new BigDecimal("45"), new BigDecimal("70"))),
                        // 적자 기업 배제
                        literal(RoeCalculator.CODE, ComparisonOperator.GT, "0"))));
    }

    /**
     * 중장기 스윙 — 게이트로 거른 뒤 남은 것만 점수로 줄 세우는 유일한 프리셋.
     *
     * <p>다른 셋과 성격이 다르다. {@code VALUE_KR}·{@code QVG_KR}은 전 종목에 점수를 매겨
     * 순위를 보는 것이고, 이것은 <b>보이면 안 되는 종목을 화면에서 지우는 것</b>이 목적이다.
     * 특히 이격도 상한(200일선 대비 +25% 이내)은 이미 과열된 종목을 아예 띄우지 않으려는 것이라,
     * 낮은 점수로 결과에 남으면 그 조건의 존재 이유가 사라진다. 필수 조건이 그것을 가능하게 한다
     * (ADR-0038).
     *
     * <p>추세 조건이 여섯인 이유는 "우상향"의 정의가 하나로는 서지 않기 때문이다. 1개월 변화율만
     * 쓰면 횡보가 노이즈로 통과하고, 60일 회귀만 쓰면 최근의 꺾임을 놓친다. 둘을 함께 걸어야
     * 양쪽 오류가 상쇄된다.
     *
     * <p>IMPORTANT — <b>원본 명세에서 옮기지 못한 것이 여럿이고 그것이 이 프리셋의 가장 큰
     * 위험이다.</b> 없는 안전장치를 있다고 믿게 되기 때문에 설명에 전부 적는다. 옮기지 못한 것:
     * <ul>
     *   <li>순부채/EBITDA·이자보상배율 — 감가상각비와 이자비용이 비금융 기업에 표준계정코드로
     *       오지 않는다(2026-08-10 실측, {@code docs/api-spec/dart-open-api.md} §7).
     *   <li>5년 누적 영업현금흐름 — 위와 같은 이유로 보류.
     *   <li>베타 — 시장 지수 시계열이 없다.
     *   <li>피인수 진행 종목 제외 — 코퍼레이트 액션 소스가 없다.
     *   <li>해자 프록시 — 매출총이익과 ROIC를 계산할 수 없다.
     *   <li>업종 표본 부족 시의 절대 기준 폴백 — 원본은 플래그를 붙여 구분하는데 우리에게
     *       플래그 도메인이 없다. 그 종목은 판정불가로 빠진다(ADR-0044).
     * </ul>
     *
     * <p><b>업종 상대 비교는 이제 원본대로 돌아왔지만 모집단만 그렇다.</b> S-01은 원본의
     * "자체 5년 P/E 중앙값 OR 업종 중앙값" 중 뒤엣것만이라 원본보다 좁고, S-02는 EV를 만들 수
     * 없어 EBIT/EV가 아니라 영업이익률이며, 원본 D/E가 차입금 기준인 데 비해 우리 부채비율은
     * 부채총계/자기자본이다. 대조표는 ADR-0039가 정본이다.
     *
     * <p><b>Altman Z는 보험·금융서비스(KSIC 65·66)에서 계산되지 않는다.</b> 64(금융업)는
     * 지주회사를 함께 담아 코드로 가를 수 없어 제외하지 않았고, 그 종목들은 유동/비유동
     * 미구분 재무제표 때문에 대개 값이 없어 자연히 빠진다 — <b>그쪽은 여전히 부작용이다.</b>
     */
    private static ScreenPreset swingMidtermKr() {
        return new ScreenPreset(
                "SWING_MIDTERM_KR",
                "중장기 스윙",
                "3~6개월 보유를 전제로, 장기 추세가 살아 있으면서 아직 과열되지 않은 종목만 남긴 뒤 "
                        + "저평가·수익성·성장으로 줄 세운다. 필수 조건을 하나라도 채우지 못하면 결과에서 빠진다. "
                        + "부채비율·저평가·수익성은 같은 업종 종목과 견준다 — 업종 표본이 5종목에 "
                        + "못 미치면 판정할 수 없어 그 종목은 결과에서 빠진다. 원본이 그 경우에 쓰라고 한 "
                        + "절대 기준 폴백과 표본부족 플래그는 플래그 체계가 없어 옮기지 않았다. "
                        + "옮기지 못한 조건이 있다 — 순부채/EBITDA와 이자보상배율(감가상각비·이자비용을 "
                        + "소스가 주지 않는다), 베타(시장 지수 시계열이 없다), 피인수 종목 제외(공시 소스가 없다), "
                        + "해자 지표(매출총이익·ROIC를 계산할 수 없다). 저평가 판정은 업종 비교만 쓰고 "
                        + "자체 5년 이력 비교가 없어 원본보다 좁으며, 이익수익률은 EV를 만들 수 없어 "
                        + "영업이익률로 대신한다. "
                        + "Altman Z는 보험·금융서비스 종목에서 계산하지 않는다 — 자본구조가 달라 "
                        + "점수가 뜻을 잃기 때문이다. 다만 지주회사는 업종 코드가 금융업으로 오는 경우가 "
                        + "있어 코드만으로는 가려지지 않는다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // ── 규모와 유동성 ──
                        // 유니버스가 이미 시총 상위 350이지만 그 안에서도 시총이 한 자릿수 배 차이가 난다.
                        // 백분위로 걸지 않는 이유는 유니버스 안에서 매겨져(ADR-0036) "코스닥150 상위 30%"가
                        // 통과하기 때문이다 — 규모로 거르려는 의도와 정반대다.
                        required(MarketCapCalculator.CODE, ComparisonOperator.GTE, "1000000000000"),
                        // 실측 분포에서 하위 4분위 언저리. 체결이 아니라 관심도 필터다.
                        required(Turnover60Calculator.CODE, ComparisonOperator.GTE, "10000000000"),
                        // 지표 대부분이 장기 시계열을 요구해, 이력이 짧으면 값은 나오되 뜻이 없다.
                        // 지금까지는 SHARES_GROWTH_3Y가 750봉을 요구하는 부작용으로 같은 종목이
                        // 빠졌는데, 그것은 주식수 증가율이지 이력 길이가 아니다 — 그 지표를 손대면
                        // 이 게이트가 아무 증상 없이 사라진다. 값이 상장 경과가 아니라 수집한 봉
                        // 수라는 한계는 TradingDays750Calculator가 적는다.
                        required(TradingDays750Calculator.CODE, ComparisonOperator.GTE, "750"),

                        // ── 추세 국면 ──
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)).asRequired(),
                        required(Sma200Momentum21Calculator.CODE, ComparisonOperator.GT, "0"),
                        required(Sma200Slope60Calculator.CODE, ComparisonOperator.GT, "0"),
                        // 원본은 50일선을 쓰지만 우리에게는 60일선이 있다. 굳이 50일선 계산기를
                        // 새로 만들지 않는다 — 정배열의 뜻이 달라지지 않는다.
                        IndicatorCondition.of(Sma60Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)).asRequired(),
                        // 추격매수 차단. 이 한 줄이 이 프리셋의 성격을 정한다.
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.LTE,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE, "1.25")).asRequired(),
                        // 하락 추세 중의 일시 반등 배제
                        required(PctFrom52wLowCalculator.CODE, ComparisonOperator.GTE, "30"),
                        // 이미 훼손된 종목 배제. 위 이격도 상한과 함께 진입 구간을 만든다.
                        required(PctFrom52wHighCalculator.CODE, ComparisonOperator.GTE, "-25"),
                        // 보유 지속력. 수익률이 아니라 견딜 수 있는가를 묻는다.
                        // 원본의 4%는 미국 대형주 기준이다. 국내 유니버스의 ATR 비율 중앙값이
                        // 7.91%(p10=4.64)라 4%를 그대로 쓰면 상위 10%만 남아 게이트가 아니라
                        // 저변동성 스크리너가 된다 — 실측으로 16종목이 1종목이 됐다.
                        // 6%는 유니버스 p25(5.88) 언저리이며, 원본이 미국 표본에서 4%를 놓은
                        // 상대 위치에 대응한다. 근거와 재보정 이력은 ADR-0039에 있다.
                        required(AtrPct14Calculator.CODE, ComparisonOperator.LTE, "6"),

                        // ── 생존 ──
                        // 계절성 있는 업종이 한 분기 적자를 내는 것은 정상이라 4가 아니라 3이다.
                        required(ProfitableQuarters4Calculator.CODE, ComparisonOperator.GTE, "3"),
                        // 원본 H-02 그대로 — 같은 업종 중앙값의 1.5배 이내.
                        // 업종 표본이 5종목에 못 미치면 값이 없어 판정불가가 되고, 그 종목은
                        // 결과에서 빠진다. 원본이 그 자리에 둔 절대 폴백(+ 표본부족 플래그)은
                        // 플래그 체계가 없어 옮기지 않았다(ADR-0044).
                        required(sectorRel(DebtRatioCalculator.CODE), ComparisonOperator.LTE, "1.5"),
                        // 절대 기준을 함께 남긴다. 원본은 H-02(상대)와 H-03(순부채/EBITDA, 절대)을
                        // 짝으로 두고 "업종 전체가 과부채인 경우"를 후자가 잡는데, H-03은
                        // 감가상각비가 없어 버렸다. 이것까지 빼면 레버리지 절대 게이트가
                        // 하나도 남지 않는다.
                        required(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "150"),
                        // 3년 누적 5%는 연 1.6% 수준이다. 주식보상 희석은 통과하고 반복 증자는 걸린다.
                        required(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "5"),
                        // 회색지대 하단까지 허용하되 위험구간(1.8 이하)은 확실히 배제한다.
                        required(AltmanZCalculator.CODE, ComparisonOperator.GTE, "2.6"),

                        // ── 점수 ──
                        // 원본 S-01의 조건 b(업종 중앙값 미만)를 옮겼다. 조건 a(자체 5년 P/E
                        // 중앙값)는 여전히 없어 **원본보다 좁다** — 원본은 둘 중 하나만 맞아도
                        // 1점이다.
                        weighted(sectorPctl(PerCalculator.CODE), ComparisonOperator.LTE, "0.50", "1"),
                        // 원본 S-02는 업종 내 이익수익률(EBIT/EV) 상위 50%다. 모집단은 업종으로
                        // 맞췄지만 **재는 대상이 다르다** — EV를 만들 수 없어 영업이익률이다.
                        weighted(sectorPctl(OperatingMarginCalculator.CODE),
                                ComparisonOperator.GTE, "0.50", "1"),
                        // 원본의 누적 배점을 조건 둘로 옮긴다 — 15% 이상 +1, 25% 이상 +1 추가.
                        weighted(OperatingIncomeGrowthCalculator.CODE, ComparisonOperator.GTE, "15", "1"),
                        weighted(OperatingIncomeGrowthCalculator.CODE, ComparisonOperator.GTE, "25", "1"))));
    }

    /** 게이트 조건. 가중치를 가질 수 없으므로 {@code asRequired()}가 그것을 강제한다. */
    private static IndicatorCondition required(String code, ComparisonOperator operator, String value) {
        return literal(code, operator, value).asRequired();
    }

    private static IndicatorCondition literal(String code, ComparisonOperator operator, String value) {
        return IndicatorCondition.of(code, operator, ConditionOperand.Literal.of(value));
    }

    private static IndicatorCondition weighted(String code, ComparisonOperator operator,
                                               String value, String weight) {
        return literal(code, operator, value).withWeight(new BigDecimal(weight));
    }
}
