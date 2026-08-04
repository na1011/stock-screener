package com.stockscreener.indicator;

import java.util.List;

/**
 * 지표 하나를 계산한다. 지표를 추가하는 유일한 확장 지점이다.
 *
 * <p>MUST: 새 지표는 이 인터페이스를 구현한 {@code @Component} 클래스 <b>하나</b>를 추가하는 것으로
 * 끝나야 한다. 레지스트리 수정도, enum 추가도, 스키마 변경도, 번역기 수정도 없어야 한다.
 * 그 비용이 1을 넘으면 확장 지점이 실패한 것이며, 지표가 계속 추가된다는 전제(spec §7)가 무너진다.
 *
 * <p>MUST: 원자료에서 자체 계산한다. 외부 API가 계산해 주는 값을 그대로 저장하지 않는다
 * (spec §7, data-pipeline.md). 소스마다 계산식이 달라 한국·미국 두 시장의 값이 어긋나고,
 * 새 지표를 추가할 때 소스가 그 값을 제공하는지에 발목이 잡힌다.
 *
 * <p>계산할 수 없는 날짜에는 값을 내지 않는다. 이력이 부족한 구간에 부정확한 값을 채우는 대신
 * 행을 만들지 않으면, 조건 판정에서 해당 종목이 자연히 제외된다({@code EXISTS}가 거짓이 된다).
 */
public interface IndicatorCalculator {

    /**
     * 지표 코드. {@code indicator} 카탈로그와 {@code indicator_value.indicator_code}의 값이 된다.
     *
     * <p>MUST: 안정적이어야 한다. 저장된 조건이 이 코드로 지표를 가리키므로,
     * 코드를 바꾸면 사용자가 저장해둔 화면이 깨진다.
     */
    String code();

    /**
     * 계산에 필요한 원자료의 종류와 기간, 그리고 다른 지표에 대한 의존.
     *
     * <p>러너가 이 선언을 보고 무엇을 얼마나 읽어올지, 어떤 순서로 계산할지 결정한다.
     */
    DataRequirement dataRequirement();

    /**
     * 한 종목에 대해 계산한다.
     *
     * <p>컨텍스트에 담긴 원자료는 {@link #dataRequirement()}가 요구한 기간을 충족한다.
     * 계산 가능한 날짜에 대해서만 결과를 낸다 — 워밍업 구간은 그냥 빠진다.
     */
    List<IndicatorPoint> calculate(IndicatorContext context);
}
