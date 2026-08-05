package com.stockscreener.pipeline.datasource;

import java.util.Optional;
import java.util.Set;

/**
 * 종목 마스터 정보 소스. 이름·섹터·상장일을 준다.
 *
 * <p>시세·재무와 분리한 이유는 갱신 주기가 다르기 때문이다. 시세는 매일, 마스터는 유니버스가
 * 바뀔 때만 필요하다. 합치면 매일 쓰지 않는 호출이 일일 배치의 쿼터를 태운다.
 *
 * <p>유니버스에 새로 편입된 종목은 티커만 알려진 상태이므로, {@code stock} 행을 만들려면
 * 이 포트가 필요하다.
 */
public interface SecurityMasterDataSource {

    Set<String> supportedMarketCodes();

    /** 실패 기록과 로그에 남는 소스 이름. */
    String sourceName();

    /**
     * 티커로 종목 정보를 찾는다.
     *
     * <p>{@link Optional#empty()}는 "소스가 이 티커를 모른다"는 뜻이며 예외가 아니다.
     * 지수 구성종목 목록과 마스터 소스가 서로 다른 시점을 볼 수 있어 정상적으로 발생한다.
     * 이 경우 호출부가 최소 정보로 {@code stock} 행을 만들지, 종목을 건너뛸지 판단한다.
     */
    Optional<SecurityDescriptor> findByTicker(String marketCode, String ticker)
            throws DataSourceException;
}
