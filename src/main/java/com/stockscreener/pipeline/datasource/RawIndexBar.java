package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 소스가 준 시장 지수 한 봉.
 *
 * <p>{@link RawPriceBar}와 별도 타입인 이유는 <b>지수에 없는 것이 있기 때문</b>이다 —
 * 상장주식수도, 수정주가도, 분할도 없다. 같은 타입으로 접으면 지수 행에 {@code adj_factor}를
 * 곱하는 코드가 언젠가 생기는데 그것은 아무 에러도 내지 않는다.
 *
 * @param constituentCount 채용종목 수(소스 {@code epyItmsCnt}). 지수 정의가 바뀐 시점을 사후에
 *                         알 수 있는 유일한 흔적이라 담는다. 계산에는 쓰지 않는다.
 */
public record RawIndexBar(
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long volume,
        BigDecimal tradeValue,
        Integer constituentCount
) {

    public RawIndexBar {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate가 null이다.");
        }
        if (close == null) {
            throw new IllegalArgumentException("종가가 null이다. tradeDate=" + tradeDate);
        }
    }

    /**
     * {@code market_index_daily}의 {@code ck_market_index_close}를 만족하는가.
     *
     * <p>지수 종가가 0 이하인 일은 없다. 그런 값이 오면 수집 결함이고, 저장되면 26주 수익률의
     * 분모가 되어 <b>조용히 0%</b>가 된다. 삽입 시점 예외로 받으면 그 지수의 나머지 정상 봉까지
     * 트랜잭션과 함께 사라지므로 미리 거른다({@link RawPriceBar#isValid()}와 같은 판단이다).
     */
    public boolean isValid() {
        return close.signum() > 0;
    }
}
