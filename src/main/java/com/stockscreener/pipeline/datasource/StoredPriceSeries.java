package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * <b>우리가 이미 저장한</b> 시세 시계열. 외부 소스가 아니다.
 *
 * <p>IMPORTANT — 이 패키지의 다른 인터페이스는 전부 <b>벤더</b> 포트지만 이것만 다르다.
 * ADR-0006의 규칙들(소스가 계산해 준 값을 받지 않는다, 벤더 예외를 감싼다 …)은 여기 걸리지
 * <b>않는다.</b> 그래도 여기 두는 이유는 {@link CorporateActionDataSource} 구현이 이것을
 * 필요로 하고, {@link SecurityRef}의 javadoc이 <i>"어댑터가 JPA 영속성 컨텍스트에 닿을 이유가
 * 없고 어댑터 테스트가 DB 없이 성립해야 한다"</i>고 못박고 있기 때문이다. 어댑터에
 * {@code JdbcTemplate}을 직접 주입하면 그 둘이 함께 깨진다.
 *
 * <h2>왜 어댑터가 우리 DB를 봐야 하는가</h2>
 * <p>국내 기업 행위는 <b>소스 하나로 값이 나오지 않는다.</b> 무엇이 가격을 바꾸는 행위인가는
 * DART의 낱말이 답하고, <b>분할·병합의 비율과 효력일은 {@code price_daily.listed_shares}가
 * 답한다</b>(ADR-0049). 실측으로 액면병합은 DART에 실리지 않고, 실리는 분할조차 종가 배수는
 * 그날 시장이 움직인 만큼 흐려진다(대한전선 10.63 대 정확한 10).
 */
public interface StoredPriceSeries {

    /**
     * {@code [from, to]} 구간에 저장된 봉. <b>거래일 오름차순</b>이며 없는 날은 없다.
     *
     * <p>연속한 두 원소가 곧 "그 종목의 이웃한 두 거래일"이라는 것이 이 계약의 요점이다 —
     * 거래정지 구간을 달력으로 세지 않고 자연히 건너뛴다(대한전선 2023은 병합 전
     * 11거래일이 비어 있다).
     */
    List<Bar> between(long stockId, LocalDate from, LocalDate to);

    /**
     * @param listedShares 그날의 상장주식수. {@code price_daily}에 결손이 없는 것을 실측으로
     *                     확인했지만(2026-08-12, 523,963행 전부), V9 이전 형태의 행이 다시
     *                     들어올 수 있으므로 null을 허용한다. <b>0으로 채우지 않는다</b> —
     *                     그러면 배수가 무한대가 되거나 0이 된다.
     */
    record Bar(LocalDate tradeDate, BigDecimal close, BigDecimal listedShares) {
    }
}
