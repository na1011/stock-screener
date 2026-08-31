package com.stockscreener.screening;

import java.util.List;

/**
 * 이 조건셋을 <b>그 시장에서는 판정할 수 없다</b> — 임계가 그 통화로 정해진 적이 없다.
 *
 * <p>IMPORTANT — <b>조건 단위 「판정불가」로 접지 않는다.</b> 판정불가는 {@code Row} 안의
 * {@code outcomes}로만 나가는데, 필수 게이트가 판정불가면 종목이 전부 빠져 <b>행이 0개</b>가
 * 되고 그러면 사유가 실릴 자리 자체가 없다. 0건일 때 이유에 닿는 경로가 있어야 한다는 것이
 * P27 축④이고, 축①은 그 처방까지 적어 뒀다 — <i>"필수 게이트가 빠진 채 도는 것보다
 * 「이 시장에서 실행 불가」가 낫다"</i>.
 *
 * <p>그래서 <b>요청 단위로 거절</b>하고 어느 조건이 비었는지를 함께 싣는다. 사용자가 할 일이
 * 「기다린다」가 아니라 「대상을 바꾸거나 임계를 정한다」로 달라지기 때문이다.
 *
 * <p>MUST NOT: 없는 통화를 다른 통화의 값으로 대신하지 않는다. 금액은 옮기면 즉시 틀린 것이
 * 드러나지만 <b>무차원은 아무 증상이 없다</b>(ADR-0072).
 */
public class MarketThresholdMissingException extends RuntimeException {

    private final String currencyCode;
    private final List<String> indicatorCodes;

    public MarketThresholdMissingException(String currencyCode, List<String> indicatorCodes) {
        super("이 전략은 " + currencyCode + " 시장에서 실행할 수 없다 — 임계가 그 시장 분포로"
                + " 정해지지 않은 조건이 " + indicatorCodes.size() + "개다: " + indicatorCodes);
        this.currencyCode = currencyCode;
        this.indicatorCodes = List.copyOf(indicatorCodes);
    }

    public String currencyCode() {
        return currencyCode;
    }

    /** 임계가 없는 조건의 지표 코드. 응답에 그대로 실어 사용자가 무엇을 정해야 하는지 보인다. */
    public List<String> indicatorCodes() {
        return indicatorCodes;
    }
}
