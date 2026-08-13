package com.stockscreener.pipeline.datasource;

import java.util.List;

/**
 * 전체 재무제표 조회의 결과와 <b>그것이 실제로 태운 외부 호출 수</b>.
 *
 * <p>호출 수를 결과에 싣는 이유는 그것을 알 다른 방법이 없기 때문이다. 요청 수는 호출자가
 * 알지만 <b>실제 호출 수는 어댑터만 안다</b> — 연결재무제표가 없어 별도재무제표로 다시 부른
 * 종목만 두 배가 되고, 그 비율은 미실측이다. 그리고 리미터의 일일 카운터는 인메모리라
 * 실행이 끝나면 사라진다.
 *
 * <p>MUST: 잡이 이 값을 합산해 실행 기록에 남긴다. 남기지 않으면 예산 대비 실제를 대조할
 * 근거가 사라지고, 다음 세션은 추정치를 실측으로 오인한다.
 *
 * @param reports  받은 보고서. 공시되지 않은 (연도, 기간)은 조용히 빠진다
 * @param apiCalls 이 조회가 태운 외부 호출 수
 */
public record FullStatementResult(List<RawFinancialReport> reports, int apiCalls) {

    public FullStatementResult {
        if (reports == null) {
            throw new IllegalArgumentException("reports는 null일 수 없다.");
        }
        if (apiCalls < 0) {
            throw new IllegalArgumentException("apiCalls는 음수일 수 없다: " + apiCalls);
        }
        reports = List.copyOf(reports);
    }
}
