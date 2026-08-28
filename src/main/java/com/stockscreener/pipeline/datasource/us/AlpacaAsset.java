package com.stockscreener.pipeline.datasource.us;

import tools.jackson.databind.JsonNode;

/**
 * {@code /v2/assets}의 한 행. 벤더 필드명을 어댑터 본문에서 몰아내려고 둔다.
 *
 * <p>담지 않는 것이 담는 것만큼 중요하다.
 * <ul>
 *   <li>{@code cusip} — <b>실측 8,504건 전부 비어 있다.</b> 개발가이드가 응답 예시에 넣고
 *       "EDGAR 매핑의 보조 키"라고 적었으나 무료 페이퍼 계정에는 오지 않는다.</li>
 *   <li>{@code class} — {@code us_equity} 하나뿐이라 증권 유형을 가르지 못한다.</li>
 *   <li>{@code attributes} — 실측 8종이 전부 거래 속성이다({@code has_options}·{@code ipo}·
 *       {@code ptp_*}·{@code overnight_*}). 유형 분류에 쓸 수 없다.</li>
 * </ul>
 *
 * <p>{@code tradable}은 <b>거르는 데 쓰지 않는다.</b> 데이터 조회 전용 자산이 그 값으로
 * 오는데(실측 34건) 우리는 거래를 하지 않으므로, 그것으로 거르면 볼 수 있는 종목을 스스로
 * 지운다. 담아 두는 이유는 그 사실을 로그로 셀 수 있게 하려는 것뿐이다.
 */
record AlpacaAsset(String symbol, String name, String exchange, String status, boolean tradable) {

    static AlpacaAsset from(JsonNode node) {
        return new AlpacaAsset(
                node.path("symbol").asString(""),
                node.path("name").asString(""),
                node.path("exchange").asString(""),
                node.path("status").asString(""),
                node.path("tradable").asBoolean(false));
    }

    boolean isUsable() {
        return !symbol.isBlank() && !name.isBlank() && !exchange.isBlank();
    }
}
