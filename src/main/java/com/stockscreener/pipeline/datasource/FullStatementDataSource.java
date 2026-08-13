package com.stockscreener.pipeline.datasource;

import java.util.Set;

/**
 * 전체 재무제표 소스. 요약본이 주지 않는 항목 — 특히 <b>영업활동현금흐름</b> — 을 받는다.
 *
 * <p>{@link FinancialDataSource}와 나란히 두는 이유는 <b>둘이 다른 것을 주기 때문</b>이다.
 * 요약본(주요계정)은 재무상태표와 손익계산서만 내고 현금흐름표를 아예 담지 않으며, 계정 행
 * 자체가 없는 회사가 있다(오리온의 이익잉여금). 전체 재무제표는 그것을 주는 대신 응답이
 * 30배 크고 계정 매핑이 회사마다 갈린다. 하나로 합치면 일일 갱신이 필요하지도 않은 비용을
 * 매번 치른다.
 *
 * <p>MUST NOT: 이 포트로 정기 갱신을 돌리지 않는다. 결손을 메우는 <b>보정 경로</b>이며,
 * 그래서 쓰기도 개정본을 쌓지 않는 모드로 간다
 * ({@code FinancialWriter.WriteMode.FILL_WITHOUT_REVISION}).
 *
 * <p>MUST: 소스가 준 형태 그대로 낸다. FY와 Q4를 합치거나 유도하지 않는다 — 그 판단은
 * 어댑터가 아니라 소비 측의 몫이고, 소스마다 다른 유도 규칙이 상위로 새면 시장이 늘 때
 * 드러난다.
 */
public interface FullStatementDataSource {

    /** 이 소스가 담당하는 시장 코드. */
    Set<String> supportedMarketCodes();

    /** 실패 기록과 로그에 남는 소스 이름. */
    String sourceName();

    /**
     * 지정한 회계연도 범위와 기간의 보고서를 받는다.
     *
     * <p>공시되지 않은 (연도, 기간)은 <b>조용히 빠진다</b> — 아직 오지 않은 분기와 상장 전
     * 회계연도가 정상이기 때문이다. 그래서 <b>돌아온 목록의 크기를 요청 수와 비교하면 안
     * 되고</b>, 무엇이 없었는지는 호출자가 요청 집합과 결과를 대조해 알아낸다.
     *
     * @param fromFiscalYear 시작 회계연도(포함)
     * @param toFiscalYear   끝 회계연도(포함)
     * @param periods        받을 기간. 비어 있으면 예외다 — 빈 집합으로 부르면 0건이
     *                       "공시가 없다"로 읽히는데 실제로는 아무것도 묻지 않은 것이다.
     * @return 보고서와 <b>실제 호출 수</b>. 후자를 함께 내는 이유는 {@link FullStatementResult}에 있다
     */
    FullStatementResult fetchStatements(SecurityRef security,
                                        int fromFiscalYear,
                                        int toFiscalYear,
                                        Set<StatementPeriod> periods)
            throws DataSourceException;
}
