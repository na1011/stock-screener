package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매 평일 화해의 두 층을 실 DB로 고정한다 — ① 원본층({@link IndicatorBackfillJob#rewriteOriginLayer})과
 * ② 파생층({@link IndicatorBackfillJob#rebuildDerived}).
 *
 * <p>스케줄러가 무엇을 어떤 순서로 부르는지는 {@code PipelineSchedulerChainTest}가 목으로 본다. 여기는
 * <b>각 층이 정말 그 모집단·그 날짜를 쓰는가</b>를 본다 — 목으로는 원리적으로 못 묻는 것이다.
 *
 * <p>픽스처는 셋이다. 지금 편입 둘(값 10·30)과 <b>창 안에서 편출한 과거 구성원</b> 하나(값 20, 편입이
 * {@link #D2}에 끝난다). 그리고 창 밖에서 편출한 종목 하나. 백분위는 그날 편입으로 매기므로(ADR-0135)
 * {@link #D1}에는 셋이, {@link #D3}에는 둘이 모집단이다 — 그 차이가 「② 가 날짜마다 그날 편입을 쓴다」를
 * 값으로 드러낸다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 러너가 {@code REQUIRES_NEW}로 커밋한다(ADR-0007).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class IndicatorReconcileLayersTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 7, 3);
    private static final String UNIVERSE = "KOSPI200";
    private static final List<String> TICKERS = List.of("910001", "910002", "910003", "910004");

    @Autowired
    private IndicatorBackfillJob job;

    @Autowired
    private StockRegistrar stockRegistrar;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long current10;
    private long departedInside20;
    private long current30;
    private long departedBefore;

    @BeforeEach
    void seed() {
        cleanUp();
        // 창의 양 끝. 「보존 창과 겹치는 편입」은 이 달력으로 판정한다.
        jdbcTemplate.update("""
                INSERT INTO indicator_trade_date (market_code, trade_date)
                VALUES ('KOSPI', ?), ('KOSPI', ?), ('KOSPI', ?) ON CONFLICT DO NOTHING
                """, D1, D2, D3);
        current10 = stock("910001", LocalDate.of(2026, 1, 2), null);
        departedInside20 = stock("910002", LocalDate.of(2026, 1, 2), D2);
        current30 = stock("910003", LocalDate.of(2026, 1, 2), null);
        departedBefore = stock("910004", LocalDate.of(2025, 1, 2), LocalDate.of(2025, 6, 1));
        Map<Long, Integer> per = Map.of(current10, 10, departedInside20, 20, current30, 30);
        for (Map.Entry<Long, Integer> entry : per.entrySet()) {
            for (LocalDate day : List.of(D1, D2, D3)) {
                bar(entry.getKey(), day);
                jdbcTemplate.update("""
                        INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                        VALUES (?, ?, ?, ?)
                        """, entry.getKey(), day, PerCalculator.CODE, new BigDecimal(entry.getValue()));
            }
        }
        bar(departedBefore, D1);
    }

    @AfterEach
    void cleanUp() {
        String inTickers = "(SELECT id FROM stock WHERE ticker IN ('910001','910002','910003','910004'))";
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        jdbcTemplate.update("DELETE FROM indicator_value WHERE stock_id IN " + inTickers);
        jdbcTemplate.update("DELETE FROM price_daily WHERE stock_id IN " + inTickers);
        jdbcTemplate.update("DELETE FROM universe_membership WHERE stock_id IN " + inTickers);
        jdbcTemplate.update("DELETE FROM stock WHERE ticker IN ('910001','910002','910003','910004')");
        jdbcTemplate.update("DELETE FROM indicator_trade_date WHERE market_code = 'KOSPI' AND trade_date IN (?, ?, ?)",
                D1, D2, D3);
    }

    @Test
    @DisplayName("IMPORTANT — ② 는 창의 날짜마다 그날 편입으로 다시 매긴다 — 과거 날짜의 낡은 순위가 닫힌다")
    void derivedLayerReranksEveryDateWithThatDaysMembers() {
        // 13:30은 최신일 하루만 파생층을 낸다. 과거 날짜에 남은 낡은 값(클래스주의 늦은 봉 · 정정 뒤)을
        // 흉내 낸다 — 이 값이 그대로 남으면 ② 가 그 날짜를 안 돈 것이다.
        pctl(departedInside20, D1, "0.9");

        BatchStatus status = job.rebuildDerived(D3, BatchExecutionRecorder.TriggeredBy.MANUAL);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        // D1: 셋이 모집단이라 10·20·30 → 0 · 0.5 · 1.
        assertThat(pctlOf(current10, D1)).isEqualByComparingTo("0");
        assertThat(pctlOf(departedInside20, D1)).as("낡은 0.9가 그날 편입 셋의 순위로 바뀐다")
                .isEqualByComparingTo("0.5");
        assertThat(pctlOf(current30, D1)).isEqualByComparingTo("1");
        // D3: 과거 구성원은 D2에 편출했다 — 모집단이 둘이고 그 종목의 순위는 없다.
        assertThat(pctlOf(current10, D3)).isEqualByComparingTo("0");
        assertThat(pctlOf(current30, D3)).isEqualByComparingTo("1");
        assertThat(pctlOf(departedInside20, D3)).isNull();
    }

    @Test
    @DisplayName("② 의 성공 단위는 거래일이다 — 대상이 거래일 수이고 하나도 안 빠진다")
    void derivedLayerCountsTradeDates() {
        job.rebuildDerived(D3, BatchExecutionRecorder.TriggeredBy.MANUAL);

        Map<String, Object> row = latestRun(IndicatorBackfillJob.DERIVED_JOB_NAME);
        // 테스트 DB에는 경계가 없으므로(봉이 보존 기간보다 적다) 시세의 첫 거래일부터 asOf까지가 창이다.
        Integer expected = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT trade_date) FROM price_daily
                WHERE trade_date BETWEEN (SELECT min(trade_date) FROM price_daily) AND ?
                """, Integer.class, D3);
        assertThat(((Number) row.get("target_count")).intValue()).isEqualTo(expected).isGreaterThanOrEqualTo(3);
        assertThat(((Number) row.get("success_count")).intValue()).isEqualTo(expected);
        assertThat((String) row.get("note")).contains("파생층 " + expected + "거래일");
    }

    @Test
    @DisplayName("② 는 원본층을 안 부른다 — 원본을 다시 쓰는 것은 ① 의 일이다")
    void derivedLayerDoesNotTouchTheOriginLayer() {
        job.rebuildDerived(D3, BatchExecutionRecorder.TriggeredBy.MANUAL);

        // 러너가 돌았다면 봉 하나로 값이 나오는 ADJ_CLOSE가 생긴다.
        assertThat(rowsOf(current10, "ADJ_CLOSE")).isZero();
        assertThat(rowsOf(departedInside20, "ADJ_CLOSE")).isZero();
    }

    @Test
    @DisplayName("IMPORTANT — ① 은 티커가 없으면 보존 창 편입 전원을 쓴다 — 과거 구성원이 들고 창 밖 편출은 빠진다")
    void originLayerCoversWindowMembers() {
        BatchStatus status = job.rewriteOriginLayer(D3, BatchExecutionRecorder.TriggeredBy.MANUAL, List.of());

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        Map<String, Object> row = latestRun(IndicatorBackfillJob.ORIGIN_JOB_NAME);
        assertThat(((Number) row.get("target_count")).intValue())
                .isEqualTo(stockRegistrar.windowMemberIds().size());
        assertThat(rowsOf(departedInside20, "ADJ_CLOSE"))
                .as("창 안에서 편출한 과거 구성원도 원본층을 다시 받는다 — 지금 편입만 돌면 옛 코드에 멈춘다")
                .isEqualTo(3);
        assertThat(rowsOf(current10, "ADJ_CLOSE")).isEqualTo(3);
        assertThat(rowsOf(departedBefore, "ADJ_CLOSE")).as("창 밖에서 편출한 종목은 대상이 아니다").isZero();
    }

    @Test
    @DisplayName("① 은 휴장일 asOf에도 그 전 마지막 봉까지 쓴다 — 주말·공휴일 손 실행이 헛돌지 않는다")
    void originLayerOnAClosedDayWritesUpToTheLastBar() {
        LocalDate saturday = LocalDate.of(2026, 7, 4);

        job.rewriteOriginLayer(saturday, BatchExecutionRecorder.TriggeredBy.MANUAL, List.of("910001"));

        Map<String, Object> row = latestRun(IndicatorBackfillJob.ORIGIN_JOB_NAME);
        assertThat(((Number) row.get("target_count")).intValue()).as("티커로 좁힌 대상").isEqualTo(1);
        assertThat(rowsOf(current10, "ADJ_CLOSE")).isEqualTo(3);
    }

    private long stock(String ticker, LocalDate from, LocalDate to) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?) RETURNING id
                """, Long.class, ticker, "종목" + ticker);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to)
                VALUES (?, ?, ?, ?)
                """, UNIVERSE, id, from, to);
        return id;
    }

    private void bar(long stockId, LocalDate day) {
        jdbcTemplate.update("""
                INSERT INTO price_daily
                    (stock_id, trade_date, open, high, low, close, adj_close, adj_factor,
                     volume, trade_value, listed_shares)
                VALUES (?, ?, 1000, 1000, 1000, 1000, 1000, 1, 100, 100000, 1000000)
                """, stockId, day);
    }

    private void pctl(long stockId, LocalDate day, String value) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                """, stockId, day, PerCalculator.CODE + "_PCTL", new BigDecimal(value));
    }

    private BigDecimal pctlOf(long stockId, LocalDate day) {
        List<BigDecimal> found = jdbcTemplate.queryForList("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND trade_date = ? AND indicator_code = ?
                """, BigDecimal.class, stockId, day, PerCalculator.CODE + "_PCTL");
        return found.isEmpty() ? null : found.get(0);
    }

    private int rowsOf(long stockId, String code) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM indicator_value WHERE stock_id = ? AND indicator_code = ?
                """, Integer.class, stockId, code);
    }

    private Map<String, Object> latestRun(String jobName) {
        return jdbcTemplate.queryForMap("""
                SELECT target_count, success_count, failure_count, note FROM batch_execution
                WHERE job_name = ? ORDER BY id DESC LIMIT 1
                """, jobName);
    }
}
