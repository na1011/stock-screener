package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertChannel;
import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.notification.AlertSuppressor;
import com.stockscreener.notification.CompositeAlertPort;
import com.stockscreener.notification.LoggingAlertPort;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceException.Kind;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 시장 미완료 판정(ADR-0174)을 고정한다 — 분류 함수 · 시장별 판정 · note 꼬리 · 마감의 알림.
 *
 * <p><b>지키는 방향이 둘이다.</b> 소스의 답을 못 받은 시장이 「끝났다」로 판정되면 그 실행이 시계를 밀어 그 시장이 다음 차례까지
 * 낡은 채 남는다. 소스가 결정적으로 답한 실패까지 미완료로 세면 그 잡이 영영 시계에 안 들어 매 평일 다시 돈다.
 */
class MarketCompletenessTest {

    private static final MarketCompleteness.Coverage ALL_SOURCES =
            new MarketCompleteness.Coverage(List.of("KOSPI", "KOSDAQ", "US"), Set.of());

    @Test
    @DisplayName("「답함」은 표지 단 NOT_FOUND·MALFORMED이면서 상장폐지 증거가 있을 때뿐 — 그 밖은 표지·증거와 무관하게 「답 못 받음」")
    void classification() {
        assertThat(MarketCompleteness.sourceAnswered("NOT_FOUND", true, true)).isTrue();
        assertThat(MarketCompleteness.sourceAnswered("MALFORMED", true, true)).isTrue();

        assertThat(MarketCompleteness.sourceAnswered("NOT_FOUND", true, false))
                .as("현재 편입 · 봉 있음의 부재는 신규 상장 등록 지연 꼴 — 다시 물으면 답이 바뀔 수 있다").isFalse();
        assertThat(MarketCompleteness.sourceAnswered("NOT_FOUND", false, true))
                .as("기본은 「답 못 받음」 — 끊긴 전송·우리 인자 오류도 NOT_FOUND로 올 수 있다").isFalse();
        assertThat(MarketCompleteness.sourceAnswered("MALFORMED", false, true)).isFalse();
        for (String kind : List.of("RATE_LIMITED", "TRANSIENT", "AUTH", "UNEXPECTED")) {
            assertThat(MarketCompleteness.sourceAnswered(kind, true, true)).as(kind).isFalse();
            assertThat(MarketCompleteness.sourceAnswered(kind, false, false)).as(kind).isFalse();
        }
        assertThat(MarketCompleteness.sourceAnswered(null, true, true)).isFalse();
    }

