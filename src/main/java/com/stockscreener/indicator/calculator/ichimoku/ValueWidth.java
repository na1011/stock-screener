package com.stockscreener.indicator.calculator.ichimoku;

/**
 * 호소다 값폭관측론(§valuewidth) VW1~VW6과 decision_tree leaf.
 *
 * <p>IMPORTANT — <b>{@code stage 1}(신생)은 언제나 {@code leaf 9}이고 {@code leaf_score}가 0이다.</b>
 * 즉 코드량이 가장 큰 이 부분이 <b>Tier 1 판정과 무관하다</b>(ADR-0046). 그 사실을 모르면
 * 여기 시간을 쓰고 Tier 1이 안 바뀌는 것을 결함으로 읽는다.
 *
 * <p>MUST: 동점 처리를 VW3·VW4에서만 검증한다. 규칙은 "더 과거(인덱스 큰 쪽) 채택"이고
 * VW3은 <b>과거방향 {@code >=} · 미래방향 {@code >}의 의도된 비대칭</b>이다.
 * <b>VW2는 {@code pivot_a}의 인덱스를 쓰지 않아 {@code <=} ↔ {@code <}가 출력에 흔적을 남기지
 * 않는다</b> — 거기에 테스트를 걸면 항등식이 된다(D-13·D-14는 무의미한 패치였다).
 */
final class ValueWidth {

    private static final int SCAN = 130;
    /** 돌파를 새 파동의 시작으로 인정하는 최소 이탈 주수. */
    private static final int BREAK_STREAK = 13;

    private final int leaf;
    private final double pivotA;
    private final double pivotB;
    private final double pivotC;
    private final int pivotBIndex;
    private final int pivotCIndex;

    private ValueWidth(int leaf, double pivotA, double pivotB, double pivotC,
                       int pivotBIndex, int pivotCIndex) {
        this.leaf = leaf;
        this.pivotA = pivotA;
        this.pivotB = pivotB;
        this.pivotC = pivotC;
        this.pivotBIndex = pivotBIndex;
        this.pivotCIndex = pivotCIndex;
    }

    int leaf() {
        return leaf;
    }

    /**
     * 피벗은 저장하지 않는다 — 원본도 반환만 하고 소비하지 않는다({@code w_pa}·{@code w_pb}·
     * {@code w_pc}). <b>여기 접근자를 두는 이유는 동점 처리를 테스트가 직접 겨눌 수 있게 하기
     * 위해서다</b>: leaf만 보면 동점 규칙이 출력에 흔적을 남기지 않는 경우가 있고, 그러면
     * 그 테스트는 항등식이 된다.
     */
    double pivotA() {
        return pivotA;
    }

    double pivotB() {
        return pivotB;
    }

    double pivotC() {
        return pivotC;
    }

    int pivotBIndex() {
        return pivotBIndex;
    }

    int pivotCIndex() {
        return pivotCIndex;
    }

