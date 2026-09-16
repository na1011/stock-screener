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
public record FinancialFetch(List<RawFinancialReport> reports, List<FinancialColumnAbsence> absences,
                             boolean completeVintageHistory) {

    public FinancialFetch {
        reports = reports == null ? List.of() : List.copyOf(reports);
        absences = absences == null ? List.of() : List.copyOf(absences);
    }

    /**
     * <b>이 호출이 그 종목의 판 이력을 전부 냈는가.</b> 참이면 저장 계층이
     * 「여기 없는 공시일은 더 이상 그 라벨의 판이 아니다」로 읽어도 된다.
     *
     * <p><b>MUST: 소스마다 다시 묻는다 — 이식하면 이력을 파괴한다.</b>
     * SEC의 {@code companyfacts}는 한 콜에 <b>판 이력 전부</b>를 실어 주므로 참이다.
     * DART는 {@code (corp_code, 연도, 보고서코드)}마다 <b>최신본 하나만</b> 내므로 거짓이다 —
     * 실측(2026-09-16)으로 국내에 공시일이 둘 이상인 라벨이 <b>54 · 26종목</b>이고
     * 전부 {@code revision_no} 0과 1을 가진 <b>진짜 정정공시</b>다. 참으로 읽으면
     * 그 원본 판이 지워지고 <b>DART는 그것을 다시 주지 않는다.</b>
     *
     * <p>기본값이 거짓인 것도 그 때문이다 — 선언하지 않은 소스는 안전한 쪽에 선다.
     */
    public boolean completeVintageHistory() {
        return completeVintageHistory;
    }

    /** 부재 축을 안 보고 판 이력도 전부 내지 않는 소스용. */
    public static FinancialFetch of(List<RawFinancialReport> reports) {
        return new FinancialFetch(reports, List.of(), false);
    }
}
