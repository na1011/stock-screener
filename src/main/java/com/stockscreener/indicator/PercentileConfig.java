package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.ExcessReturn52wCalculator;
import com.stockscreener.indicator.calculator.GrossProfitToAssetsCalculator;
import com.stockscreener.indicator.calculator.MacdVCalculator;
import com.stockscreener.indicator.calculator.OperatingMarginCalculator;
import com.stockscreener.indicator.calculator.PbrCalculator;
import com.stockscreener.indicator.calculator.PegCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.indicator.calculator.RevenueGrowthCalculator;
import com.stockscreener.indicator.calculator.RoeCalculator;
import com.stockscreener.indicator.calculator.Turnover60Calculator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 백분위를 함께 저장할 지표의 선언.
 *
 * <p>전 지표에 대해 백분위를 만들지 <b>않는다.</b> 백분위가 의미를 갖는 것은 종목 간 비교가
 * 성립하는 지표뿐이다. 이동평균처럼 가격 단위인 지표는 "SMA200이 상위 20%"라는 조건 자체가
 * 무의미하다 — 주가가 비싼 종목이 항상 위에 온다. 저장량도 지표 수에 비례해 늘어나므로
 * 상대 비교가 실제로 쓰이는 것만 고른다.
 *
 * <p>거래대금은 원 단위인데도 넣는 이유는 <b>그 금지가 겨냥한 병에 걸리지 않기 때문</b>이다.
 * 이동평균이 안 되는 것은 "주가가 비싼 종목이 항상 위에 온다"여서인데, 거래대금은 주가가 아니라
 * 유동성을 재고 <b>시총 순위와의 순위상관이 0.722</b>다(2026-08-14 실측) — 중형주가 크게
 * 거래되면 순위가 갈린다. 0.95를 넘었으면 넣지 않았을 자리다.
 *
 * <p>지표를 추가하면서 백분위도 원하면 여기 한 줄을 더한다. 계산기 추가 자체는 여전히
 * 파일 하나로 끝나며, 백분위는 그와 별개의 선택이다.
 */
@Configuration
public class PercentileConfig {

    @Bean
    public List<PercentileSpec> percentileSpecs() {
        return List.of(
                new PercentileSpec(
                        PerCalculator.CODE,
                        "PER 백분위",
                        "코스피200·코스닥150 편입 종목 중 이 종목의 PER이 어느 위치인지를 0~1로 나타낸 값.",
                        "0에 가까울수록 PER이 낮다(이익 대비 주가가 싸다). "
                                + "'저PER 하위 20%' 같은 조건은 0.2 이하로 표현한다."),
                new PercentileSpec(
                        PegCalculator.CODE,
                        "PEG 백분위",
                        "편입 종목 중 이 종목의 PEG가 어느 위치인지를 0~1로 나타낸 값.",
                        "0에 가까울수록 성장 속도에 견줘 주가가 싸다. 절대 PEG 임계는 이익 "
                                + "사이클의 위치에 따라 재는 자리가 달라지는데 이 순위는 "
                                + "그것을 따라간다 — 이익이 줄고 있는 종목은 원본 값이 없어 "
                                + "순위에도 들지 않는다."),
                new PercentileSpec(
                        PbrCalculator.CODE,
                        "PBR 백분위",
                        "편입 종목 중 이 종목의 PBR이 어느 위치인지를 0~1로 나타낸 값.",
                        "0에 가까울수록 PBR이 낮다(자산 대비 주가가 싸다)."),
                new PercentileSpec(
                        RoeCalculator.CODE,
                        "ROE 백분위",
                        "편입 종목 중 이 종목의 ROE가 어느 위치인지를 0~1로 나타낸 값.",
                        "1에 가까울수록 ROE가 높다(수익성이 좋다). "
                                + "'고ROE 상위 20%'는 0.8 이상으로 표현한다."),
                new PercentileSpec(
                        OperatingMarginCalculator.CODE,
                        "영업이익률 백분위",
                        "편입 종목 중 이 종목의 영업이익률이 어느 위치인지를 0~1로 나타낸 값.",
                        "1에 가까울수록 본업의 수익성이 좋다."),
                new PercentileSpec(
                        RevenueGrowthCalculator.CODE,
                        "매출 성장률 백분위",
                        "편입 종목 중 이 종목의 매출 성장률이 어느 위치인지를 0~1로 나타낸 값.",
                        "1에 가까울수록 매출이 빠르게 늘고 있다."),
                new PercentileSpec(
                        Turnover60Calculator.CODE,
                        "거래대금 백분위",
                        "편입 종목 중 이 종목의 60일 평균 거래대금이 어느 위치인지를 0~1로 나타낸 값.",
                        "1에 가까울수록 많이 거래된다(사고팔기 쉽다). 거래대금은 원 단위라 "
                                + "절대값으로는 종목 간 비교가 어렵고, 이 순위가 그것을 대신한다."),
                new PercentileSpec(
                        MacdVCalculator.CODE,
                        "MACD-V 백분위",
                        "편입 종목 중 이 종목의 MACD-V가 어느 위치인지를 0~1로 나타낸 값.",
                        "1에 가까울수록 변동성 대비 상승 모멘텀이 강하다."),
                new PercentileSpec(
                        GrossProfitToAssetsCalculator.CODE,
                        "총이익성 백분위",
                        "편입 종목 중 이 종목의 총이익성이 어느 위치인지를 0~1로 나타낸 값.",
                        "1에 가까울수록 같은 자산으로 더 많은 총이익을 만들었다. "
                                + "매출원가 개념이 없는 업종은 원본 값이 없어 순위에도 들지 않는다."),
                new PercentileSpec(
                        ExcessReturn52wCalculator.CODE,
                        "52주 초과 수익률 백분위",
                        "편입 종목 중 이 종목의 52주 초과 수익률이 어느 위치인지를 0~1로 나타낸 값.",
                        "1에 가까울수록 1년 동안 시장을 크게 이겼다. 추세 전략이 말하는 "
                                + "'상대강도 상위'가 이 순위다 — 다만 분기별 가중을 쓰는 "
                                + "미국식 RS 등급과는 계산이 다르다."));
    }
}
