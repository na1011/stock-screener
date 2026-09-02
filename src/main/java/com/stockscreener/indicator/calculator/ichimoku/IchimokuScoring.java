package com.stockscreener.indicator.calculator.ichimoku;

import java.util.OptionalDouble;

/**
 * 13 수식자 단계 의존 매트릭스(52셀) + leaf + 외부 시그널 → 9등급 → Tier.
 *
 * <p>IMPORTANT — 여기 나오는 값은 전부 <b>정수</b>다. 셀 하나가 틀려도 Tier는 여전히 1~4이고
 * 신호 강도도 그럴듯한 범위에 있어, <b>어떤 테스트도 깨지지 않는다.</b> 그래서 셀마다 원본의
 * 문장을 주석으로 남긴다 — 대조가 유일한 검증 수단이다({@code phase-criteria.md} §P12).
 */
final class IchimokuScoring {

    /**
     * 임계 셋. 원본은 {@code input.int}라 사용자가 바꿀 수 있지만 <b>코드 상수로 고정한다</b>
     * (ADR-0046). {@code Tier}·{@code CONCLUSION}·{@code CONFIDENCE}가 전부 이 값의 함수이고,
     * 설정으로 노출하면 같은 종목이 화면마다 다른 Tier를 갖는다.
     *
     * <p>값은 원본 파일이 스스로 적어 둔 기본값이다({@code input.int(5,…)}·{@code (15,…)}·{@code (8,…)}).
     */
    static final int THRESHOLD_CONCLUSION = 5;
    static final int THRESHOLD_STRONG = 15;
    static final int THRESHOLD_MEDIUM = 8;

    private IchimokuScoring() {
    }

