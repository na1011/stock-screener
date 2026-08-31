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
 * <p><b>미국이 여기 있다</b>(2026-08-31). 담기는 값은 지수 그 자체가 아니라 <b>SPY</b>다 —
 * Alpaca가 지수 심볼을 안 주고 ETF만 주기 때문이고, 오차는 실측했다(FRED의 실제 S&P 500과
 * 공통 거래일 1,757일에서 52주 수익률 차 평균 <b>0.03%p</b> · 일간 상관 0.9928,
 * {@code alpaca-bars.md}). <b>그 대용이라는 사실이 이름에서 안 드러나므로</b> 여기 적는다.
 *
 * <p><b>MUST: 어댑터 하나가 이 집합 전부를 담당한다고 단정하지 않는다.</b> 시장마다 소스가
 * 달라 각 어댑터는 <b>부분집합</b>만 담당한다. 합집합이 완전한지는 어댑터가 아니라 운영이
 * 답할 문제다 — 키가 없는 환경에서는 어차피 등록조차 안 되기 때문이다.
 */
public final class MarketIndexCodes {

    public static final String KOSPI = "KOSPI";
    public static final String KOSDAQ = "KOSDAQ";

    /** 미국. 값의 출처는 SPY이고, 그 대용 관계는 이 클래스 javadoc이 적는다. */
    public static final String US = "US";

    /** 시장 코드와 같은 문자열을 쓴다 — 종목의 {@code market_code}로 곧장 고를 수 있게. */
    public static final Set<String> ALL = Set.of(KOSPI, KOSDAQ, US);

    private MarketIndexCodes() {
    }
}
