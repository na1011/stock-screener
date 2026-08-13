package com.stockscreener.indicator.calculator.ichimoku;

/**
 * 한 종목·한 거래일의 일목 분석 결과. 지표 30개가 여기서 각자 자기 값을 꺼낸다.
 *
 * <p>MUST: 계산기 30개가 이 레코드 <b>하나</b>를 나눠 쓴다. 지표마다 다시 분석하면 값이 맞아도
 * 백필이 실용 시간 안에 끝나지 않는다({@code phase-criteria.md} §P12).
 *
 * <p>저장하지 않는 것: {@code score_a}~{@code score_m}·leaf·D2·D5 점수 16개. 여기 담긴 값과
 * 임계 상수로 전부 복원되는 순수 중복이고, 저장하면 {@code indicator_value}가 2.4배가 된다.
 *
 * @param tier         1~4. {@code ICHIMOKU_SP_KR}이 {@code <= 3}으로 거른다
 * @param signal       {@code score_diff}(강세−약세). 같은 Tier 안 1차 우선순위
 * @param freshness    {@code drv_51}. <b>−1은 "해당 없음"이라 조건의 좌변으로 쓰면 안 된다</b>
 * @param extSignal    {@code score_d2 + score_d5}. 3차 우선순위
 * @param pillar       13 수식자 양수합 − 음수합. 4차 우선순위
 * @param stage        1신생 2진행 3중기 4성숙 5부재 6성숙기. <b>명목형이다</b>
 * @param confidence   1강한 2중간 3약한. 단조 순서형
 * @param conclusion   1강세 −1약세 0중립
 * @param kaizenMatch  {@code drv_45}. 원본의 수식자 i와 <b>같은 값</b>이라 코드를 따로 두지 않는다
 * @param t3yeok       {@code drv_50}. −2 성숙기 · −1 미성립 · 0~129
 * @param obvT         {@code drv_26}. 유일한 실수 지표이고 ±1.96이 임계다
 * @param divergence   {@code drv_41}. 1강세 −1약세 2정합 0판정불가. <b>명목형이다</b>
 * @param excessReturn {@code drv_55}. 26주 초과 수익률(%)
 */
public record IchimokuAnalysis(
        int tier,
        int signal,
        int freshness,
        int extSignal,
        int pillar,
        int stage,
        int confidence,
        int conclusion,
        int bull,
        int bear,
        int modA,
        int modB,
        int modC,
        int modD,
        int modE,
        int modF,
        int modG,
        int modH,
        int modJ,
        int modK,
        int modL,
        int modM,
        int kaizenWeekly,
        int kaizenDaily,
        int kaizenMatch,
        int leaf,
        int t3yeok,
        double obvT,
        int divergence,
        double excessReturn
) {
}
