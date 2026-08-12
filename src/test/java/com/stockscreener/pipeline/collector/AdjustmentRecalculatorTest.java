package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.support.CountingIndicatorInvalidationPort;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * 수정계수를 원장에서 다시 짓는 것을 검증한다(ADR-0049).
 *
 * <p>이 계산이 틀리면 기술적 지표 전체가 조용히 망가지고, 분할 종목의 차트를 직접 보기
 * 전까지 드러나지 않는다(ADR-0002).
 *
 * <p><b>부호 테스트는 탐지에서 적용까지 왕복시킨다.</b> 한쪽만 재면 항등식이 된다 — 실제로
 * 예전 병합 테스트가 {@code apply()}에 0.1을 직접 넘겨 탐지를 건너뛰었고, 그래서 탐지가 역수를
 * 실어 보내는 동안에도 초록이었다. 그리고 <b>분할 표본만으로는 부호가 검증되지 않는다</b> —
 * 뒤집힌 것은 병합 쪽뿐이다.
 */
@JpaSliceTest
@Import({CorporateActionLedger.class, PriceWriter.class})
class AdjustmentRecalculatorTest {

    @Autowired
    private CorporateActionLedger ledger;

    @Autowired
    private PriceWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AdjustmentRecalculator recalculator;
    private CountingIndicatorInvalidationPort invalidations;
    private long stockId;

    @BeforeEach
    void setUp() {
        invalidations = new CountingIndicatorInvalidationPort();
        recalculator = new AdjustmentRecalculator(jdbcTemplate, ledger, invalidations);
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
    }

    // ── 부호: 탐지 → 승인 → 재구축 왕복 ────────────────────────────────────────