    /**
     * @param t3yeok     {@code drv_50}. −2 성숙기 · −1 미성립 · 0~129
     * @param shinsaeng  {@code drv_50 >= 0 && drv_51 < 9} — §valuewidth 적용 부재 구간
     * @param progressed {@code drv_50 >= 0 && drv_51 >= 9} — 호소다 강한 정의가 서는 구간
     */
    static ValueWidth identify(WeeklyWindow w, int t3yeok,
                               boolean shinsaeng, boolean progressed, boolean dropTrend) {
        // ---- VW1: t_break 식별 --------------------------------------------------------
        boolean[] above = new boolean[SCAN];
        for (int i = 0; i < SCAN; i++) {
            above[i] = w.close(i) > w.kumoTop(i);
        }

        int tBreak = -1;
        boolean fallback = true;
        if (!shinsaeng) {
            if (progressed) {
                // SP Patch C-1 case_B: 호소다 강한 정의 — 호전 3요건 충족 시점이 곧 돌파다.
                tBreak = t3yeok;
                fallback = false;
            } else {
                // case_C·D: 기존 Kumo 돌파 식별. 과거에서 현재로 훑는다.
                int belowStreak = 0;
                boolean firstIteration = true;
                for (int i = 0; i < SCAN; i++) {
                    int idx = SCAN - 1 - i;
                    if (above[idx]) {
                        if (firstIteration || belowStreak >= BREAK_STREAK) {
                            tBreak = idx;
                            if (belowStreak >= BREAK_STREAK) {
                                fallback = false;
                            }
                        }
                        belowStreak = 0;
                    } else {
                        belowStreak++;
                    }
                    // MUST: 조건 밖에서 내린다. 원본이 매 반복 끝에서 내리므로 첫 반복(idx=129)
                    // 에서만 참이다 — 안쪽으로 옮기면 "구름 위였던 첫 주"마다 참이 된다.
                    firstIteration = false;
                }
            }
        }

        // ---- VW2: 피벗 A 1차 ------------------------------------------------------------
        double pivotA = Double.NaN;
        if (!dropTrend && !shinsaeng) {
            if (fallback) {
                pivotA = lowestLow(w, 0, SCAN - 1);
            } else if (tBreak >= 0 && !progressed) {
                // case_C 추세 성숙기: 돌파 직후 26주.
                pivotA = lowestLow(w, Math.max(0, tBreak + 1), Math.min(SCAN - 1, tBreak + 26));
            } else if (tBreak >= 0) {
                // case_B 임시 산출. VW3에서 피벗 B가 나오면 아래에서 다시 잡는다.
                pivotA = lowestLow(w, Math.max(0, t3yeok - 13), Math.min(SCAN - 1, t3yeok + 13));
            }
        }

        // ---- VW3: 피벗 B — 18주 양측 윈도우의 단독 최고값 --------------------------------
        double pivotB = Double.NaN;
        int pivotBIdx = -1;
        if (!dropTrend && !shinsaeng && !fallback && tBreak >= 0) {
            for (int step = 0; step < SCAN; step++) {
                int idx = tBreak - step;
                if (idx < 0) {
                    break;
                }
                int forwardAvailable = idx;
                // A-3 패치: 미래 가용 주수가 5 미만이면 swing 자격을 준다.
                // [Tenkan 9주 / 2 = 4.5 → 5주 환원]
                if (forwardAvailable < 5) {
                    continue;
                }
                int backLimit = Math.min(9, SCAN - 1 - idx);
                int forwardLimit = Math.min(9, forwardAvailable);
                double candidate = w.high(idx);
                boolean swing = true;

                // 과거 방향(인덱스 큰 쪽). >= 이므로 동점이면 자격을 잃는다.
                for (int k = 1; k <= backLimit && swing; k++) {
                    if (w.high(idx + k) >= candidate) {
                        swing = false;
                    }
                }
                // 미래 방향(현재 쪽). > 이므로 동점이면 자격을 유지한다 — 의도된 비대칭이고,
                // 그 결과가 "동점이면 더 과거를 채택"이다.
                for (int k = 1; k <= forwardLimit && swing; k++) {
                    if (w.high(idx - k) > candidate) {
                        swing = false;
                    }
                }
                if (swing) {
                    pivotBIdx = idx;
                    pivotB = candidate;
                    break;
                }
            }
        }

        // ---- SP Patch C-2B: case_B 한정 피벗 A 재산출 -------------------------------------
        // 범위 [pivot_b 시점, drv_50 + 13]. pivot_b_idx <= drv_50 이므로 언제나 start <= end다.
        if (progressed && pivotBIdx >= 0) {
            pivotA = lowestLow(w, pivotBIdx, Math.min(SCAN - 1, t3yeok + 13));
        }

        // ---- VW4: 피벗 C — B 이후 ~ 현재의 최저 저가 --------------------------------------
        // D-20 패치: 피벗 B 자체를 포함하지 않는다(idx < pivot_b_idx).
        double pivotC = Double.NaN;
        int pivotCIdx = -1;
        if (pivotBIdx >= 0) {
            double min = Double.NaN;
            // 과거에서 현재로 훑는다. 비교가 strict `<`라 동점이면 먼저 본 쪽(더 과거)이 남는다.
            for (int idx = pivotBIdx - 1; idx >= 0; idx--) {
                double low = w.low(idx);
                if (Double.isNaN(min) || low < min) {
                    min = low;
                    pivotCIdx = idx;
                }
            }
            pivotC = min;
        }

        // ---- VW5: C 성숙 판정 -------------------------------------------------------------
        int cMaturity = -1;
        if (pivotCIdx >= 0) {
            double tenkanAtC = w.tenkan(pivotCIdx);
            boolean matured = false;
            for (int idx = pivotCIdx - 1; idx >= 0; idx--) {
                if (w.high(idx) > tenkanAtC) {
                    matured = true;
                    break;
                }
            }
            cMaturity = matured ? 1 : 0;
        }

        // ---- VW6·VW7: 유효성과 N 목표가 ----------------------------------------------------
        boolean pass = !Double.isNaN(pivotA) && !Double.isNaN(pivotB) && !Double.isNaN(pivotC)
                && cMaturity == 1 && pivotA < pivotC && pivotC < pivotB;
        // leaf가 쓰는 목표가는 N 하나다. V·E·NT(drv_36~38)는 선언만 되고 미소비라 옮기지 않는다.
        double targetN = pass ? pivotC + (pivotB - pivotA) : Double.NaN;

        return new ValueWidth(leafOf(w.close(0), shinsaeng, dropTrend, fallback,
                pivotA, pivotB, pivotC, cMaturity, pass, targetN),
                pivotA, pivotB, pivotC, pivotBIdx, pivotCIdx);
    }

