package com.stockscreener.pipeline.datasource;

/**
 * 외부 소스에서 한 종목을 식별하는 데 필요한 최소 정보. {@code stock} 행의 축약이다.
 *
 * <p>엔티티가 아니라 이 record를 포트에 넘기는 이유는 두 가지다. 어댑터가 JPA 영속성 컨텍스트에
 * 닿을 이유가 없고, 어댑터 테스트가 DB 없이 성립해야 하기 때문이다.
 *
 * @param stockId    실패 기록과 upsert의 대상. 티커가 아니라 이것이 내부 식별자다.
 * @param marketCode {@code DataSourceRegistry}의 라우팅 키.
 * @param ticker     MUST: 문자열이다. 한국 티커 '005930'을 정수로 다루면 5930이 된다.
 */
public record SecurityRef(long stockId, String marketCode, String ticker) {

    public SecurityRef {
        if (marketCode == null || marketCode.isBlank()) {
            throw new IllegalArgumentException("marketCode가 비어 있다. stockId=" + stockId);
        }
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker가 비어 있다. stockId=" + stockId);
        }
    }
}
