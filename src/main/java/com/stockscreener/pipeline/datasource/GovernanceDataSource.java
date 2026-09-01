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
     * 이 소스가 <b>실제로 답하는</b> 표. 기본은 여덟 전부다.
     *
     * <p>좁히는 소스가 생겼기 때문에 있는 자리다 — 미국은 배당 하나만 준다. 잡이 이것을 안 보고
     * 여덟을 다 부르면 나머지 일곱에서 <b>「그 회사에 그 표가 없다」는 거짓 행</b>이 쌓이고,
     * 그 행은 커버리지 판정의 재료라 <b>결손의 원인을 업종·구조·수집으로 가르는 축을 무너뜨린다.</b>
     * 던지게 두는 것도 답이 아니다 — 종목마다 실패하면 부분 실패 알림이 상시화되고 진짜 장애가 묻힌다.
     */
    default java.util.Set<GovernanceEndpoint> supportedEndpoints() {
        return java.util.EnumSet.allOf(GovernanceEndpoint.class);
    }

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
