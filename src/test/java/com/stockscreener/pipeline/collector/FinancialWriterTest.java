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
        @DisplayName("값이 있는 컬럼은 덮지 않고 경고로 남긴다 — 조용히 넘기면 소스 오류인지 파싱 오류인지 알 수 없다")
        void doesNotOverwriteAndWarnsOnMismatch() {
            writer.write(stockId, List.of(report(2025, "FY", "2026-03-15", "1000")));

            long changed = writer.write(stockId,
                    List.of(report(2025, "FY", "2026-03-15", "950")), WriteMode.FILL_MISSING);

            assertThat(changed).isZero();
            assertThat((BigDecimal) latestRow().get("net_income")).isEqualByComparingTo("1000");
            assertThat(warnings()).hasSize(1);
            assertThat(warnings().get(0)).contains("net_income").contains("덮지 않는다");
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
    private static RawFinancialReport withoutBalanceDetail(int fiscalYear, String period,
                                                           String publishedAt, String netIncome) {
        return report(fiscalYear, period, publishedAt, netIncome, false);
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
                null);
    }
}
