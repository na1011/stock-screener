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
    @DisplayName("note가 무효화 종목을 통째로 싣는다 — 알림과 채널 자르기가 둘 다 「전체는 note에 있다」고 말한다")
    void noteCarriesEveryInvalidatedTicker() {
        List<String> tickers = java.util.stream.IntStream.range(0, 574)
                .mapToObj("TICK%d"::formatted).toList();

        String note = FinancialRefillJob.note(2015, 2026, 1000, 1, 0, 0, 0, 2257, tickers);

        assertThat(note).contains("무효화=574종목");
        assertThat(note).contains("TICK0").contains("TICK573");
        // 자른 목록을 되찾는 유일한 길이므로 한 종목이라도 빠지면 그 문장이 다시 거짓이 된다.
        tickers.forEach(t -> assertThat(note).contains(t));
    }

    @Test
    @DisplayName("무효화가 0이면 목록 토큰을 아예 안 붙인다 — 매 실행 빈 괄호가 붙으면 note가 읽히지 않는다")
    void omitsTheTickerListWhenNothingWasInvalidated() {
        String note = FinancialRefillJob.note(2019, 2026, 350, 0, 0, 12, 0, 0, List.of());

        assertThat(note).contains("무효화=0종목");
        assertThat(note).doesNotContain("무효화종목=");
    }

    @Test
    @DisplayName("계수 토큰의 모양을 그대로 둔다 — ops/apply-window/env.sh의 note_nums()가 이 다섯을 정규식으로 합산한다")
    void keepsTheCounterTokensTheJudgeScriptParses() {
        String note = FinancialRefillJob.note(2015, 2026, 1000, 1, 2, 3, 4, 2257, List.of("AAPL"));

        assertThat(note).contains("신규=1").contains("정정=2").contains("채움=3")
                .contains("교정=4").contains("이른판=2257");
    }

    @Test
    @DisplayName("종목이 많으면 목록을 자르고 수와 실행 id를 싣는다 — 목록을 통째로 실어 웹훅이 400으로 거절했다")
    void capsTheTickerListSoTheAlertFitsTheChannel() {
        java.util.List<String> many = java.util.stream.IntStream.range(0, 574)
                .mapToObj(i -> "TICK" + i + "(정정 0 · 교정 0 · 이른 판 2)").toList();

        String body = FinancialRefillJob.invalidationAlertBody(many, 1148, java.util.List.of(), 601L, false);

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
                List.of("AAPL(정정 0 · 교정 0 · 이른 판 2)"), 2, List.of(), 1L, false);

        assertThat(body).doesNotContain("full-statement-refill");
        assertThat(body).contains("--job=indicator-reconcile");
    }

    @Test
    @DisplayName("전체 재무제표 소스가 있는 종목만 그 명령에 싣는다")
    void asksForFullStatementsOnlyForStocksWithThatSource() {
        String body = FinancialRefillJob.invalidationAlertBody(
                List.of("005930(정정 1 · 교정 0 · 이른 판 0)", "AAPL(정정 0 · 교정 0 · 이른 판 2)"), 3,
                List.of("005930"), 1L, false);

        assertThat(body).contains("--job=full-statement-refill --tickers=005930'");
    }

    @Test
    @DisplayName("정기 실행이면 손으로 돌리지 말라고 한다 — 같은 오후 14:20 화해와 같은 행을 두 JVM이 쓴다")
    void scheduledRunLeavesFillingToTheReconcile() {
        String body = FinancialRefillJob.invalidationAlertBody(
                List.of("AAPL(정정 0 · 교정 0 · 이른 판 2)"), 2, List.of(), 1L, true);

        assertThat(body).contains("14:20 화해").contains("손으로 돌리지 않는다").doesNotContain("--job=");
    }

    @Test
    @DisplayName("손 실행이면 화해를 시키되 잠금 문 안에서 — 끝 조항까지 싣고 옛 날짜 루프 백필 두 판은 시키지 않는다")
    void manualRunAsksForAReconcileOutsideTheBatchWindow() {
        String body = FinancialRefillJob.invalidationAlertBody(
                List.of("AAPL(정정 0 · 교정 0 · 이른 판 2)"), 2, List.of(), 1L, false);

        assertThat(body).contains("--job=indicator-reconcile --tickers=<위 종목>")
                .contains("ops/crons-done.sh가 0이고 적재 JVM이 없을 때 시작해 그 출력의 다음 명목 발화 전에 끝낼 수 있으면")
                .contains("평일 13:30~15:40은 그와 무관하게 피한다")
                .doesNotContain("--job=backfill");
    }

    @Test
    @DisplayName("14:16 공시 소비(GAP_FILL)도 같은 오후 14:20이 채운다 — 손 실행 문구를 내면 크론이 곧 쓸 행을 다른 JVM으로 쓰라고 시킨다")
    void consumptionRunIsFilledTheSameAfternoon() {
        assertThat(FinancialRefillJob.fillsSameAfternoon(BatchExecutionRecorder.TriggeredBy.GAP_FILL)).isTrue();
        assertThat(FinancialRefillJob.fillsSameAfternoon(BatchExecutionRecorder.TriggeredBy.SCHEDULER)).isTrue();
        assertThat(FinancialRefillJob.fillsSameAfternoon(BatchExecutionRecorder.TriggeredBy.MANUAL)).isFalse();
    }
}
