package com.stockscreener.pipeline.collector.identity;

import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.IdentitySource.EntityBar;
import com.stockscreener.pipeline.datasource.RegistrantLookup;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 폐지 실체 인입(D6) — 들일 것과 안 들일 것, 그리고 쓴 행의 모양(탐색 {@code US-PAST-LISTING-IMPL-1018.md} U3). 소스는 가짜다 —
 * 등록인 찾기의 판정은 {@code RegistrantFinderTest}가 묻고 여기는 계획 갈래와 DB에 무엇이 남는가를 묻는다.
 */
@JpaSliceTest
@Import({DelistedIntake.class, UsIdentityTriageTest.Config.class})
class DelistedIntakeTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 2);
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 17);
    private static final LocalDate EFFECTIVE = START.plusDays(30);

    @Autowired
    private DelistedIntake intake;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UsIdentityTriageTest.FakeSource source;

    private final FakeLookup lookup = new FakeLookup();

    @BeforeEach
    void clear() {
        source.reset();
        lookup.results.clear();
        lookup.names.clear();
    }

    @Test
    @DisplayName("행 없는 피인수 실체 — 비운 표지 · 닻(기호, 효력일 전날) · 시대 끝 · 등록인 · 거래소로 들인다")
    void endedEntityWithoutARowIsTakenIn() throws Exception {
        endedEntity("EAT", 3, "cash_merger");
        source.assets.put("285512109", new IdentitySource.Listed("EAT", "Electronic Arts Inc. Common Stock", "NASDAQ"));
        lookup.results.put("EAT", bound(712515L, "NASDAQ", "ELECTRONIC ARTS INC. Common Stock, $0.01 par value"));

        DelistedIntake.Plan plan = intake.plan(START, AS_OF, source, lookup);
        List<Long> ids = intake.apply(plan);

        assertThat(plan.intakes()).singleElement().satisfies(i -> {
            assertThat(i.eraTo()).as("효력일에 거래가 없으면 효력일").isEqualTo(EFFECTIVE);
            assertThat(i.label()).isEqualTo("EAT~" + EFFECTIVE.minusDays(1));
        });
        assertThat(lookup.names).containsEntry("EAT", Optional.of("Electronic Arts Inc. Common Stock"));
        assertThat(ids).hasSize(1);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT ticker, name, listing_exchange, alpaca_anchor_symbol, alpaca_anchor_date, sec_cik, era_to, is_active
                FROM stock WHERE id = ?""", ids.get(0));
        assertThat(row).containsEntry("ticker", "EAT~" + EFFECTIVE.minusDays(1))
                .containsEntry("listing_exchange", "NASDAQ")
                .containsEntry("alpaca_anchor_symbol", "EAT")
                .containsEntry("sec_cik", 712515L)
                .containsEntry("is_active", true);
        assertThat(row.get("alpaca_anchor_date").toString()).isEqualTo(EFFECTIVE.minusDays(1).toString());
        assertThat(row.get("era_to").toString()).isEqualTo(EFFECTIVE.toString());
    }

    @Test
    @DisplayName("재실행 — 앞서 들인 행이 같은 실체로 잡혀 다시 안 들인다")
    void secondRunFindsTheRowItMade() throws Exception {
        endedEntity("EAT", 3, "cash_merger");
        lookup.results.put("EAT", bound(712515L, "NASDAQ", "EA"));
        intake.apply(intake.plan(START, AS_OF, source, lookup));

        DelistedIntake.Plan again = intake.plan(START, AS_OF, source, lookup);

        assertThat(again.intakes()).isEmpty();
        assertThat(again.reasons()).containsEntry("행 있음", 1);
    }

    @Test
    @DisplayName("이미 행이 있는 실체(끝 판정 · 접기의 몫) — 안 들인다")
    void entityWithAnExistingRowIsSkipped() throws Exception {
        endedEntity("QRVT", 5, "cash_merger");
        jdbcTemplate.update("""
                INSERT INTO stock (market_code, ticker, name, alpaca_anchor_symbol, alpaca_anchor_date)
                VALUES ('US', 'QRVT', 'Qorvo', 'QRVT', DATE '2026-08-28')""");
        source.anchor("QRVT", series(5, 0, 25));

        DelistedIntake.Plan plan = intake.plan(START, AS_OF, source, lookup);

        assertThat(plan.intakes()).isEmpty();
        assertThat(plan.reasons()).containsEntry("행 있음", 1);
        assertThat(lookup.names).as("등록인을 안 묻는다").isEmpty();
    }

    @Test
    @DisplayName("재조직(CUSIP만 바뀌고 사건 뒤에도 거래 · 탐색 ★3) — 끝이 아니라 안 들인다")
    void reorganizationIsNotTakenIn() throws Exception {
        source.eve.put("AZNT", series(7, 0, 60));
        source.ends.add(new IdentitySource.EndEvent("AZNT", "046353108", EFFECTIVE, "stock_merger"));

        DelistedIntake.Plan plan = intake.plan(START, AS_OF, source, lookup);

        assertThat(plan.intakes()).isEmpty();
        assertThat(plan.reasons()).containsEntry("끝 아님", 1);
    }

    @Test
    @DisplayName("재사용 기호(오늘 목록에 다른 실체) — 시세 소스 이름이 오늘 주인 것이라 이름 후보를 안 넘긴다(PINC꼴 · ★2)")
    void reusedSymbolDoesNotPassTheTodayOwnersName() throws Exception {
        endedEntity("PINT", 11, "cash_merger");
        source.listed("PINT");
        source.today("PINT", series(99, 40, 70));
        source.assets.put("74051N102", new IdentitySource.Listed("PINT", "PGIM Securitized Income ETF", "BATS"));
        lookup.results.put("PINT", bound(1577916L, "NASDAQ", "Premier, Inc. Class A Common Stock"));

        DelistedIntake.Plan plan = intake.plan(START, AS_OF, source, lookup);

        assertThat(lookup.names).containsEntry("PINT", Optional.empty());
        assertThat(plan.intakes()).singleElement().satisfies(i -> assertThat(i.cik()).isEqualTo(1577916L));
    }

    @Test
    @DisplayName("오늘 목록에 같은 실체가 있으면(ARQQW꼴) 끝이 서도 안 들인다")
    void sameEntityStillListedTodayIsSkipped() throws Exception {
        endedEntity("ARQT", 13, "worthless_removal");
        source.listed("ARQT");
        source.today("ARQT", series(13, 0, 25));

        DelistedIntake.Plan plan = intake.plan(START, AS_OF, source, lookup);

        assertThat(plan.reasons()).containsEntry("오늘 목록에 있음", 1);
    }

    @Test
    @DisplayName("등록인을 못 정하면 행을 안 만들고 사유와 함께 센다(CADE꼴 · ★7)")
    void unresolvedRegistrantMakesNoRow() throws Exception {
        endedEntity("CADT", 17, "cash_merger");
        lookup.results.put("CADT", new RegistrantLookup.Result(RegistrantLookup.Outcome.NONE, null, null, null, "표지 없음"));

        DelistedIntake.Plan plan = intake.plan(START, AS_OF, source, lookup);

        assertThat(plan.intakes()).isEmpty();
        assertThat(plan.reasons()).containsEntry("NONE", 1);
        assertThat(intake.apply(plan)).isEmpty();
    }

    @Test
    @DisplayName("효력일 앞 개명(RNA → RNAM) — 등록인 조회에 개명을 넘기고 옛 기호를 기호 시대로 남긴다")
    void renameBeforeTheEndBecomesASymbolEra() throws Exception {
        endedEntity("RNAT", 19, "cash_merger");
        LocalDate renamed = EFFECTIVE.minusDays(5);
        // CUSIP 그대로의 기호 변경(RNA → RNAM꼴) — 신원 사건의 개명에는 안 실린다.
        source.symbolChanges.add(new IdentitySource.SymbolChange("RNAO", "RNAT", renamed));
        lookup.results.put("RNAT", bound(1423824L, "NASDAQ", "Avidity Biosciences Common Stock"));

        DelistedIntake.Plan plan = intake.plan(START, AS_OF, source, lookup);
        List<Long> ids = intake.apply(plan);

        assertThat(lookup.renames.get("RNAT")).containsExactly(new RegistrantLookup.Rename("RNAO", "RNAT", renamed));
        assertThat(jdbcTemplate.queryForList("SELECT symbol || '|' || valid_to FROM stock_symbol_era WHERE stock_id = ?",
                String.class, ids.get(0))).containsExactly("RNAO|" + renamed);
    }

    @Test
    @DisplayName("CUSIP꼴 기호(CVR · 에스크로 증서)는 시세 소스에 안 묻고 센다 — 하나가 묶음 조회 전체를 400으로 거절시킨다")
    void cusipShapedSymbolIsNotAsked() throws Exception {
        source.ends.add(new IdentitySource.EndEvent("294ESC017", "294ESC017", EFFECTIVE, "cash_merger"));

        DelistedIntake.Plan plan = intake.plan(START, AS_OF, source, lookup);

        assertThat(plan.reasons()).containsEntry("기호 꼴 아님", 1);
    }

    /** 효력일 전날까지 거래하고 끊긴 실체 — 효력일 전날 · 당일 asof 모두 같은 계열을 준다. */
    private void endedEntity(String symbol, int seed, String type) {
        source.eve.put(symbol, series(seed, 0, 29));
        source.ends.add(new IdentitySource.EndEvent(symbol, cusip(symbol), EFFECTIVE, type));
    }

    private static String cusip(String symbol) {
        return switch (symbol) {
            case "PINT" -> "74051N102";
            case "EAT" -> "285512109";
            default -> "";
        };
    }

    private static RegistrantLookup.Result bound(long cik, String exchange, String name) {
        return new RegistrantLookup.Result(RegistrantLookup.Outcome.BOUND, cik, exchange, name, "확인");
    }

    /** START + 일 수 [from, to]의 거래 봉 — 시드마다 값이 달라 실체가 갈린다. */
    private static List<EntityBar> series(int seed, int from, int to) {
        List<EntityBar> out = new ArrayList<>();
        for (int d = from; d <= to; d++) {
            out.add(new EntityBar(START.plusDays(d), BigDecimal.valueOf(seed * 10L + d).movePointLeft(1), 1000L + seed * 7L + d, 50 + d));
        }
        return out;
    }

    /** 기호마다 정해 둔 결과를 내고, 넘겨받은 이름 · 개명을 적는다. */
    private static final class FakeLookup implements RegistrantLookup {
        final Map<String, Result> results = new HashMap<>();
        final Map<String, Optional<String>> names = new HashMap<>();
        final Map<String, List<Rename>> renames = new HashMap<>();

        @Override
        public Session open(LocalDate from, LocalDate asOf) {
            return (symbol, before, securityName, rename) -> {
                names.put(symbol, securityName);
                renames.put(symbol, rename);
                return results.getOrDefault(symbol, new Result(Outcome.NONE, null, null, null, "가짜에 없음"));
            };
        }
    }
}
