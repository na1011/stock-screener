package com.stockscreener.pipeline.datasource;


import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 유니버스 조회에서 알아낸 종목 정보를 마스터 조회가 재사용하도록 잠깐 보관한다.
 *
 * <p>존재 이유는 <b>중복 호출 제거</b>다. 유니버스 소스가 전종목 응답을 받으면 그 안에 이미
 * {@code itmsNm}(종목명)과 {@code mrktCtg}(시장)가 들어 있다. 그런데 {@code StockRegistrar}는
 * 처음 보는 티커마다 마스터 소스를 따로 부르므로, 캐시가 없으면 <b>350종목 등록에 350콜</b>이
 * 방금 받은 것과 똑같은 정보를 위해 나간다.
 *
 * <p>포트 구현을 하나 더 만들지 않고 캐시를 별도 빈으로 둔 이유는
 * {@code DataSourceRegistry}가 같은 시장에 마스터 소스가 둘이면 <b>기동에 실패</b>하기
 * 때문이다. 시장별 마스터 어댑터가 이 캐시를 먼저 보게 하면 포트 구현은
 * 하나로 유지되면서 호출만 사라진다.
 *
 * <p><b>시장 중립이다.</b> 그래서 {@code kr} 패키지가 아니라 여기 있다 — 미국 유니버스
 * 어댑터도 같은 응답에서 이름을 심고, 그쪽은 <b>이 캐시 말고 이름을 얻을 경로가 없다</b>
 * (마스터 전용 엔드포인트를 따로 부르지 않는다).
 *
 * <p>영속 캐시가 아니다. {@code stock} 행이 만들어지고 나면
 * {@code StockRegistrar.resolveOne}이 DB에서 찾아 마스터를 부르지 않으므로, 이 캐시가
 * 필요한 구간은 "유니버스 조회 직후 등록까지"로 짧다. 그래서 만료 정책도 두지 않는다 —
 * 유니버스 조회가 매번 덮어쓴다.
 */
@Component
public class SecurityNameCache {

    /** 키는 {@code marketCode + ':' + ticker}. 시장이 다르면 같은 티커도 다른 종목이다. */
    private final Map<String, SecurityDescriptor> byKey = new ConcurrentHashMap<>();

    public void put(SecurityDescriptor descriptor) {
        byKey.put(key(descriptor.marketCode(), descriptor.ticker()), descriptor);
    }

    public Optional<SecurityDescriptor> find(String marketCode, String ticker) {
        return Optional.ofNullable(byKey.get(key(marketCode, ticker)));
    }

    public int size() {
        return byKey.size();
    }

    private static String key(String marketCode, String ticker) {
        return marketCode + ':' + ticker;
    }
}
