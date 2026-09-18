package com.stockscreener.pipeline.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재무 무효화 알림이 운영자에게 시키는 명령이 그 종목의 시장에서 성립하는지 고정한다.
 *
 * <p>알림을 받은 사람은 적힌 명령을 그대로 친다. 미국 종목에 전체 재무제표(DART 전용)를 시키면 종목마다 실패하고,
 * 창 전체 날짜 루프 백필은 원본층 한 판 + 파생층 1종목 루프의 짝보다 수십 배 오래 돈다.
 */
class FinancialRefillAlertTest {

    @Test
    @DisplayName("종목이 많으면 목록을 자르고 수와 실행 id를 싣는다 — 목록을 통째로 실어 웹훅이 400으로 거절했다")
    void capsTheTickerListSoTheAlertFitsTheChannel() {
        java.util.List<String> many = java.util.stream.IntStream.range(0, 574)
                .mapToObj(i -> "TICK" + i + "(정정 0 · 교정 0 · 이른 판 2)").toList();

        String body = FinancialRefillJob.invalidationAlertBody(many, 1148, java.util.List.of(), 601L);

        assertThat(body.length()).isLessThan(1500);
        assertThat(body).contains("574종목");
        assertThat(body).contains("실행 id=601");
        assertThat(body).contains("TICK0");
        assertThat(body).doesNotContain("TICK573");
    }

    @Test
    @DisplayName("전체 재무제표 소스가 없는 시장의 종목에는 전체 재무제표를 시키지 않는다")
    void doesNotAskForFullStatementsWhenNoInvalidatedStockHasThatSource() {
        String body = FinancialRefillJob.invalidationAlertBody(
                List.of("AAPL(정정 0 · 교정 0 · 이른 판 2)"), 2, List.of(), 1L);

        assertThat(body).doesNotContain("full-statement-refill");
        assertThat(body).contains("--job=backfill");
    }

    @Test
    @DisplayName("전체 재무제표 소스가 있는 종목만 그 명령에 싣는다")
    void asksForFullStatementsOnlyForStocksWithThatSource() {
        String body = FinancialRefillJob.invalidationAlertBody(
                List.of("005930(정정 1 · 교정 0 · 이른 판 0)", "AAPL(정정 0 · 교정 0 · 이른 판 2)"), 3,
                List.of("005930"), 1L);

        assertThat(body).contains("--job=full-statement-refill --tickers=005930'");
    }

    @Test
    @DisplayName("백필은 원본층 날짜 하나와 파생층 1종목 루프의 짝으로 시킨다 — 날짜 하나만 돌면 백분위가 그날에만 생긴다")
    void asksForTheBaseAndDerivedBackfillPair() {
        String body = FinancialRefillJob.invalidationAlertBody(
                List.of("AAPL(정정 0 · 교정 0 · 이른 판 2)"), 2, List.of(), 1L);

        assertThat(body).contains("--from=<최신 거래일> --as-of=<최신 거래일> --tickers=<위 종목>");
        assertThat(body).contains("--from=<보존 창 첫 거래일> --as-of=<최신 거래일> --tickers=<같은 시장 1종목>");
    }
}
