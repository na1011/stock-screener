package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 분사가 주주에게 나눠 준 것 — 가격 배수를 다시 낼 수 있는 원자료(V61 {@code corporate_action_distribution}).
 *
 * <h2>가치 비율 v</h2>
 * <pre>
 * v = parentValue / (parentValue + childValue)      같은 날(valueDate) 종가로 잰 존속 쪽 몫
 * 가격 배수 = s / v                                   s = 원장의 주식수 배수(ratio)
 * </pre>
 * <p>미국은 옛 1주 기준으로 {@code parentValue = s × 모체 종가 · childValue = rate × 자식 종가}이고, 국내는
 * 재상장일 존속·신설 보통주 시가총액이다 — 둘 다 같은 날의 값 나눔이라 v의 뜻이 같다.
 *
 * <p><b>왜 같은 날 종가인가</b>(탐색 브랜치 {@code explore/spin-off/PLAN.md} §2.1 — 대조 전에 커밋한 규칙): 분사 전날
 * when-issued 가격을 쓰는 소스 관례(1 − D/P)는 37건 중 사건일 수정 수익률 이상이 7(입력 하나를 고치면 6)이고, 이
 * 식은 4다(사건 없는 날의 기준선 1.19%). 그리고 국내 인적분할에는 when-issued가 없어 그 관례가 원리적으로 안 선다.
 * 이 식에서 사건일 수정 수익률은 보유자의 그날 총수익과 정확히 같다.
 *
 * @param newSymbol   자식(신설)의 심볼·종목코드. 모르면 null
 * @param newCusip    자식의 CUSIP. 국내는 null
 * @param rate        옛 1주당 받은 자식 주식 수. 국내는 시가총액으로 재므로 null일 수 있다
 * @param valueDate   두 값을 잰 날 — 분사 효력일(미국 ex · 국내 재상장일)
 * @param parentValue 존속 쪽 가치. 아직 못 쟀으면 null
 * @param childValue  자식 쪽 가치. 아직 못 쟀으면 null
 */
public record Distribution(String newSymbol, String newCusip, BigDecimal rate, LocalDate valueDate,
                           BigDecimal parentValue, BigDecimal childValue) {

    /** 가격 배수의 저장 스케일. {@code numeric(18,10)}과 같다. */
    public static final int SCALE = 10;

    public Distribution {
        if ((parentValue == null) != (childValue == null)) {
            throw new IllegalArgumentException("두 가치는 함께 오거나 함께 빈다: parent=" + parentValue
                    + " child=" + childValue);
        }
        if (parentValue != null && (parentValue.signum() <= 0 || childValue.signum() < 0)) {
            // 자식 가치 0은 정당하다(가치 없는 권리) — 그러면 v = 1, 가격 배수 = s다.
            throw new IllegalArgumentException("가치가 범위 밖이다: parent=" + parentValue + " child=" + childValue);
        }
    }

    /** 두 가치를 다 쟀는가. 못 쟀으면 가격 배수를 낼 수 없다 — 그 사건은 사람이 판정한다. */
    public boolean valued() {
        return parentValue != null;
    }

    /** 존속 쪽 가치 비율 v. */
    public BigDecimal valueFraction() {
        requireValued();
        return parentValue.divide(parentValue.add(childValue), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 가격 배수 s / v = s × (parent + child) / parent. 나눗셈은 한 번만 한다 — v를 먼저 반올림하면
     * 그 오차가 s/v에 다시 실린다.
     */
    public BigDecimal priceRatio(BigDecimal shareMultiple) {
        requireValued();
        return shareMultiple.multiply(parentValue.add(childValue))
                .divide(parentValue, SCALE, RoundingMode.HALF_UP);
    }

    private void requireValued() {
        if (!valued()) {
            throw new IllegalStateException("가치를 아직 못 쟀다: " + this);
        }
    }
}
