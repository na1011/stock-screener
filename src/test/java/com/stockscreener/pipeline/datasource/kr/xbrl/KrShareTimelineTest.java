package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Register;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncomeRows.Row;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 주식수 시간표(계획 K3-b) — 기간 가중 구간 · 닫힘 · 반기 굴림 · 보이는 날. */
class KrShareTimelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 앞 해 말 보통주 1,000만(자기주식 0). */
    private static final Map<Integer, Register> REGISTERS = Map.of(2024,
            new Register(LocalDate.of(2024, 12, 31), false, new BigDecimal("10000000"), new BigDecimal("10000000"), BigDecimal.ZERO, 1));

    @Test
    void weighsADatedIssueAndBracketsTreasuryMoves() {
        // 1분기(90일) — 2/1 제3자배정 59만주(59일 가중) · 자기주식 10만 취득 · 분기말 명부 1,049만 = 1,000만 + 59만 − 10만(닫힘).
        Row q1 = row("Q1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 5, 15));
        KrShareTimeline timeline = new KrShareTimeline(List.of(
                irds(2025, "11013", "20250515000001", event("2025.02.01", "유상증자(제3자배정)", "590,000")),
                treasury(2025, "11013", "20250515000001", "100,000", "-"),
                register(2025, "11013", "20250515000001", "10,590,000", "100,000")), REGISTERS);

        KrShareTimeline.Window w = timeline.window(q1, List.of(q1));

        // 1,000만 + 59만 × 59/90 = 10,386,777.78 · 자기주식은 [−10만, 0].
        assertThat(w.high()).isCloseTo(new BigDecimal("10386777.78"), org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
        assertThat(w.low()).isCloseTo(new BigDecimal("10286777.78"), org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
        assertThat(w.visible()).isEqualTo(LocalDate.of(2025, 5, 15));

        // 대조군 — 명부가 사건 합과 안 닫히면(빠진 사건) 구간을 안 낸다.
        KrShareTimeline open = new KrShareTimeline(List.of(
                irds(2025, "11013", "20250515000001"),
                treasury(2025, "11013", "20250515000001", "100,000", "-"),
                register(2025, "11013", "20250515000001", "10,590,000", "100,000")), REGISTERS);
        assertThat(open.window(q1, List.of(q1))).isNull();
    }

    @Test
    void rollsTheHalfYearFromTheFirstQuarterAndTakesTheLatestReceipt() {
        // 반기 3개월 — 1분기 자기주식 누계 취득 10만 · 반기 누계 취득 30만(3개월 20만) · 사건 없음 · 반기말 명부 970만(4/1 990만 − 20만).
        // 반기 표는 정정판(9/1 접수)이 왔다 — 그날부터 보인다.
        Row q1 = row("Q1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 5, 15));
        Row q2 = row("Q2", LocalDate.of(2025, 4, 1), LocalDate.of(2025, 6, 30), LocalDate.of(2025, 8, 14));
        KrShareTimeline timeline = new KrShareTimeline(List.of(
                treasury(2025, "11013", "20250515000001", "100,000", "-"),
                irds(2025, "11012", "20250901000009"),
                treasury(2025, "11012", "20250901000009", "300,000", "-"),
                register(2025, "11012", "20250901000009", "10,000,000", "300,000")), REGISTERS);

        KrShareTimeline.Window w = timeline.window(q2, List.of(q1, q2));

        assertThat(w.low()).isEqualByComparingTo("9700000");
        assertThat(w.high()).isEqualByComparingTo("9900000");
        assertThat(w.visible()).isEqualTo(LocalDate.of(2025, 9, 1));
        // 대조군 — 1분기 판 자기주식 표가 없으면 3개월 몫을 모른다.
        KrShareTimeline missing = new KrShareTimeline(List.of(
                irds(2025, "11012", "20250901000009"),
                treasury(2025, "11012", "20250901000009", "300,000", "-"),
                register(2025, "11012", "20250901000009", "10,000,000", "300,000")), REGISTERS);
        assertThat(missing.window(q2, List.of(q1, q2))).isNull();
    }

    private static Row row(String period, LocalDate start, LocalDate end, LocalDate published) {
        return new Row(1L, 2025, period, start, end, published, BigDecimal.ONE, BigDecimal.ONE, List.of());
    }

    private static KrXbrlStore.ShareMaterial irds(int year, String code, String rcept, JsonNode... events) {
        return new KrXbrlStore.ShareMaterial(year, code, "irdsSttus", rcept, new ArrayList<>(List.of(events)));
    }

    private static JsonNode event(String day, String form, String quantity) {
        return MAPPER.readTree("{\"isu_dcrs_de\":\"%s\",\"isu_dcrs_stle\":\"%s\",\"isu_dcrs_stock_knd\":\"보통주\",\"isu_dcrs_qy\":\"%s\"}"
                .formatted(day, form, quantity));
    }

    private static KrXbrlStore.ShareMaterial treasury(int year, String code, String rcept, String acquired, String disposed) {
        return new KrXbrlStore.ShareMaterial(year, code, "tesstkAcqsDspsSttus", rcept, List.of(MAPPER.readTree(
                "{\"stock_knd\":\"보통주\",\"acqs_mth1\":\"총계\",\"change_qy_acqs\":\"%s\",\"change_qy_dsps\":\"%s\"}".formatted(acquired, disposed))));
    }

    private static KrXbrlStore.ShareMaterial register(int year, String code, String rcept, String issued, String treasury) {
        return new KrXbrlStore.ShareMaterial(year, code, "stockTotqySttus", rcept, List.of(
                MAPPER.readTree("{\"se\":\"보통주\",\"istc_totqy\":\"%s\",\"tesstk_co\":\"%s\"}".formatted(issued, treasury)),
                MAPPER.readTree("{\"se\":\"합계\",\"istc_totqy\":\"%s\",\"tesstk_co\":\"%s\"}".formatted(issued, treasury))));
    }
}
