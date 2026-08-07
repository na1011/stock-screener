package com.stockscreener.indicator;

// Jackson 3의 databind는 `tools.jackson.databind`이지만 **애너테이션만은 여전히
// `com.fasterxml.jackson.annotation`이다**(jackson-annotations 2.21). 둘을 같은 것으로 보고
// 옮겨 적으면 컴파일이 멈춘다.
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;

/**
 * 지표 값의 한 구간과 그 구간이 뜻하는 것. "ROE 20% 이상 = 매우 높음"의 한 줄이다.
 *
 * <p>존재 이유는 {@code .claude/rules/frontend.md}가 MUST NOT으로 금지한 "지표를 값만 나열"이다.
 * 대상 사용자는 초보~중급 투자자이고, 조건 빌더에서 PER에 무슨 숫자를 넣어야 하는지 알 수 없다.
 * {@code interpretation}에 산문으로는 이미 적혀 있었지만("통상 70 이상이면 과매수") 화면이
 * 문장을 파싱할 수는 없다. 이 record는 <b>그 문장에 이미 있던 경계를 구조로 옮긴 것</b>이지
 * 새로운 투자 판단을 더한 것이 아니다.
 *
 * <p>IMPORTANT — 무차원 지표에만 붙인다. 주가 스케일에 비례하는 지표(SMA·ATR·MACD·종가)에
 * "3만원 이상 = 높음"을 달면 종목마다 뜻이 달라 <b>거짓말이 된다</b>. {@code Atr14Calculator}가
 * "주가 수준에 비례하므로 다른 종목과 직접 비교하지 않는다"고 이미 적어 둔 것과 같은 이유다.
 *
 * <p>부등호를 여기서 정하지 않는 이유는 {@code indicator} 패키지가 {@code screening}의 조건
 * 모델을 몰라야 하기 때문이다({@link IndicatorDescriptor.ComparisonOperatorHint} 주석과 같은
 * 제약). 구간을 조건으로 번역하는 것은 web 계층의 몫이다.
 *
 * @param label 구간의 뜻. 화면에 그대로 나온다.
 * @param from  하한. 포함한다. null이면 아래로 열려 있다.
 * @param to    상한. <b>포함하지 않는다</b> — 인접 구간이 겹치지 않아야 한 값이 한 구간에만 든다.
 */
public record IndicatorBand(String label, BigDecimal from, BigDecimal to) {

    public IndicatorBand {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("구간 라벨이 비어 있다. 라벨이 없으면 구간을 제시할 이유가 없다.");
        }
        // 양쪽이 다 열린 구간은 "모든 값"이라 아무것도 좁혀주지 않는다. 실수의 신호다.
        if (from == null && to == null) {
            throw new IllegalArgumentException("구간 '" + label + "'의 상·하한이 모두 없다.");
        }
        if (from != null && to != null && from.compareTo(to) >= 0) {
            throw new IllegalArgumentException(
                    "구간 '" + label + "'의 하한이 상한 이상이다: " + from + " ~ " + to);
        }
    }

    /**
     * 위로 열린 구간인가. "20% 이상"처럼 최상단 구간이 그렇다.
     *
     * <p>{@code @JsonIgnore}인 이유는 저장 형식이 {@code label}·{@code from}·{@code to} 셋이기
     * 때문이다. 빼면 Jackson이 이 파생값까지 jsonb에 적고, 그러면 같은 사실이 두 벌로 저장되어
     * 나중에 어느 쪽이 정본인지 알 수 없게 된다.
     */
    @JsonIgnore
    public boolean isOpenAbove() {
        return to == null;
    }

    /** 아래로 열린 구간인가. "10% 미만"처럼 최하단 구간이 그렇다. */
    @JsonIgnore
    public boolean isOpenBelow() {
        return from == null;
    }
}
