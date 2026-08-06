package com.stockscreener.stock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 지지·저항 참고정보.
 *
 * <p>IMPORTANT — <b>확정된 매매 신호가 아니다.</b> spec §3·§5와 frontend.md가 이 구분을
 * 요구하며, frontend.md는 그 이유를 명시한다 — 사용자가 이것을 신호로 오해하면 실제
 * 금전적 손실로 이어진다.
 *
 * <p>그 구분을 문구가 아니라 <b>구조로</b> 보장한다.
 * <ul>
 *   <li>저장하지 않는다 — 테이블도 {@code indicator_value} 행도 없다.
 *   <li>{@code IndicatorCalculator}가 아니다 — 지표 카탈로그에 코드가 없다.
 *   <li>따라서 조건의 좌변이 될 방법이 <b>원리적으로 없다</b>. 조건의 좌변은 언제나
 *       카탈로그에 있는 지표 코드이기 때문이다.
 *   <li>{@code disclaimer}·{@code method}·{@code window}가 응답의 필수 필드다 — 프론트가
 *       한계 표시를 빠뜨릴 수 없게 한다.
 * </ul>
 *
 * <p>진입가·손절가·목표가에 해당하는 단일 값은 어떤 형태로도 내지 않는다(spec §10).
 * 여기 있는 것은 구간과 그 구간이 <b>몇 번 의미를 가졌는지</b>뿐이다.
 *
 * @param window          분석에 쓴 구간
 * @param insufficientData 봉이 모자라 산출하지 못했다. 이때 {@code zones}는 비어 있다.
 * @param lastClose       마지막 수정종가. 존의 역할(지지/저항)을 가르는 기준이다.
 */
public record PriceReference(
        String kind,
        String method,
        String disclaimer,
        Window window,
        boolean insufficientData,
        BigDecimal lastClose,
        List<Zone> zones
) {

    public static final String KIND = "SUPPORT_RESISTANCE_REFERENCE";
    public static final String METHOD = "SWING_EXTREMA_CLUSTERING";

    public static final String DISCLAIMER =
            "확정된 매매 신호가 아니다. 과거 일정 기간 동안 가격이 반복적으로 머물렀던 구간을 "
                    + "통계적으로 묶은 참고정보이며, 앞으로 같은 구간에서 같은 일이 일어난다는 "
                    + "보장은 없다. 진입가·손절가로 사용하지 말 것.";

    public static PriceReference of(Window window, BigDecimal lastClose, List<Zone> zones) {
        return new PriceReference(KIND, METHOD, DISCLAIMER, window, false, lastClose, List.copyOf(zones));
    }

    /** 봉이 모자라 산출할 수 없다. 값을 지어내는 대신 비워 둔다 — 지표 계층의 워밍업 규칙과 같다. */
    public static PriceReference insufficient(Window window) {
        return new PriceReference(KIND, METHOD, DISCLAIMER, window, true, null, List.of());
    }

    public record Window(LocalDate from, LocalDate to, int tradingDays) {
    }

    /**
     * 가격 구간 하나.
     *
     * @param touchCount    이 구간이 의미를 가졌던 횟수. <b>연속된 봉은 한 번으로 센다</b> —
     *                      5일 눌림목은 1회지 5회가 아니다.
     * @param strength      {@code touchCount}의 거친 요약. 소수점 있는 점수를 주지 않는 이유는
     *                      그것이 신뢰도처럼 보여 과신을 부르기 때문이다.
     */
    public record Zone(
            Role role,
            BigDecimal lower,
            BigDecimal upper,
            int touchCount,
            LocalDate lastTouchedOn,
            Strength strength
    ) {
    }

    public enum Role {
        /** 현재가보다 아래 — 과거에 하방을 받쳤던 구간 */
        SUPPORT,
        /** 현재가보다 위 — 과거에 상승을 막았던 구간 */
        RESISTANCE,
        /**
         * 현재가가 그 구간 <b>안에</b> 있다.
         *
         * <p>숨기지 않는다. 지지/저항 둘로만 나누면 "지금 그 구간 안에 있다"는, 정보량이
         * 가장 큰 상태가 사라진다.
         */
        IN_RANGE
    }

    public enum Strength {
        HIGH,
        MEDIUM,
        LOW
    }
}