    @Test
    @DisplayName("병합 왕복 — 탐지가 낸 비율을 그대로 승인해도 과거 가격이 올라간다")
    void reverseSplitRoundTripRaisesPastPrices() {
        // 가격이 10배로 뛰었다 = 10주가 1주가 됐다. 정확 산술이 되도록 고른 값이다.
        writer.write(stockId, List.of(bar("2026-01-02", "1000"), bar("2026-02-02", "10000")));

        List<CorporateAction> candidates = recalculator.detectCandidates(stockId);
        assertThat(candidates).hasSize(1);
        CorporateAction merge = candidates.get(0);

        // 계약은 "1주가 몇 주가 되었는가"이므로 병합이면 1보다 작다. 역수를 실어 보내던 자리다.
        assertThat(merge.type()).isEqualTo(CorporateAction.Type.REVERSE_SPLIT);
        assertThat(merge.ratio()).isEqualByComparingTo("0.1");

        approve(merge);
        recalculator.rebuild(stockId);

        // 부호가 뒤집혀 있으면 100이 나온다 — 오차 100배인데 값은 그럴듯하다.
        assertThat(adjClose("2026-01-02")).isEqualByComparingTo("10000");
        assertThat(adjFactor("2026-01-02")).isEqualByComparingTo("10");
        // 병합 당일 이후는 이미 조정된 가격이므로 그대로다.
        assertThat(adjClose("2026-02-02")).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("대한전선 2023-05-16 실측값으로도 같다 — 반올림 때문에 정확 일치로 재지 않는다")
    void reverseSplitRoundTripOnRealValues() {
        writer.write(stockId, List.of(bar("2023-04-27", "1492"), bar("2023-05-16", "15860")));

        approve(recalculator.detectCandidates(stockId).get(0));
        recalculator.rebuild(stockId);

        // 계수가 numeric(18,10)에서 잘리므로 15,860에 정확히 떨어지지 않는다(실측 15859.999996).
        // 뒤집혀 있으면 140.36이 나온다.
        assertThat(adjClose("2023-04-27").doubleValue()).isCloseTo(15860.0, offset(0.01));
    }

    @Test
    @DisplayName("분할 왕복 — 탐지가 내는 비율이 두 방향 모두 계약을 지킨다")
    void splitRoundTripLowersPastPrices() {
        writer.write(stockId, List.of(bar("2026-01-02", "50000"), bar("2026-02-02", "1000")));

        CorporateAction split = recalculator.detectCandidates(stockId).get(0);
        assertThat(split.type()).isEqualTo(CorporateAction.Type.SPLIT);
        assertThat(split.ratio()).isEqualByComparingTo("50");

        approve(split);
        recalculator.rebuild(stockId);

        assertThat(adjClose("2026-01-02")).isEqualByComparingTo("1000");
        assertThat(adjFactor("2026-01-02")).isEqualByComparingTo("0.02");
    }

    // ── 재구축의 성질 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("사건이 둘이면 구간마다 계수가 다르다 — 곱해 쌓지 않고 원장 전체에서 짓는다")
    void buildsSegmentFactorsFromWholeLedger() {
        writer.write(stockId, List.of(
                bar("2020-01-02", "1000"), bar("2021-06-01", "1000"), bar("2022-06-01", "1000")));
        approveDisclosed("2021-01-04", CorporateAction.Type.SPLIT, "2");
        approveDisclosed("2022-01-03", CorporateAction.Type.SPLIT, "5");

        recalculator.rebuild(stockId);

        assertThat(adjFactor("2020-01-02")).isEqualByComparingTo("0.1");   // 1/(2×5)
        assertThat(adjFactor("2021-06-01")).isEqualByComparingTo("0.2");   // 1/5
        assertThat(adjFactor("2022-06-01")).isEqualByComparingTo("1");
        assertThat(adjClose("2020-01-02")).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("몇 번을 다시 지어도 같고, 두 번째부터는 아무 행도 건드리지 않는다")
    void rebuildIsIdempotent() {
        writer.write(stockId, List.of(bar("2026-01-02", "1000"), bar("2026-02-02", "200")));
        approveDisclosed("2026-02-02", CorporateAction.Type.SPLIT, "5");

        assertThat(recalculator.rebuild(stockId).changedRows()).isEqualTo(1);
        assertThat(recalculator.rebuild(stockId).changedRows()).isZero();
        assertThat(recalculator.rebuild(stockId).changedRows()).isZero();

        assertThat(adjFactor("2026-01-02")).isEqualByComparingTo("0.2");
        assertThat(adjClose("2026-01-02")).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("바뀐 행이 없으면 지표를 무효화하지 않는다 — 무효화는 그 종목 지표를 전량 지운다")
    void doesNotInvalidateWhenNothingChanged() {
        writer.write(stockId, List.of(bar("2026-01-02", "1000"), bar("2026-02-02", "200")));
        approveDisclosed("2026-02-02", CorporateAction.Type.SPLIT, "5");

        recalculator.rebuild(stockId);
        recalculator.rebuild(stockId);
        recalculator.rebuild(stockId);

        assertThat(invalidations.calls()).hasSize(1);
        assertThat(invalidations.calls().get(0).fromDate()).isEqualTo(LocalDate.parse("2026-01-02"));
    }

    @Test
    @DisplayName("반영을 되돌리면 계수가 1로 돌아온다 — 되돌리기 수단이 DB 덤프뿐이 아니다")
    void rejectingAnEventRestoresFactors() {
        writer.write(stockId, List.of(bar("2026-01-02", "1000"), bar("2026-02-02", "200")));
        long id = approveDisclosed("2026-02-02", CorporateAction.Type.SPLIT, "5");
        recalculator.rebuild(stockId);
        assertThat(adjFactor("2026-01-02")).isEqualByComparingTo("0.2");

        ledger.updateStatus(id, "REJECTED", "하한가 연속이었다");
        recalculator.rebuild(stockId);

        assertThat(adjFactor("2026-01-02")).isEqualByComparingTo("1");
        assertThat(adjClose("2026-01-02")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("사람이 확인하지 않은 후보는 계수에 닿지 않는다 — 오탐의 대가가 너무 크다")
    void observedCandidateIsNeverApplied() {
        writer.write(stockId, List.of(bar("2026-01-02", "50000"), bar("2026-02-02", "1000")));
        // 탐지 결과를 승인 없이 그대로 원장에 적는다 — 일일 갱신이 하는 일이다.
        recalculator.detectCandidates(stockId).forEach(a -> ledger.record(stockId, a));

        recalculator.rebuild(stockId);

        assertThat(adjFactor("2026-01-02")).isEqualByComparingTo("1");
        assertThat(invalidations.calls()).isEmpty();
    }

    @Test
    @DisplayName("적용 대상이 원장에 들어오면 APPLIED로 표시된다 — 조정할 봉이 없어도 마찬가지다")
    void marksAppliedEvenWhenNothingToAdjust() {
        writer.write(stockId, List.of(bar("2026-03-02", "1000")));
        long id = approveDisclosed("2026-01-04", CorporateAction.Type.SPLIT, "2");

        assertThat(recalculator.rebuild(stockId).changedRows()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM corporate_action WHERE id = ?", String.class, id))
                .isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("원주가는 보존된다 — PER·시가총액이 이것을 쓴다")
    void keepsRawClose() {
        writer.write(stockId, List.of(bar("2018-05-02", "2500000"), bar("2018-05-04", "53000")));
        approveDisclosed("2018-05-04", CorporateAction.Type.SPLIT, "50");

        recalculator.rebuild(stockId);

        assertThat(adjClose("2018-05-02")).isEqualByComparingTo("50000");
        assertThat(close("2018-05-02")).isEqualByComparingTo("2500000");
    }

    // ── 탐지 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KRX 가격제한폭을 넘는 변동만 후보다 — 임계가 비대칭이다")
    void detectsOnlyMovesBeyondThePriceLimit() {
        // +35% / −35%는 제한폭 밖이고, +25% / −25%는 안이다.
        writer.write(stockId, List.of(
                bar("2026-01-02", "1000"),
                bar("2026-01-05", "1250"),   // +25% — 정상
                bar("2026-01-06", "1688"),   // +35% — 후보
                bar("2026-01-07", "1266"),   // −25% — 정상
                bar("2026-01-08", "823")));  // −35% — 후보

        assertThat(recalculator.detectCandidates(stockId))
                .extracting(CorporateAction::effectiveDate)
                .containsExactly(LocalDate.parse("2026-01-06"), LocalDate.parse("2026-01-08"));
    }

    @Test
    @DisplayName("정상적인 등락은 후보로 잡지 않는다")
    void ignoresNormalPriceMovement() {
        writer.write(stockId, List.of(
                bar("2026-01-02", "10000"), bar("2026-01-05", "10500"), bar("2026-01-06", "9800")));

        assertThat(recalculator.detectCandidates(stockId)).isEmpty();
    }

    @Test
    @DisplayName("탐지 결과는 전부 HEURISTIC이다 — 원장이 그것으로 승인 게이트를 건다")
    void candidatesAreAlwaysHeuristic() {
        writer.write(stockId, List.of(bar("2026-01-02", "100000"), bar("2026-01-05", "2000")));

        assertThat(recalculator.detectCandidates(stockId))
                .allMatch(a -> a.source() == CorporateAction.DetectionSource.HEURISTIC);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 탐지가 낸 후보를 <b>값을 고치지 않고</b> 승인한다. 관리자 API가 할 일의 최소 형태다. */
    private void approve(CorporateAction candidate) {
        long id = ledger.record(stockId, candidate).existingId();
        ledger.updateStatus(id, "APPROVED", "사람이 확인했다");
    }

    private long approveDisclosed(String date, CorporateAction.Type type, String ratio) {
        return ledger.record(stockId, new CorporateAction(
                LocalDate.parse(date), type, new BigDecimal(ratio),
                CorporateAction.DetectionSource.SOURCE_DISCLOSURE)).existingId();
    }

    private BigDecimal adjClose(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT adj_close FROM price_daily WHERE stock_id = ? AND trade_date = ?",
                BigDecimal.class, stockId, LocalDate.parse(date));
    }

    private BigDecimal adjFactor(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT adj_factor FROM price_daily WHERE stock_id = ? AND trade_date = ?",
                BigDecimal.class, stockId, LocalDate.parse(date));
    }

    private BigDecimal close(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT close FROM price_daily WHERE stock_id = ? AND trade_date = ?",
                BigDecimal.class, stockId, LocalDate.parse(date));
    }

    /** OHLC를 종가 기준으로 만든 봉. 분할 계산은 종가만 쓰므로 나머지는 종가에 맞춘다. */
    private static RawPriceBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null, null, false);
    }
}
