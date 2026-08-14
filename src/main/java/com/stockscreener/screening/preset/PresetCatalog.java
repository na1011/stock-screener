package com.stockscreener.screening.preset;

import com.stockscreener.indicator.PercentileSpec;
import com.stockscreener.indicator.SectorMetricSpec;
import com.stockscreener.indicator.calculator.AccrualRatioCalculator;
import com.stockscreener.indicator.calculator.Adx14Calculator;
import com.stockscreener.indicator.calculator.AdjCloseCalculator;
import com.stockscreener.indicator.calculator.AltmanZCalculator;
import com.stockscreener.indicator.calculator.AtrPct14Calculator;
import com.stockscreener.indicator.calculator.Beta104wCalculator;
import com.stockscreener.indicator.calculator.Cmf21Calculator;
import com.stockscreener.indicator.calculator.DebtRatioCalculator;
import com.stockscreener.indicator.calculator.Disparity200Calculator;
import com.stockscreener.indicator.calculator.EpsGrowthTtmYoyCalculator;
import com.stockscreener.indicator.calculator.ExcessReturn52wCalculator;
import com.stockscreener.indicator.calculator.MaAlignScoreCalculator;
import com.stockscreener.indicator.calculator.MarketCapCalculator;
import com.stockscreener.indicator.calculator.OperatingCashFlow5ySumCalculator;
import com.stockscreener.indicator.calculator.OperatingMarginCalculator;
import com.stockscreener.indicator.calculator.PbrCalculator;
import com.stockscreener.indicator.calculator.PctFrom52wHighCalculator;
import com.stockscreener.indicator.calculator.PctFrom52wLowCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.indicator.calculator.ProfitableQuarters4Calculator;
import com.stockscreener.indicator.calculator.RevenueGrowthCalculator;
import com.stockscreener.indicator.calculator.RoaCalculator;
import com.stockscreener.indicator.calculator.Rsi2Calculator;
import com.stockscreener.indicator.calculator.SharesGrowth3yCalculator;
import com.stockscreener.indicator.calculator.Sma150Calculator;
import com.stockscreener.indicator.calculator.Sma200Calculator;
import com.stockscreener.indicator.calculator.Sma200Momentum21Calculator;
import com.stockscreener.indicator.calculator.Sma200Slope60Calculator;
import com.stockscreener.indicator.calculator.Sma60Calculator;
import com.stockscreener.indicator.calculator.TradingDays750Calculator;
import com.stockscreener.indicator.calculator.Turnover60Calculator;
import com.stockscreener.indicator.calculator.VolumeRatio20Calculator;
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
 * <p><b>조건의 출처가 문헌이다</b>(ADR-0061). 임의로 조합하지 않고 각 조건이 어느 이론에서
 * 왔는지를 아래 각 메서드의 javadoc이 적는다. 셋을 갈라 적는다 — <b>원문 그대로 옮긴 것</b>,
 * <b>국내 분포로 재보정한 것</b>, <b>원문에 없는데 더한 것</b>. 갈리지 않으면 다음 세션이
 * 우리가 정한 값을 문헌이 검증한 값으로 읽는다.
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
 * <p>IMPORTANT — <b>절대 임계값은 국내 분포를 재고 정한다</b>(ADR-0039·0061). 미국 문헌의
 * 값을 그대로 옮기면 그 값이 재는 위치가 달라진다. 실제로 2년 주간 베타 1.3은 국내에서
 * 82%가 통과해 <b>게이트가 아니라 상수</b>였다.
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
        return List.of(valueKr(), qvgKr(), momentumKr(), swingTrendKr(),
                stage2BreakoutKr(), pullbackUptrendKr(), lowvolQualityKr());
    }

    /**
     * 프리셋별 <b>추가 컬럼</b>. 조건에 쓰이지 않지만 그 화면에서만 함께 보여야 하는 지표다.
     *
     * <p><b>지금은 쓰는 프리셋이 없다.</b> 유일한 소비자였던 주봉 일목 프리셋을 폐기했기
     * 때문이며(ADR-0061), 확장점과 호출부({@code ScreeningResponseAssembler})는 남긴다 —
     * FILTER 프리셋에 서버 정렬이 없는 한(ADR-0016) 「걸러진 종목 사이의 우열」을 보여 줄
     * 다른 수단이 없다.
     *
     * <p>MUST NOT: {@code defaultColumn}으로 띄우지 않는다. 그것은 <b>전역</b>이라 모든 화면이
     * 시끄러워진다.
     *
     * <p>MUST NOT: 더미 조건({@code ATR_PCT_14 >= -999})으로 대신하지 않는다. 조건 내역 화면에
     * 아무 뜻 없는 줄이 남고, 사용자는 그것을 걸러내는 기준으로 읽는다.
     */
    public static List<String> extraColumns(String presetCode) {
        return List.of();
    }

    // ── 1. 저평가 가치주 ──────────────────────────────────────────────────

    public static final String VALUE_KR = "VALUE_KR";

    /**
     * 저평가 가치주 — Piotroski F-Score(2000)와 Sloan 발생액(1996), Novy-Marx 총이익성(2013).
     *
     * <p>F-Score의 핵심은 <b>싼 종목 안에서 재무 건전성으로 가르는 것</b>이다. 싸다는 것만으로는
     * 가치함정과 구분되지 않는다.
     *
     * <p><b>원문 그대로</b>: {@code ROA >= 0}(F-Score ①), {@code ACCRUAL_RATIO <= 0}
     * (F-Score ④ — 영업현금흐름이 순이익보다 많은가), {@code SHARES_GROWTH_3Y <= 0}
     * (F-Score ⑦ 주식 미발행).
     * <br><b>국내 사정으로 바꾼 것</b>: 수익성 축을 {@code ROE}가 아니라 총이익성(GP/A)으로
     * 둔다 — 한국 시장에서 수익성 이상현상의 재현율이 5%인 반면(2000–2019, 148개 중) 총이익성은
     * 별개 연구에서 유의하게 나왔다.
     * <br><b>원문에 없는데 더한 것</b>: {@code DEBT_RATIO <= 150}(레버리지 가드),
     * 200일선 10% 아래까지 허용하는 추세 조건(가치함정 방어).
     *
     * <p>MUST NOT: F-Score를 옮겼다고 적지 않는다. <b>아홉 중 셋만</b> 옮겼고 나머지는
     * 전년 대비 변화(ΔROA·Δ레버리지·Δ유동비율·Δ자산회전율·Δ매출총이익률)라 지표가 다섯 더
     * 필요하다. 그 결핍은 {@code description}에 있다.
     */
    private static ScreenPreset valueKr() {
        return new ScreenPreset(
                VALUE_KR,
                "저평가 가치주",
                "이익·자산 대비 주가가 낮으면서 재무 건전성을 갖춘 종목을 점수로 줄 세운다. "
                        + "업종별 편차를 피하려 절대 PER이 아니라 유니버스 내 상대 순위를 쓴다. "
                        + "재무 건전성은 Piotroski가 제안한 아홉 항목 중 셋만 옮겼다 — "
                        + "총자산이익률이 양수인가, 영업활동현금흐름이 순이익보다 많은가, "
                        + "주식수가 늘지 않았는가. 나머지 여섯은 전년 대비 변화라 아직 지표가 없다. "
                        + "발생액 비율은 연간 재무만 쓰므로 1년에 한 번 바뀌고, 같은 화면의 "
                        + "총자산이익률은 분기 기준이라 두 값의 기준 시점이 다르다. "
                        + "총자산이익률 조건은 편입 종목의 80%가 통과해 순위를 크게 가르지 않는다 — "
                        + "적자 기업을 걸러내는 몫이다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // 하위 30% — PER은 낮은 쪽이 좋으므로 <=
                        weighted(pctl(PerCalculator.CODE), ComparisonOperator.LTE, "0.30", "3"),
                        // 하위 30% — PBR도 낮은 쪽이 좋으므로 <=
                        weighted(pctl(PbrCalculator.CODE), ComparisonOperator.LTE, "0.30", "3"),
                        // 이익의 질. 음수면 벌어들인 이익보다 들어온 현금이 많다는 뜻이다.
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "2"),
                        // 가치함정 방어: 최소 수익성. 부호 조건이라 변별력은 낮다.
                        weighted(RoaCalculator.CODE, ComparisonOperator.GTE, "0", "2"),
                        // 주식 미발행. 자사주를 소각한 기업은 음수다.
                        weighted(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        // 부채로 부풀린 수익성 배제. 부채비율은 낮은 쪽이 좋으므로 <=
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "150", "1"),
                        // 추세가 완전히 무너지지는 않았을 것 — 200일선 10% 아래까지 허용
                        IndicatorCondition.of(
                                AdjCloseCalculator.CODE, ComparisonOperator.GTE,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE, "0.90"))
                                .withWeight(new BigDecimal("1")))));
    }

    // ── 2. 퀄리티·밸류·성장 ───────────────────────────────────────────────

    public static final String QVG_KR = "QVG_KR";

    /**
     * 퀄리티·밸류·성장 — 성장을 합리적 가격에 산다는 GARP와 CAN SLIM의 이익 성장 기준.
     *
     * <p><b>원문 그대로</b>: 이익 성장 임계 15%·25%.
     * <br><b>국내 사정으로 바꾼 것</b>: 수익성 축이 {@code ROE_PCTL}이 아니라 총이익성이다
     * (위 {@link #valueKr()}와 같은 이유).
     * <br><b>원문에 없는데 더한 것</b>: 발생액 비율(이익의 질), 부채비율 가드.
     *
     * <p>MUST NOT: 15%·25%를 CAN SLIM의 「A(3년 연간 성장)」와 「C(당분기 대 전년동기)」라고
     * 적지 않는다. <b>둘 다 아니다</b> — 우리가 재는 것은 TTM 대 직전 TTM 하나이고, 임계값
     * 둘은 서로 다른 두 기준이 아니라 <b>누적 배점</b>이다.
     */
    private static ScreenPreset qvgKr() {
        return new ScreenPreset(
                QVG_KR,
                "퀄리티·밸류·성장(QVG)",
                "수익성(Q)·저평가(V)·성장(G) 세 축을 가중 점수로 합산한다. "
                        + "한 축만 뛰어난 종목보다 세 축이 고르게 좋은 종목이 위로 올라온다. "
                        + "주당순이익 성장률은 최근 4개 분기를 그 직전 4개 분기와 견준 값이며, "
                        + "직전 4개 분기가 적자였던 종목은 값이 없어 그 조건을 채우지 못한다 — "
                        + "적자가 줄어든 것을 성장으로 읽지 않기 위해서다. "
                        + "그 때문에 편입 종목의 약 30%는 성장 조건에서 점수를 얻지 못한다. "
                        + "수익성은 자기자본이익률이 아니라 총이익성으로 본다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // V — PER은 낮은 쪽이 좋으므로 <=. 성장주를 전부 배제하지 않도록 50%로 느슨하게.
                        weighted(pctl(PerCalculator.CODE), ComparisonOperator.LTE, "0.50", "3"),
                        // G — 매출 성장률은 높은 쪽이 좋으므로 >=
                        weighted(pctl(RevenueGrowthCalculator.CODE),
                                ComparisonOperator.GTE, "0.70", "2"),
                        // G — 이익 성장. 두 임계는 누적 배점이지 서로 다른 두 기준이 아니다.
                        weighted(EpsGrowthTtmYoyCalculator.CODE, ComparisonOperator.GTE, "15", "2"),
                        weighted(EpsGrowthTtmYoyCalculator.CODE, ComparisonOperator.GTE, "25", "1"),
                        // Q — 이익의 질
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "2"),
                        // Q — 영업이익률도 높은 쪽이 좋으므로 >=
                        weighted(pctl(OperatingMarginCalculator.CODE),
                                ComparisonOperator.GTE, "0.70", "2"),
                        // 가드
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "200", "1"))));
    }

    // ── 3. 추세 상승 ──────────────────────────────────────────────────────

    public static final String MOMENTUM_KR = "MOMENTUM_KR";

    /**
     * 추세 상승 — Minervini 트렌드 템플릿 여덟 조건의 이식.
     *
     * <p>원문(『Think &amp; Trade Like a Champion』)이 완전히 기계적이라 1대1로 옮긴다.
     * <b>여덟 조건을 그대로 옮기는 것이 목적</b>이므로 원문에 없는 조건을 더하지 않는다 —
     * 더하는 순간 이것을 「트렌드 템플릿」이라 부를 수 없다.
     *
     * <p><b>치환 둘</b>: 50일선이 없어 {@code SMA_60}을 쓰고(ADR-0039 T-03과 같은 치환),
     * 상대강도 등급은 지수 대비 52주 초과수익률의 유니버스 백분위로 대신한다.
     * <b>MUST NOT: 후자를 IBD의 RS 등급이라 부르지 않는다</b> — IBD는 분기별 2-1-1-1 가중을
     * 쓰고 우리는 단일 구간 수익률을 쓴다.
     *
     * <p>{@code SMA_60 > SMA_200}은 다른 두 조건에서 추이律로 자동 성립하는 <b>중복이지만
     * 원문 그대로 남긴다</b> — 원문 이탈이 조건 한 칸보다 비싸다.
     *
     * <p>MUST NOT: 이 프리셋을 「알파의 원천」으로 설명하지 않는다. 한국 시장에서 모멘텀
     * 이상현상은 코스피만 보면 재현율이 20%로 떨어지고 우리 유니버스는 대형주뿐이다.
     * 재는 것은 <b>지금 어느 국면에 있는가</b>이지 앞으로 오를 종목이 아니다.
     */
    private static ScreenPreset momentumKr() {
        return new ScreenPreset(
                MOMENTUM_KR,
                "추세 상승",
                "장기 상승 추세가 확인된 종목만 남긴다. 널리 쓰이는 여덟 조건을 그대로 옮겼다 — "
                        + "주가가 150일선·200일선 위에 있고, 150일선이 200일선 위에 있으며, "
                        + "200일선 자체가 한 달 이상 오르고 있고, 52주 최저가보다 30% 이상 올라와 "
                        + "있으면서 52주 최고가에서 25% 이내에 있고, 시장 지수보다 1년 수익률이 "
                        + "좋은 상위 30%에 든다. 점수가 아니라 충족/불충족으로 걸러낸다. "
                        + "원문의 50일선은 계산기가 없어 60일선으로 대신했고, 상대강도 등급은 "
                        + "지수 대비 52주 초과수익률의 순위로 대신했다 — 분기별 가중을 쓰는 "
                        + "미국식 등급과는 계산이 다르다. "
                        + "이 조건들은 지금 어느 국면에 있는지를 보여줄 뿐 앞으로 오를 종목을 "
                        + "가려내지 않는다.",
                EvaluationMode.FILTER,
                null,
                ConditionGroup.and(List.of(
                        // ① 주가가 150일선·200일선 위 — 수정종가끼리 비교해야 분할에서 어긋나지 않는다
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma150Calculator.CODE)),
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        // ② 150일선 > 200일선
                        IndicatorCondition.of(Sma150Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        // ③ 200일선이 한 달 이상 상승
                        literal(Sma200Momentum21Calculator.CODE, ComparisonOperator.GT, "0"),
                        // ④ 50일선(→60일선) > 150·200일선
                        IndicatorCondition.of(Sma60Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma150Calculator.CODE)),
                        IndicatorCondition.of(Sma60Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        // ⑤ 주가 > 50일선(→60일선)
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma60Calculator.CODE)),
                        // ⑥ 52주 최저가 대비 +30% 이상
                        literal(PctFrom52wLowCalculator.CODE, ComparisonOperator.GTE, "30"),
                        // ⑦ 52주 최고가 −25% 이내. 값은 항상 0 이하다.
                        literal(PctFrom52wHighCalculator.CODE, ComparisonOperator.GTE, "-25"),
                        // ⑧ 상대강도 상위 30%
                        literal(pctl(ExcessReturn52wCalculator.CODE),
                                ComparisonOperator.GTE, "0.70"))));
    }

    // ── 4. 중장기 스윙 ────────────────────────────────────────────────────

    public static final String SWING_TREND_KR = "SWING_TREND_KR";

    /**
     * 중장기 스윙 — 추세 게이트와 재무 생존을 함께 거는 게이트+점수 프리셋.
     *
     * <p>폐기한 {@code SWING_MIDTERM_KR}의 자리를 받는다(ADR-0061). 그 프리셋의 외부 명세는
     * 「추세는 그대로, 생존은 얇은」 쪽으로 기울었다고 스스로 판정했고(ADR-0039 총량 판정),
     * 여기서는 그 얇아진 축을 검증된 조건으로 메운다.
     *
     * <p><b>원문에서 되살린 것</b>: 2년 주간 베타(T-08)는 「시장 지수 시계열이 없다」는 이유로
     * 버려졌는데 지수는 ADR-0053으로 적재됐다. 업종 상대 부채비율(H-02)과 업종 상대
     * 영업이익률(S-02)도 되살린다.
     * <br><b>국내 분포로 재보정한 것</b>: 베타 임계는 원문 1.3이 아니라 <b>1.2</b>다 — 1.3은
     * 국내에서 82%가 통과해 게이트가 아니었고, 1.2가 상위 23%를 자른다.
     * <br><b>원문보다 약하게 옮긴 것</b>: 5년 누적 영업현금흐름(H-05)과 3년 주식수 증가율
     * (H-06)을 필수 게이트가 아니라 점수 조건에 둔다. 둘 다 값이 없는 종목이 많아
     * (283종목·314종목) 게이트로 걸면 <b>현금흐름이 나빠서가 아니라 이력이 짧아서</b>
     * 탈락하는 종목이 60~70개 생긴다.
     *
     * <p>{@code DISPARITY_200 <= 1.25}가 이 프리셋의 성격을 정한다 — 급등 종목이 구조적으로
     * 나오지 않으며 <b>그것은 부작용이 아니라 설계다.</b> {@code ADJ_CLOSE > SMA_200}과
     * 중복이 아니라 같은 값의 하한과 상한이다.
     */
    private static ScreenPreset swingTrendKr() {
        return new ScreenPreset(
                SWING_TREND_KR,
                "중장기 스윙",
                "3~6개월 보유를 전제로, 장기 추세가 살아 있으면서 아직 과열되지 않은 종목만 남긴 뒤 "
                        + "저평가·수익성·성장으로 줄 세운다. 필수 조건을 하나라도 채우지 못하면 "
                        + "결과에서 빠진다. 200일선 대비 이격도를 1.25로 제한하므로 최근 급등한 "
                        + "종목은 구조적으로 나오지 않는다 — 의도된 설계다. "
                        + "부채비율·저평가·수익성은 같은 업종 종목과 견준다 — 업종 표본이 5종목에 "
                        + "못 미치면 판정할 수 없어 그 종목은 결과에서 빠진다. "
                        + "2년 주간 베타는 104주치 주간 등락이 있어야 계산되며, 그만큼의 이력이 "
                        + "없는 종목은 결과에서 빠진다. "
                        + "옮기지 못한 조건이 있다 — 순부채/EBITDA와 이자보상배율(감가상각비·이자비용을 "
                        + "소스가 주지 않는다), 피인수 종목 제외(공시 소스가 없다), "
                        + "해자 지표(매출총이익·ROIC를 계산할 수 없다). "
                        + "5년 누적 영업현금흐름과 3년 주식수 증가율은 필수 조건이 아니라 점수 조건이다 — "
                        + "다섯 회계연도가 모두 있어야 판정할 수 있어, 필수로 걸면 상장한 지 얼마 되지 "
                        + "않은 종목이 현금흐름과 무관하게 빠지기 때문이다. "
                        + "Altman Z는 보험·금융서비스 종목에서 계산하지 않는다 — 자본구조가 달라 "
                        + "점수가 뜻을 잃기 때문이다. 다만 지주회사는 업종 코드가 금융업으로 오는 "
                        + "경우가 있어 코드만으로는 가려지지 않는다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // ── 규모와 유동성 ──
                        required(MarketCapCalculator.CODE, ComparisonOperator.GTE,
                                "1000000000000"),
                        required(Turnover60Calculator.CODE, ComparisonOperator.GTE, "10000000000"),
                        required(TradingDays750Calculator.CODE, ComparisonOperator.GTE, "750"),

                        // ── 추세 국면 ──
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE))
                                .asRequired(),
                        IndicatorCondition.of(Sma150Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE))
                                .asRequired(),
                        IndicatorCondition.of(Sma60Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE))
                                .asRequired(),
                        required(Sma200Momentum21Calculator.CODE, ComparisonOperator.GT, "0"),
                        required(Sma200Slope60Calculator.CODE, ComparisonOperator.GT, "0"),
                        // 추격매수 차단. 이 한 줄이 이 프리셋의 성격을 정한다.
                        required(Disparity200Calculator.CODE, ComparisonOperator.LTE, "1.25"),
                        required(PctFrom52wLowCalculator.CODE, ComparisonOperator.GTE, "30"),
                        required(PctFrom52wHighCalculator.CODE, ComparisonOperator.GTE, "-25"),
                        required(AtrPct14Calculator.CODE, ComparisonOperator.LTE, "6"),
                        // 원문 1.3은 국내에서 82%가 통과해 게이트가 아니다. 1.2가 상위 23%를 자른다.
                        required(Beta104wCalculator.CODE, ComparisonOperator.LTE, "1.2"),

                        // ── 생존 ──
                        required(ProfitableQuarters4Calculator.CODE, ComparisonOperator.GTE, "3"),
                        required(sectorRel(DebtRatioCalculator.CODE), ComparisonOperator.LTE, "1.5"),
                        required(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "150"),
                        required(AltmanZCalculator.CODE, ComparisonOperator.GTE, "2.6"),

                        // ── 점수 ──
                        weighted(OperatingCashFlow5ySumCalculator.CODE,
                                ComparisonOperator.GT, "0", "1"),
                        weighted(SharesGrowth3yCalculator.CODE, ComparisonOperator.LTE, "5", "1"),
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        weighted(sectorPctl(PerCalculator.CODE), ComparisonOperator.LTE, "0.50", "1"),
                        weighted(sectorPctl(OperatingMarginCalculator.CODE),
                                ComparisonOperator.GTE, "0.50", "1"),
                        weighted(EpsGrowthTtmYoyCalculator.CODE, ComparisonOperator.GTE, "15", "1"),
                        weighted(pctl(ExcessReturn52wCalculator.CODE),
                                ComparisonOperator.GTE, "0.70", "1"))));
    }

    // ── 5. 2국면 돌파 ─────────────────────────────────────────────────────

    public static final String STAGE2_BREAKOUT_KR = "STAGE2_BREAKOUT_KR";

    /**
     * 2국면 돌파 — Weinstein 국면 분석의 1국면→2국면 전환 정의.
     *
     * <p>원문은 셋이 <b>동시에</b> 성립해야 전환으로 본다 — 30주선 상향 돌파, 그 이동평균 자체의
     * 상승 전환, 그리고 거래량 확장이다. 거래량 없는 돌파는 흔히 실패한다는 것이 원문의 요지다.
     *
     * <p><b>국내 분포로 재보정한 것</b>: 거래량 확장 임계. 원문은 「평소의 2~3배」인데 국내에서
     * 2.0배는 상위 10% 미만이라 과도하다. <b>1.5배</b>가 상위 약 22%에 앉는다.
     * <br><b>원문에 없는데 더한 것</b>: {@code ADX_14 >= 20}(추세가 실재하는가),
     * {@code CMF_21 > 0}(거래량이 매수 쪽에 실렸는가), 52주 고점 10% 이내(돌파 확인).
     *
     * <p>돌파는 <b>그날 일어난 것</b>을 잡는 조건이라 결과 수가 날마다 크게 흔들린다.
     */
    private static ScreenPreset stage2BreakoutKr() {
        return new ScreenPreset(
                STAGE2_BREAKOUT_KR,
                "2국면 돌파",
                "바닥 다지기를 끝내고 상승 국면으로 넘어가는 신호가 한꺼번에 나온 종목을 남긴다. "
                        + "주가가 30주선(150일선) 위로 올라섰고, 그 이동평균 자체가 상승으로 돌아섰으며, "
                        + "거래량이 평소의 1.5배 이상 실렸고, 지수보다 1년 수익률이 좋은 상위 30%에 들며, "
                        + "52주 최고가에서 10% 이내에 있는 상태다. "
                        + "원문은 거래량을 평소의 2~3배로 보지만 국내에서 그 기준은 상위 10%에도 "
                        + "못 미쳐 1.5배로 낮췄다. "
                        + "돌파는 그날 일어난 일을 잡는 조건이라 결과 수가 날마다 크게 달라지며, "
                        + "0건인 날도 정상이다. 점수가 아니라 충족/불충족으로 걸러낸다.",
                EvaluationMode.FILTER,
                null,
                ConditionGroup.and(List.of(
                        // 30주선 상향 돌파
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma150Calculator.CODE)),
                        IndicatorCondition.of(Sma150Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        // 이동평균 자체가 상승 전환
                        literal(Sma200Momentum21Calculator.CODE, ComparisonOperator.GT, "0"),
                        literal(MaAlignScoreCalculator.CODE, ComparisonOperator.GTE, "2"),
                        // 추세가 실재하는가. 방향이 아니라 유무를 잰다.
                        literal(Adx14Calculator.CODE, ComparisonOperator.GTE, "20"),
                        // 거래량 확장. 원문 2~3배를 국내 분포로 옮긴 값이다.
                        literal(VolumeRatio20Calculator.CODE, ComparisonOperator.GTE, "1.5"),
                        // 거래량이 매수 쪽에 실렸는가
                        literal(Cmf21Calculator.CODE, ComparisonOperator.GT, "0"),
                        // 지수 대비 상대강도
                        literal(pctl(ExcessReturn52wCalculator.CODE),
                                ComparisonOperator.GTE, "0.70"),
                        // 신고가 근접. 값은 항상 0 이하다.
                        literal(PctFrom52wHighCalculator.CODE, ComparisonOperator.GTE, "-10"))));
    }

    // ── 6. 상승추세 눌림목 ────────────────────────────────────────────────

    public static final String PULLBACK_UPTREND_KR = "PULLBACK_UPTREND_KR";

    /**
     * 상승추세 눌림목 — Connors의 평균회귀 규칙.
     *
     * <p>원문은 둘뿐이다 — <b>200일선 위에 있을 것</b>, 그리고 <b>RSI(2)가 10 미만일 것</b>.
     * 앞의 것이 이 전략의 유일한 구조적 보호 장치이고, 뒤의 것이 「단기 패닉」의 정의다.
     *
     * <p><b>원문 그대로</b>: 두 조건 다.
     * <br><b>원문에 없는데 더한 것</b>: 중기 추세 확인({@code SMA_60 > SMA_200},
     * {@code SMA_200_SLOPE_60 > 0}), 60일선 8% 이탈 배제(눌림목과 추세 붕괴를 가른다),
     * {@code CMF_21 >= -0.10}(분산 국면 배제).
     *
     * <p><b>{@code RSI_2}를 위해 지표를 새로 만들었다.</b> {@code RSI_14}로 대신하면 실측
     * 최솟값이 28이라 「10 미만」이 성립하는 날이 사실상 없고, 스토캐스틱으로 근사하면
     * 원문보다 훨씬 좁은 조건을 원문 이식이라 부르게 된다.
     *
     * <p><b>이 프리셋은 시장 국면에 가장 민감하다.</b> 강세장 한복판에서는 상승 추세와 극단
     * 과매도가 함께 나오는 종목이 드물어 0건이 정상이다. MUST NOT: 결과 수를 근거로 임계값을
     * 풀지 않는다 — 원인을 조건·데이터·국면으로 가른 뒤 사용자가 정한다.
     */
    private static ScreenPreset pullbackUptrendKr() {
        return new ScreenPreset(
                PULLBACK_UPTREND_KR,
                "상승추세 눌림목",
                "장기 상승 추세를 유지한 채 단기적으로만 급하게 밀린 종목을 남긴다. "
                        + "주가가 200일선 위에 있고 중기 추세도 상승인 상태에서, 2일 RSI가 10 미만으로 "
                        + "떨어졌으며, 60일선에서 8% 넘게 이탈하지 않았고, 자금이 빠져나가는 국면이 "
                        + "아닌 종목이다. "
                        + "2일 RSI는 이틀치만 보므로 0이나 100에 자주 붙는다 — 그것이 이 조건이 "
                        + "노리는 단기 극단이며, 반드시 추세 조건과 함께 읽는다. "
                        + "시장 국면에 가장 민감한 조건 묶음이라 강세장 한복판에서는 0건인 날이 "
                        + "흔하고, 그것은 조건이 잘못된 것이 아니다. "
                        + "점수가 아니라 충족/불충족으로 걸러낸다.",
                EvaluationMode.FILTER,
                null,
                ConditionGroup.and(List.of(
                        // 원문의 유일한 구조적 보호 장치
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        IndicatorCondition.of(Sma60Calculator.CODE, ComparisonOperator.GT,
                                ConditionOperand.IndicatorRef.of(Sma200Calculator.CODE)),
                        literal(Sma200Slope60Calculator.CODE, ComparisonOperator.GT, "0"),
                        // 원문 그대로. 근사가 아니다.
                        literal(Rsi2Calculator.CODE, ComparisonOperator.LT, "10"),
                        // 눌림목과 추세 이탈을 가른다
                        IndicatorCondition.of(AdjCloseCalculator.CODE, ComparisonOperator.GTE,
                                ConditionOperand.IndicatorRef.of(Sma60Calculator.CODE, "0.92")),
                        // 분산 국면 배제
                        literal(Cmf21Calculator.CODE, ComparisonOperator.GTE, "-0.10"))));
    }

    // ── 7. 저변동 우량 ────────────────────────────────────────────────────

    public static final String LOWVOL_QUALITY_KR = "LOWVOL_QUALITY_KR";

    /**
     * 저변동 우량 — 저변동성 이상현상.
     *
     * <p>변동성이 낮은 종목이 높은 종목보다 위험 대비 수익이 낫다는 관측은 신흥국에서 더 강하게
     * 나타난다. 여기서는 변동성을 두 축으로 본다 — <b>절대 변동폭</b>({@code ATR_PCT_14})과
     * <b>시장 동조</b>({@code BETA_104W})다.
     *
     * <p><b>국내 분포로 재보정한 것</b>: 베타 임계 <b>0.40</b>. 국내 베타 중앙값이 0.748이라
     * 미국식 「1 미만이면 저변동」을 그대로 쓰면 60%가 걸려 <b>축이 아니라 상수</b>가 된다.
     * 두 변동성 축을 같은 분위(하위 약 30%)에 맞춰 배점 3끼리 대등하게 만든다.
     *
     * <p>MUST NOT: 추세 조건을 더하지 않는다. 저변동성 이상현상은 추세와 무관한 축이고,
     * 섞으면 두 효과를 구분할 수 없다.
     */
    private static ScreenPreset lowvolQualityKr() {
        return new ScreenPreset(
                LOWVOL_QUALITY_KR,
                "저변동 우량",
                "적게 흔들리면서 이익과 현금흐름이 꾸준한 종목을 점수로 줄 세운다. "
                        + "흔들림은 두 가지로 본다 — 하루 평균 변동폭이 작은가, 그리고 시장이 움직일 때 "
                        + "덜 따라 움직이는가. "
                        + "베타 기준을 0.4로 둔 것은 편입 종목의 베타 중앙값이 0.75 근처이기 때문이다 — "
                        + "지수를 소수의 대형주가 끌어서 그렇고, 1 미만을 저변동으로 보면 절반 이상이 "
                        + "걸려 기준 구실을 못 한다. "
                        + "2년 주간 베타는 104주치 이력이 있어야 계산되며, 그만큼의 이력이 없는 종목은 "
                        + "그 조건에서 점수를 얻지 못한다. "
                        + "추세 조건은 넣지 않았다 — 적게 흔들리는 것과 오르는 것은 다른 축이라 "
                        + "섞으면 어느 쪽으로 뽑힌 종목인지 알 수 없다.",
                EvaluationMode.SCORING,
                null,
                ConditionGroup.and(List.of(
                        // 절대 변동폭 하위 약 30%
                        weighted(AtrPct14Calculator.CODE, ComparisonOperator.LTE, "5.9", "3"),
                        // 시장 동조 하위 약 30%. 국내 중앙값이 0.75라 0.9로는 축이 되지 않는다.
                        weighted(Beta104wCalculator.CODE, ComparisonOperator.LTE, "0.40", "3"),
                        weighted(ProfitableQuarters4Calculator.CODE,
                                ComparisonOperator.GTE, "4", "2"),
                        weighted(DebtRatioCalculator.CODE, ComparisonOperator.LTE, "100", "2"),
                        weighted(AccrualRatioCalculator.CODE, ComparisonOperator.LTE, "0", "1"),
                        weighted(RoaCalculator.CODE, ComparisonOperator.GTE, "0", "1"),
                        weighted(OperatingCashFlow5ySumCalculator.CODE,
                                ComparisonOperator.GT, "0", "1"),
                        weighted(Turnover60Calculator.CODE,
                                ComparisonOperator.GTE, "10000000000", "1"))));
    }

    // ── 조건 생성 헬퍼 ────────────────────────────────────────────────────

    private static IndicatorCondition required(String code, ComparisonOperator operator,
                                               String value) {
        return literal(code, operator, value).asRequired();
    }

    private static IndicatorCondition literal(String code, ComparisonOperator operator,
                                              String value) {
        return IndicatorCondition.of(code, operator, ConditionOperand.Literal.of(value));
    }

    private static IndicatorCondition weighted(String code, ComparisonOperator operator,
                                               String value, String weight) {
        return literal(code, operator, value).withWeight(new BigDecimal(weight));
    }
}
