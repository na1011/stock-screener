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
import com.stockscreener.pipeline.datasource.SourceAnswer;

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
    @DisplayName("「답함」은 표지 단 NOT_FOUND·MALFORMED뿐이다 — 표지 없는 같은 종류와 한도·일시·예상밖·인증은 표지와 무관하게 「답 못 받음」")
    void classification() {
        assertThat(MarketCompleteness.sourceAnswered("NOT_FOUND", true)).isTrue();
        assertThat(MarketCompleteness.sourceAnswered("MALFORMED", true)).isTrue();

        assertThat(MarketCompleteness.sourceAnswered("NOT_FOUND", false))
                .as("기본은 「답 못 받음」 — 끊긴 전송·우리 인자 오류도 NOT_FOUND로 올 수 있다").isFalse();
        assertThat(MarketCompleteness.sourceAnswered("MALFORMED", false)).isFalse();
        for (String kind : List.of("RATE_LIMITED", "TRANSIENT", "AUTH", "UNEXPECTED")) {
            assertThat(MarketCompleteness.sourceAnswered(kind, true)).as(kind).isFalse();
            assertThat(MarketCompleteness.sourceAnswered(kind, false)).as(kind).isFalse();
        }
        assertThat(MarketCompleteness.sourceAnswered(null, true)).isFalse();
    }

    @Test
    @DisplayName("「답함」 표지는 NOT_FOUND·MALFORMED에만 달린다 — 한도·일시 장애는 정의상 답을 못 받은 것이다")
    void answeredFactoryRefusesUnansweredKinds() {
        DataSourceException answered = SourceAnswer.answered(Kind.NOT_FOUND, "dart", "013");
        assertThat(SourceAnswer.isAnswered(answered)).isTrue();
        assertThat(answered.kind()).as("종류·메시지는 그대로다").isEqualTo(Kind.NOT_FOUND);
        assertThat(answered.getMessage()).isEqualTo("013");
        assertThat(SourceAnswer.isAnswered(new DataSourceException(Kind.NOT_FOUND, "dart", "013"))).isFalse();
        assertThatThrownBy(() -> SourceAnswer.answered(Kind.TRANSIENT, "dart", "5xx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAnswer.answered(Kind.RATE_LIMITED, "dart", "020"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("표지 단 NOT_FOUND는 몇 번이어도 완료 · 표지 없는 NOT_FOUND·MALFORMED는 한 건이면 미완료")
    void answeredFailuresCompleteUnmarkedOnesDoNot() {
        MarketCompleteness answered = new MarketCompleteness(ALL_SOURCES);
        answered.record("KOSPI", true, null, false);
        for (int i = 0; i < 9; i++) {
            answered.record("KOSPI", false, "NOT_FOUND", true);
        }
        assertThat(answered.judge()).isEmpty();

        MarketCompleteness unmarked = new MarketCompleteness(ALL_SOURCES);
        unmarked.record("KOSPI", true, null, false);
        unmarked.record("KOSPI", false, "NOT_FOUND", false);
        unmarked.record("US", true, null, false);
        unmarked.record("US", false, "MALFORMED", false);
        assertThat(MarketCompleteness.markets(unmarked.judge())).containsExactly("KOSPI", "US");
    }

    @Test
    @DisplayName("시장마다 판정한다 — (가) 시작 때 소스 없음 · (나) 성공 0 실패 있음 · (다) 답 못 받은 실패 한 건")
    void judgesEachMarket() {
        MarketCompleteness completeness = new MarketCompleteness(
                new MarketCompleteness.Coverage(List.of("KOSPI", "KOSDAQ", "US"), Set.of("US")));
        // KOSPI: 성공이 있고 일시 장애 한 건 — (다)
        completeness.record("KOSPI", true, null, false);
        completeness.record("KOSPI", false, "TRANSIENT", false);
        // KOSDAQ: 전부 답한 실패 — 성공 0이라 (나)
        completeness.record("KOSDAQ", false, "NOT_FOUND", true);
        // US: 대상이 없어도 소스가 없으면 (가)

        List<MarketCompleteness.Incomplete> incomplete = completeness.judge();

        assertThat(MarketCompleteness.markets(incomplete)).containsExactly("KOSPI", "KOSDAQ", "US");
        assertThat(incomplete.get(0).reasons()).containsExactly("일시 1");
        assertThat(incomplete.get(1).reasons()).containsExactly("성공 0 실패 1");
        assertThat(incomplete.get(2).reasons()).containsExactly("소스 없음");
    }

    @Test
    @DisplayName("시장이 다 끝나면 미완료가 없다 — 대상이 없는 시장도 소스만 있으면 끝난 것이다")
    void completeRunHasNothingIncomplete() {
        MarketCompleteness completeness = new MarketCompleteness(ALL_SOURCES);
        completeness.record("KOSPI", true, null, false);
        completeness.record("US", true, null, false);

        assertThat(completeness.judge()).isEmpty();
        assertThat(MarketCompleteness.noteTail(List.of())).isEmpty();
    }

    @Test
    @DisplayName("note 꼬리는 사람 말이고 「낱말=」 꼴 토큰이 없다 — 07-judge.sh와 공시 보충 칸의 정규식을 안 건드린다")
    void noteTailHasNoKeyValueTokens() {
        MarketCompleteness completeness = new MarketCompleteness(
                new MarketCompleteness.Coverage(List.of("KOSPI", "KOSDAQ", "US"), Set.of("KOSDAQ")));
        completeness.record("KOSPI", true, null, false);
        for (int i = 0; i < 3; i++) {
            completeness.record("KOSPI", false, "TRANSIENT", false);
        }
        completeness.record("US", true, null, false);
        completeness.record("US", false, "RATE_LIMITED", false);
        completeness.record("US", false, "UNEXPECTED", false);

        String tail = MarketCompleteness.noteTail(completeness.judge());

        assertThat(tail).isEqualTo("시장 미완료 KOSPI 일시 3 · KOSDAQ 소스 없음 · US 한도 1 예상밖 1");
        assertThat(tail).doesNotContain("=");
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
}