    /**
     * <p>IMPORTANT — <b>{@code excessReturn}이 비면 D5에 기대는 일곱을 {@code null}로 낸다</b>
     * (tier·signal·extSignal·confidence·conclusion·bull·bear). <b>0으로 채우지 마라</b> —
     * "지수 없음"이 "초과 수익률 0%"가 되고, 그 종목은 D5가 0인 종목과 화면에서 구별되지
     * 않는다. 나머지 여섯(pillar·freshness·stage·leaf·t3yeok·kaizenMatch)은 지수를 쓰지 않아
     * 그대로 나온다.
     */
    static IchimokuAnalysis score(WeeklyAnalysis w, int kaizenDaily, OptionalDouble excessReturn) {
        boolean apply = w.stageApplies();
        int stage = w.stage();

        // DRV-45 일봉 호전 합치. 원본의 수식자 i가 이것과 같은 값이다.
        int kaizenMatch = w.kaizenWeekly() >= 2
                ? (kaizenDaily >= 2 ? 2 : kaizenDaily == 1 ? 1 : 0)
                : -1;

        // 수식자 a (Kumo 색상): 단계 무관 ±3
        int scoreA = apply ? (w.modA() == 1 ? 3 : -3) : 0;
        // 수식자 b (미래 Kumo 색상): 신생·진행 ±3, 중기·성숙 ±2
        int scoreB = apply
                ? ((stage == 1 || stage == 2) ? (w.modB() == 1 ? 3 : -3) : (w.modB() == 1 ? 2 : -2))
                : 0;
        // 수식자 c (두께 vs 130주 중앙값): 신생 얇음 +2, 진행 얇음 +1·두꺼움 0,
        //                                  중기 두꺼움 +2, 성숙 두꺼움 +3
        int scoreC = apply ? switch (stage) {
            case 1 -> w.modC() == 0 ? 2 : -2;
            case 2 -> w.modC() == 0 ? 1 : 0;
            case 3 -> w.modC() == 1 ? 2 : -2;
            default -> w.modC() == 1 ? 3 : -3;
        } : 0;
        // 수식자 d (Chikou open space): 단계 무관 ±3
        int scoreD = apply ? (w.modD() == 1 ? 3 : -3) : 0;
        // 수식자 e (시간론 변화일): 미도래가 유리하다. 신생 ±1, 진행·중기 ±2, 성숙 ±3
        //
        // MUST NOT: w.modE() == -1 가지를 검증 대상으로 세지 않는다 — stage_apply는 stage 1~4를
        // 요구하고 그것은 drv_50 >= 0 ⟹ drv_51 >= 0을 뜻하는데, drv_52는 drv_51 >= 0일 때
        // 반드시 0이나 1로 배정된다. 즉 여기서 −1은 나오지 않는다(ADR-0046 §도달 불가).
        int scoreE = (!apply || w.modE() == -1) ? 0 : switch (stage) {
            case 1 -> w.modE() == 0 ? 1 : -1;
            case 2, 3 -> w.modE() == 0 ? 2 : -2;
            default -> w.modE() == 0 ? 3 : -3;
        };
        // 수식자 f (Tenkan 기울기, B-1): 신생·진행 ±3, 중기·성숙 ±2. 평탄은 언제나 −1(변곡)
        int baseF = (stage == 1 || stage == 2) ? 3 : 2;
        int scoreF = apply ? (w.modF() == 1 ? baseF : w.modF() == 0 ? -1 : -baseF) : 0;
        // 수식자 g (Kijun 기울기, B-1 + 신생 예외): 신생은 평탄이 0(자연 상태), 나머지는 −1
        int scoreG = apply
                ? (stage == 1
                        ? (w.modG() == 1 ? 2 : w.modG() == 0 ? 0 : -2)
                        : (w.modG() == 1 ? 3 : w.modG() == 0 ? -1 : -3))
                : 0;
        // 수식자 h (TK 위치, B-2): 강 +3, 중 0, 약 −2, 미발생 −3
        int scoreH = apply
                ? (w.modH() == 1 ? 3 : w.modH() == 0 ? 0 : w.modH() == -1 ? -2 : -3)
                : 0;
        // 수식자 i (일봉 호전 합치, B-3): 신생만 비중 중, 진행 이후는 강으로 통일
        int scoreI = apply
                ? (stage == 1
                        ? (kaizenMatch == 2 ? 2 : kaizenMatch == 1 ? 1 : kaizenMatch == 0 ? -1 : -2)
                        : (kaizenMatch == 2 ? 3 : kaizenMatch == 1 ? 1 : -3))
                : 0;
        // 수식자 j (Senkou B 평탄): 신생은 평탄이 불리(−1), 성숙은 평탄이 유리(+3)
        int scoreJ = apply ? switch (stage) {
            case 1 -> w.modJ() == 1 ? -1 : 1;
            case 2 -> w.modJ() == 0 ? 1 : 0;
            case 3 -> w.modJ() == 1 ? 2 : w.modJ() == 0 ? 1 : -2;
            default -> w.modJ() == 1 ? 3 : w.modJ() == 0 ? 0 : -3;
        } : 0;
        // 수식자 k (Kumo 내부 체류): 단계 무관. 체류 없음 +3, 단기 −2, 장기 −3
        int scoreK = apply ? (w.modK() == 0 ? 3 : w.modK() == 1 ? -2 : -3) : 0;
        // 수식자 l (Senkou A 기울기, B-1 + 신생 예외): 신생은 평탄이 +1(자연 상태)
        int scoreL = apply
                ? (stage == 1
                        ? (w.modL() == 1 ? 2 : w.modL() == 0 ? 1 : -2)
                        : (w.modL() == 1 ? 3 : w.modL() == 0 ? -1 : -3))
                : 0;
        // 수식자 m (미래 Kumo 트위스트, B-4): 부재가 유리하고 임박할수록 불리하다.
        // 신생만 ≥20주를 +1로 구분한다
        int scoreM = apply ? switch (stage) {
            case 1 -> w.modM() == -1 ? 2 : w.modM() >= 20 ? 1 : w.modM() >= 10 ? 0 : -2;
            case 2 -> w.modM() == -1 ? 2 : w.modM() >= 10 ? -1 : -2;
            default -> w.modM() == -1 ? 3 : w.modM() >= 10 ? -2 : -3;
        } : 0;

        int[] modifierScores = {scoreA, scoreB, scoreC, scoreD, scoreE, scoreF, scoreG,
                scoreH, scoreI, scoreJ, scoreK, scoreL, scoreM};
        int positive = 0;
        int negative = 0;
        for (int score : modifierScores) {
            positive += Math.max(0, score);
            negative += Math.max(0, -score);
        }

        // leaf 점수 (B-7 옵션 B). leaf 3은 stage 5·6 전용이라 여기서는 언제나 0이고,
        // (leaf 3, stage 4) 셀은 도달 불가다. leaf 9(신생 자연 상태)도 0이다.
        int leafScore = apply ? switch (w.leaf()) {
            case 1 -> 2;
            case 2 -> -2;
            case 3 -> stage == 4 ? 1 : 0;
            case 4 -> stage == 3 ? -1 : stage == 4 ? -2 : 0;
            case 5 -> (stage == 1 || stage == 2) ? 2 : 0;
            case 6 -> -3;
            default -> 0;
        } : 0;

        // 외부 시그널의 단계 의존 가중. 신생·진행 ±3, 중기 ±2, 성숙 ±1
        int extBase = apply ? ((stage == 1 || stage == 2) ? 3 : stage == 3 ? 2 : 1) : 0;

        int obvSign = w.obvT() >= 1.96 ? 1 : w.obvT() <= -1.96 ? -1 : 0;
        // C-1 패치. MUST: 폴백 가지에 IchimokuMath.round를 쓴다 — Pine의 math.round는
        // ties round up이라 ext_base가 **홀수**일 때 BigDecimal HALF_UP과 갈린다.
        // 3(신생·진행)만이 아니라 1(성숙)도 홀수다.
        int scoreD2 = apply
                ? (w.divergence() == 2 ? obvSign * extBase
                        : w.divergence() == 1 ? extBase
                        : w.divergence() == -1 ? -extBase
                        : IchimokuMath.round(obvSign * extBase * 0.5))
                : 0;
        // B-8 패치. |초과 수익률| < 5%는 노이즈 영역이라 0이다.
        boolean hasIndex = excessReturn.isPresent();
        double excess = excessReturn.orElse(0);
        int scoreD5 = excess >= 5 ? extBase : excess <= -5 ? -extBase : 0;

        int bull = positive + Math.max(0, leafScore) + Math.max(0, scoreD2) + Math.max(0, scoreD5);
        int bear = negative + Math.max(0, -leafScore) + Math.max(0, -scoreD2)
                + Math.max(0, -scoreD5);
        int diff = bull - bear;
        int absolute = Math.abs(diff);

        int conclusion = absolute < THRESHOLD_CONCLUSION ? 0 : diff > 0 ? 1 : -1;
        int confidence = absolute >= THRESHOLD_STRONG ? 1 : absolute >= THRESHOLD_MEDIUM ? 2 : 3;

        boolean bullish = conclusion == 1;
        int tier;
        if (bullish && confidence == 1 && stage == 1) {
            tier = 1;
        } else if (bullish && ((confidence == 1 && (stage == 2 || stage == 3))
                || (confidence == 2 && (stage == 1 || stage == 2)))) {
            tier = 2;
        } else if (bullish && ((confidence == 3 && stage == 1) || (confidence == 2 && stage == 3))) {
            tier = 3;
        } else {
            tier = 4;
        }

        // D5에 기댄 일곱만 지수 결손에 막힌다. 나머지는 그대로 낸다.
        return new IchimokuAnalysis(
                hasIndex ? tier : null,
                hasIndex ? diff : null,
                w.freshness(),
                hasIndex ? scoreD2 + scoreD5 : null,
                positive - negative,
                stage,
                hasIndex ? confidence : null,
                hasIndex ? conclusion : null,
                hasIndex ? bull : null,
                hasIndex ? bear : null,
                w.modA(), w.modB(), w.modC(), w.modD(), w.modE(), w.modF(),
                w.modG(), w.modH(), w.modJ(), w.modK(), w.modL(), w.modM(),
                w.kaizenWeekly(), kaizenDaily, kaizenMatch, w.leaf(), w.t3yeok(),
                w.obvT(), w.divergence(), hasIndex ? excess : null,
                w.tkCross(), w.priceVsKumo());
    }
}
