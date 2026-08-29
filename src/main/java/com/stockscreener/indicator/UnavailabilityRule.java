package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 지표 하나가 <b>왜</b> 값이 없는지를 말하는 규칙.
 *
 * <p>{@code when}이 참이고 그 지표에 값이 없을 때만 문구가 붙는다. 값이 있으면 규칙은
 * 아예 보지 않는다 — 사유는 부재에 대한 설명이지 값에 대한 주석이 아니다.
 *
 * @param code   사유를 붙일 지표 코드
 * @param when   그 사유가 성립하는가. <b>이미 읽은 값과 종목 속성만</b> 본다
 * @param reason 사용자에게 보일 문장. <b>서버가 소유한다</b> — 프론트가 자기 문구를 들면
 *               두 곳이 갈리고, 갈린 쪽은 화면에서 드러나지 않는다
 */
public record UnavailabilityRule(String code, Predicate<Context> when, String reason) {

    public UnavailabilityRule {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code가 비어 있다.");
        }
        if (when == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("규칙과 문구가 모두 있어야 한다. code=" + code);
        }
    }

    /**
     * 판정에 쓸 수 있는 것. <b>이 둘로 답할 수 없는 사유는 규칙으로 두지 않는다.</b>
     *
     * @param values           그 기준일에 계산된 지표 값들. 없는 지표는 키가 없다
     * @param industryDivisionKey KSIC 중분류. 업종 미상이면 null이다
     */
    public record Context(Map<String, BigDecimal> values, String industryDivisionKey) {

        public BigDecimal value(String code) {
            return values.get(code);
        }
    }
}
