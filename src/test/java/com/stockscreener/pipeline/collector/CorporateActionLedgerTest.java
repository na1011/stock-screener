package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.RawPriceBar;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원장이 수정계수의 정본 노릇을 하는지 검증한다(ADR-0049).
 *
 * <p>여기서 지키는 것 셋은 전부 <b>어겨도 아무 에러가 안 나는</b> 종류다 — 휴리스틱이 승인
 * 없이 적용되는 것, 같은 사건이 며칠 어긋난 두 행이 되는 것, 공시가 휴리스틱에 덮이는 것.
 */
@JpaSliceTest
@Import({CorporateActionLedger.class, PriceWriter.class})
class CorporateActionLedgerTest {

    @Autowired
    private CorporateActionLedger ledger;

    @Autowired
    private PriceWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '001440', '대한전선')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("휴리스틱 후보는 OBSERVED로, 공시는 APPROVED로 들어간다 — 이것이 승인 게이트다")
    void detectionSourceDecidesInitialStatus() {
        ledger.record(stockId, heuristic("2026-02-02", "0.1"));
        assertThat(status("2026-02-02")).isEqualTo("OBSERVED");

        ledger.record(stockId, disclosed("2026-06-01", "5"));
        assertThat(status("2026-06-01")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("같은 사건이 11번 올라와도 행은 하나다 — 수집 창이 같은 날짜를 매일 다시 본다")
    void repeatedRecordingKeepsOneRow() {
        CorporateAction action = disclosed("2026-02-02", "5");
        for (int i = 0; i < 11; i++) {
            ledger.record(stockId, action);
        }

        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("공시는 휴리스틱 후보를 덮는다 — 창이 매일 도므로 휴리스틱이 대개 먼저 들어온다")
    void disclosureOverwritesHeuristicCandidate() {
        ledger.record(stockId, heuristic("2026-02-02", "0.0940731400"));

        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, disclosed("2026-02-02", "0.1"));

        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.UPGRADED);
        assertThat(ratio("2026-02-02")).isEqualByComparingTo("0.1");
        assertThat(status("2026-02-02")).isEqualTo("APPROVED");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("사람이 판정한 행은 무엇으로도 덮지 않는다 — 덮으면 같은 후보가 매번 다시 올라온다")
    void humanJudgementIsNeverOverwritten() {
        long id = ledger.record(stockId, heuristic("2026-02-02", "0.5")).existingId();
        ledger.updateStatus(id, "REJECTED", "하한가 연속이었다");

        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, disclosed("2026-02-02", "0.1"));

        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.IGNORED);
        assertThat(status("2026-02-02")).isEqualTo("REJECTED");
        assertThat(ratio("2026-02-02")).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("하루 어긋난 같은 사건은 두 행이 되지 않는다 — 둘 다 승인되면 계수가 두 번 곱해진다")
    void nearDuplicateIsRefused() {
        writer.write(stockId, List.of(
                bar("2026-02-02", "1000"), bar("2026-02-03", "1010"), bar("2026-02-04", "1020")));
        ledger.record(stockId, heuristic("2026-02-03", "0.1"));

        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, disclosed("2026-02-04", "0.1"));

        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.NEAR_DUPLICATE);
        assertThat(result.conflictingDate()).isEqualTo(LocalDate.parse("2026-02-03"));
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("거래정지를 사이에 둔 어긋남도 같은 사건으로 본다 — 대한전선은 병합 전 11거래일이 비어 있다")
    void suspensionGapCountsAsNearDuplicate() {
        // 봉이 4/27 다음 5/16으로 건너뛴다 — 그 사이는 거래정지라 거래일이 0이다.
        writer.write(stockId, List.of(bar("2023-04-27", "1492"), bar("2023-05-16", "15860")));

        ledger.record(stockId, heuristic("2023-05-16", "0.0940731400"));
        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, disclosed("2023-05-02", "0.1"));

        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.NEAR_DUPLICATE);
    }

    @Test
    @DisplayName("봉이 하나도 없어도 멀리 떨어진 두 사건은 별개다 — 거래일만으로 재면 전부 0거래일이다")
    void distantEventsAreNotNearDuplicatesWithoutBars() {
        ledger.record(stockId, disclosed("2021-04-15", "5"));

        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, disclosed("2026-02-02", "0.1"));

        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.INSERTED);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("UNKNOWN은 공시가 알려줬어도 OBSERVED다 — APPROVED면 적용에도 검토 큐에도 안 뜬다")
    void unknownStaysObservedEvenWhenDisclosed() {
        // findApplicable이 종류로 걸러 적용되지 않는데 status가 APPROVED면 사람이 볼
        // 후보 목록에서도 빠진다. updateStatus는 ratio를 못 고치므로 그 행은 영영 살아나지
        // 못한다 — 어디에도 없는데 아무 에러도 나지 않는다.
        ledger.record(stockId, unknown("2025-11-24", "1.465",
                CorporateAction.DetectionSource.SOURCE_DISCLOSURE));

        assertThat(status("2025-11-24")).isEqualTo("OBSERVED");
        assertThat(ledger.findApplicable(stockId)).isEmpty();
    }

    @Test
    @DisplayName("종류를 모르는 공시는 휴리스틱의 관측값을 덮지 않는다 — 덮으면 남는 것이 없다")
    void unknownDisclosureDoesNotEraseHeuristicObservation() {
        // 인적분할처럼 소스가 종류를 확정해 주지 못하는 자리다. 덮으면 휴리스틱이 관측한
        // 종류(REVERSE_SPLIT)와 배수가 사라지고 사람이 볼 근거가 없어진다.
        ledger.record(stockId, heuristic("2023-05-16", "0.0940731400"));

        CorporateActionLedger.RecordResult result = ledger.record(stockId,
                unknown("2023-05-16", "1.465", CorporateAction.DetectionSource.SOURCE_DISCLOSURE));

        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.IGNORED);
        assertThat(ratio("2023-05-16")).isEqualByComparingTo("0.0940731400");
        assertThat(type("2023-05-16")).isEqualTo("REVERSE_SPLIT");
    }

    @Test
    @DisplayName("OBSERVED는 적용 대상이 아니다 — 사람이 확인하지 않은 후보가 저절로 반영되면 안 된다")
    void observedIsExcludedFromApplicable() {
        ledger.record(stockId, heuristic("2026-02-02", "0.1"));

        assertThat(ledger.findApplicable(stockId)).isEmpty();
    }

    @Test
    @DisplayName("적용 대상은 효력일 오름차순으로 나온다 — 구간 계수가 그 순서에 의존한다")
    void applicableIsOrderedByEffectiveDate() {
        ledger.record(stockId, disclosed("2022-01-03", "5"));
        ledger.record(stockId, disclosed("2021-01-04", "2"));

        assertThat(ledger.findApplicable(stockId))
                .extracting(CorporateActionLedger.Entry::effectiveDate)
                .containsExactly(LocalDate.parse("2021-01-04"), LocalDate.parse("2022-01-03"));
    }

    @Test
    @DisplayName("뒤집힌 병합 비율은 원장에 들어올 수 없다 — DB 제약과 도메인 검사가 둘 다 막는다")
    void flippedReverseSplitRatioIsRejected() {
        // detectCandidates가 실제로 이 값을 내고 있었다. ratio는 "1주가 몇 주"이므로 병합이면 <1이다.
        assertThatThrownBy(() -> new CorporateAction(
                LocalDate.parse("2023-05-16"), CorporateAction.Type.REVERSE_SPLIT,
                new BigDecimal("10.6300268068"), CorporateAction.DetectionSource.SOURCE_DISCLOSURE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("부호가 어긋난다");

        // 도메인 검사를 우회해도 DB가 막는다.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, type, ratio, detection_source, status)
                VALUES (?, ?, 'REVERSE_SPLIT', 10.6300268068, 'SOURCE_DISCLOSURE', 'APPROVED')
                """, stockId, LocalDate.parse("2023-05-16")))
                .hasMessageContaining("ck_ca_ratio_sign");
    }

    @Test
    @DisplayName("markApplied는 applied_at을 남기고, 되돌려도 그 흔적이 지워지지 않는다")
    void appliedAtSurvivesReversal() {
        long id = ledger.record(stockId, disclosed("2026-02-02", "5")).existingId();
        ledger.markApplied(List.of(id));

        assertThat(status("2026-02-02")).isEqualTo("APPLIED");
        assertThat(appliedAt(id)).isNotNull();

        ledger.updateStatus(id, "REJECTED", "오탐이었다");

        assertThat(status("2026-02-02")).isEqualTo("REJECTED");
        assertThat(appliedAt(id)).isNotNull();
    }

    private static CorporateAction heuristic(String date, String ratio) {
        return action(date, ratio, CorporateAction.DetectionSource.HEURISTIC);
    }

    private static CorporateAction disclosed(String date, String ratio) {
        return action(date, ratio, CorporateAction.DetectionSource.SOURCE_DISCLOSURE);
    }

    private static CorporateAction unknown(String date, String ratio,
                                           CorporateAction.DetectionSource source) {
        return new CorporateAction(LocalDate.parse(date), CorporateAction.Type.UNKNOWN,
                new BigDecimal(ratio), source);
    }

    private static CorporateAction action(String date, String ratio,
                                          CorporateAction.DetectionSource source) {
        BigDecimal r = new BigDecimal(ratio);
        CorporateAction.Type type = r.compareTo(BigDecimal.ONE) > 0
                ? CorporateAction.Type.SPLIT : CorporateAction.Type.REVERSE_SPLIT;
        return new CorporateAction(LocalDate.parse(date), type, r, source);
    }

    private String status(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                String.class, stockId, LocalDate.parse(date));
    }

    private String type(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT type FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                String.class, stockId, LocalDate.parse(date));
    }

    private BigDecimal ratio(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT ratio FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                BigDecimal.class, stockId, LocalDate.parse(date));
    }

    private Object appliedAt(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT applied_at FROM corporate_action WHERE id = ?", Object.class, id);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM corporate_action WHERE stock_id = ?", Integer.class, stockId);
    }

    private static RawPriceBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null, null, false);
    }
}
