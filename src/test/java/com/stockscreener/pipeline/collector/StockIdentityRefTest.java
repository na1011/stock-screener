package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미국 행의 저장된 신원이 소스 참조에 실리는지(ADR-0180). 어댑터는 이 참조로만 소스에 묻는다 — 여기서 빠지면 닻·등록인을 넣어도
 * 아무 소비자가 안 읽는다.
 */
@JpaSliceTest
@Import({StockRegistrar.class, DataSourceRegistry.class})
class StockIdentityRefTest {

    @Autowired
    private StockRegistrar registrar;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("참조가 닻 · 등록인 · 시대 · 옛 기호 시대를 싣는다")
    void refCarriesTheStoredIdentity() {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, alpaca_anchor_symbol, alpaca_anchor_date, sec_cik, era_from, era_to)
                VALUES ('US', 'HOS', '시험', 'HLX', DATE '2026-08-28', 866829, NULL, DATE '2027-01-01') RETURNING id
                """, Long.class);
        jdbcTemplate.update("INSERT INTO stock_symbol_era (stock_id, symbol, valid_from, valid_to) VALUES (?, 'HLX', NULL, DATE '2026-10-01')", id);

        SecurityRef ref = registrar.toSecurityRefs(List.of(id)).get(0);

        assertThat(ref.ticker()).isEqualTo("HOS");
        assertThat(ref.barSymbol()).isEqualTo("HLX");
        assertThat(ref.barAsOf()).isEqualTo(LocalDate.of(2026, 8, 28));
        assertThat(ref.identity().secCik()).isEqualTo(866829L);
        assertThat(ref.identity().eraTo()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(ref.identity().recordedEras())
                .containsExactly(new SecurityRef.SymbolEra("HLX", null, LocalDate.of(2026, 10, 1)));
    }

    @Test
    @DisplayName("닻이 없는 행(국내)은 표지로 묻는다 — 신원 없음")
    void domesticRefHasNoIdentity() {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자') RETURNING id", Long.class);

        SecurityRef ref = registrar.toSecurityRefs(List.of(id)).get(0);

        assertThat(ref.identity()).isEqualTo(SecurityRef.Identity.NONE);
        assertThat(ref.barSymbol()).isEqualTo("005930");
        assertThat(ref.barAsOf()).isNull();
    }

    @Test
    @DisplayName("새 미국 행은 생기는 순간 닻을 박는다 — 그 기호의 오늘(미국 동부) 실체")
    void newUsRowIsAnchoredToday() {
        long id = registrar.resolveOrCreate("US", List.of("NEWCO")).get(0);

        SecurityRef ref = registrar.toSecurityRefs(List.of(id)).get(0);

        assertThat(ref.barSymbol()).isEqualTo("NEWCO");
        assertThat(ref.barAsOf()).isEqualTo(LocalDate.now(ZoneId.of("America/New_York")));
    }

    @Test
    @DisplayName("새 국내 행에는 닻이 없다")
    void newDomesticRowHasNoAnchor() {
        long id = registrar.resolveOrCreate("KOSPI", List.of("999999")).get(0);

        assertThat(jdbcTemplate.queryForObject("SELECT alpaca_anchor_symbol FROM stock WHERE id = ?", String.class, id)).isNull();
    }
}
