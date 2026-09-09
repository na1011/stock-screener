package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유니버스가 쓰는 주식수의 계약을 잰다.
 *
 * <p><b>이 클래스가 지키는 것 셋</b>이고 셋 다 「값이 나오는」 방향으로 틀린다 —
 * 결측이 아니라 <b>그럴듯한 숫자</b>가 나와 화면에서 구별되지 않는다.
 * <ol>
 *   <li>{@code is_latest}는 「최신 기」가 아니라 「그 기의 최신 개정본」이다.</li>
 *   <li>낡은 기의 주식수를 오늘 종가에 곱하지 않는다.</li>
 *   <li>통화 판정이 {@code IndicatorDataReader.CURRENCY_GATE}와 갈리지 않는다.</li>
 *   <li><b>{@code asOf}가 세 자리에 다 걸린다</b> — 판 선택 · 기업행위 하한 앵커 · 그 상한
 *       (ADR-0135). 하나라도 빠지면 과거 기준일의 시총이 미래를 본다.</li>
 * </ol>
 *
 * <p><b>픽스처의 공시일이 기말 + 2개월인 것이 계약이다</b>({@code insertRevision}). 기업행위
 * 시험이 그 사이 구간과 그 뒤 구간을 <b>갈라서</b> 세우므로, 그 간격을 바꾸면 시험이 무엇을
 * 재는지가 조용히 달라진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class JdbcStoredShareCountsTest {

    private static final LocalDate OLDEST = LocalDate.of(2024, 8, 31);

    /** 조회 시점. 픽스처의 공시일(기말 + 2개월)보다 뒤라 그 판들이 「이미 공표된 것」이 된다. */
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Autowired
    private StoredShareCounts shareCounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("IMPORTANT — is_latest가 아니라 최신 기의 주식수를 낸다")
    void picksTheLatestPeriodNotTheLargestRevision() {
        long stockId = insertStock("US", "AAPLT", "Apple Test Inc.");
        // 실측 모양: AAPL의 is_latest 행이 38개고 옛 기의 주식수가 최신기보다 약 1.165배 크다.
        insertQuarter(stockId, 2024, "Q1", LocalDate.of(2024, 12, 31), "USD", "17001802000");
        insertQuarter(stockId, 2026, "Q3", LocalDate.of(2026, 6, 27), "USD", "14594180000");

        assertThat(find("AAPLT").orElseThrow().shares())
                .as("max()로 뽑으면 옛 기의 값이 이기고 시총이 약 1.165배 크게 나온다")
                .isEqualByComparingTo(new BigDecimal("14594180000"));
    }

    @Test
    @DisplayName("같은 기에 개정본이 여럿이면 나중 판을 쓴다")
    void picksTheLatestRevisionWithinAPeriod() {
        long stockId = insertStock("US", "REVT", "Revised Test Inc.");
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100", 0, false);
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "200", 1, true);

        assertThat(find("REVT").orElseThrow().shares()).isEqualByComparingTo(new BigDecimal("200"));
    }

    @Test
    @DisplayName("경계보다 이른 기만 있으면 내지 않는다 — 없으면 십수 년 전 값이 오늘 종가에 곱해진다")
    void dropsStaleShareCounts() {
        long stockId = insertStock("US", "STALE", "Stale Inc.");
        // sec-edgar-financial.md 실측: V가 2010-01-27, BRK가 2011-04-29로 멈춰 있었다.
        insertQuarter(stockId, 2011, "Q1", LocalDate.of(2011, 4, 29), "USD", "941481");

        assertThat(find("STALE")).isEmpty();
    }

    @Test
    @DisplayName("주식수가 NULL이거나 0이면 내지 않는다 — 0은 「작은 회사」와 구별되지 않는다")
    void dropsMissingAndZeroShareCounts() {
        long nullShares = insertStock("US", "NULS", "Null Shares Inc.");
        insertQuarter(nullShares, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", null);
        long zeroShares = insertStock("US", "ZERO", "Zero Shares Inc.");
        insertQuarter(zeroShares, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "0");

        assertThat(find("NULS")).isEmpty();
        assertThat(find("ZERO")).isEmpty();
    }

    @Test
    @DisplayName("미국 시장의 USD는 정상이다 — 판정 축은 「USD인가」가 아니라 「시장 통화와 같은가」다")
    void usdIsNormalInUsMarket() {
        long stockId = insertStock("US", "USDOK", "Usd Ok Inc.");
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100");

        assertThat(find("USDOK").orElseThrow().currencyMismatched())
                .as("여기가 참이 되면 미국 유니버스가 통째로 비고 그 0건은 「후보 없음」과 구별되지 않는다")
                .isFalse();
    }

    /**
     * <b>MUST: 이 술어를 {@code IndicatorDataReader.CURRENCY_GATE}와 같게 유지한다.</b>
     * 그쪽이 {@code is_latest}를 보지 않으므로 여기도 보지 않는다 — 갈리면 이 종목이
     * <b>유니버스에는 담기는데 재무 지표는 한 행도 안 나는</b> 상태가 된다.
     * 실측으로 미국에서 그렇게 새는 것이 134종목이다(2026-08-31).
     */
    @Test
    @DisplayName("개정 전 판에만 통화가 다르면 여기서도 걸린다 — CURRENCY_GATE와 안 갈린다")
    void mismatchOnASupersededRevisionIsSeenHereToo() {
        long stockId = insertStock("US", "SUPER", "Superseded Inc.");
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "TWD", "100", 0, false);
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100", 1, true);

        assertThat(find("SUPER").orElseThrow().currencyMismatched()).isTrue();
    }

    @Test
    @DisplayName("SIC를 함께 낸다 — REIT·SPAC 배제가 이름이 아니라 코드로 판정된다")
    void carriesSicCode() {
        long stockId = insertStock("US", "REITT", "Reit Test Inc.");
        jdbcTemplate.update("UPDATE stock SET sic_code = '6798' WHERE id = ?", stockId);
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100");

        assertThat(find("REITT").orElseThrow().sicCode()).isEqualTo("6798");
    }

    @Test
    @DisplayName("IMPORTANT — 공시일 뒤의 분할만큼 주식수를 옮긴다. 안 옮기면 시총이 배수로 틀린다")
    void shiftsSharesByCorporateActionsAfterThePublicationDate() {
        long stockId = insertStock("US", "SPLT", "Split After Report Inc.");
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100");
        // 실측 모양: BYND가 1:30 병합 전 주식수라 시총이 30배로 잡혀 상위 1,000에 잘못 들어왔다.
        insertAction(stockId, LocalDate.of(2026, 6, 1), "SPLIT", "2", "APPLIED");

        assertThat(find("SPLT").orElseThrow().shares())
                .as("안 옮기면 시총이 절반으로 잡혀 순위가 밀린다")
                .isEqualByComparingTo(new BigDecimal("200"));
    }

    /**
     * <b>이 시험이 ADR-0133을 이 계층으로 옮긴 자리다.</b> {@code dei:} 주식수는 <b>표지일</b>
     * 기준이라 기말 이후 공시 전에 일어난 사건이 그 수에 <b>이미 반영돼 있다.</b> 앵커가 기말이면
     * 그 구간을 한 번 더 곱한다 — 실측(2026-09-09)으로 편입 중 {@code MLI}가 2.0배,
     * {@code SNEX}가 1.5배 부푼 채 순위에 실려 있었다.
     */
    @Test
    @DisplayName("IMPORTANT — 기말과 공시일 사이의 사건은 이미 반영돼 있어 다시 곱하지 않는다")
    void ignoresCorporateActionsBetweenPeriodEndAndPublication() {
        long stockId = insertStock("US", "MLIT", "Between Period End And Filing Inc.");
        // 기말 2026-03-28 → 공시일 2026-05-28. 그 사이의 사건이다.
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100");
        insertAction(stockId, LocalDate.of(2026, 5, 1), "SPLIT", "2", "APPLIED");

        assertThat(find("MLIT").orElseThrow().shares())
                .as("앵커가 기말이면 200이 되고, 그 종목은 시총이 2배로 잡혀 순위가 올라간다")
                .isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("보고 종료일 이전의 사건은 이미 주식수에 반영돼 있어 다시 곱하지 않는다")
    void ignoresCorporateActionsBeforeThePeriodEnd() {
        long stockId = insertStock("US", "PRIO", "Prior Split Inc.");
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100");
        insertAction(stockId, LocalDate.of(2026, 1, 5), "SPLIT", "2", "APPLIED");

        assertThat(find("PRIO").orElseThrow().shares()).isEqualByComparingTo(new BigDecimal("100"));
    }

    /**
     * <b>상한이 없으면 과거 기준일의 시총이 미래를 본다.</b> 옛 상수 이름이
     * {@code SHIFT_TO_TODAY}였던 것이 그 상태다 — 그 이름이 계약을 정확히 적고 있었다.
     */
    @Test
    @DisplayName("IMPORTANT — 기준일 뒤의 사건은 안 곱한다. 안 막으면 과거 시총이 미래를 본다")
    void ignoresCorporateActionsAfterAsOf() {
        long stockId = insertStock("US", "FUTR", "Future Split Inc.");
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100");
        insertAction(stockId, AS_OF.plusDays(1), "SPLIT", "2", "APPLIED");

        assertThat(find("FUTR").orElseThrow().shares()).isEqualByComparingTo(new BigDecimal("100"));
    }

    /**
     * <b>MUST: `APPLIED`만 쓴다.</b> `OBSERVED`는 <i>"있었는데 아직 못 고쳤다"</i>라 그 비율이
     * 계수가 아니다(ADR-0049). 쓰면 사람이 판정하지 않은 값이 시총에 그대로 들어간다.
     */
    @Test
    @DisplayName("판정되지 않은 사건은 주식수를 안 옮긴다 — 그 비율은 계수가 아니다")
    void ignoresUnjudgedActions() {
        long stockId = insertStock("US", "OBSV", "Observed Only Inc.");
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100");
        insertAction(stockId, LocalDate.of(2026, 6, 1), "SPLIT", "2", "OBSERVED");

        assertThat(find("OBSV").orElseThrow().shares()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("사건이 여럿이면 곱해서 옮긴다")
    void multipliesMultipleActions() {
        long stockId = insertStock("US", "MULT", "Multiple Actions Inc.");
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100");
        insertAction(stockId, LocalDate.of(2026, 6, 1), "SPLIT", "2", "APPLIED");
        insertAction(stockId, LocalDate.of(2026, 7, 1), "SPLIT", "3", "APPLIED");

        assertThat(find("MULT").orElseThrow().shares()).isEqualByComparingTo(new BigDecimal("600"));
    }

    @Test
    @DisplayName("다른 시장의 종목은 내지 않는다")
    void scopesToTheRequestedMarket() {
        long stockId = insertStock("KOSPI", "999991", "국내 종목");
        insertQuarter(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "KRW", "100");

        assertThat(find("999991")).isEmpty();
    }

    /**
     * <b>{@code is_latest}에는 시간축이 없다.</b> 그것은 <i>읽는 시점의</i> 최신 개정본이라,
     * 과거 기준일에 아직 나오지 않은 정정본이 그대로 골라진다. ADR-0132가 지표 계층에서
     * 실측한 것과 같은 모양이고(저장값이 {@code LATEST}와 정확히 일치했다) 자리만 다르다.
     */
    @Test
    @DisplayName("IMPORTANT — 기준일 뒤에 공시된 정정본은 안 고른다. 고르면 과거가 미래를 본다")
    void ignoresRevisionsPublishedAfterAsOf() {
        long stockId = insertStock("US", "REVS", "Revised Later Inc.");
        // 같은 기의 판 둘. 원판은 기준일 전에, 정정본은 기준일 뒤에 공시됐다.
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100", 0, false,
                LocalDate.of(2026, 5, 28));
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "900", 1, true,
                AS_OF.plusDays(10));

        assertThat(find("REVS").orElseThrow().shares())
                .as("is_latest를 보면 900이 나오고 그 시점에 몰랐던 값이 시총에 들어간다")
                .isEqualByComparingTo(new BigDecimal("100"));
        assertThat(find("REVS").orElseThrow().publishedAt())
                .as("어느 판을 골랐는지가 결과에 실려야 대조가 「왜 빠졌는지」를 낸다")
                .isEqualTo(LocalDate.of(2026, 5, 28));
    }

    @Test
    @DisplayName("같은 기에 판이 여럿이면 기준일까지 공시된 것 중 나중 판을 고른다")
    void picksTheLatestRevisionVisibleAtAsOf() {
        long stockId = insertStock("US", "REVV", "Revised Before Inc.");
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "100", 0, false,
                LocalDate.of(2026, 5, 28));
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 28), "USD", "300", 1, true,
                LocalDate.of(2026, 7, 1));

        assertThat(find("REVV").orElseThrow().shares()).isEqualByComparingTo(new BigDecimal("300"));
    }

    /**
     * <b>정렬 첫 키가 {@code period_end}인 것이 계약이다.</b> 「가장 늦게 공시된 것」으로 바꾸면
     * 뒤늦게 공시된 옛 기가 이긴다 — 실측으로 6종목이 그렇게 움직이고 {@code NCRA}는 시총이
     * 7.16배가 된다(2026-06-30의 2.1M주 대신 2026-03-31의 15.3M주를 집는다).
     */
    @Test
    @DisplayName("IMPORTANT — 옛 기가 나중에 공시돼도 최신 기가 이긴다. 순위 축이 기말이다")
    void latePublicationOfAnOlderPeriodDoesNotWin() {
        long stockId = insertStock("US", "NCRAT", "Late Filing Of Older Period Inc.");
        // 최신 기: 기말 2026-06-27을 2026-07-01에 공시.
        insertRevision(stockId, 2026, "Q3", LocalDate.of(2026, 6, 27), "USD", "2100000", 0, true,
                LocalDate.of(2026, 7, 1));
        // 옛 기: 기말 2026-03-31인데 공시가 더 늦다.
        insertRevision(stockId, 2026, "Q2", LocalDate.of(2026, 3, 31), "USD", "15300000", 0, true,
                LocalDate.of(2026, 8, 20));

        assertThat(find("NCRAT").orElseThrow().shares())
                .as("공시일로 정렬하면 15.3M이 이겨 시총이 7.16배가 된다")
                .isEqualByComparingTo(new BigDecimal("2100000"));
    }

    private Optional<StoredShareCounts.ShareCount> find(String ticker) {
        List<StoredShareCounts.ShareCount> counts =
                shareCounts.activeByMarket("US", OLDEST, AS_OF);
        return counts.stream().filter(c -> c.ticker().equals(ticker)).findFirst();
    }

    private void insertAction(long stockId, LocalDate effectiveDate, String type,
                              String ratio, String status) {
        jdbcTemplate.update("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, type, ratio, detection_source, status)
                VALUES (?, ?, ?, ?, 'SOURCE_DISCLOSURE', ?)
                """, stockId, effectiveDate, type, new BigDecimal(ratio), status);
    }

    private long insertStock(String marketCode, String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id
                """, Long.class, marketCode, ticker, name);
    }

    private void insertQuarter(long stockId, int year, String period, LocalDate periodEnd,
                               String currencyCode, String shares) {
        insertRevision(stockId, year, period, periodEnd, currencyCode, shares, 0, true);
    }

    private void insertRevision(long stockId, int year, String period, LocalDate periodEnd,
                                String currencyCode, String shares, int revision, boolean latest) {
        insertRevision(stockId, year, period, periodEnd, currencyCode, shares, revision, latest,
                periodEnd.plusMonths(2));
    }

    /** 공시일을 직접 주는 판. 시점성 시험은 기말과 공시일을 <b>따로</b> 움직여야 한다. */
    private void insertRevision(long stockId, int year, String period, LocalDate periodEnd,
                                String currencyCode, String shares, int revision, boolean latest,
                                LocalDate publishedAt) {
        jdbcTemplate.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end,
                     published_at, revision_no, is_latest, currency_code, shares_outstanding)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stockId, year, period, periodEnd.minusMonths(3), periodEnd,
                publishedAt, revision, latest, currencyCode,
                shares == null ? null : new BigDecimal(shares));
    }
}
