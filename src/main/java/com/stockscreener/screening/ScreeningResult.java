package com.stockscreener.screening;

import java.math.BigDecimal;
import java.util.List;

/**
 * 한 종목에 대한 판정 결과.
 *
 * <p>{@code outcomes}가 조건별 내역이다. {@code frontend.md}가 요구하는 "왜 이 종목이
 * 선정되었는지"와 "조건별로 어떤 점수를 획득했는지"가 여기서 나온다. 점수만 주고 내역을
 * 감추면 사용자가 결과를 신뢰할 근거가 없다.
 *
 * @param stockId  종목
 * @param included 결과에 포함되는가. FILTER는 모든 조건 충족 시, SCORING은 항상 참.
 * @param score    획득 점수. FILTER 모드에서도 계산되지만 정렬에 쓰이지 않는다.
 * @param maxScore 만점. 점수의 상대적 크기를 보여줄 때 쓴다.
 * @param outcomes 조건별 판정 내역
 */
public record ScreeningResult(
        long stockId,
        boolean included,
        BigDecimal score,
        BigDecimal maxScore,
        List<ConditionOutcome> outcomes
) {

    public ScreeningResult {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    /** 충족한 조건 수. */
    public long matchedCount() {
        return outcomes.stream().filter(ConditionOutcome::passesFilter).count();
    }

    /** 지표가 없어 판정할 수 없었던 조건 수. 화면에서 "데이터 없음"으로 보여준다. */
    public long notEvaluableCount() {
        return outcomes.stream().filter(o -> !o.evaluable()).count();
    }

    /** 만점 대비 획득 비율(0~1). 만점이 0이면 0. */
    public BigDecimal scoreRatio() {
        if (maxScore == null || maxScore.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return score.divide(maxScore, 4, java.math.RoundingMode.HALF_UP);
    }
}
