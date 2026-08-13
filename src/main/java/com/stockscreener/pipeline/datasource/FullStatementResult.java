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
 * @param reports         낸 보고서. <b>소스가 준 것과 우리가 유도한 것이 섞여 있다</b>
 * @param apiCalls        이 조회가 태운 외부 호출 수
 * @param missingReports  요청했으나 <b>소스에 공시가 없던</b> (연도, 기간)의 수.
 *                        {@code reports.size()}에서 빼서 구하면 안 된다 — 유도한 행이 섞여
 *                        있어 음수가 나온다. 이 수가 곧 "상장 전 회계연도"와 "아직 오지 않은
 *                        분기"의 합이고, 적재로 메울 수 있는 결손과 원리적으로 없는 결손을
 *                        가르는 근거다
 */
public record FullStatementResult(List<RawFinancialReport> reports, int apiCalls,
                                  int missingReports) {

    public FullStatementResult {
        if (reports == null) {
            throw new IllegalArgumentException("reports는 null일 수 없다.");
        }
        if (apiCalls < 0) {
            throw new IllegalArgumentException("apiCalls는 음수일 수 없다: " + apiCalls);
        }
        if (missingReports < 0) {
            throw new IllegalArgumentException("missingReports는 음수일 수 없다: " + missingReports);
        }
        reports = List.copyOf(reports);
    }
}
