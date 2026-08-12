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
 *
 * <p>{@code listedShares}는 그 시점의 상장주식수다. PER·PBR의 분모가 재무가 아니라 여기 있는
 * 이유는 <b>날짜별 값</b>이기 때문이다 — 유상증자·분할로 바뀌면 그날부터 다른 값이어야 하는데,
 * 분기 재무에 묶으면 다음 공시까지 낡은 수로 나눈다. 소스가 주지 않으면 null이다.
 *
 * <p>MUST: {@code close}와 곱하거나 나누기 전에 {@link IndicatorContext#sharesOn}을 거친다.
 * 무상증자는 <b>권리락일에 가격을, 신주상장일에 주식수를</b> 바꾸므로 그 사이 14~16거래일은
 * 이 값과 종가의 축척이 다르다 — 곧바로 곱하면 시가총액·PER·PBR이 배수만큼 틀리는데
 * <b>결측이 아니라 그럴듯한 숫자라 화면에 그대로 나간다</b>(ADR-0051).
 *
 * <p>IMPORTANT — {@code tradeValue}는 소스가 준 <b>실측 거래대금</b>이다.
 * MUST NOT: {@code close × volume}으로 합성해 채우지 않는다(V1 스키마의 같은 규칙). 다른 값이며,
 * 실측과 합성이 섞이면 파생 지표가 시장마다 미묘하게 달라지고 그 차이는 시장이 늘 때에야 드러난다.
 * 없으면 null이고, 그때는 거래대금 기반 지표를 계산하지 않는다.
 */
public record PriceBar(
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal adjClose,
        long volume,
        BigDecimal tradeValue,
        BigDecimal listedShares
) {
}
