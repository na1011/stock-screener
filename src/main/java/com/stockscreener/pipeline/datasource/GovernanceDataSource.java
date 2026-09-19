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
     * 이 소스에 <b>지금 물어볼 수 있는 가장 늦은 {@code bsnsYear}</b>.
     *
     * <h2>MUST: 소스가 답한다 — 잡이 정하지 않는다</h2>
     * <p>{@code bsnsYear}의 <b>뜻이 소스마다 다르기 때문</b>이다. DART에서는 <b>사업보고서의
     * 회계연도</b>라 올해분은 이듬해 3월에야 나오고, Alpaca 배당에서는 <b>{@code ex_date}의
     * 역년</b>이라 <b>올해가 지금 답을 준다.</b>
     *
     * <p><b>이 메서드가 없던 동안 DART의 뜻이 미국으로 이식돼 있었다.</b> 잡이
     * {@code asOf.getYear() - 1}을 전역으로 박아 <b>역년 {@code asOf.getYear()}를 영영 요청하지
     * 않았고</b>, 그래서 회계연도가 역년 둘 이상에 걸치는 미국 종목은
     * {@code AlpacaGovernanceFactMapper.coversWholeWindow}에 막혀 <b>매년 영구히 한 해
     * 뒤처졌다</b> — 2026-09-20 실측으로 편입 1,000 중 <b>158종목</b>이 그 자리였고, 그 값은
     * 결측이 아니라 <b>1년 낡은 배당을 오늘 종가로 나눈 그럴듯한 수</b>로 화면에 있었다.
     * 12월 결산 723종목이 <b>100% 최신</b>이라 축이 정확히 갈렸다.
     *
     * <p><b>기본값은 DART 쪽이다</b> — 이 인터페이스가 먼저 국내를 위해 생겼고(ADR-0081),
     * 새 소스가 말없이 붙었을 때 <b>덜 요청하는 쪽이 안전하다.</b> 더 요청하면 그 소스에
     * 거짓 「데이터 없음」 행이 쌓이는데 그것은 커버리지 판정의 재료다.
     */
    default int latestRequestableYear(java.time.LocalDate asOf) {
        return asOf.getYear() - 1;
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
