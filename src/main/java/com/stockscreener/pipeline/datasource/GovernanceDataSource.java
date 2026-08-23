package com.stockscreener.pipeline.datasource;

import java.util.List;
import java.util.Set;

/**
 * 정기보고서 주요정보 소스. 벤더를 모른다(ADR-0081).
 *
 * <p>{@link FinancialDataSource}에 얹지 않은 이유는 <b>주기와 조회 단위가 다르기</b> 때문이다.
 * 재무는 분기마다 다시 받고 이쪽은 사업보고서 연 1회다. 한 포트에 두면 재무 수집이 매번
 * 쓰지 않는 호출을 함께 태운다.
 *
 * <p>표 여덟에 포트를 여덟 만들지 <b>않는다</b> — 요청 인자가 같아 엔드포인트가 타입이 아니라
 * 인자다({@link GovernanceEndpoint}).
 *
 * <p>MUST: 구현체는 벤더 예외를 {@link DataSourceException}으로 감싼다.
 */
public interface GovernanceDataSource {

    Set<String> supportedMarketCodes();

    /** 실패 기록과 로그에 남는 소스 이름. */
    String sourceName();

    /**
     * 표 하나를 받는다.
     *
     * <p>MUST: 소스가 «데이터 없음»으로 답하면 <b>빈 목록이 아니라</b>
     * {@link GovernanceReportRow#noData}를 담은 목록을 돌려준다. 빈 목록으로 접으면 호출자가
     * 「아직 안 받았다」와 구별할 수 없고, 그 구분 위에 커버리지 판정이 선다.
     *
     * @param reportCode 보고서 코드. 사업보고서는 {@code 11011}이다 — <b>넷을 다 부르면 같은 일에
     *                   콜이 네 배</b>가 되고 그 네 배는 아무 에러 없이 다른 잡의 여유를 먹는다
     */
    List<GovernanceReportRow> fetchReport(SecurityRef security, GovernanceEndpoint endpoint,
                                          int bsnsYear, String reportCode)
            throws DataSourceException;
}
