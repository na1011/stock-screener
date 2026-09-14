package com.stockscreener.pipeline.datasource;

import java.util.List;
import java.util.Set;

/**
 * 재무제표 원자료 소스. 벤더를 모른다.
 *
 * <p>MUST: 구현체는 벤더 예외를 {@link DataSourceException}으로 감싼다.
 * <p>MUST NOT: 소스가 계산해 주는 PER·ROE 같은 비율 값을 담지 않는다(spec §7,
 * {@code data-pipeline.md}). {@link RawFinancialReport}에 비율 필드가 없는 것이 그 강제 수단이다.
 */
public interface FinancialDataSource {

    Set<String> supportedMarketCodes();

    /** 실패 기록과 로그에 남는 소스 이름. */
    String sourceName();

    /**
     * {@code fromFiscalYear} 이후의 모든 보고서. 연간과 분기가 섞여 돌아온다.
     *
     * <p>정정공시가 있으면 원본과 정정본이 모두 담길 수 있다. 어느 것이 최신인지는
     * {@link RawFinancialReport#publishedAt()}로 판단하며, 그 판단은 저장 계층의 몫이다 —
     * 어댑터가 걸러내면 "그 시점에 공개되어 있던 값"을 나중에 복원할 수 없다.
     *
     * <p>MUST: 소스가 준 형태 그대로 낸다. <b>FY와 Q4를 한 행으로 합치지 않는다.</b>
     * <b>Q4는 예외다</b> — 4분기 보고서가 어느 소스에도 없고, 그 행이 없으면 분기 TTM이
     * 다섯 분기에 걸친 구멍 난 합이 된다. 근거와 계약은 {@link RawFinancialReport}에 있다.
     */
    /**
     * <p><b>부재 축을 함께 낸다</b>({@link FinancialFetch}) — 「우리 후보로는 아무 팩트도 못 얻은
     * 컬럼」이 있는 소스는 그것을 실어 보내고, 안 보는 소스는 <b>빈 목록</b>을 낸다.
     * 빈 목록은 「모른다」이지 「없다」가 아니다.
     */
    FinancialFetch fetchReports(SecurityRef security, int fromFiscalYear)
            throws DataSourceException;
}
