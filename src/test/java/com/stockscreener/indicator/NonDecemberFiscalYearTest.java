package com.stockscreener.indicator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 국내 관문 켬 A29 — 비12월 결산 국내 종목은 재무를 비운다(저장 기간이 12월 결산 합성 규약이라 회계기간을 표현하지 못한다). 판정 술어만 여기서
 * 고정한다 — 비우는 자리는 {@code IndicatorRunner.readRawData}이고, 막은 것이라 옛 행이 걷히는 문은 통화 게이트와 같다(ADR-0094).
 */
class NonDecemberFiscalYearTest {

    @Test
    @DisplayName("국내 3월 결산만 걸린다 — 12월 결산 · 결산월 모름 · 미국은 안 걸린다")
    void onlyDomesticNonDecemberIssuers() {
        assertThat(new IndicatorDataReader.StockAttributes(null, "KOSPI", false, 3).nonDecemberDomesticFiscalYear()).isTrue();
        assertThat(new IndicatorDataReader.StockAttributes(null, "KOSDAQ", false, 12).nonDecemberDomesticFiscalYear()).isFalse();
        assertThat(new IndicatorDataReader.StockAttributes(null, "KOSPI", false, null).nonDecemberDomesticFiscalYear()).isFalse();
        assertThat(new IndicatorDataReader.StockAttributes(null, "US", false, 6).nonDecemberDomesticFiscalYear()).isFalse();
    }
}
