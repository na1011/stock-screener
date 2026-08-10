package com.stockscreener.screening.preset;

import com.stockscreener.indicator.PercentileSpec;
import com.stockscreener.indicator.calculator.AdjCloseCalculator;
import com.stockscreener.indicator.calculator.DebtRatioCalculator;
import com.stockscreener.indicator.calculator.MacdVCalculator;
import com.stockscreener.indicator.calculator.OperatingIncomeGrowthCalculator;
import com.stockscreener.indicator.calculator.OperatingMarginCalculator;
import com.stockscreener.indicator.calculator.PbrCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.indicator.calculator.RevenueGrowthCalculator;
import com.stockscreener.indicator.calculator.RoeCalculator;
import com.stockscreener.indicator.calculator.Rsi14Calculator;
import com.stockscreener.indicator.calculator.Sma200Calculator;
import com.stockscreener.indicator.calculator.Sma20Calculator;
import com.stockscreener.indicator.calculator.Sma60Calculator;
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

    @Bean
    public List<ScreenPreset> screenPresets() {
        return List.of(valueKr(), qvgKr(), momentumKr());
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

    private static IndicatorCondition literal(String code, ComparisonOperator operator, String value) {
        return IndicatorCondition.of(code, operator, ConditionOperand.Literal.of(value));
    }

    private static IndicatorCondition weighted(String code, ComparisonOperator operator,
                                               String value, String weight) {
        return literal(code, operator, value).withWeight(new BigDecimal(weight));
    }
}