    /**
     * decision_tree leaf.
     *
     * <p><b>{@code leaf 7}(데이터 부족)은 도달 불가다</b> — 위 가지들이 모든 경우를 덮는다.
     * 초기값으로만 남으며, 그것이 원본과 같은 모양이다.
     */
    private static int leafOf(double close, boolean shinsaeng, boolean dropTrend, boolean fallback,
                              double pivotA, double pivotB, double pivotC,
                              int cMaturity, boolean pass, double targetN) {
        if (shinsaeng) {
            // SP Patch C-4 Step 0. 신생 단계의 자연 상태이고 leaf_score는 0이다.
            return 9;
        }
        if (dropTrend) {
            // D-11 패치: drop_trend를 파동 실패가 아니라 파동 유보로 환원한다.
            return 8;
        }
        if (fallback) {
            // A 대용. stage 5·6 전용이라 leaf_score가 언제나 0이며,
            // (leaf 3, stage 4) 셀은 도달 불가다(ADR-0046).
            return 3;
        }
        boolean anyMissing = Double.isNaN(pivotA) || Double.isNaN(pivotB) || Double.isNaN(pivotC);
        if (!pass) {
            if (anyMissing) {
                return 8;
            }
            if (!(pivotA < pivotC && pivotC < pivotB)) {
                return 2;
            }
            if (cMaturity == 0) {
                return 4;
            }
            return 7;
        }
        if (close < pivotA) {
            return 6;
        }
        return close >= targetN ? 5 : 1;
    }

    /** {@code [from, to]}의 최저 저가. 동점이면 <b>더 과거</b>가 남는다({@code <=}). */
    private static double lowestLow(WeeklyWindow w, int from, int to) {
        double min = Double.NaN;
        for (int i = from; i <= to; i++) {
            double low = w.low(i);
            if (Double.isNaN(min) || low <= min) {
                min = low;
            }
        }
        return min;
    }
}
