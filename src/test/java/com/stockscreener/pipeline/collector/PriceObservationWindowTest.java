package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 행의 관측 구간(ADR-0181) — 경계 · 등록인 시대 · 머리 평봉. 꼴마다 대조군을 둔다(대조군이 안 붉으면 시험이 술어를 안 탄 것이다).
 */
@JpaSliceTest
@Import({PriceWriter.class, PriceObservationWindow.class})
class PriceObservationWindowTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 28);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PriceWriter writer;

    private PriceObservationWindow window;

    @BeforeEach
    void window() {
        PriceObservationWindow.AnchorKey key = new PriceObservationWindow.AnchorKey("US", "VGX", ANCHOR);
        window = new PriceObservationWindow(jdbc, Map.of(key,
                new PriceObservationWindow.Boundary(key, LocalDate.of(2025, 1, 24), "REUSE_BRIDGE", "시험")));
    }

    @Test
    @DisplayName("경계 앞 봉은 안 쓰고 경계일부터 쓴다 — 닻이 같은 행에만 걸린다")
    void boundaryAppliesToAnchoredRow() {
        long vg = usRow("VGX", "VGX");
        long other = usRow("OTHR", "OTHR");
        List<RawPriceBar> bars = List.of(traded("2022-07-20", "20.98"), traded("2025-01-24", "24.00"));

        assertThat(window.screen(vg, bars).kept()).extracting(RawPriceBar::tradeDate).containsExactly(LocalDate.of(2025, 1, 24));
        assertThat(window.screen(vg, bars).outsideWindow()).isEqualTo(1);
        assertThat(window.screen(other, bars).kept()).as("대조군 — 닻이 다르면 경계가 없다").hasSize(2);
    }

    @Test
    @DisplayName("등록인 시대 밖 봉은 안 쓴다 — era_from 앞과 era_to부터")
    void eraBoundsBars() {
        long row = usRow("NEWR", "NEWR");
        jdbc.update("UPDATE stock SET era_from = DATE '2026-09-04', era_to = DATE '2026-10-02' WHERE id = ?", row);

        assertThat(window.screen(row, List.of(traded("2026-09-03", "1"), traded("2026-09-04", "1"), traded("2026-10-01", "1"),
                traded("2026-10-02", "1"))).kept()).extracting(RawPriceBar::tradeDate)
                .containsExactly(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("미국 머리 평봉(첫 실거래 앞 · 거래량 0 · 시고저종 같음)은 안 쓰고 중간 평봉은 쓴다")
    void usHeadFlatsAreDroppedMidFlatsKept() {
        long row = usRow("SNX", "SNX");
        PriceObservationWindow.Screened screened = window.screen(row, List.of(flat("2023-07-27", "0.3607"),
                flat("2023-07-28", "0.3607"), traded("2023-07-31", "30"), flat("2023-08-01", "30"), traded("2023-08-02", "31")));

        assertThat(screened.headFlat()).isEqualTo(2);
        assertThat(screened.kept()).extracting(RawPriceBar::tradeDate).containsExactly(LocalDate.of(2023, 7, 31),
                LocalDate.of(2023, 8, 1), LocalDate.of(2023, 8, 2));
    }

    @Test
    @DisplayName("첫 실거래는 저장된 것과 묶음 중 이른 것이다 — 저장된 실거래 뒤의 평봉은 머리가 아니다")
    void storedFirstTradeCounts() {
        long row = usRow("BIOB", "BIOB");
        writer.write(row, List.of(traded("2024-04-24", "500")));

        assertThat(window.screen(row, List.of(flat("2024-04-25", "500"), flat("2024-04-26", "500"))).kept())
                .as("거래 드문 클래스주의 무거래일").hasSize(2);
    }

    @Test
    @DisplayName("국내는 머리 평봉 규칙이 없다 — 거래정지 기준가는 같은 증권의 공식 값이다")
    void domesticFlatsKept() {
        long row = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('KOSDAQ', '950160', '코오롱티슈진') RETURNING id",
                Long.class);
        assertThat(window.screen(row, List.of(flat("2020-01-02", "8010"), flat("2020-01-03", "8010"))).kept()).hasSize(2);
    }

    @Test
    @DisplayName("저장된 봉 중 술어 밖 날짜를 낸다 — 교정 잡과 감시가 쓴다")
    void storedOutsideListsDates() {
        long row = usRow("VGX", "VGX");
        jdbc.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close, adj_close, volume) VALUES
                  (?, DATE '2022-07-20', 20.98, 20.98, 20.98, 20.98, 20.98, 100),
                  (?, DATE '2025-01-21', 20.99, 20.99, 20.99, 20.99, 20.99, 0),
                  (?, DATE '2025-01-24', 24, 25.5, 23.76, 24, 24, 36849647)""", row, row, row);

        assertThat(window.storedOutside(row)).containsExactly(LocalDate.of(2022, 7, 20), LocalDate.of(2025, 1, 21));
    }

    @Test
    @DisplayName("PriceWriter가 같은 술어로 거른다 — 실제 경계 파일의 CAI 줄")
    void writerUsesBoundaryFile() {
        long cai = usRow("CAI", "CAI");
        long written = writer.write(cai, List.of(traded("2021-11-22", "56"), traded("2025-06-18", "28")));

        assertThat(written).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT trade_date FROM price_daily WHERE stock_id = ?", LocalDate.class, cai))
                .containsExactly(LocalDate.of(2025, 6, 18));
    }

    @Test
    @DisplayName("경계 파일 — 열 여섯 · 근거 필수 · 닻 하나에 한 줄, 실제 파일은 그 계약을 지킨다")
    void boundaryFileContract() {
        Map<PriceObservationWindow.AnchorKey, PriceObservationWindow.Boundary> real = PriceObservationWindow.load(
                PriceObservationWindow.BOUNDARY_FILE);
        // 줄 수를 박지 않는다 — 경계는 원장 입력이라 근거와 함께 는다(WOLF 파산 재편 2026-10-31). 파일의 데이터 줄이 하나도 빠짐없이 읽혔는가를 묻는다.
        assertThat(real).isNotEmpty().hasSize(dataLines(PriceObservationWindow.BOUNDARY_FILE));
        assertThat(real.values()).allSatisfy(b -> {
            assertThat(b.evidence()).isNotBlank();
            assertThat(b.date()).isBefore(b.key().anchorDate());
        });
        assertThatThrownBy(() -> PriceObservationWindow.load("price-boundary/missing.tsv")).isInstanceOf(IllegalStateException.class);
    }

    private long usRow(String ticker, String anchor) {
        return jdbc.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, alpaca_anchor_symbol, alpaca_anchor_date)
                VALUES ('US', ?, ?, ?, ?) RETURNING id""", Long.class, ticker, ticker + " Inc.", anchor, ANCHOR);
    }

    private static RawPriceBar traded(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000L, null, null, false);
    }

    private static RawPriceBar flat(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 0L, null, null, false);
    }

    private static int dataLines(String resource) {
        try (java.io.InputStream in = new org.springframework.core.io.ClassPathResource(resource).getInputStream()) {
            return (int) new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).count();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
