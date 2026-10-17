package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 신원 판정을 행에 반영한다(ADR-0180). 소스는 가짜다 — 판정 갈래는 {@link IdentityJudgeTest}가 묻고, 여기는 <b>DB에 무엇이 남는가</b>를
 * 묻는다: 개명은 표지 + 기호 시대 · 끊기는 앞 행 시대 끝 + 새 행(등록인 · 시대 시작 · 물려받은 기호 시대) · 재사용은 비운 표지 · 기록 표.
 */
@JpaSliceTest
@Import({UsIdentityTriage.class, EraClosing.class, UsIdentityTriageTest.Config.class})
class UsIdentityTriageTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 17);
    private static final LocalDate START = AS_OF.minusDays(40);
    /** {@link #stock}이 다는 닻 날짜 — 끝 판정의 「사건 기호의 효력일 전날 봉」 요청과 가르는 데 쓴다. */
    private static final LocalDate ANCHOR_DATE = LocalDate.of(2026, 8, 28);

    @Autowired
    private UsIdentityTriage triage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeSource source;

    @BeforeEach
    void clear() {
        source.reset();
    }

    @Test
    @DisplayName("개명 — 같은 행의 표지가 새 기호가 되고 옛 기호가 기호 시대에 남는다 · 판정 기록 RENAME + RUN")
    void renameUpdatesTheRow() {
        long hlx = stock("HLX", 866829L);
        source.listed("HOS");
        source.anchor("HLX", series(1, 0, 30));
        source.today("HOS", series(1, 0, 30));
        source.literal("HOS", series(1, 12, 30));
        source.file.put("HOS", 866829L);

        UsIdentityTriage.Outcome outcome = triage.run(77L, AS_OF);

        assertThat(outcome.verdictCounts()).containsEntry("RENAME", 1);
        assertThat(ticker(hlx)).isEqualTo("HOS");
        assertThat(jdbcTemplate.queryForList("SELECT symbol || '|' || coalesce(valid_from::text, '') || '|' || valid_to FROM stock_symbol_era WHERE stock_id = ?",
                String.class, hlx)).containsExactly("HLX||" + START.plusDays(12));
        assertThat(verdicts(77L)).containsExactlyInAnyOrder("RENAME", "RUN");
        assertThat(count("SELECT count(*) FROM stock WHERE market_code = 'US' AND ticker IN ('HLX', 'HOS')")).as("행 수 그대로").isEqualTo(1);
    }

    @Test
    @DisplayName("끝 판정 — 피인수된 실체의 열린 행(QRVO꼴)을 효력일로 닫고 표지를 비운다 · 판정 END")
    void acquiredEntityRowIsClosedAtTheEffectiveDate() {
        long qrvo = stock("QRVOT", null);
        source.anchor("QRVOT", series(5, 0, 28));
        LocalDate effective = START.plusDays(28);
        source.ends.add(new IdentitySource.EndEvent("QRVOT", "74736K101", effective, "cash_merger"));

        UsIdentityTriage.Outcome outcome = triage.run(80L, AS_OF);

        assertThat(outcome.verdictCounts()).containsEntry("END", 1);
        assertThat(jdbcTemplate.queryForObject("SELECT era_to FROM stock WHERE id = ?", LocalDate.class, qrvo))
                .as("효력일에 거래 봉이 없으면 효력일").isEqualTo(effective);
        assertThat(ticker(qrvo)).isEqualTo("QRVOT~" + effective.minusDays(1));
        assertThat(verdicts(80L)).containsExactlyInAnyOrder("END", "RUN");
    }

    @Test
    @DisplayName("끝 판정 — 효력일 전날 asof가 다른 봉을 줘도 효력일 asof가 닻 봉과 같으면 같은 실체다(CCO꼴 · 소스 대응이 경계에서 갈림)")
    void entityConfirmedOnTheEffectiveDayView() {
        long cco = stock("CCOT", null);
        source.anchor("CCOT", series(9, 0, 27));
        LocalDate effective = START.plusDays(29);
        source.eveOn.put(effective.minusDays(1), Map.of("CCOT", series(9, 28, 29)));
        source.eveOn.put(effective, Map.of("CCOT", series(9, 0, 29)));
        source.ends.add(new IdentitySource.EndEvent("CCOT", "18453H106", effective, "cash_merger"));

        UsIdentityTriage.Outcome outcome = triage.run(83L, AS_OF);

        assertThat(outcome.verdictCounts()).containsEntry("END", 1);
        assertThat(jdbcTemplate.queryForObject("SELECT era_to FROM stock WHERE id = ?", LocalDate.class, cco)).isEqualTo(effective);
    }

    @Test
    @DisplayName("끝 판정 — 끝이 서도 오늘 목록이 그 기호를 아직 실으면 닫지 않는다(ARQQW꼴 · 닫으면 등록이 새 행을 만들고 매주 다시 닫는다)")
    void endHeldWhileTheListingStillCarriesTheSymbol() {
        long warrant = stock("ARQQWT", null);
        source.listed("ARQQWT");
        source.anchor("ARQQWT", series(10, 0, 5));
        source.today("ARQQWT", series(10, 0, 5));
        source.ends.add(new IdentitySource.EndEvent("ARQQWT", "G0567U119", START.plusDays(14), "worthless_removal"));

        UsIdentityTriage.Outcome outcome = triage.run(84L, AS_OF);

        assertThat(outcome.verdictCounts()).doesNotContainKey("END");
        assertThat(outcome.summary()).contains("목록이 실어 안 닫은 끝 [ARQQWT]");
        assertThat(jdbcTemplate.queryForObject("SELECT era_to FROM stock WHERE id = ?", LocalDate.class, warrant)).isNull();
    }

    @Test
    @DisplayName("끝 판정 — 사건 뒤에도 같은 실체가 거래되면(재조직 · AZN꼴) 닫지 않는다")
    void reorganizationIsNotAnEnd() {
        long azn = stock("AZNT", null);
        source.listed("AZNT");
        source.anchor("AZNT", series(6, 0, 40));
        source.today("AZNT", series(6, 0, 40));
        source.ends.add(new IdentitySource.EndEvent("AZNT", "046353108", START.plusDays(20), "stock_merger"));

        UsIdentityTriage.Outcome outcome = triage.run(81L, AS_OF);

        assertThat(outcome.verdictCounts()).doesNotContainKey("END");
        assertThat(jdbcTemplate.queryForObject("SELECT era_to FROM stock WHERE id = ?", LocalDate.class, azn)).isNull();
    }

    @Test
    @DisplayName("끝 판정 — 같은 기호의 다른 실체 사건(기호 재사용 · 사건 기호의 전날 봉 ≠ 닻 봉)은 이 행을 닫지 않는다")
    void anotherEntitysEventOnTheSameSymbolIsNotThisRowsEnd() {
        long row = stock("BNYT", null);
        source.anchor("BNYT", series(7, 0, 20));
        source.eve.put("BNYT", series(8, 0, 20));
        source.ends.add(new IdentitySource.EndEvent("BNYT", "064058100", START.plusDays(20), "stock_merger"));

        UsIdentityTriage.Outcome outcome = triage.run(82L, AS_OF);

        assertThat(outcome.verdictCounts()).doesNotContainKey("END");
        assertThat(jdbcTemplate.queryForObject("SELECT era_to FROM stock WHERE id = ?", LocalDate.class, row)).isNull();
    }

    @Test
    @DisplayName("다른 기호로의 등록인 교체(COLA→SAIQ꼴) — 앞 행도 표지를 비운다 · 옛 기호를 쥐면 그 기호를 새로 받은 실체가 앞 행에 붙는다(P0)")
    void otherSymbolSplitVacatesTheOldLabel() {
        long cola = stock("COLA", 2028201L);
        financial(cola, "0002028201-26-000001");
        LocalDate merger = LocalDate.of(2026, 10, 2);
        source.listed("SAIQ");
        source.anchor("COLA", series(3, 0, 30));
        source.today("SAIQ", series(3, 0, 30));
        source.literal("SAIQ", series(3, 25, 30));
        source.file.put("SAIQ", 2101411L);
        source.accessions.put(2101411L, Set.of("0002101411-26-000001"));
        source.events.add(new IdentitySource.IdentityEvent("stock_merger", merger, Set.of("SAIQ"), "SAIQ"));

        UsIdentityTriage.Outcome outcome = triage.run(79L, AS_OF);

        assertThat(outcome.verdictCounts()).containsEntry("SPLIT", 1);
        assertThat(ticker(cola)).as("다른 기호로 끊겨도 앞 행 표지는 비운다").startsWith("COLA~");
        assertThat(count("SELECT count(*) FROM stock WHERE market_code = 'US' AND ticker = 'COLA'"))
                .as("「COLA」 기호를 쥔 행이 없다 — 다음 실체가 그 기호를 받아도 닫힌 행에 안 붙는다").isZero();
        assertThat(count("SELECT count(*) FROM stock WHERE market_code = 'US' AND ticker = 'SAIQ' AND sec_cik = 2101411"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("기호 그대로 등록인 교체 + 전제 셋 — 앞 행 표지를 비우고 시대를 닫고, 새 행이 같은 기호 · 새 등록인 · 시대 시작으로 선다")
    void sameSymbolSplitCreatesASuccessorRow() {
        long reax = stock("REAX", 1862461L);
        jdbcTemplate.update("INSERT INTO stock_symbol_era (stock_id, symbol, valid_from, valid_to) VALUES (?, 'REAXX', NULL, DATE '2026-01-01')", reax);
        financial(reax, "0001862461-26-000001");
        LocalDate reorg = LocalDate.of(2026, 8, 25);
        source.listed("REAX");
        source.anchor("REAX", series(2, 0, 30));
        source.today("REAX", series(2, 0, 30));
        source.file.put("REAX", 2136387L);
        source.accessions.put(2136387L, Set.of("0002136387-26-000001"));
        source.events.add(new IdentitySource.IdentityEvent("reorganization", reorg, Set.of("REAX"), "REAX"));

        UsIdentityTriage.Outcome outcome = triage.run(78L, AS_OF);

        assertThat(outcome.verdictCounts()).containsEntry("SPLIT", 1);
        assertThat(ticker(reax)).isEqualTo("REAX~" + reorg.minusDays(1));
        assertThat(jdbcTemplate.queryForObject("SELECT era_to FROM stock WHERE id = ?", LocalDate.class, reax)).isEqualTo(reorg);
        Map<String, Object> successor = jdbcTemplate.queryForMap(
                "SELECT id, sec_cik, era_from, alpaca_anchor_symbol FROM stock WHERE market_code = 'US' AND ticker = 'REAX'");
        assertThat(successor.get("sec_cik")).isEqualTo(2136387L);
        assertThat(successor.get("era_from").toString()).isEqualTo(reorg.toString());
        assertThat(jdbcTemplate.queryForList("SELECT symbol FROM stock_symbol_era WHERE stock_id = ?", String.class,
                successor.get("id"))).as("앞 행의 기호 시대를 물려받는다").containsExactly("REAXX");
    }

    @Test
    @DisplayName("끊기 전제가 안 서면(끝의 증거 없음) 아무것도 안 바꾸고 보류로 남긴다")
    void splitWithoutEvidenceIsAmbiguous() {
        long reax = stock("REAX", 1862461L);
        financial(reax, "0001862461-26-000001");
        source.listed("REAX");
        source.anchor("REAX", series(2, 0, 30));
        source.today("REAX", series(2, 0, 30));
        source.file.put("REAX", 2136387L);

        UsIdentityTriage.Outcome outcome = triage.run(79L, AS_OF);

        assertThat(outcome.verdictCounts()).containsEntry("AMBIGUOUS", 1).doesNotContainKey("SPLIT");
        assertThat(ticker(reax)).isEqualTo("REAX");
        assertThat(count("SELECT count(*) FROM stock WHERE market_code = 'US' AND ticker LIKE 'REAX%'")).isEqualTo(1);
    }

    @Test
    @DisplayName("재사용 — 표지를 「기호~마지막 거래일」로 비우고 시대를 그 다음 날로 닫는다")
    void reuseVacatesTheTicker() {
        long vg = stock("VG", null);
        source.listed("VG");
        source.anchor("VG", series(3, 0, 10));
        source.today("VG", series(4, 15, 40));

        triage.run(80L, AS_OF);

        LocalDate last = START.plusDays(9);
        assertThat(ticker(vg)).isEqualTo("VG~" + last);
        assertThat(jdbcTemplate.queryForObject("SELECT era_to FROM stock WHERE id = ?", LocalDate.class, vg)).isEqualTo(last.plusDays(1));
    }

    @Test
    @DisplayName("재료를 못 받으면 아무것도 안 바꾸고 새 기호 등록을 전부 막는다 — 실패 RUN 줄을 남긴다")
    void sourceFailureBlocksAllNew() {
        long hlx = stock("HLX", 866829L);
        source.failListing = true;

        UsIdentityTriage.Outcome outcome = triage.run(81L, AS_OF);

        assertThat(outcome.blockAllNew()).isTrue();
        assertThat(ticker(hlx)).isEqualTo("HLX");
        assertThat(jdbcTemplate.queryForObject("SELECT evidence FROM identity_triage WHERE execution_id = 81", String.class))
                .startsWith("실패");
    }

    private long stock(String ticker, Long cik) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, alpaca_anchor_symbol, alpaca_anchor_date, sec_cik)
                VALUES ('US', ?, ?, ?, DATE '2026-08-28', ?) RETURNING id""", Long.class, ticker, ticker, ticker, cik);
    }

    private void financial(long stockId, String accession) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at,
                                                 currency_code, report_code, revision_no, is_latest)
                VALUES (?, 2025, 'FY', DATE '2025-01-01', DATE '2025-12-31', DATE '2026-02-20', 'USD', ?, 0, true)""",
                stockId, "10-K:" + accession);
    }

    private String ticker(long id) {
        return jdbcTemplate.queryForObject("SELECT ticker FROM stock WHERE id = ?", String.class, id);
    }

    private List<String> verdicts(long executionId) {
        return jdbcTemplate.queryForList("SELECT verdict FROM identity_triage WHERE execution_id = ?", String.class, executionId);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private static List<IdentitySource.EntityBar> series(int entity, int from, int to) {
        List<IdentitySource.EntityBar> bars = new ArrayList<>();
        for (int day = from; day < to; day++) {
            bars.add(new IdentitySource.EntityBar(START.plusDays(day), BigDecimal.valueOf(10 + day), 1_000L * entity + day, entity));
        }
        return bars;
    }

    @TestConfiguration
    static class Config {
        @Bean
        FakeSource fakeIdentitySource() {
            return new FakeSource();
        }
    }

    /** 요청 asof 갈래마다 미리 준 계열을 낸다. 닻 갈래는 기호만으로 낸다(시험의 행은 닻 날짜가 하나다). */
    static final class FakeSource implements IdentitySource {
        final List<Listed> listed = new ArrayList<>();
        final Map<String, List<EntityBar>> anchor = new HashMap<>();
        final Map<String, List<EntityBar>> today = new HashMap<>();
        final Map<String, List<EntityBar>> literal = new HashMap<>();
        final Map<String, Long> file = new HashMap<>();
        final Map<Long, Set<String>> accessions = new HashMap<>();
        final List<IdentityEvent> events = new ArrayList<>();
        final List<EndEvent> ends = new ArrayList<>();
        final Map<String, List<EntityBar>> eve = new HashMap<>();
        /** 이 날짜의 {@code asof}에만 주는 봉 — 소스의 기호 대응이 폐지 경계에서 하루 단위로 갈리는 꼴(CCO). */
        final Map<LocalDate, Map<String, List<EntityBar>>> eveOn = new HashMap<>();
        boolean failListing;

        void reset() {
            listed.clear();
            anchor.clear();
            today.clear();
            literal.clear();
            file.clear();
            accessions.clear();
            events.clear();
            ends.clear();
            eve.clear();
            eveOn.clear();
            failListing = false;
        }

        void listed(String symbol) {
            listed.add(new Listed(symbol, symbol + " Inc", "NYSE"));
        }

        void anchor(String symbol, List<EntityBar> bars) {
            anchor.put(symbol, bars);
        }

        void today(String symbol, List<EntityBar> bars) {
            today.put(symbol, bars);
        }

        void literal(String symbol, List<EntityBar> bars) {
            literal.put(symbol, bars);
        }

        @Override
        public List<Listed> listedSecurities() throws DataSourceException {
            if (failListing) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, "fake", "목록 실패");
            }
            return listed;
        }

        @Override
        public RegistrantFile registrantFile() {
            return symbol -> Optional.ofNullable(file.get(symbol));
        }

        @Override
        public Map<String, List<EntityBar>> bars(Collection<String> symbols, BarAsOf asOf, LocalDate from, LocalDate to) {
            Map<String, List<EntityBar>> pick = asOf.literal() ? literal : asOf.date() == null ? today : anchor;
            Map<String, List<EntityBar>> out = new HashMap<>();
            symbols.forEach(s -> {
                // 끝 판정의 「사건 기호의 효력일 전날 봉」 — 시험이 따로 준 것이 있으면 그것(재사용 기호의 옛 실체).
                if (asOf.date() != null && eveOn.getOrDefault(asOf.date(), Map.of()).containsKey(s)) {
                    out.put(s, eveOn.get(asOf.date()).get(s));
                } else if (asOf.date() != null && !asOf.literal() && !asOf.date().equals(ANCHOR_DATE) && eve.containsKey(s)) {
                    out.put(s, eve.get(s));
                } else if (pick.containsKey(s)) {
                    out.put(s, pick.get(s));
                }
            });
            return out;
        }

        @Override
        public Set<String> registrantAccessions(long cik) {
            return accessions.getOrDefault(cik, Set.of());
        }

        @Override
        public List<RegistrantFiling> registrantFilings(long cik, LocalDate since) {
            return List.of();
        }

        @Override
        public List<IdentityEvent> identityEvents(Collection<String> symbols, LocalDate from, LocalDate to) {
            return events.stream().filter(e -> e.symbols().stream().anyMatch(symbols::contains)).toList();
        }

        @Override
        public List<EndEvent> endEvents(LocalDate from, LocalDate to) {
            return ends.stream().filter(e -> !e.effective().isBefore(from) && !e.effective().isAfter(to)).toList();
        }
    }
}
