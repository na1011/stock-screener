package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 소스가 준 시세 한 봉. 아직 저장되지 않았고 수정주가도 계산되지 않은 상태다.
 *
 * <p>IMPORTANT — {@code adjClose}가 <b>없다</b>. 수정주가는 소스의 계산값이며, spec §7이
 * 모든 파생 값을 자체 계산하도록 요구한다. {@code adj_factor}는 우리가 유지하고
 * {@code adj_close = close × adj_factor}로 만든다. 소스가 준 수정주가를 그대로 받으면
 * 소스를 바꾸는 순간 과거 전체의 기준이 조용히 달라진다.
 *
 * <p>{@link com.stockscreener.indicator.PriceBar}와 별도 타입인 이유는 경계를 넘는 DTO와
 * 계산 계층의 모델을 같은 타입으로 두면 소스 스펙 변경이 지표 계산기까지 번지기 때문이다.
 *
 * @param tradeValue             거래대금. KRX는 제공하고 대부분의 해외 소스는 주지 않는다.
 *                               MUST NOT: 없을 때 {@code close × volume}으로 합성하지 않는다.
 *                               다른 값이며, 실측과 합성이 섞이면 파생 지표가 시장마다 미묘하게
 *                               달라진다. 없으면 null로 두고 그 지표를 계산하지 않는다.
 * @param listedShares           그 시점의 상장주식수. PER·PBR·ROE의 분모다.
 *                               <p>재무제표가 아니라 시세와 함께 오는 이유는 그것이 <b>날짜별
 *                               값</b>이기 때문이다. 유상증자·분할로 주식수가 바뀌면 그날부터
 *                               다른 값이어야 하는데, 분기 재무에 묶어두면 다음 공시까지 낡은
 *                               수로 나누게 된다. 국내 소스는 우선주를 별도 종목으로 세므로
 *                               이 값이 보통주 수이며, PER의 분모로 정확하다.
 *                               <p>주지 않는 소스도 있으므로 nullable이다.
 * @param splitAdjustedBySource  소스가 이미 분할을 반영한 값을 주는지. 이것을 모르면
 *                               분할 계수를 두 번 적용하게 된다.
 */
public record RawPriceBar(
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        BigDecimal tradeValue,
        BigDecimal listedShares,
        boolean splitAdjustedBySource
) {

    public RawPriceBar {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate가 null이다.");
        }
        if (open == null || high == null || low == null || close == null) {
            throw new IllegalArgumentException("OHLC에 null이 있다. tradeDate=" + tradeDate);
        }
    }

    /**
     * {@code price_daily}의 {@code ck_price_ohlc}·{@code ck_price_volume}를 만족하는가.
     *
     * <p>DB 제약을 코드에서 한 번 더 확인하는 이유는, 위반 행을 삽입 시점에 예외로 받으면
     * 그 종목의 나머지 정상 봉까지 트랜잭션과 함께 사라지기 때문이다. 미리 걸러내면
     * 손상된 봉만 버리고 나머지를 저장할 수 있다. 무료 API의 손상된 데이터는 드물지 않다.
     */
    public boolean isValid() {
        return volume >= 0
                && high.compareTo(low) >= 0
                && high.compareTo(open) >= 0
                && high.compareTo(close) >= 0
                && low.compareTo(open) <= 0
                && low.compareTo(close) <= 0;
    }
}
