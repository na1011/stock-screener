package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.DebtRatioCalculator;
import com.stockscreener.indicator.calculator.OperatingMarginCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 업종 내 상대값을 함께 저장할 지표의 선언.
 *
 * <p>{@link PercentileConfig}와 같은 형태이고, 고르는 기준도 같다 — <b>업종 상대 비교가
 * 실제로 뜻을 갖는 지표만</b> 넣는다. 이동평균처럼 가격 단위인 지표는 업종으로 묶어도
 * 비싼 주식이 위에 오고, 시가총액처럼 규모 자체가 조건인 지표는 업종 안에서 매기면
 * 거르려던 의도와 반대가 된다.
 *
 * <p>앞의 셋은 원본 명세가 업종 상대로 요구한 조건에 대응한다 —
 * H-02(부채비율)·S-01(밸류에이션)·S-02(이익수익률). 그 대응은 ADR-0039의 조건 대조표가
 * 정본이며, <b>모집단이 업종으로 바뀌어도 "치환, 뜻이 다르다"가 사라지지는 않는다</b>
 * (S-02는 여전히 EBIT/EV가 아니라 영업이익률이고, S-01은 자체 5년 이력이 없다).
 *
 * <p>넷째(PER 중앙값)는 조건이 아니라 <b>사용자가 보는 참조값</b>이다. 조건은 이미
 * {@code PER_SECTOR_PCTL}이 걸고, 그 백분위만으로는 "업종이 원래 몇 배인가"에 답하지 못한다.
 *
 * <p>MUST: 한 원본 지표에 선언이 둘이면 표본 수 행을 공유한다. {@code PER}이 지금 그렇다 —
 * 두 선언이 같은 {@code PER_SECTOR_N}을 쓰고 값도 같지만(같은 모집단·같은 임계),
 * 카탈로그 이름은 <b>원본 지표에서</b> 지어야 순서에 흔들리지 않는다.
 */
@Configuration
public class SectorMetricConfig {

    @Bean
    public List<SectorMetricSpec> sectorMetricSpecs() {
        return List.of(
                new SectorMetricSpec(
                        DebtRatioCalculator.CODE,
                        SectorMetricSpec.Kind.MEDIAN_RATIO,
                        "부채비율 업종 배수",
                        "이 종목의 부채비율이 같은 업종 중앙값의 몇 배인지. 1.0이면 업종 중앙값과 같다.",
                        "1.0보다 작을수록 같은 업종 안에서 부채가 가벼운 편이다. "
                                + "업종마다 정상 부채 수준이 달라 절대 부채비율만으로는 가려지지 않는 차이를 본다."),
                new SectorMetricSpec(
                        PerCalculator.CODE,
                        SectorMetricSpec.Kind.PERCENTILE,
                        "PER 업종 백분위",
                        "같은 업종 종목 중 이 종목의 PER이 어느 위치인지를 0~1로 나타낸 값.",
                        "0에 가까울수록 같은 업종 안에서 PER이 낮다(이익 대비 주가가 싸다). "
                                + "'업종 대비 저평가'는 0.5 이하로 표현한다."),
                new SectorMetricSpec(
                        OperatingMarginCalculator.CODE,
                        SectorMetricSpec.Kind.PERCENTILE,
                        "영업이익률 업종 백분위",
                        "같은 업종 종목 중 이 종목의 영업이익률이 어느 위치인지를 0~1로 나타낸 값.",
                        "1에 가까울수록 같은 업종 안에서 본업의 수익성이 좋다."),
                new SectorMetricSpec(
                        PerCalculator.CODE,
                        SectorMetricSpec.Kind.MEDIAN,
                        "PER 업종 중앙값",
                        "이 종목이 속한 업종의 PER 중앙값. 같은 업종 종목은 같은 값을 본다.",
                        "이 종목의 PER을 옆에 두고 읽는 참조값이다. 업종마다 정상 PER 수준이 "
                                + "달라 절대값만으로는 비싼지 알 수 없다. 유리한 방향이 따로 없다."));
    }
}
