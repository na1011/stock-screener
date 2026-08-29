package com.stockscreener.pipeline.datasource;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 거래소가 정규장을 연 하루(ADR-0091).
 *
 * <p><b>{@code sessionDate}가 시장 로컬 날짜이고 소스가 준 값 그대로다.</b> MUST NOT:
 * {@code coreStart}에서 유도하지 않는다 — {@link Instant}는 절대 시각이라 UTC로 환산하면
 * 뉴욕 오후 장이 다음 날로 밀린다.
 *
 * <p>시각을 {@link Instant}로 두는 이유는 이 저장소의 집안 타입이기 때문이다
 * ({@code OffsetDateTime}은 {@code src/main}에 쓰이지 않는다). 소스가 붙여 준 오프셋은
 * 절대 시각으로 접히고, 벽시계 시각이 필요하면 거래소 시간대로 다시 환산한다 —
 * {@code market.timezone}이 그 값을 갖고 있다.
 *
 * <p>정규장 밖(pre·post)과 결제일은 담지 않는다. 읽는 쪽이 없고, 읽는 쪽 없는 값은 갈려도
 * 아무도 모른다.
 */
public record MarketSession(String exchangeCode, LocalDate sessionDate,
                            Instant coreStart, Instant coreEnd) {

    public MarketSession {
        if (exchangeCode == null || exchangeCode.isBlank()) {
            throw new IllegalArgumentException("exchangeCode가 비어 있다. sessionDate=" + sessionDate);
        }
        if (sessionDate == null) {
            throw new IllegalArgumentException("sessionDate가 비어 있다. exchange=" + exchangeCode);
        }
        if (coreStart == null || coreEnd == null) {
            throw new IllegalArgumentException(
                    "정규장 시각이 비어 있다. exchange=%s date=%s".formatted(exchangeCode, sessionDate));
        }
        if (!coreEnd.isAfter(coreStart)) {
            // ck_market_calendar_core와 같은 판정을 여기서도 한다. 저장 직전에 터지면 어느
            // 거래소의 어느 날인지가 SQL 오류에 안 남는다.
            throw new IllegalArgumentException(
                    "정규장 종료가 개장보다 이르다. exchange=%s date=%s %s~%s"
                            .formatted(exchangeCode, sessionDate, coreStart, coreEnd));
        }
    }
}
