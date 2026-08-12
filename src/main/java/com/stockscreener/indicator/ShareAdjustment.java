package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 기업 행위 하나가 지표 계층에 남기는 것 — <b>가격이 바뀐 날, 주식수가 바뀐 날, 그 배수</b>.
 *
 * <p>IMPORTANT — 두 날짜가 다른 것이 이 타입의 존재 이유다. 시장은 <b>권리락일</b>에 신주를
 * 미리 가격에 반영하지만 {@code price_daily.listed_shares}는 <b>신주상장일</b>에야 늘어난다.
 * 액면분할·병합은 둘이 같아 구간이 공집합이지만 <b>무상증자는 14~16거래일 벌어지고</b>
 * (루닛 2023은 16, 2026은 14거래일) 그 사이 구간은 이미 희석된 종가를 희석 전 주식수와
 * 짝지어 시가총액·PER·PBR이 배수만큼 틀린다. 결측이 아니라 그럴듯한 숫자라 흔적이 없다.
 *
 * <p>{@code ratio}의 계약은 원장과 같다 — <b>1주가 몇 주가 되었는가</b>이고 병합이면 1보다 작다
 * ({@code CorporateAction.ratio}). 여기서는 주식수에 <b>곱하고</b>, 재구축은 가격에 역수를
 * 곱한다(ADR-0049). 같은 값을 반대 방향으로 쓰는 두 자리이므로 부호를 베껴 쓰지 않는다.
 *
 * <p>MUST: {@code listingDate}는 null이 아니다. 원장은 그것을 모를 수 있고(휴리스틱 후보에는
 * 없다) 그때는 <b>{@code effectiveDate}로 접어</b> 구간을 공집합으로 만든다 — 모르는 것을
 * 추측하지 않고 보정하지 않는 쪽이다. 접는 자리는
 * {@link IndicatorDataReader#readShareAdjustments(long)} 하나이며, 여기서 null을 거부하는 이유는
 * <b>접지 않은 값이 흘러들면 구간 판정이 NPE가 되어 그 종목 전체가 롤백되기</b> 때문이다.
 */
public record ShareAdjustment(LocalDate effectiveDate, LocalDate listingDate, BigDecimal ratio) {

    public ShareAdjustment {
        if (effectiveDate == null) {
            throw new IllegalArgumentException("효력일이 없는 기업 행위는 없다.");
        }
        if (listingDate == null) {
            throw new IllegalArgumentException(
                    "신주상장일이 null이다 — 모르면 효력일로 접어서 만든다. 효력일=" + effectiveDate);
        }
        if (ratio == null || ratio.signum() <= 0) {
            throw new IllegalArgumentException("비율은 양수여야 한다: " + ratio);
        }
    }

    /**
     * 그 거래일이 <b>권리락 이후·신주상장 이전</b>인가. 가격은 이미 희석됐고 주식수는 아직인 구간이다.
     *
     * <p>양 끝의 등호가 실측이다 — {@code effectiveDate} 당일이 희석 가격의 <b>첫날</b>이고
     * ({@code 170,500 → 90,200}, 루닛 2023-11-09) {@code listingDate} 당일이 새 주식수의
     * <b>첫날</b>이다({@code 14,238,154 → 28,476,308}, 2023-12-01). 그래서 앞은 포함하고 뒤는 뺀다.
     */
    boolean coversPriceButNotShares(LocalDate tradeDate) {
        return !tradeDate.isBefore(effectiveDate) && tradeDate.isBefore(listingDate);
    }

    /** 그 거래일까지 이 사건이 주식수를 이미 바꿨는가. {@code effectiveDate} 기준인 이유는 위 계단과 맞추기 위해서다. */
    boolean tookEffectBy(LocalDate tradeDate) {
        return !effectiveDate.isAfter(tradeDate);
    }
}
