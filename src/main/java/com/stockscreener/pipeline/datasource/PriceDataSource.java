package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 시세 원자료 소스. 벤더를 모른다.
 *
 * <p>재무와 인터페이스를 분리한 이유는 조회 단위가 다르기 때문이다. 시세는 날짜 구간이고
 * 재무는 회계기간이며, 재무에는 정정공시라는 개정 개념이 있다. 하나로 합치면 한쪽 소스만
 * 바꾸고 싶을 때 다른 쪽까지 건드리게 된다 — 실제로 미국 시세만 교체하는 일이 일어났다.
 *
 * <p>MUST: 구현체는 벤더 예외를 {@link DataSourceException}으로 감싼다.
 * <p>MUST NOT: 소스가 계산해 준 수정주가를 반환값에 담지 않는다({@link RawPriceBar} 참조).
 */
public interface PriceDataSource {

    /**
     * 이 소스가 담당하는 시장 코드. {@code DataSourceRegistry}의 라우팅 키다.
     *
     * <p>{@code market.code}의 값을 쓴다 — {@code KOSPI}, {@code KOSDAQ}, {@code US}.
     */
    Set<String> supportedMarketCodes();

    /** 실패 기록과 로그에 남는 소스 이름. 벤더를 식별할 수 있어야 한다. */
    String sourceName();

    /**
     * 한 종목의 {@code [from, to]} 구간 일별 시세. 거래일만 담기며 날짜 오름차순이다.
     *
     * <p>MUST NOT: 데이터가 없는 날을 0이나 직전값으로 채우지 않는다. 휴장일과 수집 실패는
     * 다른 사건이고, 채워 넣으면 이동평균이 조용히 틀린다. 없는 날은 그냥 없는 것이다.
     *
     * <p>구간에 데이터가 하나도 없는 것이 정상일 수 있다(상장 전, 거래정지). 그것과
     * "종목 자체를 찾을 수 없음"({@link DataSourceException.Kind#NOT_FOUND})은 다르며,
     * 전자는 빈 목록으로 반환한다.
     */
    List<RawPriceBar> fetchDailyBars(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException;

    /**
     * 수정계수에 영향을 주는 기업 행위.
     *
     * <p>기본 구현이 빈 목록인 이유는 소스마다 제공 여부가 다르기 때문이다. 제공하지 않는
     * 소스에 대해서는 시세 급변으로 후보를 탐지하되, 휴리스틱 탐지 결과는 자동 적용하지 않는다
     * ({@link CorporateAction.DetectionSource} 참조).
     */
    default List<CorporateAction> fetchCorporateActions(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException {
        return List.of();
    }
}
