package com.stockscreener.indicator.calculator.ichimoku;

/**
 * 원본 {@code weekly_analysis()}의 반환 튜플. 주봉만으로 정해지는 것 전부다.
 *
 * @param tkCross      전환선이 기준선 <b>위</b>면 1, 아니면 0. {@code drv_42}와 같은 부등호다
 * @param priceVsKumo  종가와 구름의 위치. 1위 0안 −1아래
 * @param kaizenWeekly {@code drv_44} 주봉 호전 분류 0~3
 * @param obvT         {@code drv_26} OBV 13주 OLS t-통계량
 * @param divergence   {@code drv_41} 1강세 −1약세 2정합 0판정불가
 */
record WeeklyAnalysis(
        int tkCross,
        int priceVsKumo,
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
        int modM
) {
}
