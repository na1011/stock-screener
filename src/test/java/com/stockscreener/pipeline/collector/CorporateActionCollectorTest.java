package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.support.FakeCorporateActionDataSource;
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
        PriceCalendar.class, PriceWriter.class, NoOpIndicatorInvalidationPort.class})
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
        writer.write(stockId, List.of(bar("2026-02-02", "1000", "1000000"),
                bar("2026-02-03", "1010", "1000000"), bar("2026-02-04", "1020", "1000000")));
        // 사람이 이미 판정한 행이 있어 공시가 옮기지도 덮지도 못한다.
        long judged = ledger.record(stockId, heuristic("2026-02-03", "0.1")).existingId();
        ledger.judge(judged, rejected("하한가 연속이었다"));
        FakeCorporateActionDataSource source = new FakeCorporateActionDataSource("KOSPI")
                .with(stockId, disclosed("2026-02-04", "0.1"));

        CorporateActionCollector.Result result = collectorWith(source).collect(security, FROM, TO);

        assertThat(result.nearDuplicates()).singleElement().satisfies(conflict -> {
            assertThat(conflict.ticker()).isEqualTo("001440");
            assertThat(conflict.proposed()).isEqualTo(LocalDate.parse("2026-02-04"));
            assertThat(conflict.existing()).isEqualTo(LocalDate.parse("2026-02-03"));
        });
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

    private String sourceForm(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT source_form FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                String.class, stockId, LocalDate.parse(date));
    }

    private CorporateActionCollector collectorWith(FakeCorporateActionDataSource source) {
        DataSourceRegistry registry = new DataSourceRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(source), List.of());
        return new CorporateActionCollector(registry, ledger, recalculator, priceCalendar);
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
                new BigDecimal(listedShares), false);
    }
}
