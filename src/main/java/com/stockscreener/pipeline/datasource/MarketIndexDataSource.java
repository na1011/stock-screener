package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 시장 지수 원자료 소스. 벤더를 모른다.
 *
 * <p>{@link PriceDataSource}와 분리한 이유는 <b>조회 단위가 종목이 아니기 때문</b>이다.
 * 지수는 {@link SecurityRef}를 받지 않고 지수 코드를 받으며, 실패의 단위도 종목이 아니다 —
 * 지수 한 줄이 없으면 <b>전 종목이 함께 판정불가</b>가 된다. 실패 격리의 단위가 종목이라는
 * 파이프라인 원칙이 원리적으로 성립하지 않는 첫 입력이고, 타입이 다르면 그 사실을 호출부가
 * 잊기 어렵다.
 *
 * <p>MUST: 구현체는 벤더 예외를 {@link DataSourceException}으로 감싼다.
 */
public interface MarketIndexDataSource {

    /**
     * 이 소스가 담당하는 지수 코드. {@code DataSourceRegistry}의 라우팅 키다.
     *
     * <p>{@code market_index_daily.index_code}의 값을 쓴다 — {@code KOSPI}, {@code KOSDAQ}.
     * 그 컬럼에 CHECK가 걸려 있으므로 여기와 어긋나면 적재가 <b>막히는 형태로</b> 드러난다.
     */
    Set<String> supportedIndexCodes();

    /** 실패 기록과 로그에 남는 소스 이름. */
    String sourceName();

    /**
     * 한 지수의 {@code [from, to]} 구간 일별 시세. 거래일만 담기며 날짜 오름차순이다.
     *
     * <p>MUST NOT: 데이터가 없는 날을 직전값으로 채우지 않는다. 휴장과 미공개는 다른 사건이고,
     * 채우면 26주 수익률이 조용히 틀린다. 없는 날은 그냥 없는 것이다.
     */
    List<RawIndexBar> fetchDailyBars(String indexCode, LocalDate from, LocalDate to)
            throws DataSourceException;
}
