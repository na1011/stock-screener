package com.stockscreener.pipeline.collector;

import java.time.LocalDate;

/**
 * 한 종목의 시세 구멍 구간 — 지수 달력에서 이웃한 「봉이 없는 거래일」의 묶음.
 *
 * <p>구간마다 소스에 <b>정확히 [{@code from}, {@code to}]</b>를 묻는다. 한 종목의 떨어진 두 구멍을
 * 한 콜로 묶으면 그 사이의 멀쩡한 봉을 매일 다시 받고, 진짜 부재(정지·폐지·상장 전)인 구간이
 * 섞여 있으면 그 수년치를 매일 다시 받는다.
 *
 * <p><b>확정 재확인 구간</b>({@code settle})은 구멍이 아니다 — 봉이 <b>있지만</b> 소스가 아직 잠정값을 주던 때(거래일 다음
 * 날, KST) 한 번 쓰이고 그 뒤로 다시 안 쓰인 날들이다. 미국 소스는 일봉 거래량을 다음 날 뉴욕 새벽에 고친다 — 그 앞의 수집
 * (평일 21:00 미국 사슬 전의 13:30 수집 · 뉴욕 새벽 전의 손 실행)이 받은 값은 잠정이다({@code docs/api-spec/alpaca-bars.md}
 * 「일봉 거래량은 다음 날 뉴욕 새벽까지 잠정이다」). 지금 편입은
 * 15일 창이 다시 받지만 편출 종목의 마지막 편입일 봉과 창 밖으로 밀린 봉은 아무도 다시 안 묻는다(ADR-0167).
 *
 * @param currentMember 지금 편입인가. 아니면 보존 창 안에서 편입이 끝난 과거 구성원이다 — 그 종목은
 *                      구간만 받고 지표는 매 평일 화해(ADR-0166)가 다시 쓴다
 * @param settle        확정 재확인 구간인가. 아니면 봉 없는 날의 구멍 구간이다
 * @param days          구멍이면 구간 안의 봉 없는 거래일 수, 확정 재확인이면 다시 받을 봉 수(지수 달력 기준)
 */
public record PriceGapRun(long stockId, String marketCode, String ticker, boolean currentMember,
                          boolean settle, LocalDate from, LocalDate to, int days) {
}
