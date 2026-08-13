package com.stockscreener.indicator.calculator.ichimoku;

/**
 * 원본 {@code weekly_analysis()}의 이식. 주봉 창 하나를 받아 13 수식자·leaf·단계를 낸다.
 *
 * <p>MUST: 부등호와 경계를 원본 그대로 옮긴다. 여기 나오는 값은 전부 <b>정수</b>라 하나가
 * 뒤집혀도 결과가 여전히 그럴듯하고 어떤 테스트도 깨지지 않는다({@code phase-criteria.md} §P12).
 * 원본의 줄 번호를 주석에 남기는 이유가 그것이다 — 대조가 유일한 검증 수단이다.
 *
 * <p>MUST NOT: 죽은 코드를 옮기지 않는다(ADR-0046). 옮기면 검증 대상만 늘고 출력은 안 바뀐다.
 * 여기서 빠진 것: {@code vol_sma_w} · {@code drv_04} · {@code drv_49} ·
 * {@code drv_36~38}(V·E·NT 목표가, leaf가 쓰는 것은 {@code drv_35} 하나다).
 */
final class WeeklyAnalyzer {

    /** {@code arr_3yeok}·{@code arr_22}·VW 탐색이 도는 범위. */
    private static final int SCAN = 130;

    /**
     * 시간론 anchor. <b>실효는 9개다</b> — {@code drv_51 ∈ [0,128]}이라 172·200·257은 도달
     * 불가이지만 <b>129는 {@code drv_51 = 128}에서 ±1로 걸린다.</b> 그때 stage 4이고
     * {@code score_e}가 ±3이라 <b>빠뜨리면 6점이 뒤집힌다</b>(ADR-0046).
     */
    private static final int[] HOSODA_ANCHORS = {9, 17, 26, 33, 42, 52, 65, 76, 129, 172, 200, 257};

    private WeeklyAnalyzer() {
    }

    static WeeklyAnalysis analyze(WeeklyWindow w) {
        double atr14 = w.atr14Current();
        double atr26 = w.atr26(0);

        // ---- DRV-22 130주 정규화 두께 중앙값 -----------------------------------------
        double[] normalized = new double[SCAN];
        for (int i = 0; i < SCAN; i++) {
            double thickness = Math.abs(w.spanA(i) - w.spanB(i));
            double a = w.atr26(i);
            normalized[i] = a != 0 ? thickness / a : 0.0;
        }
        double drv22 = IchimokuMath.median(normalized);

        double kumoThickness = Math.abs(w.spanA(0) - w.spanB(0));
        double drv02 = atr26 != 0 ? kumoThickness / atr26 : 0.0;

        // ---- DRV-24 Chikou open space (27주 overlap, idx 13~39) ----------------------
        int overlap = 0;
        double close = w.close(0);
        for (int i = 13; i <= 39; i++) {
            boolean ohlcOverlap = w.low(i) <= close && close <= w.high(i);
            boolean kumoOverlap = w.kumoBottom(i) <= close && close <= w.kumoTop(i);
            if (ohlcOverlap || kumoOverlap) {
                overlap++;
            }
        }
        int drv24 = overlap == 0 ? 1 : 0;

        // ---- DRV-26·41 OBV와 가격의 13주 t-통계량 --------------------------------------
        double[] obvSeries = new double[IchimokuMath.REGRESSION_POINTS];
        double[] closeSeries = new double[IchimokuMath.REGRESSION_POINTS];
        double[] tenkanSeries = new double[IchimokuMath.REGRESSION_POINTS];
        double[] kijunSeries = new double[IchimokuMath.REGRESSION_POINTS];
        double[] spanASeries = new double[IchimokuMath.REGRESSION_POINTS];
        for (int k = 0; k < IchimokuMath.REGRESSION_POINTS; k++) {
            // 과거 → 현재 순서로 뒤집는다. 창의 인덱스는 현재가 0이다.
            int i = IchimokuMath.REGRESSION_POINTS - 1 - k;
            obvSeries[k] = w.obv(i);
            closeSeries[k] = w.close(i);
            tenkanSeries[k] = w.tenkan(i);
            kijunSeries[k] = w.kijun(i);
            spanASeries[k] = w.spanA(i);
        }
        double drv26 = IchimokuMath.tStatistic(obvSeries);
        double priceT = IchimokuMath.tStatistic(closeSeries);
        int signObv = drv26 >= 1.96 ? 1 : drv26 <= -1.96 ? -1 : 0;
        int signPrice = priceT >= 1.96 ? 1 : priceT <= -1.96 ? -1 : 0;
        int drv41 = signObv == 1 && signPrice == -1 ? 1
                : signObv == -1 && signPrice == 1 ? -1
                : signObv == signPrice && signObv != 0 ? 2 : 0;

        // ---- DRV-39·40·53 기울기 분류. 임계가 39·40은 0.5×ATR-14, 53은 0.25×ATR-14다 ----
        int drv39 = slopeClass(IchimokuMath.slope(tenkanSeries) * 12, 0.5 * atr14);
        int drv40 = slopeClass(IchimokuMath.slope(kijunSeries) * 12, 0.5 * atr14);
        int drv53 = slopeClass(IchimokuMath.slope(spanASeries) * 12, 0.25 * atr14);

        // ---- DRV-42 TK 위치 (1강 0중 −1약 −2미발생) ------------------------------------
        double tenkan = w.tenkan(0);
        double kijun = w.kijun(0);
        double kumoTop = w.kumoTop(0);
        int drv42;
        if (tenkan > kijun) {
            boolean tenkanAbove = tenkan > kumoTop;
            boolean kijunAbove = kijun > kumoTop;
            drv42 = tenkanAbove && kijunAbove ? 1 : (tenkanAbove != kijunAbove ? 0 : -1);
        } else {
            drv42 = -2;
        }

        // ---- DRV-44 주봉 호전 분류 ----------------------------------------------------
        int drv44 = (w.kumoPosition(0) == 1 ? 1 : 0)
                + (tenkan > kijun ? 1 : 0)
                + (close > w.close(26) ? 1 : 0);

        // ---- DRV-46 Senkou B 26주 평탄 -------------------------------------------------
        double sbMax = Double.NEGATIVE_INFINITY;
        double sbMin = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 26; i++) {
            sbMax = Math.max(sbMax, w.spanB(i));
            sbMin = Math.min(sbMin, w.spanB(i));
        }
        double sbRange = sbMax - sbMin;
        int drv46 = sbRange < 0.25 * atr26 ? 1 : sbRange < 0.5 * atr26 ? 0 : -1;

