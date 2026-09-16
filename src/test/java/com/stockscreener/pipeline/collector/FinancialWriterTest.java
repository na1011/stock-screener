package com.stockscreener.pipeline.collector;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.stockscreener.pipeline.collector.FinancialWriter.WriteMode;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정정공시 처리를 검증한다.
 *
 * <p>핵심은 <b>덮어쓰지 않는다</b>는 것이다. 하나의 가변 행으로 두면 "그 시점에 공개되어
 * 있던 값"이 영구히 파괴되어 어떤 마이그레이션으로도 복구할 수 없다.
 *
 * <p>바깥의 여섯은 {@link WriteMode#APPEND_ONLY}의 계약이며 {@code FILL_MISSING} 도입으로
 * 하나도 바뀌지 않아야 한다 — 그것이 이 테스트들을 남겨 두는 이유다.
 */
@JpaSliceTest
@Import({FinancialWriter.class, NoOpIndicatorInvalidationPort.DefaultInvalidationPortConfig.class})
class FinancialWriterTest {

    @Autowired
    private FinancialWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("보고서를 저장하고 최신본으로 표시한다")
    void writesReportAsLatest() {
        long written = writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));

        assertThat(written).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM financial_statement WHERE stock_id = ?", stockId);
        assertThat(row.get("revision_no")).isEqualTo(0);
        assertThat(row.get("is_latest")).isEqualTo(true);
        assertThat((BigDecimal) row.get("net_income")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("정정공시는 덮어쓰지 않고 새 개정본으로 쌓는다 — 과거 공개값이 파괴되면 안 된다")
    void appendsRevisionInsteadOfOverwriting() {
        writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));
        long written = writer.write(stockId, List.of(report(2025, "FY", "2026-04-20", "950")));

        assertThat(written).isEqualTo(1);
        // 원본이 남아 있어야 한다.
        assertThat(rowCount()).isEqualTo(2);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT revision_no, is_latest, net_income FROM financial_statement
                WHERE stock_id = ? ORDER BY revision_no
                """, stockId);
        assertThat(rows.get(0).get("revision_no")).isEqualTo(0);
        assertThat(rows.get(0).get("is_latest")).isEqualTo(false);
        assertThat((BigDecimal) rows.get(0).get("net_income")).isEqualByComparingTo("1000");
        assertThat(rows.get(1).get("revision_no")).isEqualTo(1);
        assertThat(rows.get(1).get("is_latest")).isEqualTo(true);
        assertThat((BigDecimal) rows.get(1).get("net_income")).isEqualByComparingTo("950");
    }

    @Test
    @DisplayName("같은 공시일의 재수집은 아무것도 하지 않는다 — 멱등해야 한다")
    void isIdempotentForSamePublicationDate() {
        RawFinancialReport report = report(2025, "FY", "2026-03-15", "1000");
        writer.write(stockId, List.of(report));
        long written = writer.write(stockId, List.of(report));

        assertThat(written).isZero();
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이전 공시일의 보고서는 무시한다 — 소스가 오래된 값을 줘도 최신본을 덮지 않는다")
    void ignoresOlderPublication() {
        writer.write(stockId, List.of(report(2025, "FY", "2026-04-20", "950")));
        long written = writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));

        assertThat(written).isZero();
        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT net_income FROM financial_statement WHERE stock_id = ? AND is_latest",
                BigDecimal.class, stockId)).isEqualByComparingTo("950");
    }

    @Test
    @DisplayName("FY와 Q4를 별개로 저장한다 — 소스가 준 형태 그대로 두고 판단은 지표 계층에 맡긴다")
    void keepsAnnualAndFourthQuarterSeparate() {
        writer.write(stockId, List.of(
                report(2025, "FY", "2026-03-15", "1000"),
                report(2025, "Q4", "2026-02-10", "300")));

        assertThat(rowCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT fiscal_period FROM financial_statement WHERE stock_id = ? ORDER BY fiscal_period",
                String.class, stockId)).containsExactly("FY", "Q4");
    }

    @Test
    @DisplayName("여러 기간의 보고서를 한 번에 저장한다")
    void writesMultiplePeriods() {
        long written = writer.write(stockId, List.of(
                report(2023, "FY", "2024-03-15", "800"),
                report(2024, "FY", "2025-03-15", "900"),
                report(2025, "FY", "2026-03-15", "1000")));

        assertThat(written).isEqualTo(3);
        assertThat(rowCount()).isEqualTo(3);
    }

    /**
     * {@link WriteMode#FILL_MISSING} — 스키마에 컬럼이 늘어 NULL로 남은 자리를 채우는 경로(ADR-0040).
     *
     * <p>이 모드의 위험은 "채운다"가 "덮는다"로 번지는 것이다. 아래 일곱이 그 경계를 고정한다.
     */
    @Nested
    @DisplayName("결손 채움")
    class FillMissing {

        private ListAppender<ILoggingEvent> appender;
        private ch.qos.logback.classic.Logger logger;
        /** 덮어쓰기가 무효화를 부르는지 보려면 기록하는 포트가 필요하다. */
        private final RecordingInvalidationPort invalidations = new RecordingInvalidationPort();
        private FinancialWriter fillOnlyWriter;

        @BeforeEach
        void useRecordingPort() {
            fillOnlyWriter = new FinancialWriter(jdbcTemplate, invalidations);
        }

        @BeforeEach
        void attachAppender() {
            logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FinancialWriter.class);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
        }

        @Test
        @DisplayName("같은 공시일의 재수집이 NULL 컬럼만 채운다 — 행이 늘지 않는다")
        void fillsOnlyNullColumns() {
            writer.write(stockId, List.of(withoutBalanceDetail(2025, "FY", "2026-03-15", "1000")));

            long changed = writer.write(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "1000")), WriteMode.FILL_MISSING);

            assertThat(changed).isEqualTo(1);
            assertThat(rowCount()).isEqualTo(1);
            Map<String, Object> row = latestRow();
            assertThat((BigDecimal) row.get("current_assets")).isEqualByComparingTo("9000");
            assertThat((BigDecimal) row.get("current_liabilities")).isEqualByComparingTo("4000");
            assertThat((BigDecimal) row.get("retained_earnings")).isEqualByComparingTo("6000");
            // 원래 값이 있던 컬럼은 그대로다.
            assertThat((BigDecimal) row.get("net_income")).isEqualByComparingTo("1000");
            assertThat(row.get("revision_no")).isEqualTo(0);
        }

        @Test
        @DisplayName("같은 공시일에 값이 달라지면 덮고, 무엇이 무엇으로 바뀌었는지 남긴다")
        void overwritesAndRecordsOnMismatch() {
            writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));

            long changed = fillOnlyWriter.write(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "950")), WriteMode.FILL_MISSING);

            // 같은 공시일이므로 값이 달라진 것은 소스가 고쳐 낸 것이 아니라 우리 추출이 바뀐
            // 것이다. 예전에는 덮지 않고 경고만 남겼는데, 그 규칙이 지킨 것은 데이터가 아니라
            // 틀린 값이었다 — 매출 태그 우선순위를 고쳤을 때 US_TOP1000의 92종목이 과소계상인
            // 채로 남았고 그때 이미 틀린 지표 값 542개가 화면에 나가 있었다.
            assertThat(changed).isOne();
            assertThat((BigDecimal) latestRow().get("net_income")).isEqualByComparingTo("950");
            assertThat(warnings()).hasSize(1);
            assertThat(warnings().get(0))
                    .as("위험한 것은 갱신이 아니라 조용한 갱신이다 — 옛값과 새값이 다 남아야 한다")
                    .contains("net_income").contains("1000").contains("950");
        }

        @Test
        @DisplayName("주식수는 달라져도 안 덮는다 — 그 값은 기간 보고서가 아니라 표지에서 온다")
        void neverOverwritesSharesOutstanding() {
            writer.write(stockId,
                    List.of(withShares(2025, "FY", "2026-03-15", "1000", "15655000")));

            long changed = fillOnlyWriter.write(stockId,
                    List.of(withShares(2025, "FY", "2026-03-15", "1000", "15655")),
                    WriteMode.FILL_MISSING);

            // 덮기의 근거는 "같은 보고서인데 값이 달라졌으면 우리 추출이 바뀐 것"인데, 주식수만은
            // 라벨 앵커의 표지에서 오므로 그 전제가 안 선다 — 차이는 소스의 두 보고서가 축척을
            // 다르게 낸 것이다. 실측으로 DDS가 15,655,000 -> 15,655로 덮여 시총이 1/1000이 됐다.
            assertThat(changed).isZero();
            assertThat((BigDecimal) latestRow().get("shares_outstanding"))
                    .as("표지 값은 소스의 두 보고서가 갈리므로 저장본을 지킨다")
                    .isEqualByComparingTo("15655000");
            assertThat(invalidations.calls).isEmpty();
        }

        @Test
        @DisplayName("값을 덮으면 무효화한다 — 그 값으로 계산된 지표가 이미 저장돼 있다")
        void invalidatesWhenItOverwrites() {
            writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));

            fillOnlyWriter.write(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "950")), WriteMode.FILL_MISSING);

            // NULL을 채우는 것은 「행 없음 → 행 생김」이라 지울 낡은 값이 없지만, 덮는 것은
            // 「틀린 값 → 다른 값」이라 이미 저장된 지표가 낡는다. 안 지우면 그 값이 그대로
            // 남는데 그것은 그럴듯해서 아무도 눈치채지 못한다.
            assertThat(invalidations.calls)
                    .as("회계기간 시작일 이후를 지워야 한다")
                    .hasSize(1);
        }

        @Test
        @DisplayName("scale만 다른 같은 값은 불일치가 아니다 — equals로 비교하면 전 컬럼이 매번 경고가 된다")
        void treatsSameValueWithDifferentScaleAsEqual() {
            // 저장은 numeric(24,2)라 1000.00으로 돌아오고, 소스 파싱값은 scale 0이다.
            writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));

            long changed = writer.write(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "1000")), WriteMode.FILL_MISSING);

            assertThat(changed).isZero();
            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("더 이른 공시본으로는 채우지 않는다 — 한 행이 두 공시의 혼합이 되면 안 된다")
        void doesNotFillFromOlderPublication() {
            writer.write(stockId, List.of(withoutBalanceDetail(2025, "FY", "2026-04-20", "950")));

            long changed = writer.write(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "1000")), WriteMode.FILL_MISSING);

            assertThat(changed).isZero();
            assertThat(latestRow().get("current_assets")).isNull();
            assertThat(rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("더 이른 공시본이라도 그 판의 행이 있으면 그 행을 채운다 — 비-is_latest 행은 과거 기준일의 값이다")
        void fillsTheOlderVintageRowWhenItExists() {
            // 이른 판을 결손인 채로 넣고, 늦은 판으로 개정본을 쌓아 이른 판을 비-is_latest로 내린다.
            writer.write(stockId, List.of(withoutBalanceDetail(2025, "FY", "2026-03-15", "1000")));
            writer.write(stockId, List.of(report(2025, "FY", "2026-04-20", "950")));

            long changed = writer.write(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "1000")), WriteMode.FILL_MISSING);

            assertThat(changed).isEqualTo(1);
            assertThat(rowCount()).isEqualTo(2);
            // 그 공시일의 행이 채워졌다.
            assertThat((BigDecimal) vintageRow("2026-03-15").get("current_assets"))
                    .isEqualByComparingTo("9000");
            // 최신본은 안 건드렸다.
            assertThat((BigDecimal) latestRow().get("net_income")).isEqualByComparingTo("950");
        }

        @Test
        @DisplayName("소스가 값을 잃어도 저장된 값을 지우지 않는다")
        void neverClearsStoredValueWhenSourceIsNull() {
            writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));

            long changed = writer.write(stockId,
                    List.of(withoutBalanceDetail(2025, "FY", "2026-03-15", "1000")),
                    WriteMode.FILL_MISSING);

            assertThat(changed).isZero();
            assertThat((BigDecimal) latestRow().get("current_assets")).isEqualByComparingTo("9000");
            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("더 늦은 공시일은 모드와 무관하게 새 개정본을 쌓는다")
        void stillAppendsRevision() {
            writer.write(stockId, List.of(withoutBalanceDetail(2025, "FY", "2026-03-15", "1000")));

            long changed = writer.write(stockId,
                    List.of(report(2025, "FY", "2026-04-20", "950")), WriteMode.FILL_MISSING);

            assertThat(changed).isEqualTo(1);
            assertThat(rowCount()).isEqualTo(2);
            Map<String, Object> latest = latestRow();
            assertThat(latest.get("revision_no")).isEqualTo(1);
            assertThat((BigDecimal) latest.get("net_income")).isEqualByComparingTo("950");
            assertThat((BigDecimal) latest.get("current_assets")).isEqualByComparingTo("9000");
        }

        @Test
        @DisplayName("기존 행이 없으면 모드와 무관하게 새로 쓴다 — 재무를 한 번도 받지 못한 종목이 이 경로로 온다")
        void insertsWhenNothingStored() {
            long changed = writer.write(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "1000")), WriteMode.FILL_MISSING);

            assertThat(changed).isEqualTo(1);
            assertThat(rowCount()).isEqualTo(1);
            assertThat((BigDecimal) latestRow().get("current_assets")).isEqualByComparingTo("9000");
        }

        private List<String> warnings() {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    /**
     * P10의 판정이 통째로 걸린 자리다.
     *
     * <p>§P10은 <b>"표본 몇 종목의 {@code rcept_no}가 같더라는 확인으로는 이 기준이 충족되지
     * 않는다"</b>고 못 박는다. 판정할 (종목, 연도) 쌍이 2,100개라 표본으로 답할 수 없기
     * 때문이다. 그래서 여기서 재는 것은 "이번에는 안 불렸다"가 아니라 <b>부를 가지가 없다</b>는
     * 것이고, 그 증거로 <b>같은 입력에 {@code FILL_MISSING}은 부른다</b>는 대조군을 함께 둔다 —
     * 대조군이 없으면 이 테스트는 항등식이라 초록이라는 사실로 거짓 안심만 준다.
     */
    @Nested
    @DisplayName("FILL_WITHOUT_REVISION — 정정공시 가지에 도달하지 않는다")
    class FillWithoutRevision {

        private final RecordingInvalidationPort invalidations = new RecordingInvalidationPort();
        private FinancialWriter fillOnlyWriter;

        @BeforeEach
        void useRecordingPort() {
            fillOnlyWriter = new FinancialWriter(jdbcTemplate, invalidations);
        }

        @Test
        @DisplayName("더 늦은 공시본을 만나도 무효화를 부르지 않고 개정본도 쌓지 않는다")
        void neverInvalidatesOnLaterPublication() {
            writer.write(stockId, List.of(withoutBalanceDetail(2025, "FY", "2026-03-15", "1000")));

            FinancialWriter.Outcome outcome = fillOnlyWriter.writeDetailed(stockId,
                    List.of(report(2025, "FY", "2026-04-20", "950")),
                    WriteMode.FILL_WITHOUT_REVISION);

            assertThat(invalidations.calls).isEmpty();
            assertThat(outcome.deferredRevisions()).isEqualTo(1);
            assertThat(outcome.changed()).isZero();
            assertThat(rowCount()).isEqualTo(1);
            // 채우지 않은 것이 요점이다. 결손은 결손인 채로 남고, 그 사실이 위 수로 드러난다.
            assertThat(latestRow().get("current_assets")).isNull();
            assertThat(latestRow().get("revision_no")).isEqualTo(0);
        }

        @Test
        @DisplayName("대조군 — 같은 입력에 FILL_MISSING은 무효화를 부른다")
        void fillMissingStillInvalidatesOnTheSameInput() {
            writer.write(stockId, List.of(withoutBalanceDetail(2025, "FY", "2026-03-15", "1000")));

            fillOnlyWriter.writeDetailed(stockId,
                    List.of(report(2025, "FY", "2026-04-20", "950")), WriteMode.FILL_MISSING);

            assertThat(invalidations.calls)
                    .as("이 한 줄이 위 테스트를 항등식이 아니게 만든다")
                    .containsExactly(stockId + "@2025-01-01:FINANCIAL_REVISED");
        }

        @Test
        @DisplayName("공시일이 같으면 NULL 컬럼을 채운다 — 결손 보충이라는 목적은 그대로다")
        void fillsNullColumnsOnSamePublication() {
            writer.write(stockId, List.of(withoutBalanceDetail(2025, "FY", "2026-03-15", "1000")));

            FinancialWriter.Outcome outcome = fillOnlyWriter.writeDetailed(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "1000")),
                    WriteMode.FILL_WITHOUT_REVISION);

            assertThat(invalidations.calls).isEmpty();
            assertThat(outcome.filled()).isEqualTo(1);
            assertThat(rowCount()).isEqualTo(1);
            assertThat((BigDecimal) latestRow().get("operating_cash_flow"))
                    .isEqualByComparingTo("1500");
        }

        @Test
        @DisplayName("행이 없으면 새로 쓴다 — 상장 이력은 있는데 그 해 재무가 없는 종목이 이 경로로 온다")
        void insertsWhenNothingStored() {
            FinancialWriter.Outcome outcome = fillOnlyWriter.writeDetailed(stockId,
                    List.of(report(2021, "FY", "2022-03-15", "1000")),
                    WriteMode.FILL_WITHOUT_REVISION);

            assertThat(invalidations.calls).isEmpty();
            assertThat(outcome.inserted()).isEqualTo(1);
            assertThat(rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("더 이른 공시본으로는 채우지 않는다 — 한 행이 두 공시의 혼합이 되면 안 된다")
        void doesNotFillFromOlderPublication() {
            writer.write(stockId, List.of(withoutBalanceDetail(2025, "FY", "2026-04-20", "950")));

            FinancialWriter.Outcome outcome = fillOnlyWriter.writeDetailed(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "1000")),
                    WriteMode.FILL_WITHOUT_REVISION);

            assertThat(invalidations.calls).isEmpty();
            assertThat(outcome.changed()).isZero();
            assertThat(latestRow().get("current_assets")).isNull();
        }

        @Test
        @DisplayName("더 이른 공시본의 행이 있어도 채우지 않는다 — 이 모드의 소스는 DART이고 판을 하나만 낸다")
        void doesNotFillTheOlderVintageRow() {
            writer.write(stockId, List.of(withoutBalanceDetail(2025, "FY", "2026-03-15", "1000")));
            writer.write(stockId, List.of(report(2025, "FY", "2026-04-20", "950")));

            FinancialWriter.Outcome outcome = fillOnlyWriter.writeDetailed(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "1000")),
                    WriteMode.FILL_WITHOUT_REVISION);

            assertThat(outcome.changed()).isZero();
            assertThat(vintageRow("2026-03-15").get("current_assets")).isNull();
        }
    }

    /** 무효화 호출을 그대로 적는다. 이 슬라이스의 기본 포트는 아무것도 기록하지 않는다. */
    private static final class RecordingInvalidationPort implements IndicatorInvalidationPort {

        private final List<String> calls = new java.util.ArrayList<>();

        @Override
        public void invalidate(long stockId, LocalDate fromDate, InvalidationReason reason) {
            calls.add(stockId + "@" + fromDate + ":" + reason);
        }
    }

    private Map<String, Object> latestRow() {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM financial_statement WHERE stock_id = ? AND is_latest", stockId);
    }

    /** 공시일로 판 하나를 집는다. {@code is_latest}를 안 보는 자리를 검증하려면 이것이 필요하다. */
    private Map<String, Object> vintageRow(String publishedAt) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM financial_statement WHERE stock_id = ? AND published_at = ?::date",
                stockId, publishedAt);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_statement WHERE stock_id = ?", Integer.class, stockId);
    }

    private static RawFinancialReport report(int fiscalYear, String period,
                                             String publishedAt, String netIncome) {
        return report(fiscalYear, period, publishedAt, netIncome, true);
    }

    /**
     * 유동자산·유동부채·이익잉여금이 비어 있는 보고서.
     *
     * <p>V16이 컬럼을 더하기 전에 저장된 10,882행을 재현한다 — 그 결손이 ADR-0040의 출발점이다.
     */
    @Test
    @DisplayName("유도한 행은 새 규칙이 못 만든 컬럼을 지운다 — 소스가 준 행은 안 지운다")
    void derivedRowClearsWhatTheNewRuleCannotMake() {
        // 재적재 잡이 쓰는 모드다(FinancialRefillJob). APPEND_ONLY는 같은 공시일의 재수집을
        // 아예 안 보므로 이 갈래에 못 닿는다.
        // 운영에서는 FY·Q1~Q3가 함께 들어온다 — 그 형제 행이 이 호출의 **관할 컬럼**을 정한다.
        writer.write(stockId, List.of(derivedQuarter("-100", true), siblingWithCostOfSales("500")),
                FinancialWriter.WriteMode.FILL_MISSING);
        assertThat(storedCostOfSales()).isEqualByComparingTo("-100");

        // 새 규칙이 「이 컬럼은 못 만든다」고 판정했다. 재수집 경로가 NULL만 채우면 옛 유도값이
        // 영영 남는다 — 실측으로 미국 4분기 음수 매출 45행·매출원가 41행이 재적재 뒤에도
        // 한 행도 안 줄었다.
        writer.write(stockId, List.of(derivedQuarter(null, true), siblingWithCostOfSales("500")),
                FinancialWriter.WriteMode.FILL_MISSING);
        assertThat(storedCostOfSales()).as("유도 행이면 지운다").isNull();

        // 대조군 — 소스가 준 행에서 컬럼이 빠진 것은 「그 판이 안 실었다」이지 「값이 없다」가
        // 아니다. 지우면 정정 하나가 그 기간의 손익을 통째로 날린다.
        writer.write(stockId, List.of(derivedQuarter("-100", true), siblingWithCostOfSales("500")),
                FinancialWriter.WriteMode.FILL_MISSING);
        writer.write(stockId, List.of(derivedQuarter(null, false), siblingWithCostOfSales("500")),
                FinancialWriter.WriteMode.FILL_MISSING);
        assertThat(storedCostOfSales()).as("소스가 준 행이면 안 지운다").isEqualByComparingTo("-100");
    }

    @Test
    @DisplayName("새 규칙이 더는 만들지 않는 유도 판은 회수된다 — 옛 규칙의 행이 최신으로 남지 않는다")
    void retractsDerivedRevisionsThatTheNewRuleNoLongerMakes() {
        // 옛 규칙이 두 세대를 만들어 뒀다.
        writer.write(stockId, List.of(
                derivedQuarterAt("2024-02-20", "250000", true),
                derivedQuarterAt("2026-02-20", "-350000", true)),
                FinancialWriter.WriteMode.FILL_MISSING);
        assertThat(storedLatestRevenue()).isEqualByComparingTo("-350000");

        // 새 규칙은 2026 세대를 안 만든다. writeOne은 더 이른 공시본을 건너뛰므로, 회수가
        // 없으면 틀린 행이 is_latest인 채 영영 남는다.
        writer.write(stockId, List.of(derivedQuarterAt("2024-02-20", "250000", true)),
                FinancialWriter.WriteMode.FILL_MISSING);

        assertThat(storedLatestRevenue())
                .as("세트에 없는 세대는 지워지고 남은 것이 최신이 된다")
                .isEqualByComparingTo("250000");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("소스가 준 판은 회수하지 않는다 — 적재된 원자료는 되돌리지 않는다")
    void doesNotRetractSourceReportedRevisions() {
        writer.write(stockId, List.of(
                derivedQuarterAt("2024-02-20", "250000", false),
                derivedQuarterAt("2026-02-20", "-350000", false)),
                FinancialWriter.WriteMode.FILL_MISSING);

        writer.write(stockId, List.of(derivedQuarterAt("2024-02-20", "250000", false)),
                FinancialWriter.WriteMode.FILL_MISSING);

        assertThat(rowCount()).as("판 둘이 그대로 남는다").isEqualTo(2);
        assertThat(storedLatestRevenue()).isEqualByComparingTo("-350000");
    }

    @Test
    @DisplayName("같은 공시일에 기간이 달라지면 기간도 고쳐 쓰고 무효화한다 — 그 셋은 INSERT에만 있었다")
    void rewritesThePeriodWhenTheSamePublicationNowSpansDifferentDates() {
        RecordingInvalidationPort port = new RecordingInvalidationPort();
        FinancialWriter w = new FinancialWriter(jdbcTemplate, port);
        w.write(stockId, List.of(quarterSpanning("2025-10-01", "2025-12-31")),
                FinancialWriter.WriteMode.FILL_MISSING);
        port.calls.clear();

        // 같은 공시일인데 우리 기간 규칙이 바뀌어 창이 며칠 앞으로 늘었다.
        w.write(stockId, List.of(quarterSpanning("2025-09-28", "2025-12-31")),
                FinancialWriter.WriteMode.FILL_MISSING);

        Map<String, Object> row = latestRow();
        assertThat(row.get("period_start")).hasToString("2025-09-28");
        assertThat(rowCount()).as("판을 새로 쌓지 않는다").isEqualTo(1);
        assertThat(port.calls).as("기간이 바뀌면 값이 그대로여도 무효화한다")
                .containsExactly(stockId + "@2025-09-28:FINANCIAL_REVISED");
    }

    /** 기간만 다른 같은 공시일의 분기 행. */
    private static RawFinancialReport quarterSpanning(String start, String end) {
        return new RawFinancialReport(
                2025, "Q4",
                LocalDate.parse(start), LocalDate.parse(end),
                LocalDate.parse("2026-03-15"),
                "11011", "KRW",
                new BigDecimal("5000"), new BigDecimal("1200"), new BigDecimal("900"),
                new BigDecimal("20000"), new BigDecimal("12000"), null, new BigDecimal("8000"),
                null, null, null,
                null, new BigDecimal("100"),
                null, null, null, null, null, null,
                null,
                null, null, null, null, null, null, null, null, null, null,
                false);
    }

    @Test
    @DisplayName("판 이력을 전부 내는 소스에서는 소스가 준 판도 회수한다 — 유령이 교정을 영구히 막는다")
    void retractsSourceVintagesWhenTheFetchCarriesTheWholeHistory() {
        writer.write(stockId, List.of(
                derivedQuarterAt("2024-02-20", "250000", false),
                derivedQuarterAt("2026-02-20", "-350000", false)),
                FinancialWriter.WriteMode.FILL_MISSING);

        // 계약이 참이면 「여기 없는 공시일」은 더 이상 그 라벨의 판이 아니다.
        writer.writeDetailed(stockId, List.of(derivedQuarterAt("2024-02-20", "250000", false)),
                FinancialWriter.WriteMode.FILL_MISSING, true);

        assertThat(rowCount()).as("유령 판이 회수된다").isEqualTo(1);
        assertThat(storedLatestRevenue()).isEqualByComparingTo("250000");
    }

    @Test
    @DisplayName("어댑터가 그 라벨을 하나도 안 내면 계약이 참이어도 한 행도 안 건드린다")
    void neverTouchesALabelTheFetchDidNotProduce() {
        writer.write(stockId, List.of(
                derivedQuarterAt("2024-02-20", "250000", false),
                derivedQuarterAt("2026-02-20", "-350000", false)),
                FinancialWriter.WriteMode.FILL_MISSING);

        // 다른 라벨만 들어온다 — 그 라벨은 자격이 없으므로 보존 집합을 만들지 않는다.
        writer.writeDetailed(stockId, List.of(report(2024, "FY", "2025-03-15", "10")),
                FinancialWriter.WriteMode.FILL_MISSING, true);

        assertThat(rowCount()).as("판 둘이 그대로 남고 새 라벨이 하나 는다").isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_statement WHERE stock_id = ? AND fiscal_period = 'Q4'",
                Integer.class, stockId)).isEqualTo(2);
    }

    private BigDecimal storedLatestRevenue() {
        return jdbcTemplate.queryForObject(
                "SELECT revenue FROM financial_statement WHERE stock_id = ? AND is_latest",
                BigDecimal.class, stockId);
    }

    /** 공시일과 매출을 지정한 4분기 행. */
    private static RawFinancialReport derivedQuarterAt(String publishedAt, String revenue,
                                                       boolean derived) {
        return new RawFinancialReport(
                2025, "Q4",
                LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31),
                LocalDate.parse(publishedAt),
                "11011", "KRW",
                new BigDecimal(revenue), new BigDecimal("1200"), new BigDecimal("900"),
                new BigDecimal("20000"), new BigDecimal("12000"), null, new BigDecimal("8000"),
                null, null, null,
                null, new BigDecimal("100"),
                null, null, null, null, null, null,
                null,
                null, null, null, null, null, null, null, null, null, null,
                derived);
    }

    @Test
    @DisplayName("유도 행이라도 이 호출이 안 다루는 컬럼은 안 지운다 — 다른 소스가 채운 값이다")
    void neverClearsAColumnThisFetchDoesNotManage() {
        // 전체 재무제표가 재고를 채워 뒀다.
        writer.write(stockId, List.of(derivedQuarter("-100", true), siblingWithCostOfSales("500")),
                FinancialWriter.WriteMode.FILL_MISSING);
        jdbcTemplate.update(
                "UPDATE financial_statement SET inventories = 777 WHERE stock_id = ? AND fiscal_period = 'Q4'",
                stockId);

        // 주요계정의 유도 4분기는 재고를 **언제나 null로 낸다** — 그 엔드포인트가 안 준다.
        // 그것을 「새 규칙이 못 만든다」로 읽으면 남의 값을 지운다(실측: 국내 594칸).
        writer.write(stockId, List.of(derivedQuarter(null, true), siblingWithCostOfSales("500")),
                FinancialWriter.WriteMode.FILL_MISSING);

        assertThat((BigDecimal) jdbcTemplate.queryForObject(
                "SELECT inventories FROM financial_statement WHERE stock_id = ? AND fiscal_period = 'Q4'",
                BigDecimal.class, stockId))
                .as("이 호출이 한 행에서도 값을 안 낸 컬럼은 관할이 아니다")
                .isEqualByComparingTo("777");
        assertThat(storedCostOfSales())
                .as("같은 호출이 값을 낸 적 있는 컬럼은 그대로 지운다").isNull();
    }

    /** 같은 소스의 형제 행. 이 호출의 **관할 컬럼**을 정하는 것이 목적이다. */
    private static RawFinancialReport siblingWithCostOfSales(String costOfSales) {
        return new RawFinancialReport(
                2025, "Q3",
                LocalDate.of(2025, 7, 1), LocalDate.of(2025, 9, 30),
                LocalDate.parse("2026-03-15"),
                "11014", "KRW",
                new BigDecimal("4000"), new BigDecimal("1000"), new BigDecimal("700"),
                new BigDecimal("19000"), new BigDecimal("11000"), null, new BigDecimal("8000"),
                null, null, null,
                null, new BigDecimal("100"),
                null, null,
                new BigDecimal(costOfSales),
                null, null, null,
                null,
                null, null, null, null, null, null, null, null, null, null,
                false);
    }

    private BigDecimal storedCostOfSales() {
        // 형제 행이 함께 들어오므로 is_latest가 라벨마다 하나씩 있다 — 4분기 행을 집는다.
        return jdbcTemplate.queryForObject(
                "SELECT cost_of_sales FROM financial_statement "
                        + "WHERE stock_id = ? AND is_latest AND fiscal_period = 'Q4'",
                BigDecimal.class, stockId);
    }

    /** 4분기 유도 행. {@code derived}가 그 행을 우리가 만들었는지 말한다. */
    private static RawFinancialReport derivedQuarter(String costOfSales, boolean derived) {
        return new RawFinancialReport(
                2025, "Q4",
                LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31),
                LocalDate.parse("2026-03-15"),
                "11011", "KRW",
                new BigDecimal("5000"), new BigDecimal("1200"), new BigDecimal("900"),
                new BigDecimal("20000"), new BigDecimal("12000"), null, new BigDecimal("8000"),
                null, null, null,
                null, new BigDecimal("100"),
                null, null,
                costOfSales == null ? null : new BigDecimal(costOfSales),
                null, null, null,
                null,
                null, null, null, null, null, null, null, null, null, null,
                derived);
    }

    private static RawFinancialReport withoutBalanceDetail(int fiscalYear, String period,
                                                           String publishedAt, String netIncome) {
        return report(fiscalYear, period, publishedAt, netIncome, false);
    }

    /** 주식수만 바꾼 보고서. 표지 값이 소스의 보고서마다 갈리는 것을 재현한다. */
    private static RawFinancialReport withShares(int fiscalYear, String period, String publishedAt,
                                                 String netIncome, String shares) {
        RawFinancialReport base = report(fiscalYear, period, publishedAt, netIncome);
        return new RawFinancialReport(
                base.fiscalYear(), base.fiscalPeriod(), base.periodStart(), base.periodEnd(),
                base.publishedAt(), base.reportCode(), base.currencyCode(),
                base.revenue(), base.operatingIncome(), base.netIncome(),
                base.totalAssets(), base.totalLiabilities(), base.totalEquity(),
                base.currentAssets(), base.currentLiabilities(), base.retainedEarnings(),
                base.operatingCashFlow(), new BigDecimal(shares),
                base.eps(), base.bps(), base.costOfSales(), base.cashAndEquivalents(),
                base.capex(), base.inventories(), base.netIncomeControlling(),
                base.interestAndDividendIncome(), base.noninterestIncome(),
                base.interestExpense(), base.noninterestExpense(),
                base.benefitsLossesAndExpenses(), base.grossProfit(),
                base.operatingExpenses(), base.costsAndExpenses(), null, null);
    }

    private static RawFinancialReport report(int fiscalYear, String period, String publishedAt,
                                             String netIncome, boolean withBalanceDetail) {
        return new RawFinancialReport(
                fiscalYear, period,
                LocalDate.of(fiscalYear, 1, 1), LocalDate.of(fiscalYear, 12, 31),
                LocalDate.parse(publishedAt),
                "11011", "KRW",
                new BigDecimal("5000"), new BigDecimal("1200"), new BigDecimal(netIncome),
                new BigDecimal("20000"), new BigDecimal("12000"), new BigDecimal("8000"),
                withBalanceDetail ? new BigDecimal("9000") : null,
                withBalanceDetail ? new BigDecimal("4000") : null,
                withBalanceDetail ? new BigDecimal("6000") : null,
                new BigDecimal("1500"), new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("120"), null, null, null, null,
                // 지배주주 귀속 순이익. 주요계정 어댑터가 낼 수 없는 값이라 null이 기본이다.
                null,
                null, null, null, null, null, null, null, null, null, null);
    }
}