    @Test
    @DisplayName("「답함」 표지는 NOT_FOUND·MALFORMED에만 달린다 — 한도·일시 장애는 정의상 답을 못 받은 것이다")
    void answeredFactoryRefusesUnansweredKinds() {
        DataSourceException answered = DataSourceException.answered(Kind.NOT_FOUND, "dart", "미매핑");
        assertThat(answered.sourceAnswered()).isTrue();
        assertThat(answered.kind()).as("종류·메시지는 그대로다").isEqualTo(Kind.NOT_FOUND);
        assertThat(answered.getMessage()).isEqualTo("미매핑");
        assertThat(new DataSourceException(Kind.NOT_FOUND, "dart", "013").sourceAnswered()).isFalse();
        assertThatThrownBy(() -> DataSourceException.answered(Kind.TRANSIENT, "dart", "5xx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataSourceException.answered(Kind.RATE_LIMITED, "dart", "020"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("표지 단 NOT_FOUND는 몇 번이어도 완료 · 표지 없는 NOT_FOUND·MALFORMED는 한 건이면 미완료")
    void answeredFailuresCompleteUnmarkedOnesDoNot() {
        // 9번 되풀이된 표지 실패는 상장폐지 증거가 있는 종목(9)의 것이다.
        MarketCompleteness answered = new MarketCompleteness(ALL_SOURCES.withRetired(Set.of(9L)));
        answered.record("KOSPI", 1L, true, null, false);
        for (int i = 0; i < 9; i++) {
            answered.record("KOSPI", 9L, false, "NOT_FOUND", true);
        }
        assertThat(answered.judge()).isEmpty();

        MarketCompleteness unmarked = new MarketCompleteness(ALL_SOURCES);
        unmarked.record("KOSPI", 1L, true, null, false);
        unmarked.record("KOSPI", 1L, false, "NOT_FOUND", false);
        unmarked.record("US", 1L, true, null, false);
        unmarked.record("US", 1L, false, "MALFORMED", false);
        assertThat(MarketCompleteness.markets(unmarked.judge())).containsExactly("KOSPI", "US");
    }

    @Test
    @DisplayName("같은 표지 실패도 상장폐지 증거가 없는 종목(현재 편입 · 봉 있음)이면 미완료다 — 신규 상장 등록 지연 꼴")
    void answeredWithoutRetirementEvidenceIsIncomplete() {
        MarketCompleteness completeness = new MarketCompleteness(ALL_SOURCES.withRetired(Set.of(9L)));
        completeness.record("KOSPI", 1L, true, null, false);
        completeness.record("KOSPI", 2L, false, "NOT_FOUND", true);

        assertThat(MarketCompleteness.noteTail(completeness.judge())).isEqualTo("시장 미완료 KOSPI 없음(답 미확인) 1");
    }

    @Test
    @DisplayName("시장마다 판정한다 — (가) 시작 때 소스 없음 · (나) 성공 0 실패 있음 · (다) 답 못 받은 실패 한 건")
    void judgesEachMarket() {
        MarketCompleteness completeness = new MarketCompleteness(
                new MarketCompleteness.Coverage(List.of("KOSPI", "KOSDAQ", "US"), Set.of("US"), Set.of(9L)));
        // KOSPI: 성공이 있고 일시 장애 한 건 — (다)
        completeness.record("KOSPI", 1L, true, null, false);
        completeness.record("KOSPI", 1L, false, "TRANSIENT", false);
        // KOSDAQ: 전부 답한 실패 — 성공 0이라 (나)
        completeness.record("KOSDAQ", 9L, false, "NOT_FOUND", true);
        // US: 대상이 없어도 소스가 없으면 (가)

        List<MarketCompleteness.Incomplete> incomplete = completeness.judge();

        assertThat(MarketCompleteness.markets(incomplete)).containsExactly("KOSPI", "KOSDAQ", "US");
        assertThat(incomplete.get(0).reasons()).containsExactly("일시 1");
        assertThat(incomplete.get(1).reasons()).containsExactly("성공 0 실패 1");
        assertThat(incomplete.get(2).reasons()).containsExactly("소스 없음");
    }

    @Test
    @DisplayName("(라) 종목은 성공해도 보충을 받지 못했으면 그 시장은 미완료다 — 비어 있으면(UNREADABLE만 있었던 것 포함) 끝난 것이다")
    void unfetchedLayerMakesTheMarketIncomplete() {
        MarketCompleteness completeness = new MarketCompleteness(ALL_SOURCES);
        completeness.record("US", 1L, true, null, false);
        completeness.recordUnfetchedLayer("US", "보충", List.of(Kind.TRANSIENT, Kind.NOT_FOUND, Kind.TRANSIENT));
        completeness.record("KOSPI", 1L, true, null, false);
        completeness.recordUnfetchedLayer("KOSPI", "보충", List.of());

        assertThat(MarketCompleteness.markets(completeness.judge())).containsExactly("US");
        assertThat(MarketCompleteness.noteTail(completeness.judge()))
                .isEqualTo("시장 미완료 US 보충 없음(답 미확인) 1 보충 일시 2");
    }

    @Test
    @DisplayName("시장이 다 끝나면 미완료가 없다 — 대상이 없는 시장도 소스만 있으면 끝난 것이다")
    void completeRunHasNothingIncomplete() {
        MarketCompleteness completeness = new MarketCompleteness(ALL_SOURCES);
        completeness.record("KOSPI", 1L, true, null, false);
        completeness.record("US", 1L, true, null, false);

        assertThat(completeness.judge()).isEmpty();
        assertThat(MarketCompleteness.noteTail(List.of())).isEmpty();
    }

    @Test
    @DisplayName("note 꼬리는 사람 말이고 「낱말=」 꼴 토큰이 없다 — note_nums()와 공시 보충 칸의 정규식을 안 건드린다")
    void noteTailHasNoKeyValueTokens() {
        MarketCompleteness completeness = new MarketCompleteness(
                new MarketCompleteness.Coverage(List.of("KOSPI", "KOSDAQ", "US"), Set.of("KOSDAQ")));
        completeness.record("KOSPI", 1L, true, null, false);
        for (int i = 0; i < 3; i++) {
            completeness.record("KOSPI", 1L, false, "TRANSIENT", false);
        }
        completeness.record("US", 1L, true, null, false);
        completeness.record("US", 1L, false, "RATE_LIMITED", false);
        completeness.record("US", 1L, false, "UNEXPECTED", false);

        String tail = MarketCompleteness.noteTail(completeness.judge());

        assertThat(tail).isEqualTo("시장 미완료 KOSPI 일시 3 · KOSDAQ 소스 없음 · US 한도 1 예상밖 1");
        assertThat(tail).doesNotContain("=");
    }

    @Test
    @DisplayName("MALFORMED 종목 실패가 처리 20건 이상에서 20%를 넘으면 「응답 해석 실패 다발」 ERROR 한 통 — 실패 기록 자리가 센다")
    void malformedSurgeAlertsOnce() throws Exception {
        assertThat(malformedAlerts(16, 4, false)).containsExactly("governance-load 소스 응답 해석 실패 다발");
    }

    @Test
    @DisplayName("다발 경보가 안 나는 셋 — 20% 미만 · 처리 20건 미만(1건이 50%) · 소스가 답한(표지) MALFORMED")
    void malformedAlertGuards() throws Exception {
        assertThat(malformedAlerts(19, 1, false)).as("5%").isEmpty();
        assertThat(malformedAlerts(1, 1, false)).as("처리 2건 — 리밸런싱 꼬리 꼴의 오보").isEmpty();
        assertThat(malformedAlerts(16, 4, true)).as("표지 단 MALFORMED는 스펙 변경 신호가 아니다").isEmpty();
    }

    /** 성공 {@code ok}건과 MALFORMED {@code garbled}건을 처리하고 마감한 뒤 나간 ERROR 알림 제목. */
    private static List<String> malformedAlerts(int ok, int garbled, boolean answered) throws Exception {
        List<AlertEvent> delivered = new ArrayList<>();
        CompositeAlertPort alertPort = new CompositeAlertPort(List.of(recordingChannel(delivered)),
                new AlertSuppressor(Duration.ofMinutes(10)), new LoggingAlertPort());
        StockTaskRunner runner = mock(StockTaskRunner.class);
        BatchExecutionRecorder recorder = mock(BatchExecutionRecorder.class);
        when(recorder.finish(anyLong(), any(), any(), any())).thenReturn(BatchStatus.PARTIAL_SUCCESS);
        when(runner.runInNewTransaction(any(), any())).thenAnswer(call -> {
            SecurityRef security = call.getArgument(0);
            if (security.stockId() > ok) {
                throw answered ? DataSourceException.answered(Kind.MALFORMED, "dart", "필드 없음")
                        : new DataSourceException(Kind.MALFORMED, "dart", "필드 없음");
            }
            return 1L;
        });
        // 시장 판정은 끈다(coverage null) — 이 시험은 다발 경보 하나만 본다.
        BatchExecutionContext context = new BatchExecutionContext(8L, "governance-load",
                LocalDate.of(2026, 10, 29), new StockTaskExecutor(runner, recorder), recorder, alertPort, id -> { });
        for (int i = 1; i <= ok + garbled; i++) {
            context.process(new SecurityRef(i, "KOSPI", "%06d".formatted(i)), CollectionPhase.GOVERNANCE,
                    security -> 1L);
        }
        context.finish();
        return delivered.stream().filter(event -> event.severity() == AlertSeverity.ERROR)
                .map(AlertEvent::title).toList();
    }

    private static AlertChannel recordingChannel(List<AlertEvent> delivered) {
        return new AlertChannel() {
            @Override
            public String name() {
                return "fake-webhook";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public boolean accepts(AlertEvent event) {
                return event.severity().compareTo(AlertSeverity.WARNING) >= 0;
            }

            @Override
            public void deliver(AlertEvent event) {
                delivered.add(event);
            }
        };
    }

    @Test
    @DisplayName("미완료 알림과 부분 실패 알림이 실제 억제기를 지나 둘 다 채널에 닿는다 — 같은 키면 10분 억제가 뒤의 것을 버린다")
    void incompleteAlertIsNotSuppressedByThePartialAlert() throws Exception {
        List<AlertEvent> delivered = new ArrayList<>();
        AlertChannel webhookLike = new AlertChannel() {
            @Override
            public String name() {
                return "fake-webhook";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public boolean accepts(AlertEvent event) {
                // 운영 웹훅의 최소 심각도와 같다(application.yaml) — 그 아래면 채널이 안 받는다.
                return event.severity().compareTo(AlertSeverity.WARNING) >= 0;
            }

            @Override
            public void deliver(AlertEvent event) {
                delivered.add(event);
            }
        };
        CompositeAlertPort alertPort = new CompositeAlertPort(List.of(webhookLike),
                new AlertSuppressor(Duration.ofMinutes(10)), new LoggingAlertPort());

        StockTaskRunner runner = mock(StockTaskRunner.class);
        BatchExecutionRecorder recorder = mock(BatchExecutionRecorder.class);
        when(recorder.finish(anyLong(), any(), any(), any())).thenReturn(BatchStatus.PARTIAL_SUCCESS);
        SecurityRef ok = new SecurityRef(1, "KOSPI", "005930");
        SecurityRef broken = new SecurityRef(2, "KOSPI", "000660");
        when(runner.runInNewTransaction(eq(ok), any())).thenReturn(1L);
        when(runner.runInNewTransaction(eq(broken), any()))
                .thenThrow(new DataSourceException(Kind.TRANSIENT, "dart", "503"));
        BatchExecutionContext context = new BatchExecutionContext(7L, "financial-refill",
                LocalDate.of(2026, 10, 29), new StockTaskExecutor(runner, recorder), recorder, alertPort,
                id -> { }, ALL_SOURCES);

        context.process(ok, CollectionPhase.FINANCIAL, security -> 1L);
        context.process(broken, CollectionPhase.FINANCIAL, security -> 1L);
        context.finish("연도=2018~2026");

        assertThat(delivered).extracting(AlertEvent::title)
                .containsExactly("financial-refill 부분 실패", "financial-refill 시장 미완료");
        assertThat(delivered.get(1).severity()).isEqualTo(AlertSeverity.WARNING);
        assertThat(delivered.get(1).body()).contains("시장 미완료 KOSPI 일시 1");
        // 기록 계층에는 앞머리를 그대로 두고 꼬리만 붙인 note와 구조화된 시장 목록이 간다.
        verify(recorder).finish(eq(7L), any(), eq("연도=2018~2026 시장 미완료 KOSPI 일시 1"), eq(List.of("KOSPI")));
        verify(recorder).recordFailure(eq(7L), eq(2L), eq(CollectionPhase.FINANCIAL), any(DataSourceException.class));
    }

    @Test
    @DisplayName("시작부터 좁힌 실행(꼬리 · --tickers 손 실행)에는 시장 미완료 알림을 안 보낸다 — 부분 실패 알림 하나뿐 · 기록은 남긴다")
    void narrowStartSendsNoIncompleteAlert() throws Exception {
        List<AlertEvent> delivered = new ArrayList<>();
        CompositeAlertPort alertPort = new CompositeAlertPort(List.of(recordingChannel(delivered)),
                new AlertSuppressor(Duration.ofMinutes(10)), new LoggingAlertPort());
        StockTaskRunner runner = mock(StockTaskRunner.class);
        BatchExecutionRecorder recorder = mock(BatchExecutionRecorder.class);
        when(recorder.finish(anyLong(), any(), any(), any())).thenReturn(BatchStatus.PARTIAL_SUCCESS);
        SecurityRef broken = new SecurityRef(2, "KOSPI", "000660");
        when(runner.runInNewTransaction(eq(broken), any()))
                .thenThrow(new DataSourceException(Kind.TRANSIENT, "dart", "503"));
        BatchExecutionContext context = new BatchExecutionContext(7L, "governance-load",
                LocalDate.of(2026, 10, 29), new StockTaskExecutor(runner, recorder), recorder, alertPort,
                id -> { }, ALL_SOURCES, false);

        context.process(broken, CollectionPhase.GOVERNANCE, security -> 1L);
        context.finish();

        assertThat(delivered).extracting(AlertEvent::title).containsExactly("governance-load 부분 실패");
        verify(recorder).finish(eq(7L), any(), any(), eq(List.of("KOSPI")));
    }

    @Test
    @DisplayName("같은 잡·같은 대상일의 두 실행(14:16 소비의 미국 FR → 국내 FR)은 부분 실패 알림을 하나씩 보낸다 — 앞 실행이 10분 억제로 뒤 실행을 삼키지 않는다")
    void sameDayRunsOfOneJobAlertSeparately() throws Exception {
        List<AlertEvent> delivered = new ArrayList<>();
        CompositeAlertPort alertPort = new CompositeAlertPort(List.of(recordingChannel(delivered)),
                new AlertSuppressor(Duration.ofMinutes(10)), new LoggingAlertPort());
        StockTaskRunner runner = mock(StockTaskRunner.class);
        BatchExecutionRecorder recorder = mock(BatchExecutionRecorder.class);
        when(recorder.finish(anyLong(), any(), any(), any())).thenReturn(BatchStatus.PARTIAL_SUCCESS);
        when(runner.runInNewTransaction(any(), any()))
                .thenThrow(new DataSourceException(Kind.TRANSIENT, "sec", "503"));

        for (long executionId : new long[] {7L, 8L}) {
            BatchExecutionContext context = new BatchExecutionContext(executionId, "financial-refill",
                    LocalDate.of(2026, 10, 29), new StockTaskExecutor(runner, recorder), recorder, alertPort,
                    id -> { }, ALL_SOURCES, false);
            context.process(new SecurityRef(executionId, "US", "T" + executionId), CollectionPhase.FINANCIAL,
                    security -> 1L);
            context.finish();
        }

        assertThat(delivered).extracting(AlertEvent::title)
                .containsExactly("financial-refill 부분 실패", "financial-refill 부분 실패");
    }
}
