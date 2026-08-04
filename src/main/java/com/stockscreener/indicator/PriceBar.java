package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일별 시세 한 봉.
 *
 * <p>IMPORTANT — {@code close}와 {@code adjClose}를 구분해서 쓴다.
 * <ul>
 *   <li>기술적 지표는 예외 없이 {@link #adjClose()}로 계산한다. 액면분할 시 원주가로 계산한
 *       이동평균은 무의미해지고, 분할 당일 거짓 신호를 만든다.
 *   <li>PER·시가총액·화면 표시가는 {@link #close()}를 쓴다. 사용자가 실제 지불하는 가격이고
 *       시점의 발행주식수와 짝이 맞아야 한다.
 * </ul>
 */
public record PriceBar(
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal adjClose,
        long volume
) {
}
