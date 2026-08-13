package com.stockscreener.pipeline.datasource;

import java.util.Set;

/**
 * 우리가 시계열을 갖기로 한 지수 코드(ADR-0053).
 *
 * <p>어댑터 등록 여부와 <b>무관한</b> 목록이라는 것이 요점이다. 등록된 어댑터에서 뽑으면 키가
 * 없는 환경에서 목록이 비고, 그러면 "시세는 왔는데 지수가 없다"는 알림이 <b>원리적으로 발화하지
 * 않는다</b> — 가장 조용한 실패다.
 *
 * <p>{@code market_index_daily.ck_market_index_code}가 같은 값을 담는다. 사본이지만 둘이
 * 갈리면 적재가 <b>막히는 형태로</b> 드러나므로 조용히 어긋나지 않는다.
 *
 * <p>미국(v1.1, ADR-0010)은 여기 없다. 그래서 미국 종목이 유니버스에 들어와도 "지수가 없다"고
 * 알리지 않는다 — <b>아직 갖기로 하지 않은 것</b>과 <b>갖기로 했는데 없는 것</b>은 다르다.
 */
public final class MarketIndexCodes {

    public static final String KOSPI = "KOSPI";
    public static final String KOSDAQ = "KOSDAQ";

    /** 시장 코드와 같은 문자열을 쓴다 — 종목의 {@code market_code}로 곧장 고를 수 있게. */
    public static final Set<String> ALL = Set.of(KOSPI, KOSDAQ);

    private MarketIndexCodes() {
    }
}
