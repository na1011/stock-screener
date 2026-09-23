package com.stockscreener.pipeline.collector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판별자 시험 — <b>표본이 원장의 실제 값이다.</b>
 *
 * <p>여기 적힌 비율은 전부 {@code corporate_action}에 실제로 저장된 값이고(2026-09-23 · 미국
 * APPLIED 155건), 통과·기각 명단은 Alpaca가 준 {@code old_rate}/{@code new_rate}로 대조해
 * <b>불일치 0</b>으로 확인했다. <b>손으로 지은 값으로 재면 소스의 두 표기를 놓친다</b> —
 * 3-for-2가 {@code 2→3}(=1.5)과 {@code 1→1.5}(=1.5) 두 형태로 온다.
 */
class ShareBasisDeriverTest {

    @ParameterizedTest(name = "선언된 주식 교환이다: {0}")
    @DisplayName("[단위] 원장의 정상 분할·병합 비율이 전부 통과한다")
    @ValueSource(strings = {
            "0.0050000000",   // 1:200 NXTT
            "0.0083333333",   // 1:120
            "0.0100000000",   // 1:100 LNZA
            "0.0250000000",   // 1:40  AKTX 2026-03-31
            "0.0500000000",   // 1:20  AMRN · AKTX 2023-08-17
            "0.0833333333",   // 1:12  ORKA
            "0.1250000000",   // 1:8   QXO
            "0.3333333333",   // 1:3
            "1.5000000000",   // 3:2   SNEX — 소스가 2→3 과 1→1.5 두 표기로 준다
            "2.0000000000",
            "3.0000000000",
            "10.0000000000",  // NVDA 2024-06-10
            "15.0000000000",  // ORLY
            "38.0000000000",  // RGC
            "50.0000000000",  // CMG
    })
    void acceptsDeclaredExchanges(String ratio) {
        assertThat(ShareBasisDeriver.isDeclaredShareExchange(new BigDecimal(ratio))).isTrue();
    }

    @ParameterizedTest(name = "가격계수라 기각한다: {0}")
    @DisplayName("[단위] 분사·역합병의 가격계수가 전부 기각된다")
    @ValueSource(strings = {
            "1.9730000000",   // DELL 2021-11-02 — VMware 분사 (1000→1973)
            "1.2890000000",   // TT   2020-03-02 — Reverse Morris Trust (1000→1289)
            "0.8824000000",   // IR   2020-03-02 — 같은 거래의 반대쪽 (1→0.8824)
            "1.1950000000",   // FTV  2020-10-09 — Vontier 분사 (1000→1195)
            "0.4725000000",   // DD   2019-06-03 — Corteva (10000→4725)
            "0.9700000000",   // QGEN 2024-01-30 — 합성 자사주 (25→24.25)
    })
    void rejectsPriceCoefficients(String ratio) {
        assertThat(ShareBasisDeriver.isDeclaredShareExchange(new BigDecimal(ratio))).isFalse();
    }

    @Test
    @DisplayName("[단위] 임계가 빈 구간에 앉아 있다 — 3~96 어디를 골라도 답이 같다")
    void thresholdSitsInAnEmptyGap() {
        // 통과하는 것 중 작은 쪽이 가장 큰 값은 2(3-for-2)이고, 기각되는 것 중 가장 작은 값은
        // 97(QGEN 97/100)이다. 그 사이가 비어 있다는 것이 이 임계를 정당화한다 —
        // MUST: 이 시험이 깨지면 임계를 옮기기 전에 **분포를 다시 재고** ADR §결과를 고친다.
        assertThat(ShareBasisDeriver.isDeclaredShareExchange(new BigDecimal("1.5000000000"))).isTrue();
        assertThat(ShareBasisDeriver.isDeclaredShareExchange(new BigDecimal("0.9700000000"))).isFalse();
    }
}
