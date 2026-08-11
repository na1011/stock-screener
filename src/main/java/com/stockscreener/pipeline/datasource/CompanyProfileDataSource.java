package com.stockscreener.pipeline.datasource;

import java.util.Optional;
import java.util.Set;

/**
 * 기업 개황(업종·결산월) 소스. 벤더를 모른다.
 *
 * <p>{@link FinancialDataSource}에 얹지 않은 이유는 <b>업종이 재무가 아니기</b> 때문이다.
 * 재무는 분기마다 다시 받아야 하고 업종은 거의 바뀌지 않는다. 한 포트에 두면 재무 수집이
 * 매번 쓰지 않는 호출을 함께 태우거나, 업종만 받고 싶을 때 재무 경로를 흉내내야 한다.
 *
 * <p>MUST: 구현체는 벤더 예외를 {@link DataSourceException}으로 감싼다.
 */
public interface CompanyProfileDataSource {

    Set<String> supportedMarketCodes();

    /** 실패 기록과 로그에 남는 소스 이름. */
    String sourceName();

    /**
     * 종목 하나의 개황을 받는다.
     *
     * <p>{@link Optional#empty()}는 "소스가 이 종목을 모른다"는 뜻이다. 신규 상장 직후나
     * 상장폐지 종목에서 정상적으로 발생하므로 예외가 아니다 — 예외로 만들면 그 종목이
     * 매 실행마다 실패로 기록되어 실제 장애를 덮는다.
     */
    Optional<CompanyProfile> fetchProfile(SecurityRef security) throws DataSourceException;
}
