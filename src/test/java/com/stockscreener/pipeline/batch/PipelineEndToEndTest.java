package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.UniverseReconciler;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.PriceDataSource;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.support.FakePriceDataSource;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유니버스 리밸런싱 → 초기 적재 → 일일 갱신의 전 과정을 Fake 소스로 검증한다.
 *
 * <p>API 키 없이 파이프라인 전체가 도는지를 확인하는 것이 목적이다. 실제 어댑터가 붙어도
 * 이 테스트는 그대로 유효하다 — 포트 계약만 쓰기 때문이다.
 *
 * <p>{@code @Transactional}을 쓰지 않는다. 종목 단위 격리가 커밋된 트랜잭션을 요구한다.
 */
/*
 * 실제 벤더 어댑터를 제외하고 돈다. 이 테스트의 관심사는 Fake 소스로 파이프라인 전체가
 * 도는지이며, 실제 어댑터가 함께 등록되면 DataSourceRegistry가 같은 시장에 두 소스가
 * 있다고 보고 기동에 실패한다 — 그 실패 자체는 레지스트리가 의도대로 동작한다는 증거다.
 * 제외는 아래 FakeSourceConfig의 @ComponentScan 필터가 담당한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, PipelineEndToEndTest.FakeSourceConfig.class})
class PipelineEndToEndTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);

    /** 시드 CSV의 첫 종목. 시드 파일이 바뀌면 함께 바뀐다. */
    private static final String SEEDED_TICKER = "005930";

    @Autowired
    private UniverseRebalanceJob universeRebalanceJob;

    @Autowired
    private InitialLoadJob initialLoadJob;

    @Autowired
    private DailyUpdateJob dailyUpdateJob;

    @Autowired
    private IndicatorBackfillJob indicatorBackfillJob;

    @Autowired
    private BatchExecutions batchExecutions;

    @Autowired
    private UniverseReconciler reconciler;

    @Autowired
    private FakePriceDataSource fakePriceSource;

    @Autowired
    private RecordingAlertPort alertPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        cleanUp();
        // 미국은 이 클래스의 사정권 밖이다 — 지수 구성을 주는 어댑터가 @ConditionalOnApiKey라
        // 키 없는 테스트 환경에서 등록되지 않는다(FakeSourceConfig가 대신 두는 것은 **시세**
        // 소스뿐이다). 켜 둔 채로 리밸런싱을 돌리면 미국 하나가 실패해 잡 전체가
        // PARTIAL_SUCCESS가 되고, 그것은 이 테스트가 재려는 것과 무관한 붉음이다.
        // 되돌리는 것은 cleanUp()이 맡는다 — 다른 클래스는 마이그레이션이 정한 상태를 본다.
        jdbcTemplate.update("UPDATE universe SET is_active = false WHERE code = 'US_TOP1000'");
        alertPort.events.clear();
        // 컨텍스트가 캐시되므로 Fake의 상태가 테스트 간에 새어나간다.
        fakePriceSource.reset();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        // IMPORTANT — stock보다 먼저 지운다. 일일 갱신이 지표까지 계산하므로(DailyUpdateJob의
        // INDICATOR 단계) 여기 행이 남고, 그러면 DELETE FROM stock이 FK 위반으로 실패한다.
        // 정리가 중단되면 종목이 커밋된 채 남아 KOSPI/005930을 넣는 다른 테스트가 전부 깨지는데,
        // 그 증상은 이 테스트와 무관해 보인다.
        //
        // 오랫동안 드러나지 않았던 이유: 픽스처의 봉이 몇 개뿐이라 계산 가능한 지표가 없었다.
        // ADJ_CLOSE가 봉 하나로 값을 내는 첫 지표라 그때 비로소 이 구멍이 열렸다.
        jdbcTemplate.update("DELETE FROM indicator_value");
        jdbcTemplate.update("DELETE FROM price_daily");
        jdbcTemplate.update("DELETE FROM market_index_daily");
        jdbcTemplate.update("DELETE FROM universe_membership");
        jdbcTemplate.update("DELETE FROM stock");
        // reset()이 끈 것을 되돌린다. 이 클래스가 DB에 남기는 유일한 전역 상태다.
        jdbcTemplate.update("UPDATE universe SET is_active = true WHERE code = 'US_TOP1000'");
    }

    @Test
    @DisplayName("리밸런싱이 시드에서 종목을 만들고 유니버스에 편입한다")
    void rebalanceCreatesStocksFromSeed() {
        BatchStatus status = universeRebalanceJob.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(reconciler.currentMembers("KOSPI200")).isNotEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stock WHERE ticker = ?", Integer.class, SEEDED_TICKER))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("리밸런싱은 멱등하다 — 두 번 돌려도 종목과 편입 행이 늘지 않는다")
    void rebalanceIsIdempotent() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        int stocksAfterFirst = countStocks();
        int membershipsAfterFirst = countMemberships();

        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        assertThat(countStocks()).isEqualTo(stocksAfterFirst);
        assertThat(countMemberships()).isEqualTo(membershipsAfterFirst);
    }

    @Test
    @DisplayName("초기 적재가 편입 종목의 시세를 받아온다")
    void initialLoadFetchesHistory() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        long stockId = stockIdOf(SEEDED_TICKER);
        fakePriceSource.withBars(stockId, List.of(
                bar("2026-08-03", "70000"), bar("2026-08-04", "71000")));

        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE stock_id = ?", Integer.class, stockId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("초기 적재는 이미 적재된 종목을 건너뛴다 — 쿼터 소진 후 재실행의 근거다")
    void initialLoadSkipsAlreadyLoadedStocks() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        long stockId = stockIdOf(SEEDED_TICKER);
        fakePriceSource.withBars(stockId, List.of(bar("2026-08-03", "70000")));

        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        fakePriceSource.fetchedStockIds.clear();
        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        assertThat(fakePriceSource.fetchedStockIds)
                .as("이미 이력이 있는 종목은 다시 호출하지 않는다")
                .doesNotContain(stockId);
    }

    @Test
    @DisplayName("기간을 늘려 재적재하면 이미 적재된 종목도 다시 받는다")
    void reloadsExistingStocksWhenRangeExpands() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        long stockId = stockIdOf(SEEDED_TICKER);
        fakePriceSource.withBars(stockId, List.of(bar("2026-08-03", "70000")));

        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        fakePriceSource.fetchedStockIds.clear();

        // 2년치를 받아둔 상태에서 7년으로 넓힌다. 기본 경로(NOT EXISTS)로는 대상이 0건이 되어
        // "적재할 종목이 없다"며 성공으로 끝나는데, 기간을 늘렸는데 아무 일도 일어나지 않고
        // 성공으로 보이는 것이 그 결함의 성질이다.
        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                new InitialLoadJob.LoadRange(7, 7, true));

        assertThat(fakePriceSource.fetchedStockIds)
                .as("확장 적재는 이미 이력이 있는 종목도 대상에 넣어야 한다")
                .contains(stockId);
    }

    @Test
    @DisplayName("확장 적재가 더 긴 기간을 요청한다")
    void expandedLoadRequestsLongerRange() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        long stockId = stockIdOf(SEEDED_TICKER);
        fakePriceSource.withBars(stockId, List.of(bar("2026-08-03", "70000")));

        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                new InitialLoadJob.LoadRange(7, 7, true));

        assertThat(fakePriceSource.lastFrom)
                .as("7년을 요청하면 조회 시작일이 그만큼 앞이어야 한다")
                .isEqualTo(AS_OF.minusYears(7));
    }

    @Test
    @DisplayName("일일 갱신이 편입 종목만 수집한다")
    void dailyUpdateCollectsCurrentMembers() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        long stockId = stockIdOf(SEEDED_TICKER);
        fakePriceSource.withBars(stockId, List.of(bar("2026-08-05", "72000")));

        BatchStatus status = dailyUpdateJob.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT close FROM price_daily WHERE stock_id = ? AND trade_date = ?",
                BigDecimal.class, stockId, AS_OF)).isEqualByComparingTo("72000");
    }

    @Test
    @DisplayName("주말에는 아무것도 하지 않는다 — 매 주말 850건의 실패 알림이 오면 안 된다")
    void dailyUpdateSkipsWeekend() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        alertPort.events.clear();

        LocalDate saturday = LocalDate.of(2026, 8, 8);
        BatchStatus status = dailyUpdateJob.run(
                saturday, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(alertPort.events).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_execution WHERE job_name = ? AND target_date = ?",
                Integer.class, DailyUpdateJob.JOB_NAME, saturday)).isZero();
    }

    @Test
    @DisplayName("한 종목도 안 들어오면 성공으로 끝나되 알린다 — 배치 시각이 소스보다 이르면 이것만 드러난다")
    void dailyUpdateAlertsWhenNothingArrived() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        // 봉을 하나도 등록하지 않는다. 빈 응답은 예외가 아니라 0행이다.
        alertPort.events.clear();

        BatchStatus status = dailyUpdateJob.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        // IMPORTANT — 상태가 SUCCESS인 것이 요점이다. 빈 응답은 실패로 세지 않으므로
        // 관리자 화면도 초록이고, 이 알림이 없으면 아무 데도 흔적이 남지 않는다.
        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE trade_date = ?", Integer.class, AS_OF))
                .isZero();
        assertThat(alertPort.events).hasSize(1);
        assertThat(alertPort.events.get(0).severity()).isEqualTo(AlertSeverity.WARNING);
    }

    @Test
    @DisplayName("이력이 이미 쌓인 뒤에도 0건 알림이 뜬다 — 지표 행이 시세 0행을 덮으면 안 된다")
    void dailyUpdateAlertsWhenNothingArrivedEvenAfterHistoryExists() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        long stockId = stockIdOf(SEEDED_TICKER);
        fakePriceSource.withBars(stockId, List.of(
                bar("2026-08-03", "70000"), bar("2026-08-04", "71000")));
        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        // 소스가 대상일치를 아직 안 내놓은 상황. 이력은 DB에 그대로 있다.
        fakePriceSource.reset();
        alertPort.events.clear();

        BatchStatus status = dailyUpdateJob.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE trade_date = ?", Integer.class, AS_OF))
                .as("대상일의 시세는 한 건도 들어오지 않았다")
                .isZero();

        // IMPORTANT — 이 단언이 이 테스트의 존재 이유다. 지표 단계가 이력을 재계산해 행을
        // 남기므로 합계는 0이 아니다. 합계로 판정하면 아래 알림이 오지 않는데, 그 상태가
        // 실제로 2026-08-07 종가 결손을 조용히 넘겼다.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT written_row_count FROM batch_execution
                WHERE job_name = ? AND target_date = ? ORDER BY id DESC LIMIT 1
                """, Long.class, DailyUpdateJob.JOB_NAME, AS_OF))
                .as("지표 행이 쌓여 합계는 0이 아니다 — 그래서 합계로 판정하면 안 된다")
                .isPositive();

        assertThat(alertPort.events).hasSize(1);
        assertThat(alertPort.events.get(0).severity()).isEqualTo(AlertSeverity.WARNING);
    }

    @Test
    @DisplayName("이미 수집한 날짜를 다시 돌려도 0건 알림이 오지 않는다")
    void dailyUpdateDoesNotFalselyAlertOnRerun() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        // 지수 하나가 아니라 편입 전 종목에 심는다 — 0건 판정이 시장별이라, 한 지수만 심으면
        // 나머지 지수의 결손이 알림으로 올라와 아래 "비어 있다"가 깨진다.
        for (Long member : allActiveMembers()) {
            fakePriceSource.withBars(member, List.of(bar("2026-08-05", "72000")));
        }
        seedMarketIndexes(AS_OF);
        dailyUpdateJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);
        alertPort.events.clear();

        dailyUpdateJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.ADMIN);

        // 여기에 "PriceWriter를 값이 같으면 0행으로 바꾸지 마라"는 MUST NOT이 있었다.
        // **풀렸다** — 0건 판정이 더 이상 이 실행이 쓴 행 수를 보지 않고 대상일에 행이 있는지를
        // DB에 묻기 때문이다(ADR-0042). 쓰기가 멱등이어도 판정은 같은 답을 낸다.
        // 재실행은 관리자의 정상 경로이므로(ADR-0029) 그때 오탐이 없어야 한다는 것만 남는다.
        assertThat(alertPort.events)
                .as("같은 날짜를 다시 수집한 것은 결측이 아니다")
                .isEmpty();
    }

    @Test
    @DisplayName("창이 과거 날짜를 채워도 대상일이 비면 알린다 — 이번엔 PRICE 단계가 스스로를 덮는다")
    void dailyUpdateAlertsWhenOnlyOlderDaysArrived() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        long stockId = stockIdOf(SEEDED_TICKER);
        // 창 안에 있지만 대상일은 아닌 날짜만 준다. 이 실행이 이 봉들을 **실제로 적재한다** —
        // 그래서 PRICE 단계의 쓰기 행 수가 0이 아니다.
        fakePriceSource.withBars(stockId, List.of(
                bar("2026-08-03", "70000"), bar("2026-08-04", "71000")));
        alertPort.events.clear();

        BatchStatus status = dailyUpdateJob.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE trade_date = ?", Integer.class, AS_OF))
                .as("대상일은 비어 있다")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE trade_date < ?", Integer.class, AS_OF))
                .as("창 안의 과거 날짜는 적재됐다 — 이것이 판정을 덮을 수 있는 것이다")
                .isPositive();

        // IMPORTANT — 이 단언이 이 테스트의 존재 이유다. 판정을 PRICE 단계의 쓰기 행 수로 하면
        // 이 값이 0이 아니므로 아래 알림이 오지 않는다. 앞선 테스트가 막는 것은 INDICATOR가
        // 덮는 경우이고, 여기서 막는 것은 **PRICE가 스스로를 덮는 경우**다.
        assertThat(alertPort.events)
                .as("대상일이 비었는데 과거 재적재가 알림을 죽이면 결손이 조용히 지나간다")
                .hasSize(1);
        assertThat(alertPort.events.get(0).severity()).isEqualTo(AlertSeverity.WARNING);
    }

    @Test
    @DisplayName("대상일에 봉이 없으면 백분위는 실제로 적재된 최신 거래일로 돈다")
    void percentilesUseTheLatestActuallyLoadedTradeDate() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        List<Long> members = reconciler.currentMembers("KOSPI200");
        // 백분위는 population >= 2라 두 종목 이상이어야 값이 난다. 하나만 심으면 0행이 나오고
        // 이 테스트가 조용히 아무것도 검사하지 않게 된다.
        assertThat(members).hasSizeGreaterThan(1);

        LocalDate loaded = LocalDate.of(2026, 8, 4);
        int price = 70000;
        for (Long member : members) {
            fakePriceSource.withBars(member, List.of(bar(loaded.toString(), String.valueOf(price))));
            price += 1000;
        }

        // 백분위 원본(PER·PBR·ROE·…)은 전부 재무나 긴 시계열을 요구해 봉 하나로는 나지 않는다.
        // 검사 대상은 "어느 날짜로 백분위를 도느냐"이므로 원본을 직접 심어 그 축만 남긴다.
        int per = 10;
        for (Long member : members) {
            jdbcTemplate.update("""
                    INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value, updated_at)
                    VALUES (?, ?, 'PER', ?, now())
                    """, member, loaded, new BigDecimal(per));
            per += 1;
        }
        alertPort.events.clear();

        // 대상일(AS_OF)은 비어 있다. 공휴일에 도는 배치와 같은 상황이다.
        dailyUpdateJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        List<LocalDate> percentileDates = jdbcTemplate.queryForList(
                "SELECT DISTINCT trade_date FROM indicator_value WHERE indicator_code = 'PER_PCTL'",
                LocalDate.class);

        assertThat(percentileDates)
                .as("대상일로 계산하면 그날 원본이 없어 0행이 된다 — 지표는 있는데 백분위만 "
                        + "없는 날이 화면의 기준 일자가 되고, 백분위 조건이 전부 판정 불가가 된다")
                .containsExactly(loaded);
    }

    @Test
    @DisplayName("미국 봉이 그날 있어도 국내 결손은 알리고, 국내 지수 결손은 알리지 않는다")
    void marketScopedAlertsDoNotBleedAcrossMarkets() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        // 국내는 대상일에 한 봉도 없고, 미국만 그날 봉이 있다. 국내 휴장·미국 개장일의 모양이다.
        // 국내 지수도 없다 — 국내가 쉬었으니 그것이 정상이고, 알리면 안 되는 것이 이 테스트다.
        long usStockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('US', 'AAPL', 'Apple Inc.')
                ON CONFLICT (market_code, ticker) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close,
                                         adj_close, adj_factor, volume)
                VALUES (?, ?, 100, 100, 100, 100, 100, 1, 1000)
                """, usStockId, AS_OF);
        alertPort.events.clear();

        dailyUpdateJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(alertPort.events)
                .as("전 시장 합계로 세면 미국 봉 하나가 국내 결손 알림을 죽인다(거짓 음성)")
                .extracting(AlertEvent::title)
                .contains("daily-update — 대상일 시세가 한 건도 없다");
        assertThat(alertPort.events)
                .as("같은 합계가 지수 쪽에서는 반대로 작동한다 — 국내가 쉰 날 지수가 없는 것은 "
                        + "정상인데 「시세는 들어왔다」가 참이 되어 상시 오발화한다(거짓 양성)")
                .extracting(AlertEvent::title)
                .doesNotContain("daily-update — 대상일 지수가 없다");
    }

    @Test
    @DisplayName("일부 종목이 실패해도 나머지가 수집되고 요약 알림 한 건이 간다")
    void dailyUpdateIsolatesFailures() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        // 지수 하나가 아니라 편입 전 종목이다 — 위 allActiveMembers의 javadoc이 이유다.
        List<Long> members = allActiveMembers();
        long failing = members.get(0);
        for (Long member : members) {
            fakePriceSource.withBars(member, List.of(bar("2026-08-05", "1000")));
        }
        fakePriceSource.failingWith(failing, new DataSourceException(
                DataSourceException.Kind.TRANSIENT, "fake-price", "일시적 오류"));
        seedMarketIndexes(AS_OF);
        alertPort.events.clear();

        BatchStatus status = dailyUpdateJob.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(status).isEqualTo(BatchStatus.PARTIAL_SUCCESS);
        // 실패한 종목만 빠지고 나머지는 저장된다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE trade_date = ?", Integer.class, AS_OF))
                .isEqualTo(members.size() - 1);
        // 알림은 종목마다가 아니라 요약 한 건이다.
        assertThat(alertPort.events).hasSize(1);
    }

    @Test
    @DisplayName("실패한 종목만 재실행하고 해결로 표시한다")
    void retriesOnlyFailedStocks() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        // 지수 하나가 아니라 편입 전 종목이다 — 위 allActiveMembers의 javadoc이 이유다.
        List<Long> members = allActiveMembers();
        long failing = members.get(0);
        for (Long member : members) {
            fakePriceSource.withBars(member, List.of(bar("2026-08-05", "1000")));
        }
        fakePriceSource.failingWith(failing, new DataSourceException(
                DataSourceException.Kind.TRANSIENT, "fake-price", "일시적 오류"));
        dailyUpdateJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        long executionId = jdbcTemplate.queryForObject("""
                SELECT id FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1
                """, Long.class, DailyUpdateJob.JOB_NAME);

        // 이번엔 성공하게 만든다.
        fakePriceSource.clearFailure(failing);
        fakePriceSource.fetchedStockIds.clear();

        dailyUpdateJob.retryFailures(executionId, AS_OF);

        assertThat(fakePriceSource.fetchedStockIds)
                .as("재실행은 실패한 종목만 호출한다 — 성공 종목의 API를 다시 부르지 않는다")
                .containsExactly(failing);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM batch_execution_failure
                WHERE execution_id = ? AND NOT resolved
                """, Integer.class, executionId)).isZero();
    }

    @Test
    @DisplayName("재실행에서 또 실패하면 해결로 표시하지 않는다 — 실패가 조용히 사라지면 안 된다")
    void keepsFailureUnresolvedWhenRetryFailsAgain() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        // 지수 하나가 아니라 편입 전 종목이다 — 위 allActiveMembers의 javadoc이 이유다.
        List<Long> members = allActiveMembers();
        long failing = members.get(0);
        for (Long member : members) {
            fakePriceSource.withBars(member, List.of(bar("2026-08-05", "1000")));
        }
        fakePriceSource.failingWith(failing, new DataSourceException(
                DataSourceException.Kind.TRANSIENT, "fake-price", "일시적 오류"));
        dailyUpdateJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        long executionId = jdbcTemplate.queryForObject("""
                SELECT id FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1
                """, Long.class, DailyUpdateJob.JOB_NAME);

        // 이번에도 실패하게 둔다 — 소스가 계속 죽어 있는 상황이다.
        dailyUpdateJob.retryFailures(executionId, AS_OF);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM batch_execution_failure
                WHERE execution_id = ? AND stock_id = ? AND NOT resolved
                """, Integer.class, executionId, failing))
                .as("두 번 실패한 종목은 미해결로 남아야 한다. 해결로 표시하면 관리자의 "
                        + "실패 목록에서 사라져 다시는 재실행 대상이 되지 않는다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("IMPORTANT — 백필의 분모는 종목 수가 아니라 거래일 × 종목이다")
    void backfillPlansOneTaskPerTradeDateAndStock() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        // 잡의 대상은 지수 하나가 아니라 편입 중인 전 지수다. KOSPI200만 세면 분모가 달라진다.
        List<Long> members = activeUniverseMembers();
        List<String> tradeDates = List.of("2026-08-03", "2026-08-04", "2026-08-05");
        for (Long member : members) {
            fakePriceSource.withBars(member, tradeDates.stream()
                    .map(date -> bar(date, "1000")).toList());
        }
        initialLoadJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        indicatorBackfillJob.run(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-05"),
                BatchExecutionRecorder.TriggeredBy.MANUAL, List.of(), null);

        Map<String, Object> row = latestExecution(IndicatorBackfillJob.JOB_NAME);
        int expected = tradeDates.size() * members.size();

        // 이 단언이 이 테스트의 존재 이유다. 분모가 종목 수였을 때 화면에 43400/350이 떴다 —
        // 350종목 × 124거래일을 종목 수로 나눈 값이라 데이터가 부풀어 보였다.
        assertThat(row.get("planned_task_count"))
                .as("계획은 실제로 process()를 부를 횟수여야 한다")
                .isEqualTo(expected);
        assertThat(row.get("success_count"))
                .as("분자와 분모가 같은 단위라야 진행률이 성립한다")
                .isEqualTo(expected);
        assertThat(row.get("target_count"))
                .as("대상 종목 수는 그대로 남는다 — 뜻을 갈아타면 과거 행이 다른 것을 의미하게 된다")
                .isEqualTo(members.size());
    }

    @Test
    @DisplayName("단계별 집계가 남는다 — 합계만으로는 어느 단계가 비었는지 알 수 없다")
    void recordsPerPhaseTally() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);
        List<Long> members = activeUniverseMembers();
        for (Long member : members) {
            fakePriceSource.withBars(member, List.of(bar("2026-08-05", "72000")));
        }
        seedMarketIndexes(AS_OF);

        dailyUpdateJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        long executionId = ((Number) latestExecution(DailyUpdateJob.JOB_NAME).get("id")).longValue();
        List<Map<String, Object>> phases = jdbcTemplate.queryForList("""
                SELECT phase, planned_count, success_count, written_row_count
                FROM batch_execution_phase WHERE execution_id = ? ORDER BY phase
                """, executionId);

        assertThat(phases).extracting(phase -> phase.get("phase"))
                .as("일일 갱신은 지수·시세·지표 세 단계를 돈다. 지수는 종목 루프 **앞**이다 — "
                        + "지표가 그날 지수를 입력으로 쓰기 때문이다(ADR-0053)")
                .containsExactlyInAnyOrder("INDICATOR", "MARKET_INDEX", "PRICE");
        Map<String, Object> price = phases.stream()
                .filter(phase -> "PRICE".equals(phase.get("phase"))).findFirst().orElseThrow();
        assertThat(price.get("planned_count")).isEqualTo(members.size());
        assertThat(((Number) price.get("written_row_count")).longValue())
                .as("시세 단계가 실제로 행을 썼는지는 이쪽으로만 물을 수 있다")
                .isEqualTo(members.size());
    }

    @Test
    @DisplayName("돌지 않은 단계는 집계에 없다 — 0으로 채우면 '안 돈다'와 '못 받았다'가 같아진다")
    void omitsPhasesTheJobNeverRuns() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        long executionId =
                ((Number) latestExecution(UniverseRebalanceJob.JOB_NAME).get("id")).longValue();

        assertThat(jdbcTemplate.queryForList(
                "SELECT phase FROM batch_execution_phase WHERE execution_id = ?",
                String.class, executionId))
                .containsExactly("UNIVERSE");
    }

    @Test
    @DisplayName("IMPORTANT — 마감된 실행은 종료 훅이 덮지 못한다")
    void keepsFinishedExecutionFromBeingReopened() {
        universeRebalanceJob.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL);

        // 유니버스 리밸런싱은 process()를 쓰지 않아 오랫동안 마감 신호를 보내지 않았고,
        // 그래서 열린 실행 목록에 남아 종료 훅이 성공한 행을 ABORTED로 덮을 수 있었다.
        //
        // MUST: 목록을 직접 묻는다. 마감 뒤 DB 상태로만 물으면 배선(finishQuietly의
        // onTerminal)이 빠져도 updateTerminalState의 가드가 SUCCESS를 지켜 **초록이 나오고**,
        // 종단 플래그가 생긴 뒤로는 그것까지 덮어 준다. 그러면 이 펜스는 떨어질 수 없다.
        long executionId = ((Number) latestExecution(UniverseRebalanceJob.JOB_NAME).get("id")).longValue();
        assertThat(batchExecutions.isOpen(executionId))
                .as("마감했는데 열린 목록에 남아 있다 — finishQuietly의 onTerminal이 끊겼다")
                .isFalse();

        batchExecutions.closeOpenExecutions();

        assertThat(latestExecution(UniverseRebalanceJob.JOB_NAME).get("status"))
                .isEqualTo(BatchStatus.SUCCESS.name());
    }

    /** 잡들이 대상으로 삼는 모집단. 지수 하나가 아니라 편입 중인 전 지수다. */
    private List<Long> activeUniverseMembers() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN stock s ON s.id = m.stock_id
                WHERE m.effective_to IS NULL AND s.is_active
                """, Long.class);
    }

    private Map<String, Object> latestExecution(String jobName) {
        return jdbcTemplate.queryForMap("""
                SELECT * FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1
                """, jobName);
    }

    private int countStocks() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM stock", Integer.class);
    }

    private int countMemberships() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM universe_membership", Integer.class);
    }

    /**
     * 대상일의 지수를 심는다.
     *
     * <p>MUST: 알림을 느슨하게 하는 대신 여기서 심는다. 운영에서 지수는 시세와 함께 들어오는
     * 것이 정상이고, <b>시세만 있고 지수가 없는 상태는 실제로 알려야 하는 사건</b>이다 —
     * 그날 {@code EXCESS_RETURN_26W}가 한 행도 안 써지는데 화면에서는 조건에 안 맞은 것과
     * 같아 보인다.
     * 단언을 넓히면 그 계약이 이 테스트에서 사라진다.
     */
    private void seedMarketIndexes(LocalDate date) {
        for (String code : List.of("KOSPI", "KOSDAQ")) {
            jdbcTemplate.update("""
                    INSERT INTO market_index_daily (index_code, trade_date, close)
                    VALUES (?, ?, 2500)
                    ON CONFLICT (index_code, trade_date) DO NOTHING
                    """, code, date);
        }
    }

    /**
     * 활성 유니버스 <b>전부</b>의 편입 종목.
     *
     * <p>MUST: 봉을 심을 때 이것을 쓴다. 한 지수에만 심으면 나머지 지수는 대상일에 봉이 0이
     * 되는데, <b>0건 판정이 시장별이라</b> 그 지수에 대해 결손 알림이 뜬다 — 그것은 오탐이
     * 아니라 픽스처가 만든 진짜 결손이고, 그 알림 때문에 다른 단언이 깨진다.
     */
    private List<Long> allActiveMembers() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT m.stock_id FROM universe_membership m
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE m.effective_to IS NULL
                """, Long.class);
    }

    private long stockIdOf(String ticker) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM stock WHERE ticker = ?", Long.class, ticker);
    }

    private static RawPriceBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null, null, false);
    }

    static class RecordingAlertPort implements AlertPort {
        final List<AlertEvent> events = new ArrayList<>();

        @Override
        public void send(AlertEvent event) {
            events.add(event);
        }
    }

    @TestConfiguration
    static class FakeSourceConfig {

        /**
         * 빈 하나만 등록한다. 같은 인스턴스를 두 개의 {@code @Bean}으로 노출하면
         * {@code DataSourceRegistry}가 같은 시장에 두 소스가 등록된 것으로 보고 기동에 실패한다.
         */
        @Bean
        FakePriceDataSource fakePriceDataSource() {
            return new FakePriceDataSource("KOSPI", "KOSDAQ", "US");
        }

        @Bean
        @Primary
        RecordingAlertPort recordingAlertPort() {
            return new RecordingAlertPort();
        }
    }
}
