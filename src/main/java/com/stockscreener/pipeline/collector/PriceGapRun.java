package com.stockscreener.pipeline.collector;

import java.time.LocalDate;

/**
 * 한 종목의 시세 구멍 구간 — 지수 달력에서 이웃한 「봉이 없는 거래일」의 묶음.
 *
 * <p>구간마다 소스에 <b>정확히 [{@code from}, {@code to}]</b>를 묻는다. 한 종목의 떨어진 두 구멍을
 * 한 콜로 묶으면 그 사이의 멀쩡한 봉을 매일 다시 받고, 진짜 부재(정지·폐지·상장 전)인 구간이
 * 섞여 있으면 그 수년치를 매일 다시 받는다.
 *
 * @param currentMember 지금 편입인가. 아니면 보존 창 안에서 편입이 끝난 과거 구성원이다 — 그 종목은
 *                      구간만 받고 지표는 매 평일 화해(ADR-0166)가 다시 쓴다
 * @param days          구간 안의 구멍 거래일 수(지수 달력 기준)
 */
public record PriceGapRun(long stockId, String marketCode, String ticker, boolean currentMember,
                          LocalDate from, LocalDate to, int days) {
}
