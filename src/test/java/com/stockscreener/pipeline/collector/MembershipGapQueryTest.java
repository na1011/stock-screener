package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 편입 계약의 일곱 축이 <b>걸릴 때 걸리고, 면제가 실제로 먹는가</b>.
 *
 * <h2>MUST: 픽스처를 직접 심는다</h2>
 * <p>이 질의는 {@code u.is_active} · {@code m.effective_to IS NULL} · {@code s.is_active} ·
 * {@code CollectedMarkets} 넷을 조인한다. <b>테스트 DB는 운영과 활성 유니버스가 정반대</b>라
 * ({@code .claude/rules/test.md}) 아무것도 안 심으면 <b>모든 케이스가 0행으로 초록</b>이 된다 —
 * 대조군이 가드만 태우고 본체를 안 태우는 형태다. 아래 {@link #controlGroupActuallyReachesTheQuery}가
 * 그것을 먼저 확인한다.
 *
 * <p>여기서 쓰는 {@code KOSPI200}은 <b>테스트 DB에서 활성</b>이다.
 */
@JpaSliceTest
@Import({StockRegistrar.class, DataSourceRegistry.class})
class MembershipGapQueryTest {

    private static final LocalDate JOINED = LocalDate.of(2026, 3, 2);

    @Autowired
    private StockRegistrar registrar;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("대조군이 질의에 실제로 닿는다 — 안 닿으면 아래 시험 전부가 0행으로 초록이 된다")
    void controlGroupActuallyReachesTheQuery() {
        member("005930", "삼성전자");

        assertThat(gapOf("005930"))
                .as("아무것도 안 채운 편입 종목이 안 잡히면 픽스처가 질의에 안 닿은 것이다")
                .isPresent();
    }

    @Test
    @DisplayName("아무것도 없는 편입 종목은 네 축에 한꺼번에 걸린다")
    void emptyMemberHitsFourAxesAtOnce() {
        member("005930", "삼성전자");

        assertThat(gapOf("005930").orElseThrow().missing()).contains(
                MembershipGap.Axis.PRICE_HISTORY, MembershipGap.Axis.INDUSTRY,
                MembershipGap.Axis.FINANCIAL, MembershipGap.Axis.CASH_FLOW);
    }

    @Test
    @DisplayName("IMPORTANT — 봉이 있어도 편입일 뒤에서 시작하면 걸린다. NOT EXISTS였다면 지워질 신호다")
    void priceHistoryIsNotAnExistenceCheck() {
        long withBarsAfterJoining = member("005930", "삼성전자");
        bar(withBarsAfterJoining, JOINED.plusDays(1));
        long withBarsBeforeJoining = member("035420", "NAVER");
        bar(withBarsBeforeJoining, JOINED.minusDays(1));

        assertThat(gapOf("005930").orElseThrow().missing())
                .as("일일 갱신이 봉을 남겨도 첫 봉이 편입일 뒤면 신호가 남아야 한다")
                .contains(MembershipGap.Axis.PRICE_HISTORY);
        assertThat(axesOf("035420")).doesNotContain(MembershipGap.Axis.PRICE_HISTORY);
    }

    @Test
    @DisplayName("업종 결손이 알려진 SIC 결손이면 면제로 간다 — 신고가 아니라 OK 문장으로 옮긴다")
    void knownMissingSicIsExemptNotReported() {
        usMember("ARCC", "Ares Capital");
        usMember("AAPL", "Apple");

        assertThat(gapOf("ARCC").orElseThrow().exempt()).contains(MembershipGap.Axis.INDUSTRY);
        assertThat(gapOf("ARCC").orElseThrow().missing()).doesNotContain(MembershipGap.Axis.INDUSTRY);
        assertThat(axesOf("AAPL"))
                .as("허용목록 밖은 그대로 신고된다 — 안 그러면 이 면제가 축 전체를 끈다")
                .contains(MembershipGap.Axis.INDUSTRY);
    }

    @Test
    @DisplayName("인프라 투자회사의 재무 결손은 면제다 — 소스가 재무제표를 주지 않는다")
    void infraFundFinancialsAreExempt() {
        long infra = member("088980", "맥쿼리인프라");
        // industry_group_key는 industry_code에서 유도되는 생성 컬럼이라 직접 못 쓴다 —
        // 64201이 곧 KSIC:642다. 면제 술어가 그 생성 컬럼을 보는 것이 이 시험의 요점이다.
        jdbcTemplate.update("UPDATE stock SET industry_code = '64201' WHERE id = ?", infra);

        MembershipGap gap = gapOf("088980").orElseThrow();
        assertThat(gap.exempt()).contains(
                MembershipGap.Axis.FINANCIAL, MembershipGap.Axis.CASH_FLOW);
        assertThat(gap.missing()).doesNotContain(
                MembershipGap.Axis.FINANCIAL, MembershipGap.Axis.CASH_FLOW);
    }

    @Test
    @DisplayName("IMPORTANT — 재무 통화가 시장 통화와 다르면 면제다. 채워도 지표 계층이 재무를 통째로 안 준다")
    void currencyMismatchIsExemptBecauseFillingItChangesNothing() {
        long mismatched = member("950260", "인제니아테라퓨틱스");
        financialRow(mismatched, "USD", null);
        long normal = member("035420", "NAVER");
        financialRow(normal, "KRW", null);

        MembershipGap gap = gapOf("950260").orElseThrow();
        assertThat(gap.exempt()).contains(MembershipGap.Axis.CASH_FLOW);
        assertThat(gap.missing()).doesNotContain(MembershipGap.Axis.CASH_FLOW);
        assertThat(axesOf("035420"))
                .as("통화가 맞으면 현금흐름 결손은 그대로 신고된다")
                .contains(MembershipGap.Axis.CASH_FLOW);
    }

    @Test
    @DisplayName("영업활동현금흐름이 차 있으면 그 축만 빠진다 — 재무 축과 따로 판정한다")
    void cashFlowClosesIndependentlyOfTheFinancialAxis() {
        long stockId = member("005930", "삼성전자");
        financialRow(stockId, "KRW", 1_000L);

        assertThat(axesOf("005930")).doesNotContain(
                MembershipGap.Axis.FINANCIAL, MembershipGap.Axis.CASH_FLOW);
    }

    @Test
    @DisplayName("IMPORTANT — 지표 달력이 없어도 나머지 네 축은 판정된다. INNER JOIN이면 그 시장이 통째로 사라진다")
    void axesStillJudgedWhenTheIndicatorCalendarIsEmpty() {
        jdbcTemplate.update("DELETE FROM indicator_trade_date WHERE market_code = 'KOSPI'");
        member("005930", "삼성전자");

        assertThat(axesOf("005930"))
                .as("창이 없다고 이 종목이 통째로 빠지면 이 기제가 없애려는 조용한 0건이 된다")
                .contains(MembershipGap.Axis.PRICE_HISTORY, MembershipGap.Axis.FINANCIAL);
    }

    @Test
    @DisplayName("거버넌스 원문이 0행이면 걸린다 — 팩트로 재면 무배당과 미완결이 결손으로 섞인다")
    void governanceGapIsMeasuredOnRawRowsNotFacts() {
        long covered = member("000660", "SK하이닉스");
        governanceRow(covered);
        member("005930", "삼성전자");

        assertThat(gapOf("005930").orElseThrow().missing())
                .contains(MembershipGap.Axis.GOVERNANCE);
        assertThat(gapOf("000660").map(gap -> gap.missing().contains(MembershipGap.Axis.GOVERNANCE)))
                .as("원문이 있는 종목은 이 축에 안 걸린다")
                .hasValue(false);
    }

    @Test
    @DisplayName("IMPORTANT — 그 시장에 거버넌스가 한 행도 없으면 아무도 안 걸린다. 상시 붉는 펜스가 상한을 밀어 시세 적재까지 멈춘다")
    void governanceAxisIsSilentWhileTheMarketHasNoGovernanceAtAll() {
        // 미국이 2026-09-01 전에 정확히 이 상태였다 — 편입 전원이 한꺼번에 붉으면
        // AUTO_GOVERNANCE_STOCK_LIMIT을 넘겨 그 축이 통째로 막히고, 알림은 상시 경고가 된다.
        usMember("AAPL", "Apple");
        // 국내에만 원문을 심는다. 시장이 갈려 있어야 이 시험이 무엇을 재는지 분명하다.
        governanceRow(member("000660", "SK하이닉스"));

        assertThat(gapOf("AAPL").map(gap -> gap.missing().contains(MembershipGap.Axis.GOVERNANCE)))
                .as("거버넌스를 한 번도 안 받은 시장에서 이 축이 울리면 안 된다")
                .hasValue(false);
    }

    @Test
    @DisplayName("시세에 구멍이 뚫리면 걸리고, 첫 봉이 늦은 신규 상장은 안 걸린다")
    void coverageAnchorsOnTheStocksOwnFirstBar() {
        resetCalendar();
        tradeDates(JOINED.minusDays(1), 20);

        // 구멍: 첫 봉이 창 앞이지만 그 뒤로 거의 비어 있다.
        long holed = member("005930", "구멍난 종목");
        bar(holed, JOINED.minusDays(1));

        // 신규 상장: 첫 봉이 창 한가운데지만 그 뒤로는 빠짐없이 있다.
        long newListing = member("035420", "신규 상장");
        for (int i = 15; i < 20; i++) {
            bar(newListing, JOINED.minusDays(1).plusDays(i));
        }

        assertThat(axesOf("005930")).contains(MembershipGap.Axis.PRICE_COVERAGE);
        assertThat(axesOf("035420"))
                .as("앵커가 첫 봉이라 신규 상장은 충족률 100%가 되어야 한다")
                .doesNotContain(MembershipGap.Axis.PRICE_COVERAGE);
    }

    @Test
    @DisplayName("어제 나던 지표가 오늘 안 나면 걸린다 — 시장 중앙값이 아니라 그 종목 자신이 기준선이다")
    void indicatorDropComparesTheStockToItsOwnYesterday() {
        resetCalendar();
        LocalDate prev = JOINED.plusDays(1);
        LocalDate latest = JOINED.plusDays(2);
        tradeDate(prev);
        tradeDate(latest);

        long dropped = member("005930", "급감한 종목");
        indicators(dropped, prev, 10);
        indicators(dropped, latest, 3);

        long steady = member("035420", "그대로인 종목");
        indicators(steady, prev, 10);
        indicators(steady, latest, 10);

        assertThat(axesOf("005930")).contains(MembershipGap.Axis.INDICATOR_DROP);
        assertThat(axesOf("035420")).doesNotContain(MembershipGap.Axis.INDICATOR_DROP);
    }

    private List<MembershipGap.Axis> axesOf(String ticker) {
        return gapOf(ticker).map(gap -> List.copyOf(gap.missing())).orElse(List.of());
    }

    private Optional<MembershipGap> gapOf(String ticker) {
        return registrar.membershipGaps().stream()
                .filter(gap -> gap.ticker().equals(ticker))
                .findFirst();
    }

    private long member(String ticker, String name) {
        return member("KOSPI", ticker, name);
    }

    private long usMember(String ticker, String name) {
        return member("US", ticker, name);
    }

    private long member(String market, String ticker, String name) {
        long stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, is_active)
                VALUES (?, ?, ?, true) RETURNING id
                """, Long.class, market, ticker, name);
        jdbcTemplate.update("""
                INSERT INTO universe (code, name, market_code, is_active)
                VALUES (?, ?, ?, true) ON CONFLICT (code) DO NOTHING
                """, universeOf(market), universeOf(market), market);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, universeOf(market), stockId, JOINED);
        return stockId;
    }

    private static String universeOf(String market) {
        return "US".equals(market) ? "US_GAPTEST" : "KOSPI200";
    }

    private void bar(long stockId, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close,
                                         adj_close, volume)
                VALUES (?, ?, 1, 1, 1, 1, 1, 0) ON CONFLICT DO NOTHING
                """, stockId, date);
    }

    /**
     * 시드가 심어 둔 거래일을 걷는다.
     *
     * <p><b>MUST: 창을 쓰는 시험은 이것을 먼저 부른다.</b> 테스트 DB에 KOSPI 거래일이
     * <b>2026-08-05 하나</b> 심겨 있어서, 픽스처 날짜가 그보다 앞이면 질의의 {@code hi}가
     * 시드 쪽으로 잡힌다 — 그러면 <b>모든 종목의 최신일 지표가 0</b>이 되어 급감 축이
     * 전부 참이 되고, 시험은 <b>엉뚱한 이유로 붉거나 초록</b>이 된다. 실제로 그랬다.
     */
    private void resetCalendar() {
        jdbcTemplate.update("DELETE FROM indicator_trade_date WHERE market_code = 'KOSPI'");
    }

    private void tradeDate(LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO indicator_trade_date (market_code, trade_date)
                VALUES ('KOSPI', ?) ON CONFLICT DO NOTHING
                """, date);
    }

    private void tradeDates(LocalDate from, int count) {
        for (int i = 0; i < count; i++) {
            tradeDate(from.plusDays(i));
        }
    }

    private void indicators(long stockId, LocalDate date, int codes) {
        for (int i = 0; i < codes; i++) {
            String code = "GAPTEST_%02d".formatted(i);
            jdbcTemplate.update("""
                    INSERT INTO indicator (code, name, category, value_type, higher_is_better)
                    VALUES (?, ?, 'TECHNICAL', 'RATIO', true) ON CONFLICT (code) DO NOTHING
                    """, code, code);
            jdbcTemplate.update("""
                    INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                    VALUES (?, ?, ?, 1) ON CONFLICT DO NOTHING
                    """, stockId, date, code);
        }
    }

    /** 거버넌스 원문 한 행. <b>내용은 상관없다</b> — 감지 술어가 존재 여부만 본다. */
    private void governanceRow(long stockId) {
        jdbcTemplate.update("""
                INSERT INTO governance_report_row (stock_id, endpoint, bsns_year, reprt_code,
                        receipt_no, published_at, row_ordinal, payload)
                VALUES (?, 'DIVIDEND', 2025, '11011', '20260301000001', '2026-03-01', 0, '{}'::jsonb)
                """, stockId);
    }

    private void financialRow(long stockId, String currency, Long operatingCashFlow) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period,
                        period_start, period_end, published_at, currency_code, operating_cash_flow)
                VALUES (?, 2025, 'FY', '2025-01-01', '2025-12-31', '2026-03-01', ?, ?)
                """, stockId, currency, operatingCashFlow);
    }
}
