package com.stockscreener.indicator.calculator.ichimoku;

/**
 * 원본 {@code weekly_analysis()}의 반환 튜플. 주봉만으로 정해지는 것 전부다.
 *
 * @param stage        {@code stage_label_final}. 1신생 2진행 3중기 4성숙 5부재 6성숙기
 * @param kaizenWeekly {@code drv_44} 주봉 호전 분류 0~3
 * @param obvT         {@code drv_26} OBV 13주 OLS t-통계량
 * @param divergence   {@code drv_41} 1강세 −1약세 2정합 0판정불가
 * @param leaf         {@code leaf} 1~9
 * @param t3yeok       {@code drv_50} −2성숙기 −1미성립 0~129
 * @param freshness    {@code drv_51} −1해당없음 0~128
 */
record WeeklyAnalysis(
        int stage,
        int kaizenWeekly,
        double obvT,
        int divergence,
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
        int leaf,
        int t3yeok,
        int freshness
) {

    /** 매트릭스 점수화가 걸리는 단계인가. {@code stage 5·6}이면 모든 점수가 0이다. */
    boolean stageApplies() {
        return stage >= 1 && stage <= 4;
    }
}
