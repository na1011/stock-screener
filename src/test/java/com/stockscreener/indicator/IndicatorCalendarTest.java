package com.stockscreener.indicator;

import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기준 일자 해석의 재료. {@code @DataJpaTest}가 롤백하므로 아래 {@code DELETE}는
 * 이 테스트 안에서만 유효하다 — 다른 테스트가 남긴 행에 결과가 흔들리지 않게 하려는 것이다.
 */
@JpaSliceTest
@Import({IndicatorCalendar.class, IndicatorTradeDateIndex.class})
class IndicatorCalendarTest {

    private static final Set<String> MARKET = Set.of("TESTMKT");
    private static final Set<String> OTHER = Set.of("TESTMKT2");

    @Autowired
    private IndicatorCalendar calendar;

    @Autowired
    private IndicatorTradeDateIndex tradeDateIndex;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;
    private long otherMarketStockId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM indicator_value");
        jdbcTemplate.update("""
                INSERT INTO market (code, country_code, currency_code, timezone)
                VALUES ('TESTMKT','KR','KRW','Asia/Seoul'), ('TESTMKT2','US','USD','America/New_York')
                ON CONFLICT DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, category, value_type)
                VALUES ('PER','PER','FUNDAMENTAL','RATIO') ON CONFLICT DO NOTHING
                """);
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('TESTMKT','000001','테스트종목')
                RETURNING id
                """, Long.class);
        otherMarketStockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('TESTMKT2','OTHER','다른시장종목')
                RETURNING id
                """, Long.class);
        tradeDateIndex.rebuild();
    }

    @Test
    @DisplayName("IMPORTANT — 지표가 하나도 없으면 비어 있다. 오늘 날짜로 대신하지 않는다")
    void emptyWhenNoIndicatorsExist() {
        // 오늘을 돌려주면 호출자가 그 날짜로 조회해 전 종목 '판정 불가'를 받고,
        // 그것을 "조건에 맞는 종목이 없음"으로 읽는다. 비어 있음이 유일하게 정직한 답이다.
        assertThat(calendar.latestIndicatorDate(MARKET)).isEmpty();
        assertThat(calendar.earliestIndicatorDate(MARKET)).isEmpty();
        assertThat(calendar.nearestOnOrBefore(LocalDate.of(2026, 8, 6), MARKET)).isEmpty();
    }

    @Test
    @DisplayName("최신·최소 거래일을 낸다")
    void reportsRange() {
        insert(LocalDate.of(2026, 3, 2));
        insert(LocalDate.of(2026, 8, 5));
        insert(LocalDate.of(2026, 6, 1));

        assertThat(calendar.latestIndicatorDate(MARKET)).contains(LocalDate.of(2026, 8, 5));
        assertThat(calendar.earliestIndicatorDate(MARKET)).contains(LocalDate.of(2026, 3, 2));
    }

    @Test
    @DisplayName("휴장일을 물으면 그 이하 가장 가까운 거래일로 내려간다")
    void fallsBackToNearestTradingDay() {
        insert(LocalDate.of(2026, 8, 5));   // 수
        insert(LocalDate.of(2026, 8, 7));   // 금

        // 8/8은 토요일이라 지표가 없다. 오류로 만들면 사용자가 거래일 달력을 알아야 한다.
        assertThat(calendar.nearestOnOrBefore(LocalDate.of(2026, 8, 8), MARKET))
                .contains(LocalDate.of(2026, 8, 7));
        assertThat(calendar.nearestOnOrBefore(LocalDate.of(2026, 8, 6), MARKET))
                .contains(LocalDate.of(2026, 8, 5));
    }

    @Test
    @DisplayName("보존 구간보다 이전을 물으면 비어 있다 — 호출자가 이것을 오류로 바꾼다")
    void emptyBeforeRetentionWindow() {
        insert(LocalDate.of(2026, 3, 2));

        assertThat(calendar.nearestOnOrBefore(LocalDate.of(2020, 1, 2), MARKET)).isEmpty();
    }

    /**
     * 이 시장의 지표를 넣고 색인을 다시 짓는다.
     *
     * <p>색인 갱신을 픽스처에 묶어 둔 이유는 <b>운영 경로가 그렇기 때문</b>이다 —
     * 지표를 쓰는 잡이 끝에서 {@link IndicatorTradeDateIndex}를 부른다. 여기서 손으로
     * 색인 행을 넣으면 그 계약을 우회하게 되고, 잡이 그것을 빠뜨려도 테스트가 초록이다.
     */
    private void insert(LocalDate date) {
        insertFor(stockId, date);
        tradeDateIndex.rebuild();
    }

    @Test
    @DisplayName("다른 시장의 더 최근 지표가 이 시장의 기준일을 움직이지 않는다")
    void otherMarketDoesNotMoveThisMarketsLatest() {
        insertFor(stockId, LocalDate.of(2026, 8, 5));
        // 이 시장이 쉰 날에 다른 시장이 열렸다. 실측으로 보존창 안 국내 휴장 평일 13일 중
        // 미국 개장이 9일이고, 그날 기준일이 미국으로 밀리면 국내 전 프리셋이 0건이 된다.
        insertFor(otherMarketStockId, LocalDate.of(2026, 8, 6));
        tradeDateIndex.rebuild();

        assertThat(calendar.latestIndicatorDate(MARKET)).contains(LocalDate.of(2026, 8, 5));
        assertThat(calendar.latestIndicatorDate(OTHER)).contains(LocalDate.of(2026, 8, 6));
        // 요청이 8/6을 물어도 이 시장에는 그날 값이 없으므로 8/5로 내려간다.
        assertThat(calendar.nearestOnOrBefore(LocalDate.of(2026, 8, 6), MARKET))
                .contains(LocalDate.of(2026, 8, 5));
    }

    @Test
    @DisplayName("빈 시장 집합은 「전 시장」이 아니라 예외다")
    void emptyMarketSetIsRejected() {
        insert(LocalDate.of(2026, 8, 5));

        // 「전 시장」으로 접으면 이 클래스가 존재하는 이유가 사라지고, 그 접힘은 값이 나오는
        // 형태라 드러나지 않는다.
        assertThatThrownBy(() -> calendar.latestIndicatorDate(Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void insertFor(long targetStockId, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, 'PER', ?)
                """, targetStockId, date, new BigDecimal("10"));
    }
}
