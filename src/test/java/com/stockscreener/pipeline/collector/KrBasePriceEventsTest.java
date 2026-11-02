package com.stockscreener.pipeline.collector;

import com.stockscreener.indicator.IndicatorDataReader;
import com.stockscreener.indicator.ShareAdjustment;
import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.kr.KrSpinOffResolver;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 국내 관문 켬 — 거래소 기준가 조정이 원장의 사건이다(ADR-0205). 봉은 SQL로 심는다(기준가 · 시장 구분은 V70_13 관문 칸).
 */
@JpaSliceTest
class KrBasePriceEventsTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long stockId;
    private long otherId;
    private KrSpinOffResolver resolver;
    private KrBasePriceEvents events;

    @BeforeEach
    void setUp() {
        stockId = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('KOSDAQ', '196170', '알테오젠') RETURNING id", Long.class);
        otherId = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자') RETURNING id", Long.class);
        resolver = Mockito.mock(KrSpinOffResolver.class);
        events = new KrBasePriceEvents(jdbc, resolver);
    }

    private void bar(long stock, String day, long open, long close, long volume, long shares, Long base, String market) {
        jdbc.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close, adj_close, adj_factor, volume, listed_shares,
                                         base_price, market_category)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                """, stock, LocalDate.parse(day), open, Math.max(open, close), Math.min(open, close), close, close, volume, shares,
                base == null ? null : BigDecimal.valueOf(base), market);
    }

    private SecurityRef security() {
        return new SecurityRef(stockId, "KOSDAQ", "196170");
    }

    @Test
    @DisplayName("거래 중 기준가 조정 — 무상 요소 사건(배수 = 직전 종가 ÷ 기준가 · 상장일은 모른다) · 같은 날 주식수가 배수만큼 바뀌면 분할(상장일 = 그날)")
    void tradingDayAdjustmentIsABonusElementOrASplit() throws Exception {
        bar(stockId, "2022-10-24", 42_000, 42_200, 500_000, 42_933_685, 42_000L, "KOSDAQ");
        bar(stockId, "2022-10-25", 36_400, 35_900, 291_585, 42_933_685, 35_200L, "KOSDAQ");
        bar(stockId, "2022-10-26", 35_900, 34_950, 268_206, 42_933_685, 35_900L, "KOSDAQ");
        bar(stockId, "2022-11-14", 36_000, 36_500, 300_000, 51_520_422, 34_950L, "KOSDAQ");
        bar(stockId, "2022-11-15", 36_500, 7_400, 300_000, 257_602_110, 7_300L, "KOSDAQ");
        List<CorporateAction> found = events.detect(security()).events();
        assertThat(found).hasSize(2);
        assertThat(found.get(0).type()).isEqualTo(CorporateAction.Type.BONUS_ISSUE);
        assertThat(found.get(0).effectiveDate()).isEqualTo(LocalDate.parse("2022-10-25"));
        assertThat(found.get(0).ratio()).isEqualByComparingTo(new BigDecimal("42200").divide(new BigDecimal("35200"), new java.math.MathContext(20)));
        assertThat(found.get(0).listingDate()).isNull();
        assertThat(found.get(0).source()).isEqualTo(CorporateAction.DetectionSource.SOURCE_DISCLOSURE);
        assertThat(found.get(1).type()).as("주식수 ×5 · 배수 36,500 ÷ 7,300 = 5").isEqualTo(CorporateAction.Type.SPLIT);
        assertThat(found.get(1).listingDate()).isEqualTo(LocalDate.parse("2022-11-15"));
        assertThat(found.get(1).ratio()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("병합은 가격 배수가 비율이다(같은 날 전환 신주가 섞인다 · 024850 꼴) · 같은 날 소량 주식수 변화는 분할이 아니다(126340 2025-12-29 주식배당)")
    void mergeUsesThePriceMultipleAndSmallSameDayChangeIsNotASplit() throws Exception {
        bar(stockId, "2025-12-26", 20_000, 20_000, 1_000, 10_000_000, 20_000L, "KOSDAQ");
        bar(stockId, "2025-12-29", 19_500, 19_600, 1_000, 10_243_000, 19_432L, "KOSDAQ");   // ×1.0292 · 같은 날 주식수 ×1.0243
        bar(stockId, "2026-04-30", 19_600, 4_620, 1_000, 10_243_000, 19_600L, "KOSDAQ");    // 조정 없는 날(기준가 = 직전 종가)
        bar(stockId, "2026-05-04", 23_000, 23_300, 1_000, 2_061_916, 23_100L, "KOSDAQ");    // ×0.2 · 주식수 ×0.2013
        List<CorporateAction> found = events.detect(security()).events();
        assertThat(found).extracting(CorporateAction::type)
                .containsExactly(CorporateAction.Type.BONUS_ISSUE, CorporateAction.Type.REVERSE_SPLIT);
        assertThat(found.get(0).listingDate()).as("상장일은 판독기가 계단으로").isNull();
        assertThat(found.get(1).ratio()).isEqualByComparingTo("0.2");
        assertThat(found.get(1).listingDate()).isEqualTo(LocalDate.parse("2026-05-04"));
    }

    @Test
    @DisplayName("기준가가 배수의 증거가 아닌 날은 짓지 않는다 — 시장 이동 · 정지 뒤 경매 재개 · 봉 구멍 · 대비 0인데 계단이 확인 안 함(현대건설 2021-09-01)")
    void daysWhereTheBaseIsNotAMultiple() throws Exception {
        bar(stockId, "2021-08-12", 10_000, 10_000, 1_000, 1_000_000, 10_000L, "KONEX");
        bar(stockId, "2021-08-13", 8_000, 8_200, 9_000, 1_000_000, 12_000L, "KOSDAQ");      // 이전상장
        bar(stockId, "2021-08-16", 8_200, 8_200, 0, 1_000_000, 8_200L, "KOSDAQ");            // 정지 봉
        bar(stockId, "2021-08-17", 6_000, 6_500, 5_000, 1_000_000, 6_000L, "KOSDAQ");        // 재개 경매(기준가 = 시가)
        bar(otherId, "2021-08-18", 70_000, 70_000, 1, 1, 70_000L, "KOSPI");
        bar(stockId, "2021-08-19", 6_500, 6_400, 5_000, 1_000_000, 6_000L, "KOSDAQ");        // 18일 봉이 없다
        bar(stockId, "2021-08-20", 6_300, 6_200, 5_000, 1_000_000, 6_200L, "KOSDAQ");        // 대비 0 · 계단 없음
        bar(otherId, "2021-08-19", 70_000, 70_000, 1, 1, 70_000L, "KOSPI");
        bar(otherId, "2021-08-20", 70_000, 70_000, 1, 1, 70_000L, "KOSPI");
        KrBasePriceEvents.Detection detection = events.detect(security());
        assertThat(detection.events()).isEmpty();
        assertThat(detection.marketChanges()).isEqualTo(1);
        assertThat(detection.auctions()).isEqualTo(1);
        assertThat(detection.gaps()).isEqualTo(1);
        assertThat(detection.unconfirmedNoChange()).isEqualTo(1);
        Mockito.verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("대비 0이어도 뒤 150일 안 주식수 계단이 배수 이상이면 사건이다(코리안리 2023-11-20 · 12-12 ×1.17429)")
    void noChangeDayConfirmedByALaterStep() throws Exception {
        bar(stockId, "2023-11-17", 8_700, 9_080, 1_000, 140_822_939, 8_620L, "KOSPI");
        bar(stockId, "2023-11-20", 7_750, 7_740, 1_000, 140_822_939, 7_740L, "KOSPI");
        bar(stockId, "2023-12-12", 7_800, 7_800, 1_000, 165_367_526, 7_800L, "KOSPI");
        assertThat(events.detect(security()).events()).singleElement()
                .satisfies(e -> assertThat(e.type()).isEqualTo(CorporateAction.Type.BONUS_ISSUE));
    }

    @Test
    @DisplayName("정지 뒤 경매 재개인데 그날 주식수가 바뀌었다 — 신설회사를 찾으면 인적분할(v = 존속 ÷ 합 시총) · 못 찾으면 종류 미상(판정 대기)")
    void structuralRelistingIsASpinOffOrUnknown() throws Exception {
        bar(stockId, "2024-06-27", 100_000, 100_000, 1_000, 20_000_000, 100_000L, "KOSPI");
        bar(stockId, "2024-06-28", 100_000, 100_000, 0, 20_000_000, 100_000L, "KOSPI");
        bar(stockId, "2024-07-29", 80_000, 82_000, 9_000, 16_000_000, 80_000L, "KOSPI");
        Mockito.when(resolver.resolve("196170", LocalDate.parse("2024-07-29"), LocalDate.parse("2024-06-28")))
                .thenReturn(Optional.of(new KrSpinOffResolver.Child("487570", new BigDecimal("328000000000"))));
        CorporateAction spin = events.detect(security()).events().get(0);
        assertThat(spin.type()).isEqualTo(CorporateAction.Type.SPIN_OFF);
        assertThat(spin.ratio()).as("s = 존속 주식수 배수").isEqualByComparingTo("0.8");
        BigDecimal parentCap = new BigDecimal("82000").multiply(new BigDecimal("16000000"));
        BigDecimal v = parentCap.divide(parentCap.add(new BigDecimal("328000000000")), new java.math.MathContext(20));
        assertThat(spin.priceRatio().doubleValue()).isCloseTo(0.8 / v.doubleValue(), org.assertj.core.data.Offset.offset(1e-6));

        Mockito.when(resolver.resolve(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        assertThat(events.detect(security()).events().get(0).type()).isEqualTo(CorporateAction.Type.UNKNOWN);

        Mockito.clearInvocations(resolver);
        assertThat(events.detect(security(), LocalDate.parse("2024-08-01"), LocalDate.parse("2024-08-31")).events())
                .as("창 밖 구조 사건은 소스에 다시 묻지 않는다").isEmpty();
        Mockito.verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("국내 반증 — 원장 무상증자 효력일에 거래소가 조정하지 않았으면(기준가 = 직전 종가) 보류한다(알테오젠 id 10)")
    void ledgerRowTheExchangeContradictsIsHeld() {
        bar(stockId, "2022-10-06", 43_800, 40_400, 1_058_859, 42_933_685, 43_700L, "KOSDAQ");
        bar(stockId, "2022-10-07", 40_600, 37_650, 918_113, 42_933_685, 40_400L, "KOSDAQ");
        long id = jdbc.queryForObject("""
                INSERT INTO corporate_action (stock_id, effective_date, listing_date, type, ratio, detection_source, status)
                VALUES (?, '2022-10-07', '2022-11-14', 'BONUS_ISSUE', 1.2, 'SOURCE_DISCLOSURE', 'APPLIED') RETURNING id
                """, Long.class, stockId);
        CorporateActionLedger on = new CorporateActionLedger(jdbc);
        assertThat(on.screenBeforeApplying(stockId, on.findApplicable(stockId))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM corporate_action WHERE id = ?", String.class, id)).isEqualTo("OBSERVED");
    }

    @Test
    @DisplayName("상장일 없는 무상 요소 행 — 판독기가 주식수 계단으로 짓는다(권리락 순 · 계단 하나에 사건 하나) · 계단이 아직 없으면 상장 전")
    void listingDateComesFromTheShareSteps() {
        bar(stockId, "2020-06-22", 10_000, 10_000, 1, 1_000_000, 10_000L, "KOSDAQ");
        bar(stockId, "2020-06-23", 9_000, 9_000, 1, 1_000_000, 8_800L, "KOSDAQ");     // 유상 권리락 ×1.1364
        bar(stockId, "2020-08-04", 9_000, 9_000, 1, 1_000_000, 7_500L, "KOSDAQ");     // 무상 권리락 ×1.2
        bar(stockId, "2020-08-18", 7_500, 7_500, 1, 1_650_000, 7_500L, "KOSDAQ");     // 유상 상장 ×1.65
        bar(stockId, "2020-08-26", 7_500, 7_500, 1, 1_980_000, 7_500L, "KOSDAQ");     // 무상 상장 ×1.2
        bar(stockId, "2026-10-26", 7_500, 7_500, 1, 1_980_000, 3_769L, "KOSDAQ");     // 아직 상장 전 ×1.99
        for (String[] row : new String[][]{{"2020-06-23", "1.1364"}, {"2020-08-04", "1.2"}, {"2026-10-26", "1.99"}}) {
            jdbc.update("""
                    INSERT INTO corporate_action (stock_id, effective_date, type, ratio, detection_source, status, source_form)
                    VALUES (?, ?, 'BONUS_ISSUE', ?, 'SOURCE_DISCLOSURE', 'APPLIED', '기준가')
                    """, stockId, LocalDate.parse(row[0]), new BigDecimal(row[1]));
        }
        ShareClassStore shareClasses = Mockito.mock(ShareClassStore.class);
        CorporateActionLedger ledger = new CorporateActionLedger(jdbc);
        List<ShareAdjustment> on = new IndicatorDataReader(jdbc, ledger, shareClasses).readShareAdjustments(stockId);
        assertThat(on).extracting(ShareAdjustment::listingDate).containsExactly(
                LocalDate.parse("2020-08-18"), LocalDate.parse("2020-08-26"), LocalDate.parse("2026-10-27"));
    }
}
