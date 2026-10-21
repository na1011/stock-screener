package com.stockscreener.pipeline.datasource.us.listing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 원문은 전부 2026-10-22 SEC 표지 탐침에서 옮겼다(탐색 {@code ADS-BASIS-PLAN-1022.md} §1e). */
class DepositaryRatioTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "American Depositary Shares, each representing one Ordinary Share, nominal value £0.001 per share | 1",
            "American Depositary Shares, each representing eight Ordinary Shares | 8",
            "American Depositary Shares, each representing three Equity Shares, Par value Rs. 1.0 per share | 3",
            "American depositary shares, each ADS represents 15 ordinary shares, par valueUS$0.001 per share | 15",
            "American Depositary Shares, each representing 13 Ordinary Shares, par value $0.0001 per share | 13",
            "American depositary shares (one American depositary share representing 300 Class A ordinary shares, par value US$0.0001 per share) | 300",
            "American depositary shares, each ten(10) American depositary shares representing twenty-three (23) ordinary shares | 2.3",
            "American depositary shares, each six (6) ADSs representing one (1) Class A ordinary share, par value US$0.0001 per share | 0.16666666666666666",
            "American Depositary Shares, each three representing one Class A common share | 0.3333333333333333",
            "American depositary shares, each representing two hundred and forty Class B ordinary shares | 240",
            "American Depositary Shares (ADS(s)), each ADSrepresenting the right to receive twenty (20) Ordinary Share of Amarin Corp | 20",
            "American Depositary Shares, each representingone Ordinary Share, par value oneNew Israeli Shekel per share | 1",
            "American Depositary Shares, each representingthree Class A Ordinary Shares, par valueUS$0.0001 per share | 3",
            "American Depositary Shares, each representing 15Class A ordinary share | 15",
            "American depositary shares, each representing 0.4 Class A ordinary shares, par value US$0.0001 per share | 0.4",
            "American Depositary Shares, each representing 4,000 ordinary share, no par value | 4000",
            "American Depository Shares, each representing five ordinary shares, nominal value €0.10 per share | 5",
            "American         Depositary Shares, each representing eighty (80) ordinary shares, no par value per share | 80",
            "American depositary shares, each representing ten common shares | 10",
            "American Depositary Shares, one representing five Class A ordinary shares, par value US$0.001 per share | 5",
            "American depositary shares, two American depositary shares representing one Class A ordinary share, par value US$0.000125 per share | 0.5",
            "American depositary shares, two representing one Class A ordinary share, par value US$0.001 per share | 0.5",
            "American depositary shares, each representing one-half of one ordinary share, nominal value €0.10 per share | 0.5",
            "American depositary shares, each representing one-third of an ordinary share, par value $0.00005 per share | 0.3333333333333333",
            "American depositary shares (each ADS represents two of our Class A ordinary shares, par value US$0.0001 per share) | 2",
            "American depositary shares (each ADS represents two of our Class Aordinary shares, par value US$0.0001 per share) | 2",
            "American Depositary Shares, each representing three ordinaryshares, nominal value £0.05 per share | 3",
            "American depositary shares, each representing one Class A | 1",
            "American depositary shares (each representing 0.5 Class A ordinary shares, par value US$0.000125 per share) | 0.5",
            "American depositary shares, each representing5 ordinary sharesordinary shares, par value US$0.0001 per share* | 5",
            "American depositary shares, each representing oneordinary share, par value $0.00006 per share | 1",
    })
    @DisplayName("표지 글의 꼴마다 1 예탁증권당 주식 수를 읽는다")
    void readsTheRatio(String title, double expected) {
        assertThat(DepositaryRatio.isDepositary(title)).isTrue();
        assertThat(DepositaryRatio.sharesPerDepositary(title)).hasValueCloseTo(expected, within(1e-9));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "American depositary shares",
            "American Depositary Shares",
            "American depositary shares,",
            "American depositary shares, two of which represent",
            "American Depositary Shares, each representing twenty (20)",
    })
    @DisplayName("비율이 글에 없으면 빈 값이다 — 1로 짐작하지 않는다")
    void noRatioIsEmpty(String title) {
        assertThat(DepositaryRatio.isDepositary(title)).isTrue();
        assertThat(DepositaryRatio.sharesPerDepositary(title)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "Depositary shares, each representing a 1/1,000th interest in a 5.45% Series F preferred share",
            "Class A Common Stock, par value $0.0001 per share",
            "Ordinary shares, par value US$0.0001 per share",
            "Common Shares, $0.0011 par value per share",
            "Warrants, each exercisable for one ordinary share at an exercise price of $11.50",
            "ADS-TEC Energy plc Ordinary Shares",
            "Rights to Purchase American Depositary Shares",
            "Rights to purchase American depositary shares, each American depositary share representing one ordinary share, par value NIS 5.00 per share",
    })
    @DisplayName("예탁증권이 아닌 줄은 거짓 · 빈 값이다 — 우선주 예탁증권 · 보통주 · 워런트 · 이름 안의 ADS")
    void notDepositary(String title) {
        assertThat(DepositaryRatio.isDepositary(title)).isFalse();
        assertThat(DepositaryRatio.sharesPerDepositary(title)).isEmpty();
    }
}
