package com.stockscreener.pipeline.datasource;

import java.util.List;

/**
 * 한 종목의 재무 수집 결과. <b>보고서와 「못 얻은 컬럼」을 함께 낸다.</b>
 *
 * <p><b>왜 반환형을 넓혔는가</b>: 부재는 <b>기간이 아니라 컬럼</b>의 성질이라
 * {@link RawFinancialReport} 안에 담을 자리가 없다. 거버넌스는 같은 문제를 <b>원자료 표 안의
 * 센티널 행</b>으로 풀었지만({@code GovernanceReportRow.noData} · ADR-0081 §규칙
 * <i>"소스가 「데이터 없음」이라 답한 것도 행으로 남긴다"</i>) 그쪽은 부재가 <b>행</b> 단위라
 * 가능했다.
 *
 * <p><b>MUST: 부재를 안 내는 소스는 빈 목록을 낸다 — {@code null}이 아니다.</b> 빈 목록은
 * 「이 소스는 그 축을 안 본다」이고, 그때 저장 계층이 <b>기존 행을 지우지 않는다.</b>
 * 「모른다」와 「없다」를 가르는 것이 이 축의 존재 이유다.
 */
public record FinancialFetch(List<RawFinancialReport> reports, List<FinancialColumnAbsence> absences) {

    public FinancialFetch {
        reports = reports == null ? List.of() : List.copyOf(reports);
        absences = absences == null ? List.of() : List.copyOf(absences);
    }

    /** 부재 축을 안 보는 소스용. */
    public static FinancialFetch of(List<RawFinancialReport> reports) {
        return new FinancialFetch(reports, List.of());
    }
}
