package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.Distribution;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.support.FakeCorporateActionDataSource;
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

import static com.stockscreener.pipeline.support.Judgements.approved;
import static com.stockscreener.pipeline.support.Judgements.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원장을 채우는 경로가 <b>공시와 휴리스틱 둘</b>이고 둘 다 있어야 성립하는지 검증한다(ADR-0049).
 *
 * <p>여기서 지키는 것은 전부 <b>어겨도 아무 에러가 안 나는</b> 종류다 — 공시가 없는 시장에서
 * 0건으로 성공하는 것, 근접중복이 조용히 사라지는 것, 봉이 없는 종목에 헛콜을 태우는 것.
 */
@JpaSliceTest
// 컬렉터는 여기서 만들지 않는다 — registry를 테스트마다 다르게 주려면 직접 생성해야 한다.
@Import({CorporateActionLedger.class, AdjustmentRecalculator.class,
        PriceCalendar.class, PriceWriter.class, NoOpIndicatorInvalidationPort.class, PriceObservationWindow.class})
class CorporateActionCollectorTest {

    private static final LocalDate FROM = LocalDate.of(2020, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    @Autowired
    private CorporateActionLedger ledger;

    @Autowired
    private AdjustmentRecalculator recalculator;

    @Autowired
    private PriceCalendar priceCalendar;

    @Autowired
    private PriceWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;
    private SecurityRef security;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '001440', '대한전선')
                RETURNING id
                """, Long.class);
        security = new SecurityRef(stockId, "KOSPI", "001440");
    }

    @Test
    @DisplayName("공시는 APPROVED로, 휴리스틱이 올린 후보는 OBSERVED로 들어간다 — 이것이 승인 게이트다")
    void bothPathsFillTheLedgerWithTheirOwnStatus() throws Exception {
        // 2023-05-16에 주식수가 정확히 1/10로 줄고 가격이 10.63배 뛴다 — 대한전선 실측 모양이다.
        writer.write(stockId, List.of(
                bar("2023-04-27", "1492", "45000000"), bar("2023-05-16", "15860", "4500000")));
        // 공시는 그와 무관한 날짜의 액면분할을 준다.
        FakeCorporateActionDataSource source = new FakeCorporateActionDataSource("KOSPI")
                .with(stockId, disclosed("2021-04-15", "5"));

        CorporateActionCollector.Result result = collectorWith(source).collect(security, FROM, TO);

        assertThat(result.recorded()).isEqualTo(2);
        assertThat(status("2021-04-15")).isEqualTo("APPROVED");
        assertThat(status("2023-05-16")).isEqualTo("OBSERVED");
        // 휴리스틱이 주식수에서 비율을 낸다. 종가로 내면 0.094073이고 그것이 6% 틀린 계수다.
        assertThat(ratio("2023-05-16")).isEqualByComparingTo("0.1");
    }

    @Test
    @DisplayName("공시가 못 주는 액면병합을 휴리스틱이 올린다 — 그 경로가 없으면 하락형 사건이 하나도 안 들어온다")
    void heuristicCoversWhatTheDisclosureCannotGive() throws Exception {
        writer.write(stockId, List.of(
                bar("2023-04-27", "1492", "45000000"), bar("2023-05-16", "15860", "4500000")));

        CorporateActionCollector.Result result =
                collectorWith(new FakeCorporateActionDataSource("KOSPI")).collect(security, FROM, TO);

        assertThat(result.recorded()).isEqualTo(1);
        assertThat(type("2023-05-16")).isEqualTo("REVERSE_SPLIT");
    }

    @Test
    @DisplayName("창 밖 휴리스틱 후보는 적지 않는다 — 포트 계약과 같은 창을 써야 --from이 뜻을 갖는다")
    void heuristicCandidatesOutsideTheWindowAreNotRecorded() throws Exception {
        writer.write(stockId, List.of(
                bar("2023-04-27", "1492", "45000000"), bar("2023-05-16", "15860", "4500000")));

        CorporateActionCollector.Result result = collectorWith(new FakeCorporateActionDataSource("KOSPI"))
                .collect(security, LocalDate.of(2024, 1, 1), TO);

        assertThat(result.recorded()).isZero();
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("두 번 돌려도 행이 늘지 않고 두 번째 실행은 0을 낸다 — 그 0이 멱등의 증거다")
    void secondRunRecordsNothing() throws Exception {
        writer.write(stockId, List.of(
                bar("2023-04-27", "1492", "45000000"), bar("2023-05-16", "15860", "4500000")));
        FakeCorporateActionDataSource source = new FakeCorporateActionDataSource("KOSPI")
                .with(stockId, disclosed("2021-04-15", "5"));
        CorporateActionCollector collector = collectorWith(source);

        collector.collect(security, FROM, TO);
        CorporateActionCollector.Result second = collector.collect(security, FROM, TO);

        assertThat(second.recorded()).isZero();
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("근접중복은 결과에 실려 나온다 — 조용히 버리면 사람이 갈라야 할 것이 사라진다")
    void nearDuplicatesAreReportedNotDropped() throws Exception {
        // 이틀 사이에 불연속이 둘이다(거래정지 후 재개가 이 모양이다). 둘 다 휴리스틱이라
        // 뒤엣것이 앞엣것을 옮기지 못하고, 원장에 자리가 없어 반영되지 않는다.
        writer.write(stockId, List.of(bar("2026-02-02", "1000", "1000000"),
                bar("2026-02-03", "400", "1000000"), bar("2026-02-04", "1200", "1000000")));

        CorporateActionCollector.Result result =
                collectorWith(new FakeCorporateActionDataSource("KOSPI")).collect(security, FROM, TO);

        assertThat(result.nearDuplicates()).singleElement().satisfies(conflict -> {
            assertThat(conflict.ticker()).isEqualTo("001440");
            assertThat(conflict.proposed()).isEqualTo(LocalDate.parse("2026-02-04"));
            assertThat(conflict.existing()).isEqualTo(LocalDate.parse("2026-02-03"));
        });
    }

    @Test
    @DisplayName("IMPORTANT — 사람이 판정한 행과의 충돌은 알리지 않는다. 영구적이라 매 실행 같은 알림이 나간다")
    void conflictsWithJudgedRowsAreNotAlerted() throws Exception {
        writer.write(stockId, List.of(bar("2026-02-02", "1000", "1000000"),
                bar("2026-02-03", "1010", "1000000"), bar("2026-02-04", "1020", "1000000")));
        // 사람이 이미 판정한 행이라 공시가 옮기지도 덮지도 못한다 — 그 충돌은 판정을 되돌리는
        // 것 말고는 없앨 방법이 없다. 알림으로 두면 며칠 안에 아무도 그 알림을 안 본다.
        long judged = ledger.record(stockId, heuristic("2026-02-03", "0.1")).existingId();
        ledger.judge(judged, rejected("하한가 연속이었다"));
        FakeCorporateActionDataSource source = new FakeCorporateActionDataSource("KOSPI")
                .with(stockId, disclosed("2026-02-04", "0.1"));

        CorporateActionCollector.Result result = collectorWith(source).collect(security, FROM, TO);

        assertThat(result.nearDuplicates()).isEmpty();
        // 그래도 적지는 않는다 — 판정이 원장에 남아 있는 것이 곧 "사람이 봤다"이다.
        assertThat(rowCount()).isEqualTo(1);
        assertThat(status("2026-02-03")).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("뒤늦게 온 공시가 먼저 들어간 후보를 제자리로 옮긴다 — 소스가 최대 넉 달 늦어 이 순서가 정상이다")
    void lateDisclosureRelocatesTheEarlierCandidate() throws Exception {
        writer.write(stockId, List.of(bar("2026-02-02", "1000", "1000000"),
                bar("2026-02-03", "1010", "1000000"), bar("2026-02-04", "1020", "1000000")));
        ledger.record(stockId, heuristic("2026-02-03", "0.1"));

        CorporateActionCollector.Result result = collectorWith(new FakeCorporateActionDataSource("KOSPI")
                .with(stockId, disclosed("2026-02-04", "0.1"))).collect(security, FROM, TO);

        assertThat(result.nearDuplicates()).isEmpty();
        assertThat(rowCount()).isEqualTo(1);
        assertThat(status("2026-02-04")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("봉이 없는 종목은 소스를 부르지 않는다 — 어댑터가 우리 시계열에서 값을 유도한다")
    void stocksWithoutBarsAreSkippedBeforeAnyCall() throws Exception {
        FakeCorporateActionDataSource source = new FakeCorporateActionDataSource("KOSPI")
                .with(stockId, disclosed("2021-04-15", "5"));

        CorporateActionCollector.Result result = collectorWith(source).collect(security, FROM, TO);

        assertThat(source.fetchedStockIds).isEmpty();
        assertThat(result.recorded()).isZero();
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("담당 소스가 없는 시장은 예외다 — 0건으로 성공하면 계수가 영영 1로 남는다")
    void missingSourceIsAnException() {
        writer.write(stockId, List.of(bar("2023-04-27", "1492", "45000000")));

        CorporateActionCollector collector = collectorWith(new FakeCorporateActionDataSource("US"));

        assertThatThrownBy(() -> collector.collect(security, FROM, TO))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("KOSPI");
    }

    @Test
    @DisplayName("IMPORTANT — 소스가 이름만 아는 사건은 휴리스틱이 확정한 종류·비율을 밀어내지 않는다")
    void nameOnlyDisclosureKeepsTheHeuristicValues() throws Exception {
        // 아시아나항공 2021-01-15 모양이다. 공시는 「무상감자」라는 낱말만 알고, 휴리스틱은
        // 주식수 배수에서 REVERSE_SPLIT 1/3을 안다. 둘 다 남아야 사람이 판정할 수 있다.
        writer.write(stockId, List.of(
                bar("2020-12-23", "4210", "223235294"),
                bar("2021-01-15", "18000", "74411764")));

        collectorWith(new FakeCorporateActionDataSource("KOSPI")
                .with(stockId, CorporateAction.named(LocalDate.parse("2021-01-15"),
                        LocalDate.parse("2021-01-12"), new BigDecimal("0.2338888889"), "무상감자")))
                .collect(security, FROM, TO);

        // 공시가 먼저 도는데도 휴리스틱의 값이 남는다 — 주석은 값을 건드리지 않기 때문이다.
        assertThat(type("2021-01-15")).isEqualTo("REVERSE_SPLIT");
        assertThat(ratio("2021-01-15")).isEqualByComparingTo("0.3333333333");
        assertThat(sourceForm("2021-01-15")).isEqualTo("무상감자");
        assertThat(status("2021-01-15")).isEqualTo("OBSERVED");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("붙일 행이 없으면 이름만으로 후보를 만든다 — 사건이 있었다는 사실이 사라지면 안 된다")
    void nameOnlyDisclosureCreatesACandidateWhenNothingIsThere() throws Exception {
        // 휴리스틱이 못 잡는 자리다(가격 불연속이 우리 창 밖이거나 폭 안이다).
        writer.write(stockId, List.of(
                bar("2021-01-14", "4210", "223235294"),
                bar("2021-01-15", "4300", "223235294")));

        collectorWith(new FakeCorporateActionDataSource("KOSPI")
                .with(stockId, CorporateAction.named(LocalDate.parse("2021-01-15"),
                        LocalDate.parse("2021-01-12"), new BigDecimal("0.9790697674"), "무상감자")))
                .collect(security, FROM, TO);

        assertThat(type("2021-01-15")).isEqualTo("UNKNOWN");
        assertThat(sourceForm("2021-01-15")).isEqualTo("무상감자");
        // UNKNOWN이라 적용 대상이 아니다. 사람이 종류와 비율을 정해야 한다.
        assertThat(ledger.findApplicable(stockId)).isEmpty();
    }

    // ── 분사: 같은 날 두 종가로 가격 배수를 정한다 ────────────────────────────────

    @Test
    @DisplayName("분사는 ex일 자식 종가와 모체 종가로 가격 배수를 정해 승인된다 — CTVA 2026-10-01")
    void spinOffIsValuedWithSameDayCloses() throws Exception {
        long ctva = usStock("CTVA");
        writer.write(ctva, List.of(bar("2026-09-30", "77.65", null), bar("2026-10-01", "12.57", null)));
        FakePriceDataSource prices = new FakePriceDataSource("US")
                .withBars(0L, List.of(bar("2026-10-01", "68.26", null), bar("2026-10-02", "67.26", null)));

        CorporateActionCollector.Result result = usCollector(new FakeCorporateActionDataSource("US")
                .with(ctva, spinNotice("2026-10-01", "VYLR", "1")), prices)
                .collect(new SecurityRef(ctva, "US", "CTVA"), FROM, TO);

        assertThat(result.pending()).isEmpty();
        assertThat(ledger.findApplicable(ctva)).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(CorporateAction.Type.SPIN_OFF);
            assertThat(e.ratio()).as("주식수는 그대로다").isEqualByComparingTo("1");
            // s/v = (12.57 + 68.26) / 12.57 — 사건 전 봉이 × 0.1555로 내려간다.
            assertThat(e.priceMultiple()).isEqualByComparingTo("6.4303898170");
        });
        assertThat(note(ctva, "2026-10-01")).contains("VYLR");
    }

    @Test
    @DisplayName("자식이 ex 며칠 뒤에야 거래되면 그 첫 날의 두 종가로 잰다 — 분배 워런트(GME.WS +3거래일)")
    void spinOffIsValuedOnTheChildsFirstBarWithinTheWindow() throws Exception {
        long gme = usStock("GME");
        writer.write(gme, List.of(bar("2025-10-02", "24.00", null), bar("2025-10-03", "23.60", null),
                bar("2025-10-06", "23.50", null), bar("2025-10-07", "23.40", null), bar("2025-10-08", "23.20", null)));
        FakePriceDataSource prices = new FakePriceDataSource("US")
                .withBars(0L, List.of(bar("2025-10-08", "3.16", null)));

        usCollector(new FakeCorporateActionDataSource("US").with(gme, spinNotice("2025-10-03", "GME.WS", "0.1")), prices)
                .collect(new SecurityRef(gme, "US", "GME"), FROM, TO);

        assertThat(ledger.findApplicable(gme)).singleElement()
                .satisfies(e -> assertThat(e.priceMultiple()).isEqualByComparingTo("1.0136206897"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT d.value_date FROM corporate_action_distribution d JOIN corporate_action a ON a.id = d.corporate_action_id
                WHERE a.stock_id = ?""", LocalDate.class, gme)).isEqualTo(LocalDate.parse("2025-10-08"));
    }

    @Test
    @DisplayName("자식 첫 봉이 근접중복 창(5거래일) 밖이면 해석하지 않고 판정 대기로 남긴다 — 소스 날짜를 의심한다")
    void spinOffBeyondTheWindowWaitsForAHuman() throws Exception {
        // ex 2025-01-06(월) · 자식 첫 봉 01-15 = 모체 7거래일 뒤 · 달력 9일(조회 폭 14일 안 — 창 검사만 막는다).
        // 실물 MRP(LEN 2025-01-21 · 첫 봉 02-07)는 달력 17일이라 조회 폭에서 먼저 걸린다 — 같은 결과다.
        long len = usStock("LEN");
        List<RawPriceBar> parent = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.parse("2025-01-03"); d.isBefore(LocalDate.parse("2025-01-20")); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() < 6) {
                parent.add(bar(d.toString(), "130", null));
            }
        }
        writer.write(len, parent);
        FakePriceDataSource prices = new FakePriceDataSource("US")
                .withBars(0L, List.of(bar("2025-01-15", "26.74", null)));

        CorporateActionCollector.Result result = usCollector(new FakeCorporateActionDataSource("US")
                .with(len, spinNotice("2025-01-06", "MRP", "0.5")), prices)
                .collect(new SecurityRef(len, "US", "LEN"), FROM, TO);

        assertThat(ledger.findApplicable(len)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status || ' ' || type FROM corporate_action WHERE stock_id = ?", String.class, len))
                .isEqualTo("OBSERVED UNKNOWN");
        assertThat(result.pending()).as("억제가 걸리므로 사람에게 알린다").hasSize(1);
    }

    @Test
    @DisplayName("같은 날 나눠 준 자식 여럿은 모두 거래된 첫 날의 종가 합으로 잰다 — OPEN 워런트 세 종")
    void multipleChildrenAreValuedTogether() throws Exception {
        long open = usStock("OPEN");
        writer.write(open, List.of(bar("2025-11-17", "7.00", null), bar("2025-11-18", "6.90", null),
                bar("2025-11-19", "6.80", null), bar("2025-11-20", "6.70", null), bar("2025-11-21", "6.60", null),
                bar("2025-11-24", "6.50", null)));
        FakePriceDataSource prices = new FakePriceDataSource("US")
                .withTickerBars("OPENW", List.of(bar("2025-11-24", "1.82", null)))
                .withTickerBars("OPENL", List.of(bar("2025-11-24", "1.08", null)))
                .withTickerBars("OPENZ", List.of(bar("2025-11-21", "0.60", null), bar("2025-11-24", "0.6506", null)));
        CorporateAction notice = CorporateAction.unresolvedSpinOff(LocalDate.parse("2025-11-18"), null, BigDecimal.ONE,
                // CUSIP을 실은 채로 둔다 — 셋을 이은 29자가 varchar(16)을 넘어 수집이 롤백된 적이 있다(V62).
                new Distribution(List.of(
                        new Distribution.Child("OPENW", "683712111", new BigDecimal("0.033333333")),
                        new Distribution.Child("OPENL", "683712129", new BigDecimal("0.033333333")),
                        new Distribution.Child("OPENZ", "683712137", new BigDecimal("0.033333333"))),
                        LocalDate.parse("2025-11-18"), null, null), "spin_off");

        usCollector(new FakeCorporateActionDataSource("US").with(open, notice), prices)
                .collect(new SecurityRef(open, "US", "OPEN"), FROM, TO);

        // 11-24: 존속 6.50 · 자식 0.033333333 × (1.82 + 1.08 + 0.6506) = 0.1183533321 → s/v = 1.0182082049
        assertThat(ledger.findApplicable(open)).singleElement()
                .satisfies(e -> assertThat(e.priceMultiple()).isEqualByComparingTo("1.0182082049"));
    }

    @Test
    @DisplayName("자식 시세의 일시 장애는 미해석으로 접지 않는다 — 접으면 거짓 판정 대기가 가고 종목이 억제된다")
    void transientChildPriceFailurePropagates() {
        long ctva = usStock("CTVA");
        writer.write(ctva, List.of(bar("2026-09-30", "77.65", null), bar("2026-10-01", "12.57", null)));
        FakePriceDataSource prices = new FakePriceDataSource("US").failingWith(0L,
                new DataSourceException(DataSourceException.Kind.TRANSIENT, "fake", "타임아웃"));

        assertThatThrownBy(() -> usCollector(new FakeCorporateActionDataSource("US")
                .with(ctva, spinNotice("2026-10-01", "VYLR", "1")), prices)
                .collect(new SecurityRef(ctva, "US", "CTVA"), FROM, TO))
                .isInstanceOf(DataSourceException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM corporate_action WHERE stock_id = ?",
                Integer.class, ctva)).isZero();
    }

    private long usStock(String ticker) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO stock (market_code, ticker, name) VALUES ('US', ?, ?) RETURNING id",
                Long.class, ticker, ticker);
    }

    private CorporateActionCollector usCollector(FakeCorporateActionDataSource source, FakePriceDataSource prices) {
        DataSourceRegistry registry = new DataSourceRegistry(
                List.of(prices), List.of(), List.of(), List.of(), List.of(), List.of(source), List.of(),
                List.of(), List.of(), List.of());
        return new CorporateActionCollector(registry, ledger, recalculator, priceCalendar,
                new SpinOffValuer(registry, jdbcTemplate), new PriceObservationWindow(jdbcTemplate), new CorporateActionSupplement());
    }

    /** 소스가 알린 분사 — 주식수 그대로(1)이고 가치는 아직 모른다. */
    private static CorporateAction spinNotice(String ex, String child, String rate) {
        return CorporateAction.unresolvedSpinOff(LocalDate.parse(ex), LocalDate.parse(ex), BigDecimal.ONE,
                Distribution.of(child, null, new BigDecimal(rate), LocalDate.parse(ex), null, null), "spin_off");
    }

    private String note(long stock, String date) {
        return jdbcTemplate.queryForObject(
                "SELECT note FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                String.class, stock, LocalDate.parse(date));
    }

    private String sourceForm(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT source_form FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                String.class, stockId, LocalDate.parse(date));
    }

    private CorporateActionCollector collectorWith(FakeCorporateActionDataSource source) {
        DataSourceRegistry registry = new DataSourceRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(source), List.of(),
                List.of(),
                List.of(),
                List.of());
        return new CorporateActionCollector(registry, ledger, recalculator, priceCalendar,
                new SpinOffValuer(registry, jdbcTemplate), new PriceObservationWindow(jdbcTemplate), new CorporateActionSupplement());
    }

    private static CorporateAction disclosed(String date, String ratio) {
        return action(date, ratio, CorporateAction.DetectionSource.SOURCE_DISCLOSURE);
    }

    private static CorporateAction heuristic(String date, String ratio) {
        return action(date, ratio, CorporateAction.DetectionSource.HEURISTIC);
    }

    private static CorporateAction action(String date, String ratio,
                                          CorporateAction.DetectionSource source) {
        BigDecimal value = new BigDecimal(ratio);
        CorporateAction.Type type = value.compareTo(BigDecimal.ONE) > 0
                ? CorporateAction.Type.SPLIT
                : CorporateAction.Type.REVERSE_SPLIT;
        return new CorporateAction(LocalDate.parse(date), type, value, source);
    }

    private String status(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                String.class, stockId, LocalDate.parse(date));
    }

    private String type(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT type FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                String.class, stockId, LocalDate.parse(date));
    }

    private BigDecimal ratio(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT ratio FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                BigDecimal.class, stockId, LocalDate.parse(date));
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM corporate_action WHERE stock_id = ?", Integer.class, stockId);
    }

    private static RawPriceBar bar(String date, String close, String listedShares) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null,
                listedShares == null ? null : new BigDecimal(listedShares), false);
    }
}
