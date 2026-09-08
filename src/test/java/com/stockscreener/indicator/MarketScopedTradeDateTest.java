package com.stockscreener.indicator;

import com.stockscreener.pipeline.collector.PriceCalendar;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>거래일을 시장으로 가르는가</b>를 잰다.
 *
 * <p>MUST: 단언을 「시장 인자를 받는가」로 세우지 않는다 — 그것은 시그니처를 보는 것이라
 * 정의상 언제나 참이다. 여기서 재는 것은 <b>미국 봉이 국내 답을 움직이는가</b>이고, 시장
 * 조건을 지우면 아래 셋이 전부 붉어진다.
 *
 * <p>픽스처가 그 축을 만든다 — 국내는 {@code AS_OF}까지 {@code KR_DAYS}거래일(보존 일수 + 20), 미국은 <b>더 최근으로
 * 10일</b>과 <b>훨씬 이른 하루</b>다. 그래서 전 시장 합계로 세면 국내의 보존 경계가 10일
 * 뒤로 밀리고 첫 거래일이 몇 백 일 앞으로 당겨진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class MarketScopedTradeDateTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);
    /**
     * 국내 봉 수. <b>보존 일수에서 유도한다 — 리터럴로 박지 않는다.</b>
     * {@code writeCutoff}가 {@code OFFSET RETAINED_TRADING_DAYS}로 묻는데 봉이 그보다
     * 적으면 <b>0행이 돌아와 {@code Optional.empty()}가 되고</b>, 그러면 아래 단언이
     * 보존 경계가 아니라 픽스처 크기 때문에 떨어진다. 상수를 올린 커밋이 실제로
     * 그 자리에서 붉었다(180 → 255).
     */
    private static final int KR_DAYS = IndicatorRetentionPolicy.RETAINED_TRADING_DAYS + 20;
    private static final int US_RECENT_DAYS = 10;

    @Autowired
    private PriceCalendar priceCalendar;

    @Autowired
    private IndicatorRetentionPolicy retentionPolicy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long krStockId;
    private long usStockId;

    @BeforeEach
    void seed() {
        krStockId = insertStock("KOSPI", "005930", "삼성전자");
        usStockId = insertStock("US", "AAPL", "Apple Inc.");

        for (int i = 0; i < KR_DAYS; i++) {
            insertBar(krStockId, AS_OF.minusDays(i));
        }
        // 국내보다 최근. 전 시장으로 세면 이 열흘이 국내 보존 경계를 밀어낸다.
        for (int i = 1; i <= US_RECENT_DAYS; i++) {
            insertBar(usStockId, AS_OF.plusDays(i));
        }
        // 국내보다 훨씬 이르다. 전 시장으로 세면 이것이 국내의 「첫 거래일」이 된다.
        insertBar(usStockId, AS_OF.minusDays(500));
    }

    @Test
    @DisplayName("미국 봉이 더 최근이어도 국내 보존 경계가 밀리지 않는다")
    void retentionCutoffDoesNotShiftWithForeignBars() {
        LocalDate expected = AS_OF.minusDays(IndicatorRetentionPolicy.RETAINED_TRADING_DAYS);

        assertThat(retentionPolicy.writeCutoff("KOSPI"))
                .as("전 시장으로 세면 미국의 최근 %d일이 경계를 그만큼 뒤로 민다 — "
                        + "그러면 국내 지표가 보존 창 밖에 쓰이고 purge가 그것을 다시 지운다",
                        US_RECENT_DAYS)
                .contains(expected);
    }

    @Test
    @DisplayName("첫 거래일도 시장 안에서만 센다 — 기업 행위 조회 창의 기본값이다")
    void earliestTradeDateIsScopedToMarket() {
        assertThat(priceCalendar.earliestTradeDate("KOSPI"))
                .as("전 시장 최솟값을 쓰면 미국이 국내의 기본 조회 창을 정한다")
                .contains(AS_OF.minusDays(KR_DAYS - 1));

        assertThat(priceCalendar.earliestTradeDate("US"))
                .contains(AS_OF.minusDays(500));
    }

    @Test
    @DisplayName("국내가 쉬고 미국만 연 날의 국내 봉 수는 0이다 — 결손 판정이 여기 걸려 있다")
    void countOnSeesOnlyItsOwnMarket() {
        LocalDate usOnly = AS_OF.plusDays(1);

        assertThat(priceCalendar.countOn("KOSPI", usOnly))
                .as("전 시장 합계로 물으면 국내 결손 알림이 원리적으로 발화하지 않는다")
                .isZero();
        assertThat(priceCalendar.countOn("US", usOnly)).isEqualTo(1);
    }

    private long insertStock(String market, String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?)
                ON CONFLICT (market_code, ticker) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Long.class, market, ticker, name);
    }

    private void insertBar(long stockId, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close,
                                         adj_close, adj_factor, volume)
                VALUES (?, ?, 100, 100, 100, 100, 100, 1, 1000)
                ON CONFLICT (stock_id, trade_date) DO NOTHING
                """, stockId, date);
    }
}
