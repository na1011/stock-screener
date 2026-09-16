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
 *                               <p><b>IMPORTANT — 지금 이 값을 읽는 코드가 하나도 없다.</b>
 *                               국내 소스가 미조정 값을 주므로 무증상이고, 위 문장은 경고이지
 *                               구현이 아니다. <b>조정된 시세에 원장 계수를 다시 곱하면
 *                               분할비의 제곱만큼 틀리는데, 그 값은 그럴듯하다</b>(ADR-0049).
 *                               <p><b>미국 어댑터가 그 경고를 「읽는 코드」가 아니라 「그럴 일이
 *                               없게 하는 파라미터」로 닫았다</b> — {@code adjustment=raw}를
 *                               보내므로 언제나 {@code false}다(ADR-0088). 즉 이 값을 읽어야
 *                               하는 자리는 아직 없다. <b>그 파라미터를 {@code all}이나
 *                               {@code split}으로 바꾸는 순간 이 값을 읽는 코드가 필요해진다</b> —
 *                               바꾸면서 여기를 안 보면 계수가 두 번 적용된다.
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
        if (isHalted()) {
            return true;
        }
        return volume >= 0
                && high.compareTo(low) >= 0
                && high.compareTo(open) >= 0
                && high.compareTo(close) >= 0
                && low.compareTo(open) <= 0
                && low.compareTo(close) <= 0;
    }

    /**
     * <b>거래정지 봉인가.</b> KRX는 정지 기간에 <b>기준가(종가)만 유지하고</b> 시·고·저가와
     * 거래량을 0으로 준다 — 소스가 정직하게 주는 사실이지 손상이 아니다.
     *
     * <p>실측(2026-09-17 · 삼성바이오로직스 {@code 207940}):
     * {@code 2025-10-30 o=0 h=0 l=0 c=1221000 v=0}. 국내 27종목을 다시 받아 버려진 봉을
     * 패턴별로 세니 <b>이 형태가 1,185봉 · 그 밖의 형태가 0봉</b>이었다.
     *
     * <p><b>미국은 같은 사실을 {@code o=h=l=c ∧ v=0}으로 준다</b> — 그 형태는 원래 유효해서
     * 이미 68종목 14,884행이 저장돼 있다. <b>소스의 표기가 다를 뿐 사실은 같다.</b>
     *
     * <p>MUST: 이 봉을 저장할 때는 {@code o/h/l}을 {@code close}로 채운다. DB 제약
     * {@code ck_price_ohlc}가 {@code high >= close}를 요구하므로 원본 그대로는
     * <b>삽입 자체가 거부되고</b>, 그러면 그 종목의 정상 봉까지 트랜잭션과 함께 사라진다.
     * 채운 값은 사실에 부합한다 — <b>거래가 없었으므로 그날의 진폭은 실제로 0이다.</b>
     */
    public boolean isHalted() {
        return volume == 0L
                && close != null && close.signum() > 0
                && isZero(open) && isZero(high) && isZero(low);
    }

    private static boolean isZero(java.math.BigDecimal value) {
        return value != null && value.signum() == 0;
    }
}
