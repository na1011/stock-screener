package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.MacdVCalculator;
import com.stockscreener.indicator.calculator.OperatingMarginCalculator;
import com.stockscreener.indicator.calculator.PbrCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.indicator.calculator.RevenueGrowthCalculator;
import com.stockscreener.indicator.calculator.RoeCalculator;

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
                        MacdVCalculator.CODE,
                        "MACD-V 백분위",
                        "편입 종목 중 이 종목의 MACD-V가 어느 위치인지를 0~1로 나타낸 값.",
                        "1에 가까울수록 변동성 대비 상승 모멘텀이 강하다."));
    }
}
