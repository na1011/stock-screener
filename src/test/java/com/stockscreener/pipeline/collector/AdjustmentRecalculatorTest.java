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

import static com.stockscreener.pipeline.support.Judgements.approved;
import static com.stockscreener.pipeline.support.Judgements.rejected;
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
        // 가격이 10배로 뛰었다 = 10주가 1주가 됐다. 비율은 주식수가 답하고 가격은 그것을
        // 뒷받침하기만 한다 — 그래서 봉에 상장주식수가 함께 있어야 한다.
        writer.write(stockId, List.of(
                bar("2026-01-02", "1000", 1_000_000_000L),
                bar("2026-02-02", "10000", 100_000_000L)));

        List<CorporateAction> candidates = recalculator.detectCandidates(stockId).candidates();
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
    @DisplayName("대한전선 2023-05-16 실측값 왕복 — 비율이 종가가 아니라 주식수에서 나온다")
    void reverseSplitRoundTripOnRealValues() {
        // 종가 배수의 역수는 0.094073이고 주식수 배수는 정확히 0.1이다. 6% 차이가 승인하는
        // 순간 전 구간에 소급 적용되는데, 값은 어느 쪽이든 그럴듯하다.
        writer.write(stockId, List.of(
                bar("2023-04-27", "1492", 1_244_473_009L),
                bar("2023-05-16", "15860", 124_447_300L)));

        CorporateAction merge = recalculator.detectCandidates(stockId).candidates().get(0);
        assertThat(merge.ratio()).isEqualByComparingTo("0.1");

        approve(merge);
        recalculator.rebuild(stockId);

        // 종가에서 냈으면 1492 / 0.094073 = 15,860이 나온다 — 정확해 보이지만 그것은
        // 그날 시장이 움직인 만큼 흐려진 값이다. 주식수에서 내면 1492 × 10 = 14,920이다.
        assertThat(adjClose("2023-04-27")).isEqualByComparingTo("14920");
        assertThat(adjFactor("2023-04-27"))
                .as("스냅이 없으면 10.0000000700이 되어 봉이 14,920.000104로 조정된다")
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("분할 왕복 — 탐지가 내는 비율이 두 방향 모두 계약을 지킨다")
    void splitRoundTripLowersPastPrices() {
        writer.write(stockId, List.of(
                bar("2026-01-02", "50000", 10_000_000L),
                bar("2026-02-02", "1000", 500_000_000L)));

        CorporateAction split = recalculator.detectCandidates(stockId).candidates().get(0);
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
    @DisplayName("가격 배수가 따로 적힌 사건은 그것으로 짓고, 주식수 배수는 주식수 쪽에만 남는다 — 분사(V60)")
    void priceRatioDrivesTheFactorWhileRatioStaysTheShareMultiple() {
        // 한화 2026-08-25 모양: 주식수 ×0.7563532970 · 가치비 v 0.8816 → 가격 배수 s/v = 0.8580.
        // 주식수 배수를 가격에 실으면 사건 전 봉이 ×1.3221로 오른다(ADR-0117 §규칙의 결함).
        writer.write(stockId, List.of(bar("2026-07-29", "83800"), bar("2026-08-25", "118100")));
        long id = approveDisclosed("2026-08-25", CorporateAction.Type.SPIN_OFF, "0.7563532970");
        jdbcTemplate.update("UPDATE corporate_action SET price_ratio = 0.8580 WHERE id = ?", id);

        recalculator.rebuild(stockId);

        assertThat(adjFactor("2026-07-29"))
                .as("1/0.8580 — 주식수 배수 0.756을 읽으면 1.3221이 나온다")
                .isEqualByComparingTo(BigDecimal.ONE.divide(new BigDecimal("0.8580"), 10, java.math.RoundingMode.HALF_UP));
        assertThat(ledger.findApplicable(stockId)).singleElement()
                .satisfies(e -> {
                    assertThat(e.ratio()).as("주식수 소비자가 읽는 값").isEqualByComparingTo("0.7563532970");
                    assertThat(e.priceMultiple()).isEqualByComparingTo("0.8580");
                });
    }

    @Test
    @DisplayName("가격 배수가 비면 주식수 배수로 짓는다 — 분할·병합은 V60 앞과 한 칸도 안 바뀐다")
    void missingPriceRatioFallsBackToRatio() {
        writer.write(stockId, List.of(bar("2026-01-02", "1000"), bar("2026-02-02", "200")));
        approveDisclosed("2026-02-02", CorporateAction.Type.SPLIT, "5");

        recalculator.rebuild(stockId);

        assertThat(adjFactor("2026-01-02")).isEqualByComparingTo("0.2");
        assertThat(ledger.findApplicable(stockId)).singleElement()
                .satisfies(e -> assertThat(e.priceRatio()).isNull());
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

        ledger.judge(id, rejected("하한가 연속이었다"));
        recalculator.rebuild(stockId);

        assertThat(adjFactor("2026-01-02")).isEqualByComparingTo("1");
        assertThat(adjClose("2026-01-02")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("사람이 확인하지 않은 후보는 계수에 닿지 않는다 — 오탐의 대가가 너무 크다")
    void observedCandidateIsNeverApplied() {
        writer.write(stockId, List.of(bar("2026-01-02", "50000"), bar("2026-02-02", "1000")));
        // 탐지 결과를 승인 없이 그대로 원장에 적는다 — 일일 갱신이 하는 일이다.
        recalculator.detectCandidates(stockId).candidates().forEach(a -> ledger.record(stockId, a));

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
        // 주식수를 채운다. 이 시험의 주제는 임계이고, 주식수가 없으면 그 앞의 게이트에서
        // 걸러져 임계를 재지 못한다(detectCandidates javadoc).
        writer.write(stockId, List.of(
                bar("2026-01-02", "1000", 1_000_000L),
                bar("2026-01-05", "1250", 1_000_000L),   // +25% — 정상
                bar("2026-01-06", "1688", 1_000_000L),   // +35% — 후보
                bar("2026-01-07", "1266", 1_000_000L),   // −25% — 정상
                bar("2026-01-08", "823", 1_000_000L)));  // −35% — 후보

        assertThat(recalculator.detectCandidates(stockId).candidates())
                .extracting(CorporateAction::effectiveDate)
                .containsExactly(LocalDate.parse("2026-01-06"), LocalDate.parse("2026-01-08"));
    }

    @Test
    @DisplayName("정상적인 등락은 후보로 잡지 않는다")
    void ignoresNormalPriceMovement() {
        writer.write(stockId, List.of(
                bar("2026-01-02", "10000"), bar("2026-01-05", "10500"), bar("2026-01-06", "9800")));

        assertThat(recalculator.detectCandidates(stockId).candidates()).isEmpty();
    }

    @Test
    @DisplayName("주식수가 그대로인 급락은 SPLIT이 아니라 UNKNOWN이다 — 무상증자 권리락이 그 모양이다")
    void exRightsDayWithUnchangedShareCountIsUnknown() {
        // 루닛 2023-11-09 실측. 예전에는 여기서 SPLIT + ratio 1.89가 나갔고, 승인하면
        // 틀린 계수가 들어갔다. 무상증자의 비율은 신주상장일에야 나온다(dart-corporate-action.md의 「분할·병합의 비율과 효력일」 절).
        writer.write(stockId, List.of(
                bar("2023-11-08", "170500", 12_381_004L),
                bar("2023-11-09", "90200", 12_381_004L)));

        assertThat(recalculator.detectCandidates(stockId).candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.type()).isEqualTo(CorporateAction.Type.UNKNOWN);
            assertThat(candidate.ratio())
                    .as("관측된 가격 배수일 뿐 계수가 아니다")
                    .isEqualByComparingTo("1.8902439024");
        });
        assertThat(ledgerAfterRecordingCandidates())
                .as("UNKNOWN은 적용 대상에서 빠진다")
                .isEmpty();
    }

    @Test
    @DisplayName("주식수 배수가 단순 유리수가 아니면 UNKNOWN이다 — 인적분할이 그 모양이다")
    void spinOffIsUnknown() {
        // 삼성바이오로직스 2025-11-24 실측(직전 봉은 10-29다 — 그 사이가 거래정지다).
        // 주식수 배수 0.65039…는 분모 50 안에서 단순 유리수로 떨어지지 않는다. 그것을
        // REVERSE_SPLIT으로 읽으면 인적분할에 틀린 계수가 들어간다.
        writer.write(stockId, List.of(
                bar("2025-10-29", "1221000", 71_174_000L),
                bar("2025-11-24", "1789000", 46_290_951L)));

        assertThat(recalculator.detectCandidates(stockId).candidates())
                .singleElement()
                .extracting(CorporateAction::type)
                .isEqualTo(CorporateAction.Type.UNKNOWN);
    }

    /**
     * <b>이 시험은 계약을 뒤집은 자리다.</b> 전에는 주식수가 없어도 {@code UNKNOWN} 후보를
     * 올렸는데, 그 행은 무해하지 않다 — {@code findUnadjusted}가 {@code OBSERVED}를 돌려주고
     * {@code UnadjustedActionGuard}가 <b>그 효력일을 창에 삼키는 산출 일자를 억제한다.</b>
     * 임계가 KRX 가격제한폭이라 그 제도가 없는 시장에서는 정상 매매가 그 폭을 넘고,
     * 실측으로 미국 1,652,647쌍 중 771건·317종목이 그렇게 올라온다(국내는 543,002쌍 중 72건).
     *
     * <p><b>「가격으로 비율을 대신 내지 않는다」는 옛 시험의 이유는 그대로 유효하다</b> —
     * 바뀐 것은 <b>올릴지</b>이고 {@code parValueRatio}는 안 건드렸다.
     */
    @Test
    @DisplayName("주식수가 없으면 후보로 올리지 않는다 — 판정할 수 없는 후보가 지표를 억제한다")
    void missingShareCountYieldsNoCandidate() {
        writer.write(stockId, List.of(bar("2023-04-27", "1492"), bar("2023-05-16", "15860")));

        assertThat(recalculator.detectCandidates(stockId).candidates()).isEmpty();
    }

    @Test
    @DisplayName("주식수가 있는데 비율이 가격을 설명하지 못하면 여전히 UNKNOWN이다")
    void unexplainedShareRatioStillYieldsUnknown() {
        // 주식수가 1.54배로 늘었는데 가격은 1.47배로 올랐다 — 액면 변경으로 설명되지 않는다.
        writer.write(stockId, List.of(
                bar("2025-10-29", "1221000", 71_174_000L),
                bar("2025-11-24", "1789000", 109_608_000L)));

        assertThat(recalculator.detectCandidates(stockId).candidates())
                .singleElement()
                .extracting(CorporateAction::type)
                .isEqualTo(CorporateAction.Type.UNKNOWN);
    }

    @Test
    @DisplayName("탐지 결과는 전부 HEURISTIC이다 — 원장이 그것으로 승인 게이트를 건다")
    void candidatesAreAlwaysHeuristic() {
        writer.write(stockId, List.of(
                bar("2026-01-02", "100000", 1_000_000L),
                bar("2026-01-05", "2000", 50_000_000L)));

        assertThat(recalculator.detectCandidates(stockId).candidates())
                .isNotEmpty()
                .allMatch(a -> a.source() == CorporateAction.DetectionSource.HEURISTIC);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 후보를 전부 원장에 적고 그중 실제로 적용될 것을 낸다. */
    private List<CorporateActionLedger.Entry> ledgerAfterRecordingCandidates() {
        recalculator.detectCandidates(stockId).candidates().forEach(a -> ledger.record(stockId, a));
        return ledger.findApplicable(stockId);
    }

    /** 탐지가 낸 후보를 <b>값을 고치지 않고</b> 승인한다. 관리자 API가 할 일의 최소 형태다. */
    private void approve(CorporateAction candidate) {
        long id = ledger.record(stockId, candidate).existingId();
        ledger.judge(id, approved("사람이 확인했다"));
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
    /** 상장주식수가 없는 봉. 탐지는 이런 봉에서 <b>종류를 모르는 것으로 둔다.</b> */
    // ── 거래정지 뒤 첫 거래 봉(RESUMPTION_SHARE_WINDOW_BARS) ─────────────────────

    @Test
    @DisplayName("거래정지 뒤 첫 거래 봉의 가격 발견은 앞쪽 창에서 주식수가 그대로면 후보가 아니다")
    void priceDiscoveryAfterHaltIsNotACandidate() {
        // 피노 2023-03-30 모양: 정지 봉 829 → 재개 1,273(×1.5356) · 주식수 그대로.
        writer.write(stockId, resumption("829", "1273", 5_000_000L, 5_000_000L, 45));

        assertThat(recalculator.detectCandidates(stockId).candidates())
                .as("정지 봉 → 재개 봉 급등은 기업행위가 아니다 — OBSERVED로 쌓이면 장기창 지표가 억제된다")
                .isEmpty();
    }

    @Test
    @DisplayName("재개 뒤 앞쪽 창 안에서 주식수가 늘면 후보로 남긴다 — 권리락이 재개일에 걸린 모양이다")
    void resumptionWithLaterShareIncreaseStaysACandidate() {
        List<RawPriceBar> bars = new java.util.ArrayList<>(
                resumption("20000", "10000", 5_000_000L, 5_000_000L, 45));
        // 재개 20봉 뒤 신주상장(원장 무상증자 권리락→상장 간격 최대 34봉 안).
        for (int i = 22; i < bars.size(); i++) {
            RawPriceBar b = bars.get(i);
            bars.set(i, bar(b.tradeDate().toString(), b.close().toPlainString(), 10_000_000L));
        }
        writer.write(stockId, bars);

        assertThat(recalculator.detectCandidates(stockId).candidates())
                .extracting(CorporateAction::effectiveDate)
                .containsExactly(bars.get(1).tradeDate());
    }

    @Test
    @DisplayName("재개 뒤 봉이 창만큼 안 쌓였으면 후보로 남긴다 — 신주가 아직 상장 안 된 권리락일 수 있다")
    void resumptionTailStaysACandidate() {
        writer.write(stockId, resumption("829", "1273", 5_000_000L, 5_000_000L, 10));

        assertThat(recalculator.detectCandidates(stockId).candidates()).hasSize(1);
    }

    @Test
    @DisplayName("정지 → 재개에서 주식수가 같은 날 바뀐 병합은 그대로 잡는다 — 대한전선 2023-05-16")
    void mergeAtResumptionIsStillDetected() {
        writer.write(stockId, resumption("1492", "15860", 1_244_473_009L, 124_447_300L, 45));

        assertThat(recalculator.detectCandidates(stockId).candidates())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.type()).isEqualTo(CorporateAction.Type.REVERSE_SPLIT);
                    assertThat(candidate.ratio()).isEqualByComparingTo("0.1");
                });
    }

    @Test
    @DisplayName("정지 중의 주식수 변화는 재개 판정에 안 끼어든다 — 앞쪽 창만 본다(코오롱티슈진 2022-10-25)")
    void shareChangeDuringHaltDoesNotKeepResumptionAlive() {
        LocalDate day = LocalDate.parse("2022-08-01");
        List<RawPriceBar> bars = new java.util.ArrayList<>();
        bars.add(bar(day.toString(), "3000", 64_920_050L));
        // 정지 30봉 — 재개 27봉 앞에서 주식수가 1.0612배로 는다(정지 봉 위).
        for (int i = 1; i <= 30; i++) {
            day = nextBusinessDay(day);
            bars.add(halted(day.toString(), "3080", i < 4 ? 64_920_050L : 68_890_050L));
        }
        day = nextBusinessDay(day);
        bars.add(bar(day.toString(), "8010", 68_890_050L));   // ×2.6006
        for (int i = 0; i < 45; i++) {
            day = nextBusinessDay(day);
            bars.add(bar(day.toString(), "8010", 68_890_050L));
        }
        writer.write(stockId, bars);

        assertThat(recalculator.detectCandidates(stockId).candidates()).isEmpty();
    }

    /**
     * 정지 봉 하나 → 재개 봉 → 그 뒤 {@code after}봉. 정지 봉은 KRX 원형(o=h=l=0 · 거래량 0)으로
     * 넣어 {@link PriceWriter}의 평탄화(ADR-0160)를 그대로 태운다.
     */
    private static List<RawPriceBar> resumption(String haltedClose, String resumedClose,
                                                long sharesBefore, long sharesAfter, int after) {
        LocalDate day = LocalDate.parse("2023-03-02");
        List<RawPriceBar> bars = new java.util.ArrayList<>();
        bars.add(halted(day.toString(), haltedClose, sharesBefore));
        day = nextBusinessDay(day);
        bars.add(bar(day.toString(), resumedClose, sharesAfter));
        for (int i = 0; i < after; i++) {
            day = nextBusinessDay(day);
            bars.add(bar(day.toString(), resumedClose, sharesAfter));
        }
        return bars;
    }

    private static RawPriceBar halted(String date, String close, long listedShares) {
        return new RawPriceBar(LocalDate.parse(date), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(close), 0L, null, BigDecimal.valueOf(listedShares), false);
    }

    private static LocalDate nextBusinessDay(LocalDate day) {
        LocalDate next = day.plusDays(1);
        while (next.getDayOfWeek().getValue() >= 6) {
            next = next.plusDays(1);
        }
        return next;
    }

    private static RawPriceBar bar(String date, String close) {
        return bar(date, close, null);
    }

    private static RawPriceBar bar(String date, String close, Long listedShares) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null,
                listedShares == null ? null : BigDecimal.valueOf(listedShares), false);
    }
}
