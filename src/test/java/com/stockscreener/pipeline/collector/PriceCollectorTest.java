package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.support.FakePriceDataSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 소스 → 저장의 연결을 Fake 소스로 검증한다. API 키가 필요 없다.
 *
 * <p>실제 어댑터가 붙어도 이 테스트는 그대로 유효하다 — 포트 계약을 검증하지
 * 벤더를 검증하지 않기 때문이다.
 */
@JpaSliceTest
@Import({PriceWriter.class, CorporateActionLedger.class, AdjustmentRecalculator.class,
        PriceCalendar.class, NoOpIndicatorInvalidationPort.class})
class PriceCollectorTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Autowired
    private PriceWriter writer;

    @Autowired
    private CorporateActionLedger ledger;

    @Autowired
    private AdjustmentRecalculator recalculator;

    @Autowired
    private PriceCalendar priceCalendar;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;
    private SecurityRef security;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
        security = new SecurityRef(stockId, "KOSPI", "005930");
    }

    @Test
    @DisplayName("소스에서 받은 시세를 저장한다")
    void collectsAndStoresBars() throws Exception {
        PriceCollector collector = collectorWith(new FakePriceDataSource("KOSPI")
                .withBars(stockId, List.of(bar("2026-08-03", "100"), bar("2026-08-04", "105"))));

        assertThat(collector.collect(security, FROM, TO)).isEqualTo(2);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("소스가 공시한 기업 행위는 원장에 적힌다 — 신뢰할 수 있으므로 승인 상태로 들어간다")
    void recordsDisclosedCorporateActionToLedger() throws Exception {
        PriceCollector collector = collectorWith(new FakePriceDataSource("KOSPI")
                .withBars(stockId, List.of(bar("2026-08-03", "50000"), bar("2026-08-10", "1000")))
                .withCorporateAction(stockId, split()));

        collector.collect(security, FROM, TO);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM corporate_action WHERE stock_id = ? AND effective_date = ?
                """, String.class, stockId, LocalDate.parse("2026-08-10")))
                .isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("조회 창 밖의 기업 행위는 원장에 적히지 않는다 — 1콜이 5년 이력을 준다")
    void corporateActionOutsideWindowIsNotRecorded() throws Exception {
        // 소스가 창을 안 지키면 창 밖 사건이 매 실행 다시 올라와 근접중복 경고를 쌓거나
        // 몇 년 전 사건을 오늘 승인 대기로 만든다(ADR-0049).
        PriceCollector collector = collectorWith(new FakePriceDataSource("KOSPI")
                .withBars(stockId, List.of(bar("2026-08-03", "50000")))
                .withCorporateAction(stockId, splitOn("2021-04-15"))
                .withCorporateAction(stockId, split()));

        collector.collect(security, FROM, TO);

        assertThat(jdbcTemplate.queryForList("""
                SELECT effective_date FROM corporate_action WHERE stock_id = ?
                """, LocalDate.class, stockId))
                .containsExactly(LocalDate.parse("2026-08-10"));
    }

    @Test
    @DisplayName("수집은 계수를 건드리지 않는다 — 창이 같은 날짜를 매일 다시 보므로 그만큼 곱해진다")
    void collectingNeverRewritesAdjustmentFactors() throws Exception {
        PriceCollector collector = collectorWith(new FakePriceDataSource("KOSPI")
                .withBars(stockId, List.of(bar("2026-08-03", "50000"), bar("2026-08-10", "1000")))
                .withCorporateAction(stockId, split()));

        collector.collect(security, FROM, TO);
        collector.collect(security, FROM, TO);
        collector.collect(security, FROM, TO);

        // 원장에는 한 행만 쌓이고, 계수는 재구축이 부를 때까지 손대지 않는다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM corporate_action WHERE stock_id = ?", Integer.class, stockId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM price_daily WHERE stock_id = ? AND adj_factor <> 1
                """, Integer.class, stockId)).isZero();
    }

    @Test
    @DisplayName("빈 결과는 예외가 아니다 — 휴장·거래정지에서 정상적으로 발생한다")
    void emptyResultIsNotAnError() throws Exception {
        PriceCollector collector = collectorWith(new FakePriceDataSource("KOSPI"));

        assertThat(collector.collect(security, FROM, TO)).isZero();
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("소스 실패는 DataSourceException으로 전파된다 — 배치가 종목 단위로 격리한다")
    void propagatesSourceFailure() {
        PriceCollector collector = collectorWith(new FakePriceDataSource("KOSPI")
                .failingWith(stockId, new DataSourceException(
                        DataSourceException.Kind.TRANSIENT, "fake-price", "일시적 오류")));

        assertThatThrownBy(() -> collector.collect(security, FROM, TO))
                .isInstanceOf(DataSourceException.class);
    }

    @Test
    @DisplayName("담당 소스가 없는 시장은 예외다 — 조용히 건너뛰면 그 시장이 통째로 누락된다")
    void failsForUnsupportedMarket() {
        PriceCollector collector = collectorWith(new FakePriceDataSource("US"));

        assertThatThrownBy(() -> collector.collect(security, FROM, TO))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("KOSPI");
    }

    private PriceCollector collectorWith(FakePriceDataSource source) {
        DataSourceRegistry registry = new DataSourceRegistry(
                List.of(source), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        // 시세 경로는 원장 기록을 CorporateActionCollector에 위임한다. 그쪽의 공시·휴리스틱
        // 수집은 여기서 안 쓰이므로 registry만 같은 것을 준다.
        return new PriceCollector(registry, writer,
                new CorporateActionCollector(registry, ledger, recalculator, priceCalendar));
    }

    private static CorporateAction split() {
        return splitOn("2026-08-10");
    }

    private static CorporateAction splitOn(String effectiveDate) {
        return new CorporateAction(LocalDate.parse(effectiveDate), CorporateAction.Type.SPLIT,
                new BigDecimal("50"), CorporateAction.DetectionSource.SOURCE_DISCLOSURE);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE stock_id = ?", Integer.class, stockId);
    }

    private static RawPriceBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null, null, false);
    }
}
