package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.CorporateActionCollector;
import com.stockscreener.pipeline.collector.PriceCalendar;
import com.stockscreener.pipeline.collector.PriceWriter;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.support.FakeCorporateActionDataSource;
import com.stockscreener.support.TestDatabaseGuard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기업 행위 수집 잡을 검증한다(ADR-0049).
 *
 * <p>고정하려는 것이 셋이고 전부 <b>어겨도 초록인</b> 종류다. <b>키가 없을 때 0건으로 성공하지
 * 않는가</b>(그러면 수정계수가 영영 1로 남는데 로그도 알림도 없다), <b>봉이 없는 종목에 헛콜을
 * 태우지 않는가</b>, 그리고 <b>이 잡이 계수를 건드리지 않는가</b>이다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 종목 단위 격리가 {@code REQUIRES_NEW}로
 * 커밋된 트랜잭션을 요구한다(ADR-0007). 대신 {@code @AfterEach}로 직접 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, CorporateActionJobTest.FakeCorporateActionConfig.class})
class CorporateActionJobTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 12);
    private static final String UNIVERSE = "KOSPI200";

    @Autowired
    private CorporateActionJob job;

    @Autowired
    private FakeCorporateActionDataSource fakeSource;

    @Autowired
    private CorporateActionCollector collector;

    @Autowired
    private StockRegistrar stockRegistrar;

    @Autowired
    private PriceCalendar priceCalendar;

    @Autowired
    private BatchExecutions batchExecutions;

    @Autowired
    private PriceWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long memberId;

    @BeforeEach
    void seed() {
        cleanUp();
        memberId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, UNIVERSE, memberId, AS_OF.minusYears(1));
        fakeSource.fetchedStockIds.clear();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        jdbcTemplate.update("DELETE FROM corporate_action");
        jdbcTemplate.update("DELETE FROM price_daily");
        jdbcTemplate.update("DELETE FROM universe_membership");
        jdbcTemplate.update("DELETE FROM stock WHERE ticker IN ('005930', 'NVDA', '000660')");
        jdbcTemplate.update("DELETE FROM indicator_trade_date WHERE market_code = 'KOSPI' AND trade_date IN (?, ?)",
                AS_OF.minusMonths(6), AS_OF);
    }

    @Test
    @DisplayName("공시를 원장에 적는다 — 창 기본값이 저장된 시세 전 구간이라 옛 사건도 든다")
    void recordsDisclosedActionsIntoTheLedger() {
        seedBars();
        fakeSource.with(memberId, split("2026-02-04"));

        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(ledgerRowCount()).isEqualTo(1);
        assertThat(statusOf("2026-02-04")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("보존 창 안에서 편출된 종목도 원장에 적는다 — 안 적으면 과거 기준일에 고친 모체와 안 고친 모체가 섞인다")
    void recordsActionsOfPastMembersInsideTheRetentionWindow() {
        // 창: 지표 거래일 [AS_OF − 6개월, AS_OF]. 편입 [AS_OF − 2년, AS_OF − 1개월)은 창과 겹친다 — 분사 설계 반증의
        // MIDD(2026-07-07 분사 · 편입 [2025-09-24, 2026-08-14)) 모양이다.
        jdbcTemplate.update("INSERT INTO indicator_trade_date (market_code, trade_date) VALUES ('KOSPI', ?), ('KOSPI', ?)"
                + " ON CONFLICT DO NOTHING", AS_OF.minusMonths(6), AS_OF);
        long pastId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '000660', 'SK하이닉스') RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to)
                VALUES (?, ?, ?, ?)
                """, UNIVERSE, pastId, AS_OF.minusYears(2), AS_OF.minusMonths(1));
        writer.write(pastId, List.of(bar("2026-02-02", "1000"), bar("2026-02-04", "1020")));
        fakeSource.with(pastId, split("2026-02-04"));

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM corporate_action WHERE stock_id = ?", Integer.class, pastId))
                .as("지금 편입만 물으면 0이다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("이 잡은 수정계수를 건드리지 않는다 — rebuild()는 되돌릴 수 없는 유일한 지점이다")
    void neverTouchesTheAdjustmentFactor() {
        // 픽스처 조건이 중요하다. APPROVED가 될 非UNKNOWN 사건 하나와 **그보다 이른 봉**이
        // 있어야 rebuild()가 실제로 값을 바꾼다 — 없으면 몰래 불러도 이 검사가 초록이다.
        seedBars();
        fakeSource.with(memberId, split("2026-02-04"));

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(statusOf("2026-02-04")).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE stock_id = ? AND adj_factor <> 1",
                Integer.class, memberId))
                .as("계수가 바뀌었다면 이 잡이 rebuild()를 불렀다는 뜻이다")
                .isZero();
    }

    @Test
    @DisplayName("담당 소스가 없으면 종목 루프를 돌기 전에 실패한다 — 0건 성공이면 계수가 영영 1로 남는다")
    void failsBeforeTheLoopWhenNoSourceIsRegistered() {
        seedBars();
        DataSourceRegistry empty = new DataSourceRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(),
                List.of(),
                List.of());
        CorporateActionJob jobWithoutSource = new CorporateActionJob(
                collector, stockRegistrar, priceCalendar, empty, batchExecutions);

        BatchStatus status = jobWithoutSource.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(status).isEqualTo(BatchStatus.FAILED);
        // 루프를 돌았다면 종목마다 NOT_FOUND가 실패로 기록됐을 것이다. 그 0이
        // "루프 앞에서 막았다"의 실제 증거다 — 소스 호출 수로는 구분되지 않는다.
        assertThat(failureCount()).isZero();
        assertThat(ledgerRowCount()).isZero();
    }

    @Test
    @DisplayName("봉이 없는 종목에는 콜을 태우지 않는다 — 어댑터가 우리 시계열에서 값을 유도한다")
    void doesNotCallTheSourceForStocksWithoutBars() {
        // 봉은 다른 종목에만 있다. 전역 가드는 통과하고 종목 가드가 걸려야 한다.
        long other = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '000660', 'SK하이닉스')
                RETURNING id
                """, Long.class);
        writer.write(other, List.of(bar("2026-02-02", "1000")));

        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(fakeSource.fetchedStockIds).isEmpty();

        jdbcTemplate.update("DELETE FROM price_daily WHERE stock_id = ?", other);
        jdbcTemplate.update("DELETE FROM stock WHERE id = ?", other);
    }

    @Test
    @DisplayName("저장된 시세가 하나도 없으면 소스를 부르지 않고 끝낸다 — 시세가 먼저다")
    void doesNothingWhenNoPriceIsStored() {
        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(fakeSource.fetchedStockIds).isEmpty();
    }

    @Test
    @DisplayName("티커가 하나도 매칭되지 않으면 조용히 성공하지 않고 실패한다")
    void failsLoudlyWhenNoTickerMatches() {
        seedBars();

        assertThatThrownBy(() -> job.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of("999999"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999");

        assertThat(fakeSource.fetchedStockIds).isEmpty();
    }

    @Test
    @DisplayName("창의 시작이 대상일보다 늦으면 즉시 실패하고 그 실패가 기록된다")
    void rejectsAnInvertedWindow() {
        seedBars();

        assertThatThrownBy(() -> job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                AS_OF.plusDays(1), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);

        // IMPORTANT — 검증이 실행 행보다 앞에 있으면 batch_execution에 아무 흔적도 안 남는다.
        // 관리자 화면으로 띄운 경우 던진 예외를 받을 호출부가 없어(런처가 비동기로 제출한다)
        // 화면에는 202 뒤 아무 일도 안 일어난 것으로 보인다.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM batch_execution WHERE job_name = ?
                ORDER BY id DESC LIMIT 1
                """, String.class, CorporateActionJob.JOB_NAME))
                .as("나쁜 창으로 끝난 실행이 기록되지 않았다")
                .isEqualTo("FAILED");
    }

    /** 불연속이 없는 봉 셋. 휴리스틱이 끼어들지 않아 공시 경로만 재게 된다. */
    // ── 주식수가 없어 못 올린 후보: 시장별 한 줄 ──────────────────────────────
    //
    // MUST: 이 셋이 함께 있어야 뜻이 있다. 「줄이 뜬다」만 보면 **집계가 0을 찍어도 초록**이라
    // 그 줄이 실제로 detectCandidates 경로를 태웠는지 알 수 없다 — 실제로 한 번 그 설계로
    // 갈 뻔했고 컴파일도 테스트도 전부 초록이었다.

    /** 로그를 문자열로 잡는다. 문구를 바꿔도 붉는 곳이 여기뿐이다. */
    private ListAppender<ILoggingEvent> attach(Class<?> type, Level level) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        logger.setLevel(level);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private List<String> linesContaining(ListAppender<ILoggingEvent> appender, String needle) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(needle))
                .toList();
    }

    private void seedDiscontinuityWithoutShares() {
        // 2.5배 점프라 PriceDiscontinuity의 1.30/0.70 밖이고, listed_shares가 없어
        // 종류도 비율도 못 낸다 — 그것이 이 줄이 세는 대상이다.
        writer.write(memberId, List.of(
                barNoShares("2026-02-02", "1000"), barNoShares("2026-02-03", "2500")));
    }

    @Test
    @DisplayName("주식수가 없어 못 올린 후보를 시장마다 한 줄로 적는다 — 종목마다가 아니다")
    void reportsSkippedCandidatesOncePerMarket() {
        ListAppender<ILoggingEvent> job_ = attach(CorporateActionJob.class, Level.INFO);
        seedDiscontinuityWithoutShares();

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(linesContaining(job_, "상장주식수가 없어 후보로 올리지 않은"))
                .as("시장이 하나이므로 한 줄이어야 한다")
                .singleElement()
                .satisfies(line -> {
                    assertThat(line).contains("[KOSPI]");
                    // MUST: 수가 실제로 흘러왔는지 본다. 0이면 배선이 끊긴 것이고,
                    // 그때도 「줄이 하나」는 참이라 이 단언이 없으면 못 잡는다.
                    assertThat(line).contains("1건");
                });
    }

    @Test
    @DisplayName("결손이 없으면 그 줄이 아예 안 뜬다 — 신호의 형태가 「부재 → 출현」이다")
    void staysSilentWhenNothingWasSkipped() {
        ListAppender<ILoggingEvent> job_ = attach(CorporateActionJob.class, Level.INFO);
        seedBars();

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(linesContaining(job_, "상장주식수가 없어 후보로 올리지 않은"))
                .as("0인 시장을 찍으면 「늘 있는 줄 안의 숫자 변화」가 되어 신호가 죽는다")
                .isEmpty();
    }

    @Test
    @DisplayName("종목별 줄은 지운 것이 아니라 내린 것이다 — DEBUG면 stockId가 돌아온다")
    void perStockLineComesBackAtDebug() {
        ListAppender<ILoggingEvent> recalc =
                attach(com.stockscreener.pipeline.collector.AdjustmentRecalculator.class,
                        Level.DEBUG);
        seedDiscontinuityWithoutShares();

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(linesContaining(recalc, "상장주식수가 없어 후보로 올리지 않은"))
                .as("LOG_ADJUSTMENT=DEBUG가 종목 귀속을 되살리는 경로다")
                .singleElement()
                .satisfies(line -> assertThat(line).contains("stockId=" + memberId));
    }

    @Test
    @DisplayName("담지 않은 기업 행위를 시장마다 한 줄로 적되 종류를 키로 남긴다 — 총합으로 접지 않는다")
    void rollsUpNotStoredByTypePerMarket() {
        ListAppender<ILoggingEvent> job_ = attach(CorporateActionJob.class, Level.INFO);
        seedBars();
        fakeSource.notStoredByStock.put(memberId,
                java.util.Map.of("cash_dividend", 4, "spinoff", 1));

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(linesContaining(job_, "담지 않은 기업 행위"))
                .as("심볼마다가 아니라 시장마다 한 줄이어야 한다")
                .singleElement()
                .satisfies(line -> {
                    assertThat(line).contains("[KOSPI]");
                    // MUST: 종류가 키로 남아야 한다. phase 기준 P23 축③이 「합계로 적으면
                    // 그 구분이 사라진다」고 못박으므로 단일 총합(=5)으로 접으면 이 단언이 붉는다.
                    assertThat(line).contains("cash_dividend=4");
                    assertThat(line).contains("spinoff=1");
                });
    }

    @Test
    @DisplayName("안 담은 것이 없으면 그 줄이 아예 안 뜬다 — 「사건이 없다」와 구분된다")
    void staysSilentWhenNothingWasSkippedByType() {
        ListAppender<ILoggingEvent> job_ = attach(CorporateActionJob.class, Level.INFO);
        seedBars();

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(linesContaining(job_, "담지 않은 기업 행위")).isEmpty();
    }

    private static RawPriceBar barNoShares(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null, null, false);
    }

    private void seedBars() {
        writer.write(memberId, List.of(
                bar("2026-02-02", "1000"), bar("2026-02-03", "1010"), bar("2026-02-04", "1020")));
    }

    private static CorporateAction split(String date) {
        return new CorporateAction(LocalDate.parse(date), CorporateAction.Type.SPLIT,
                new BigDecimal("5"), CorporateAction.DetectionSource.SOURCE_DISCLOSURE);
    }

    private static RawPriceBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null,
                new BigDecimal("1000000"), false);
    }

    private int ledgerRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM corporate_action", Integer.class);
    }

    @Test
    @DisplayName("유니버스를 손으로 주면 비활성이어도 그 종목을 고른다 — 활성 조인을 안 탄다")
    void picksTheChosenUniverseEvenWhenItIsInactive() {
        seedBars();
        long usStockId = seedInactiveUniverseMember();

        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                LocalDate.of(2020, 1, 2), List.of(), "US_LISTED");

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(fakeSource.fetchedStockIds)
                .as("US_LISTED는 비활성이라 활성 유니버스 경로로는 하나도 안 나온다")
                .containsExactly(usStockId);
        assertThat(fakeSource.fetchedStockIds)
                .as("활성 유니버스의 국내 종목은 대상이 아니다 — 손으로 고른 유니버스 하나만 본다")
                .doesNotContain(memberId);
    }

    @Test
    @DisplayName("고른 유니버스에 대상이 0건이면 예외다 — 조용한 0건은 「적재할 것이 없다」와 같다")
    void failsWhenTheChosenUniverseHasNoTargets() {
        seedBars();

        assertThatThrownBy(() -> job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                null, List.of(), "SP500"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대상이 하나도 없다");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM batch_execution", Integer.class))
                .as("대상을 못 고른 실행은 행을 열지 않는다")
                .isZero();
    }

    /** 비활성 유니버스에 든 미국 종목 하나. 봉을 함께 넣는다 — 없으면 수집기가 건너뛴다. */
    private long seedInactiveUniverseMember() {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('US', 'NVDA', 'NVIDIA')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('US_LISTED', ?, ?)
                """, id, AS_OF.minusYears(1));
        writer.write(id, List.of(bar("2026-02-02", "1000"), bar("2026-02-03", "1010")));
        return id;
    }

    private String statusOf(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM corporate_action WHERE effective_date = ?",
                String.class, LocalDate.parse(date));
    }

    private int failureCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_execution_failure", Integer.class);
    }

    @TestConfiguration
    static class FakeCorporateActionConfig {

        @Bean
        FakeCorporateActionDataSource fakeCorporateActionDataSource() {
            return new FakeCorporateActionDataSource("KOSPI", "KOSDAQ", "US");
        }
    }
}