        // ---- DRV-47 Kumo 내부 체류 -----------------------------------------------------
        int residence = 0;
        if (w.kumoPosition(0) == 0) {
            residence = 1;
            for (int i = 1; i <= 129; i++) {
                if (w.kumoPosition(i) != 0) {
                    break;
                }
                residence++;
            }
        }
        int drv47 = residence == 0 ? 0 : residence < 9 ? 1 : 2;

        // ---- DRV-50·51 t_3yeok 식별 ----------------------------------------------------
        boolean[] threeWay = new boolean[SCAN];
        boolean allTrue = true;
        boolean allFalse = true;
        for (int i = 0; i < SCAN; i++) {
            // D-8 패치: idx_26ago가 언제나 배열 안이다(156 확장).
            boolean hit = w.close(i) > w.kumoTop(i)
                    && w.tenkan(i) > w.kijun(i)
                    && w.close(i) > w.close(i + 26);
            threeWay[i] = hit;
            allTrue &= hit;
            allFalse &= !hit;
        }
        int firstIndex = -1;
        for (int i = 0; i <= 128; i++) {
            if (threeWay[i] && !threeWay[i + 1]) {
                firstIndex = i;
                break;
            }
        }
        int drv50 = allTrue ? -2 : (allFalse || firstIndex == -1) ? -1 : firstIndex;
        int drv51 = drv50 >= 0 ? drv50 : -1;

        // ---- DRV-52 시간론 ±1주 매칭 ---------------------------------------------------
        int drv52 = -1;
        if (drv51 >= 0) {
            boolean matched = false;
            for (int anchor : HOSODA_ANCHORS) {
                if (Math.abs(drv51 - anchor) <= 1) {
                    matched = true;
                    break;
                }
            }
            drv52 = matched ? 1 : 0;
        }

        // ---- DRV-54 미래 Kumo Twist (n=1~26) -------------------------------------------
        // D-10 패치: idx_a = idx_b = 26 − n. n=26이면 인덱스 0이라 자기 자신과 비교하게 되어
        // 발화하지 않는다 — 실효 범위는 1~25다(ADR-0046 §도달 불가).
        double futureA = (tenkan + kijun) / 2.0;
        double futureB = (w.high52(0) + w.low52(0)) / 2.0;
        boolean currentColor = futureA > futureB;
        int drv54 = -1;
        for (int n = 1; n <= 26; n++) {
            int idx = 26 - n;
            double a = (w.tenkan(idx) + w.kijun(idx)) / 2.0;
            double b = (w.high52(idx) + w.low52(idx)) / 2.0;
            if ((a > b) != currentColor) {
                drv54 = n;
                break;
            }
        }

        // ---- 값폭관측론 prejudgment ------------------------------------------------------
        boolean noneAbove = true;
        for (int i = 0; i <= 25; i++) {
            if (w.kumoPosition(i) == 1) {
                noneAbove = false;
                break;
            }
        }
        boolean dropTrend = w.kumoPosition(0) == -1 && noneAbove;

        boolean shinsaeng = drv50 >= 0 && drv51 < 9;
        boolean progressed = drv50 >= 0 && drv51 >= 9;

        ValueWidth vw = ValueWidth.identify(w, drv50, shinsaeng, progressed, dropTrend);

        // ---- 단계 라벨 --------------------------------------------------------------------
        int stage = drv50 == -2 ? 6
                : (drv50 == -1 || drv51 < 0) ? 5
                : drv51 < 9 ? 1 : drv51 < 17 ? 2 : drv51 < 26 ? 3 : 4;

        return new WeeklyAnalysis(
                stage,
                drv44,
                drv26,
                drv41,
                w.spanA(0) > w.spanB(0) ? 1 : -1,           // mod_a 현재 Kumo 색상
                currentColor ? 1 : -1,                       // mod_b 미래 Kumo 색상(DRV-10)
                drv02 > drv22 ? 1 : 0,                       // mod_c 두께 vs 130주 중앙값
                drv24,                                       // mod_d Chikou 조망
                drv52,                                       // mod_e 시간론
                drv39,                                       // mod_f Tenkan 기울기
                drv40,                                       // mod_g Kijun 기울기
                drv42,                                       // mod_h TK 위치
                drv46,                                       // mod_j Senkou B 평탄
                drv47,                                       // mod_k Kumo 체류
                drv53,                                       // mod_l Senkou A 기울기
                drv54,                                       // mod_m 미래 트위스트
                vw.leaf(),
                drv50,
                drv51);
    }

    /** {@code change >= +임계 ? 1 : change <= −임계 ? −1 : 0}. 세 자리가 같은 모양이다. */
    private static int slopeClass(double change, double threshold) {
        return change >= threshold ? 1 : change <= -threshold ? -1 : 0;
    }
}
