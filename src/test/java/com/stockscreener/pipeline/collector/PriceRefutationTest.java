package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.support.CountingIndicatorInvalidationPort;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적용 직전 가격 반증(ADR-0181 · {@link PriceRefutation}). 미국 소스 공시만 걸리고, 꼴마다 대조군을 둔다.
 * 봉은 효력일 앞 100일을 ±0.4%로 번갈아 움직여 σ ≈ 0.004를 만든다.
 */
@JpaSliceTest
@Import({CorporateActionLedger.class, PriceWriter.class, PriceObservationWindow.class})
class PriceRefutationTest {

    private static final LocalDate EX = LocalDate.of(2023, 4, 13);

    @Autowired
    private CorporateActionLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    private AdjustmentRecalculator recalculator;

    @BeforeEach
    void setUp() {
        recalculator = new AdjustmentRecalculator(jdbc, ledger, new CountingIndicatorInvalidationPort());
    }

    @Test
    @DisplayName("관측이 1에 더 가깝고 k ≥ 5면 보류한다 — RJF 2023-04-13 「주식배당 1.3」(종가 92.08 → 92.86)")
    void holdsWhenPriceContradicts() {
        long rjf = stock("US", "RJF");
        history(rjf, "92.08");
        bar(rjf, EX, "92.86", 1_234_129);
        long id = approved(rjf, "BONUS_ISSUE", "1.3");

        recalculator.rebuild(rjf);

        assertThat(status(id)).isEqualTo("OBSERVED");
        assertThat(note(id)).startsWith("가격 반증 — 관측 ×1.0085");
        assertThat(factorBefore(rjf)).as("적용 안 됨").isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("대조군 — 관측이 기대에 가까우면 적용한다(CBSH 2024-12-03 5% 주식배당 · 73.15 → 68.75)")
    void appliesWhenPriceSupports() {
        long cbsh = stock("US", "CBSH");
        history(cbsh, "73.15");
        bar(cbsh, EX, "68.75", 431_577);
        long id = approved(cbsh, "BONUS_ISSUE", "1.05");

        recalculator.rebuild(cbsh);

        assertThat(status(id)).isEqualTo("APPLIED");
        assertThat(factorBefore(cbsh)).isLessThan(BigDecimal.ONE);
    }

    @Test
    @DisplayName("앞 마지막 봉이 체결 없는 채움이면 보류한다 — CORZ 2024-01-24(장외 평봉 0.0751)")
    void holdsWhenPreBarIsAFill() {
        long corz = stock("US", "CORZ");
        history(corz, "0.0751");
        jdbc.update("UPDATE price_daily SET volume = 0, open = close, high = close, low = close WHERE stock_id = ? AND trade_date = ?",
                corz, lastBefore(EX));
        bar(corz, EX, "3.44", 3_722_662);
        long id = approved(corz, "REVERSE_SPLIT", "0.1");

        recalculator.rebuild(corz);

        assertThat(status(id)).isEqualTo("OBSERVED");
        assertThat(note(id)).contains("체결 없는 채움");
    }

    @Test
    @DisplayName("효력일 뒤 봉이 아직 없으면 이번 판에 적용하지 않고 APPROVED로 둔다 · 다음 판에 판정한다")
    void defersUntilThePostBarArrives() {
        long cbsh = stock("US", "CBSX");
        history(cbsh, "73.15");
        long id = approved(cbsh, "BONUS_ISSUE", "1.05");

        recalculator.rebuild(cbsh);
        assertThat(status(id)).isEqualTo("APPROVED");

        bar(cbsh, EX, "68.75", 431_577);
        recalculator.rebuild(cbsh);
        assertThat(status(id)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("앞 봉이 없으면(상장 전 사건 · 봉 없는 순위 후보) 소스 정본대로 적용한다")
    void appliesWhenThereIsNoPreBar() {
        long candidate = stock("US", "NEWB");
        bar(candidate, EX, "10", 1_000);
        long id = approved(candidate, "SPLIT", "2");

        recalculator.rebuild(candidate);

        assertThat(status(id)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("대조군 — 국내는 이 반증을 안 탄다(소스와 판정 경로가 다르다)")
    void domesticIsNotScreened() {
        long kr = stock("KOSPI", "005930");
        history(kr, "92.08");
        bar(kr, EX, "92.86", 1_000);
        long id = approved(kr, "BONUS_ISSUE", "1.3");

        recalculator.rebuild(kr);

        assertThat(status(id)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("보류 행은 다음 수집이 같은 값으로 다시 와도 APPROVED로 안 돌아간다 · 재구축이 다시 보류하지 않는다(표지 1회)")
    void collectionDoesNotReviveAHeldRow() {
        long rjf = stock("US", "RJF");
        history(rjf, "92.08");
        bar(rjf, EX, "92.86", 1_234_129);
        long id = approved(rjf, "BONUS_ISSUE", "1.3");
        recalculator.rebuild(rjf);

        CorporateActionLedger.RecordResult again = ledger.record(rjf, disclosed("BONUS_ISSUE", "1.3"));
        recalculator.rebuild(rjf);

        assertThat(again.outcome()).isEqualTo(CorporateActionLedger.Outcome.IGNORED);
        assertThat(status(id)).isEqualTo("OBSERVED");
        assertThat(note(id).split(PriceRefutation.NOTE_MARK + " — ", -1)).as("표지 1회").hasSize(2);
    }

    @Test
    @DisplayName("보류 행에 소스가 고친 값이 오면 덮고 다시 판정한다 — 정정된 비율은 가격이 받친다")
    void sourceCorrectionReopensAHeldRow() {
        long rjf = stock("US", "RJF");
        history(rjf, "92.08");
        bar(rjf, EX, "92.86", 1_234_129);
        long id = approved(rjf, "BONUS_ISSUE", "1.3");
        recalculator.rebuild(rjf);

        CorporateActionLedger.RecordResult corrected = ledger.record(rjf, disclosed("BONUS_ISSUE", "1.005"));
        recalculator.rebuild(rjf);

        assertThat(corrected.outcome()).isEqualTo(CorporateActionLedger.Outcome.UPGRADED);
        assertThat(status(id)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("사람이 보류 행을 APPROVED로 판정하면 다음 재구축이 가격 반증 없이 적용한다")
    void humanApprovalIsNotHeldAgain() {
        long rjf = stock("US", "RJF");
        history(rjf, "92.08");
        bar(rjf, EX, "92.86", 1_234_129);
        long id = approved(rjf, "BONUS_ISSUE", "1.3");
        recalculator.rebuild(rjf);

        ledger.judge(id, new CorporateActionLedger.Judgement(CorporateActionStatus.APPROVED, null, null, null, null, "사람 확인"));
        recalculator.rebuild(rjf);

        assertThat(status(id)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("대조군 — 값만 고치는 판정(상태 없음)은 보류를 안 푼다")
    void valueOnlyJudgementKeepsTheHold() {
        long rjf = stock("US", "RJF");
        history(rjf, "92.08");
        bar(rjf, EX, "92.86", 1_234_129);
        long id = approved(rjf, "BONUS_ISSUE", "1.3");
        recalculator.rebuild(rjf);

        ledger.judge(id, new CorporateActionLedger.Judgement(null, null, null, null, null, "메모만"));
        ledger.record(rjf, disclosed("BONUS_ISSUE", "1.3"));
        recalculator.rebuild(rjf);

        assertThat(status(id)).isEqualTo("OBSERVED");
    }

    @Test
    @DisplayName("보류 알림은 보류한 판에 그 행을 집는다")
    void heldSinceListsTheHeldRow() {
        long rjf = stock("US", "RJF");
        history(rjf, "92.08");
        bar(rjf, EX, "92.86", 1_234_129);
        approved(rjf, "BONUS_ISSUE", "1.3");
        java.time.Instant since = jdbc.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();

        recalculator.rebuild(rjf);

        assertThat(ledger.heldSince(since)).containsExactly("RJF " + EX + " BONUS_ISSUE 1.3000000000");
    }

    @Test
    @DisplayName("k < 5라 가격만으로 못 가르는 오류를 주식수가 가른다 — GBCI 2021-02-04 「주식배당 1.1」(종가 48.86 → 49.74 · 주식수 +0.05%)")
    void sharesRefuteWhatPriceCannot() {
        long gbci = stock("US", "GBCI");
        noisyHistory(gbci, "48.86");
        bar(gbci, EX, "49.74", 500_000);
        steadyFilings(gbci, "100000000");
        filing(gbci, 2023, "Q2", LocalDate.of(2023, 6, 30), LocalDate.of(2023, 8, 4), "100050000");
        long id = approved(gbci, "BONUS_ISSUE", "1.1");

        recalculator.rebuild(gbci);

        assertThat(status(id)).isEqualTo("OBSERVED");
        assertThat(note(id)).startsWith(PriceRefutation.NOTE_MARK + " — 주식수");
        assertThat(factorBefore(gbci)).as("적용 안 됨").isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("대조군 — 주식수가 배수만큼 늘었으면 가격이 1에 가까워도 적용한다(CBSH 2023-12-01 5% 주식배당 꼴)")
    void sharesConfirmTheEvent() {
        long cbsh = stock("US", "CBSH");
        noisyHistory(cbsh, "48.86");
        bar(cbsh, EX, "49.74", 500_000);
        steadyFilings(cbsh, "100000000");
        filing(cbsh, 2023, "Q2", LocalDate.of(2023, 6, 30), LocalDate.of(2023, 8, 4), "110000000");
        long id = approved(cbsh, "BONUS_ISSUE", "1.1");

        recalculator.rebuild(cbsh);

        assertThat(status(id)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("대조군 — 사건 없는 판 쌍이 8개 미만이면 주식수 렌즈는 말하지 않고 적용한다")
    void sharesStaySilentWithoutEnoughHistory() {
        long thin = stock("US", "THIN");
        noisyHistory(thin, "48.86");
        bar(thin, EX, "49.74", 500_000);
        filing(thin, 2022, "Q4", LocalDate.of(2022, 12, 31), LocalDate.of(2023, 2, 20), "100000000");
        filing(thin, 2023, "Q2", LocalDate.of(2023, 6, 30), LocalDate.of(2023, 8, 4), "100050000");
        long id = approved(thin, "BONUS_ISSUE", "1.1");

        recalculator.rebuild(thin);

        assertThat(status(id)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("뒤 판이 없을 때 적용된 행은 판이 오면 다시 판정해 보류한다 — 계수가 1로 돌아가고 적용 이력은 남는다")
    void appliedRowIsHeldWhenTheFilingArrives() {
        long gbci = stock("US", "GBCI");
        noisyHistory(gbci, "48.86");
        bar(gbci, EX, "49.74", 500_000);
        steadyFilings(gbci, "100000000");
        long id = approved(gbci, "BONUS_ISSUE", "1.1");
        recalculator.rebuild(gbci);
        assertThat(status(id)).isEqualTo("APPLIED");

        filing(gbci, 2023, "Q2", LocalDate.of(2023, 6, 30), LocalDate.of(2023, 8, 4), "100050000");
        recalculator.rebuild(gbci);

        assertThat(status(id)).isEqualTo("OBSERVED");
        assertThat(jdbc.queryForObject("SELECT applied_at IS NOT NULL AND held_at IS NOT NULL FROM corporate_action WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(factorBefore(gbci)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("대조군 — 사건 뒤에 공시됐어도 기말이 사건 앞인 판(20-F 꼴)은 뒤 판이 아니다 · 다음 판으로 판정한다(QGEN 2024-01-30)")
    void afterFilingMustEndAfterTheEvent() {
        long qgen = stock("US", "QGEN");
        noisyHistory(qgen, "48.86");
        bar(qgen, EX, "49.74", 500_000);
        steadyFilings(qgen, "100000000");
        filing(qgen, 2023, "Q1", LocalDate.of(2023, 3, 31), LocalDate.of(2023, 5, 5), "100000000");
        filing(qgen, 2023, "Q2", LocalDate.of(2023, 6, 30), LocalDate.of(2023, 8, 4), "110000000");
        long id = approved(qgen, "BONUS_ISSUE", "1.1");

        recalculator.rebuild(qgen);

        assertThat(status(id)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("대조군 — 같은 판 키에 주식수가 둘이면(Q4·FY 겹침) 렌즈는 말하지 않는다")
    void ambiguousFilingIsSilent() {
        long amb = stock("US", "AMB");
        noisyHistory(amb, "48.86");
        bar(amb, EX, "49.74", 500_000);
        steadyFilings(amb, "100000000");
        filing(amb, 2023, "Q2", LocalDate.of(2023, 6, 30), LocalDate.of(2023, 8, 4), "100050000");
        filing(amb, 2023, "FY", LocalDate.of(2023, 6, 30), LocalDate.of(2023, 8, 4), "110000000");
        long id = approved(amb, "BONUS_ISSUE", "1.1");

        recalculator.rebuild(amb);

        assertThat(status(id)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("대조군 — 사람이 본 APPLIED 행(옛 판정의 노트 · judged_at)은 다시 판정하지 않는다")
    void humanReviewedAppliedRowsAreNotRescreened() {
        long noted = stock("US", "NOTED");
        history(noted, "92.08");
        bar(noted, EX, "92.86", 1_234_129);
        long a = approved(noted, "BONUS_ISSUE", "1.3");
        jdbc.update("UPDATE corporate_action SET status = 'APPLIED', applied_at = now(), note = '사람 확인' WHERE id = ?", a);

        long judged = stock("US", "JUDGED");
        history(judged, "92.08");
        bar(judged, EX, "92.86", 1_234_129);
        long b = approved(judged, "BONUS_ISSUE", "1.3");
        jdbc.update("UPDATE corporate_action SET status = 'APPLIED', applied_at = now(), judged_at = now() WHERE id = ?", b);

        recalculator.rebuild(noted);
        recalculator.rebuild(judged);

        assertThat(status(a)).isEqualTo("APPLIED");
        assertThat(status(b)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("앞 봉 없이 적용된 행(봉 없는 후보)은 봉이 생기면 가격으로 다시 판정한다")
    void rowAppliedWithoutBarsIsRescreenedWhenBarsArrive() {
        long rjf = stock("US", "RJF");
        bar(rjf, EX, "92.86", 1_234_129);
        long id = approved(rjf, "BONUS_ISSUE", "1.3");
        recalculator.rebuild(rjf);
        assertThat(status(id)).isEqualTo("APPLIED");

        history(rjf, "92.08");
        recalculator.rebuild(rjf);

        assertThat(status(id)).isEqualTo("OBSERVED");
    }

    @Test
    @DisplayName("적용됐다가 보류된 행을 사람이 APPROVED하면 「승인됐는데 계수 없음」이 센다 — applied_at이 남아 있어도")
    void reapprovedRowCountsAsWithoutCoefficient() {
        long rjf = stock("US", "RJF");
        bar(rjf, EX, "92.86", 1_234_129);
        long id = approved(rjf, "BONUS_ISSUE", "1.3");
        recalculator.rebuild(rjf);
        history(rjf, "92.08");
        recalculator.rebuild(rjf);
        long before = new com.stockscreener.pipeline.batch.JobFreshnessPolicy(jdbc).approvedWithoutCoefficient();

        ledger.judge(id, new CorporateActionLedger.Judgement(CorporateActionStatus.APPROVED, null, null, null, null, "사람 확인"));

        assertThat(new com.stockscreener.pipeline.batch.JobFreshnessPolicy(jdbc).approvedWithoutCoefficient()).isEqualTo(before + 1);
    }

    /** 효력일 앞 약 100일을 ±3%로 번갈아 움직인다 — σ ≈ 0.03이라 비율 1.1의 k가 약 3.2로 5 아래다(GBCI 3.43). */
    private void noisyHistory(long stockId, String lastClose) {
        BigDecimal last = new BigDecimal(lastClose);
        BigDecimal up = new BigDecimal("1.03");
        int i = 0;
        for (LocalDate d = EX.minusDays(1); d.isAfter(EX.minusDays(100)); d = d.minusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            BigDecimal close = i % 2 == 0 ? last : last.divide(up, 6, RoundingMode.HALF_UP);
            bar(stockId, d, close.toPlainString(), 1_000_000);
            i++;
        }
    }

    /** 2020 Q1 ~ 2022 Q4 분기 판 12개 · 주식수 고정 — 사건 없는 2칸 쌍 10개. 마지막 판이 사건 앞 판이다. */
    private void steadyFilings(long stockId, String shares) {
        for (int year = 2020; year <= 2022; year++) {
            for (int q = 1; q <= 4; q++) {
                LocalDate end = LocalDate.of(year, q * 3, 1).plusMonths(1).minusDays(1);
                filing(stockId, year, "Q" + q, end, end.plusDays(40), shares);
            }
        }
    }

    private void filing(long stockId, int year, String period, LocalDate periodEnd, LocalDate publishedAt, String shares) {
        jdbc.update("""
                INSERT INTO financial_statement
                    (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at, currency_code, shares_outstanding)
                VALUES (?, ?, ?, ?, ?, ?, 'USD', ?)
                """, stockId, year, period, periodEnd.minusMonths(3), periodEnd, publishedAt, new BigDecimal(shares));
    }

    private CorporateAction disclosed(String type, String ratio) {
        return new CorporateAction(EX, EX, EX, CorporateAction.Type.valueOf(type), new BigDecimal(ratio),
                CorporateAction.DetectionSource.SOURCE_DISCLOSURE, null);
    }

    private long stock(String market, String ticker) {
        return jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id", Long.class,
                market, ticker, ticker);
    }

    /** 효력일 앞 약 100일 — 마지막 봉이 {@code lastClose}가 되게 ±0.4%로 번갈아 움직인다. */
    private void history(long stockId, String lastClose) {
        BigDecimal last = new BigDecimal(lastClose);
        BigDecimal up = new BigDecimal("1.004");
        int i = 0;
        for (LocalDate d = EX.minusDays(1); d.isAfter(EX.minusDays(100)); d = d.minusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            BigDecimal close = i % 2 == 0 ? last : last.divide(up, 6, RoundingMode.HALF_UP);
            bar(stockId, d, close.toPlainString(), 1_000_000);
            i++;
        }
    }

    private void bar(long stockId, LocalDate date, String close, long volume) {
        BigDecimal c = new BigDecimal(close);
        jdbc.update("""
                INSERT INTO price_daily (stock_id, trade_date, open, high, low, close, adj_close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", stockId, date, c, c, c, c, c, volume);
    }

    private long approved(long stockId, String type, String ratio) {
        return jdbc.queryForObject("""
                INSERT INTO corporate_action (stock_id, effective_date, listing_date, type, ratio, detection_source, status)
                VALUES (?, ?, ?, ?, ?, 'SOURCE_DISCLOSURE', 'APPROVED') RETURNING id""", Long.class, stockId, EX, EX, type,
                new BigDecimal(ratio));
    }

    private LocalDate lastBefore(LocalDate date) {
        return jdbc.queryForObject("SELECT max(trade_date) FROM price_daily WHERE trade_date < ?", LocalDate.class, date);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM corporate_action WHERE id = ?", String.class, id);
    }

    private String note(long id) {
        return jdbc.queryForObject("SELECT note FROM corporate_action WHERE id = ?", String.class, id);
    }

    private BigDecimal factorBefore(long stockId) {
        return jdbc.queryForObject("SELECT adj_factor FROM price_daily WHERE stock_id = ? AND trade_date = ?", BigDecimal.class,
                stockId, lastBefore(EX));
    }
}
