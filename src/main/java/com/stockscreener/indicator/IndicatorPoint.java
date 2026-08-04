package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 특정 날짜의 지표 값 하나.
 *
 * <p>계산할 수 없는 날짜에 대해서는 이 객체를 만들지 않는다. {@code value}가 null이거나
 * 의미 없는 값인 point는 존재해서는 안 된다 — 그런 행이 저장되면 조건 판정이
 * "이력 부족"과 "조건 불충족"을 구분하지 못한다.
 */
public record IndicatorPoint(LocalDate tradeDate, BigDecimal value) {

    public IndicatorPoint {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate는 null일 수 없다");
        }
        if (value == null) {
            throw new IllegalArgumentException(
                    "value는 null일 수 없다. 계산할 수 없는 날짜는 point를 만들지 않는다: " + tradeDate);
        }
    }
}
