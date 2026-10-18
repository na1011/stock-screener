package com.stockscreener.pipeline.datasource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 분사가 주주에게 나눠 준 것 — 가격 배수를 다시 낼 수 있는 원자료(V61 {@code corporate_action_distribution}).
 *
 * <h2>가치 비율 v</h2>
 * <pre>
 * v = parentValue / (parentValue + childValue)      같은 날(valueDate) 종가로 잰 존속 쪽 몫
 * 가격 배수 = s / v                                   s = 원장의 주식수 배수(ratio)
 * </pre>
 * <p>미국은 옛 1주 기준으로 {@code parentValue = s × 모체 종가 · childValue = Σ rate × 자식 종가}이고, 국내는
 * 재상장일 존속·신설 보통주 시가총액이다 — 둘 다 같은 날의 값 나눔이라 v의 뜻이 같다.
 *
 * <p><b>왜 같은 날 종가인가</b>(탐색 브랜치 {@code explore/spin-off/PLAN.md} §2.1 — 대조 전에 커밋한 규칙): 분사 전날
 * when-issued 가격을 쓰는 소스 관례(1 − D/P)는 37건 중 사건일 수정 수익률 이상이 7(입력 하나를 고치면 6)이고, 이
 * 식은 4다(사건 없는 날의 기준선 1.19%). 그리고 국내 인적분할에는 when-issued가 없어 그 관례가 원리적으로 안 선다.
 * 이 식에서 사건일 수정 수익률은 보유자의 그날 총수익과 정확히 같다.
 *
 * <p><b>자식이 여럿일 수 있다</b> — 같은 날 여러 증권을 나눠 준다(OPEN 2025-11-18 워런트 세 종 · UTX 2020-04-03
 * Carrier·Otis). 원장은 (종목, 효력일)에 한 행이라 자식마다 행을 두면 둘째부터 버려진다.
 *
 * @param children    받은 자식 증권과 옛 1주당 받은 수
 * @param valueDate   두 값을 잰 날(값을 아직 못 쟀으면 분사 효력일)
 * @param parentValue 존속 쪽 가치. 아직 못 쟀으면 null
 * @param childValue  자식 쪽 가치(합). 아직 못 쟀으면 null
 */
public record Distribution(List<Child> children, LocalDate valueDate,
                           BigDecimal parentValue, BigDecimal childValue) {

    /** 가격 배수의 저장 스케일. {@code numeric(18,10)}과 같다. */
    public static final int SCALE = 10;

    /**
     * @param symbol 심볼·종목코드. 모르면 null
     * @param cusip  CUSIP. 국내는 null
     * @param rate   옛 1주당 받은 수. 국내는 시가총액으로 재므로 null일 수 있다
     */
    public record Child(String symbol, String cusip, BigDecimal rate, java.time.LocalDate asOf) {

        /**
         * 그날의 기호로 받은 자식 — 값을 재는 쪽이 ex 날짜를 asof로 묻는다(ADR-0181). asof 없이 물으면 「오늘 그 기호의 실체」가 와서
         * 재사용·개명된 자식 기호가 다른 실체를 준다(UHAL.B: 2022-11-10 배분 자식은 그날 기호 UHALB · asof 없는 UHAL.B 첫 봉은 12-19).
         */
        public Child(String symbol, String cusip, BigDecimal rate) {
            this(symbol, cusip, rate, null);
        }
    }

    public Distribution {
        children = List.copyOf(children);
        if (children.isEmpty()) {
            throw new IllegalArgumentException("분배된 자식이 없다.");
        }
        Objects.requireNonNull(valueDate, "valueDate");
        if ((parentValue == null) != (childValue == null)) {
            throw new IllegalArgumentException("두 가치는 함께 오거나 함께 빈다: parent=" + parentValue
                    + " child=" + childValue);
        }
        if (parentValue != null && (parentValue.signum() <= 0 || childValue.signum() < 0)) {
            // 자식 가치 0은 정당하다(가치 없는 권리) — 그러면 v = 1, 가격 배수 = s다.
            throw new IllegalArgumentException("가치가 범위 밖이다: parent=" + parentValue + " child=" + childValue);
        }
    }

    /** 자식 하나. */
    public static Distribution of(String symbol, String cusip, BigDecimal rate, LocalDate valueDate,
                                  BigDecimal parentValue, BigDecimal childValue) {
        return new Distribution(List.of(new Child(symbol, cusip, rate)), valueDate, parentValue, childValue);
    }

    /** 같은 날 다른 분배를 더한다. 둘 다 아직 못 잰 상태여야 한다 — 잰 값은 같은 날 다시 재서 합한다. */
    public Distribution plus(Distribution other) {
        if (valued() || other.valued()) {
            throw new IllegalStateException("잰 분배는 합치지 않는다 — 자식을 모아 다시 잰다.");
        }
        List<Child> merged = new ArrayList<>(children);
        merged.addAll(other.children);
        return new Distribution(merged, valueDate, null, null);
    }

    /** 원장 표에 싣는 자식 심볼. 여럿이면 쉼표로 잇는다. */
    public String newSymbol() {
        return joined(Child::symbol);
    }

    public String newCusip() {
        return joined(Child::cusip);
    }

    /** 자식이 하나일 때의 배정 비율. 여럿이면 null — 자식별 비율은 note에 남는다. */
    public BigDecimal rate() {
        return children.size() == 1 ? children.get(0).rate() : null;
    }

    /** 두 가치를 다 쟀는가. 못 쟀으면 가격 배수를 낼 수 없다 — 그 사건은 사람이 판정한다. */
    public boolean valued() {
        return parentValue != null;
    }

    /** 같은 날 잰 두 가치를 싣는다. */
    public Distribution valuedAt(LocalDate date, BigDecimal parent, BigDecimal child) {
        return new Distribution(children, date, parent, child);
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

    /**
     * 원장 note에 남길 근거 한 줄. ADR-0117 premise가 분사 행마다 note를 요구한다 — 사람이 붙인 행이 아니어도
     * 왜 그 가격 배수인지(어느 날 무엇의 값인지)가 행 안에 남아야 다음 세션이 다시 잴 수 있다.
     */
    public String describe() {
        String kids = children.stream()
                .map(c -> (c.symbol() == null ? "?" : c.symbol())
                        + (c.rate() == null ? "" : " × " + c.rate().stripTrailingZeros().toPlainString()))
                .collect(Collectors.joining(", "));
        if (!valued()) {
            return "분사 · 자식 " + kids + " · " + valueDate + " 가치 미측정 — 가격 배수 없음";
        }
        return "분사 · 자식 " + kids + " · " + valueDate + " 같은 날 종가로 존속 "
                + parentValue.stripTrailingZeros().toPlainString() + " · 자식 "
                + childValue.stripTrailingZeros().toPlainString() + " → v " + valueFraction().toPlainString();
    }

    private String joined(java.util.function.Function<Child, String> field) {
        List<String> values = children.stream().map(field).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : String.join(",", values);
    }

    private void requireValued() {
        if (!valued()) {
            throw new IllegalStateException("가치를 아직 못 쟀다: " + this);
        }
    }
}
