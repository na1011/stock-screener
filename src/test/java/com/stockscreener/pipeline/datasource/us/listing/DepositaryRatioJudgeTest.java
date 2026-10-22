package com.stockscreener.pipeline.datasource.us.listing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** 공시마다 k(ADR-0183) — 꼴마다 하나씩. 수는 드라이런 1(77 CIK 표지 · Alpaca 사건)에서 온 실제 모양을 줄인 것이다. */
class DepositaryRatioJudgeTest {

    private static DepositaryCover.Reading ratio(double k) {
        return new DepositaryCover.Reading(DepositaryCover.ReadingKind.RATIO, k, new TreeSet<>(List.of(k)));
    }

    private static DepositaryCover.Reading absent() {
        return new DepositaryCover.Reading(DepositaryCover.ReadingKind.ABSENT, Double.NaN, new TreeSet<>());
    }

    private static DepositaryRatioJudge.Filing filing(String accn, String filed, DepositaryCover.Reading r, String shares) {
        return new DepositaryRatioJudge.Filing(accn, LocalDate.parse(filed), r, shares == null ? null : new BigDecimal(shares));
    }

    @Test
    @DisplayName("표지가 못 읽히는 판은 사건 없고 보통주 1.5배 안인 이웃 표지의 k를 잇는다 — 앞이 먼저")
    void carriesFromTheNearestOwnCover() {
        Map<String, DepositaryRatioJudge.Decision> d = DepositaryRatioJudge.decide(List.of(
                filing("a", "2023-04-01", ratio(3), "300"),
                filing("b", "2024-04-01", absent(), "310"),
                filing("c", "2025-04-01", null, "320")), List.of());

        assertThat(d.get("b")).isEqualTo(new DepositaryRatioJudge.Decision(3.0, DepositaryRatioJudge.How.CARRY_FORWARD));
        assertThat(d.get("c").how()).isEqualTo(DepositaryRatioJudge.How.CARRY_FORWARD);
    }

    @Test
    @DisplayName("사이에 ADS 사건이 있으면 그쪽으로 잇지 않는다 — UXIN 3 → 30 꼴은 뒤 표지에서 잇는다")
    void eventBlocksCarry() {
        Map<String, DepositaryRatioJudge.Decision> d = DepositaryRatioJudge.decide(List.of(
                filing("a", "2022-04-01", ratio(3), "1000"),
                filing("b", "2023-04-01", null, "1000"),
                filing("c", "2023-08-01", ratio(30), "1000")),
                List.of(new DepositaryRatioJudge.Event(LocalDate.parse("2022-10-28"), 0.1)));

        assertThat(d.get("b")).isEqualTo(new DepositaryRatioJudge.Decision(30.0, DepositaryRatioJudge.How.CARRY_BACK));
    }

    @Test
    @DisplayName("보통주가 1.5배 넘게 바뀌면 잇지 않는다 — BABA 2019 보통주 8:1(ADS 사건 없음) 앞 판에 k = 8이 안 붙는다")
    void ordinarySplitBlocksCarry() {
        Map<String, DepositaryRatioJudge.Decision> d = DepositaryRatioJudge.decide(List.of(
                filing("pre", "2019-05-15", null, "2600"),
                filing("post", "2020-07-09", ratio(8), "21500")), List.of());

        assertThat(d.get("pre")).isEqualTo(new DepositaryRatioJudge.Decision(null, DepositaryRatioJudge.How.UNRESOLVED));
        assertThat(d.get("post").k()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("k ≥ 2인데 그 판 주식수가 앞 보통주 판의 1/k면 그 판은 ADS 단위다 — CMMB 10-Q 11.4M 대 10-K 228M")
    void adsUnitFilingGetsKOne() {
        Map<String, DepositaryRatioJudge.Decision> d = DepositaryRatioJudge.decide(List.of(
                filing("k1", "2022-03-30", ratio(20), "228000000"),
                filing("q1", "2022-05-12", ratio(20), "11400000"),
                filing("q2", "2022-08-12", ratio(20), "11420000"),
                filing("k2", "2023-03-20", ratio(20), "230000000")), List.of());

        assertThat(d.get("q1")).isEqualTo(new DepositaryRatioJudge.Decision(1.0, DepositaryRatioJudge.How.ADS_UNIT));
        assertThat(d.get("q2").how()).isEqualTo(DepositaryRatioJudge.How.ADS_UNIT);
        assertThat(d.get("k2")).isEqualTo(new DepositaryRatioJudge.Decision(20.0, DepositaryRatioJudge.How.OWN));
    }

    @Test
    @DisplayName("ADS 사건이 설명하는 계단은 단위 이탈이 아니다 — 사건 배수를 곱해 비교한다")
    void eventExplainedStepIsNotAUnitFlip() {
        Map<String, DepositaryRatioJudge.Decision> d = DepositaryRatioJudge.decide(List.of(
                filing("a", "2024-01-01", ratio(2), "1000"),
                filing("b", "2025-01-01", ratio(10), "1000")),
                List.of(new DepositaryRatioJudge.Event(LocalDate.parse("2024-06-07"), 0.2)));

        assertThat(d.get("b")).isEqualTo(new DepositaryRatioJudge.Decision(10.0, DepositaryRatioJudge.How.OWN));
    }

    @Test
    @DisplayName("표지에 예탁증권 줄이 없으면 k = 1 — AZN 2026 보통주 직접 상장 꼴")
    void notDepositaryLineIsOne() {
        DepositaryCover.Reading ordinary = new DepositaryCover.Reading(
                DepositaryCover.ReadingKind.NOT_DEPOSITARY, Double.NaN, new TreeSet<>());
        Map<String, DepositaryRatioJudge.Decision> d = DepositaryRatioJudge.decide(List.of(
                filing("a", "2026-02-10", ordinary, "1550000000")), List.of());

        assertThat(d.get("a")).isEqualTo(new DepositaryRatioJudge.Decision(1.0, DepositaryRatioJudge.How.NOT_DEPOSITARY));
    }
}
