package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 오늘 순위가 예탁증권 비율을 가릴 표지를 묻는 자리({@link UsMarketCapUniverseAdapter} · 탐색 {@code ADS-BASIS-PLAN-1022.md} §2 v3).
 * 운영은 SEC 공시 표지({@link SecDepositaryCovers}) · 시험은 픽스처다.
 */
public interface DepositaryCovers {

    /** {@code asOf}까지(그날 포함) 낸 마지막 정기 공시 중 증권 이름이 실린 표지. 없으면 빈 값. */
    Optional<ClassFacts> lastTitledCover(long cik, LocalDate asOf) throws DataSourceException;
}
