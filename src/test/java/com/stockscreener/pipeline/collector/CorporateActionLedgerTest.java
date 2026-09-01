package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.ShareRatioSnap;
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

import static com.stockscreener.pipeline.support.Judgements.approved;
import static com.stockscreener.pipeline.support.Judgements.rejected;
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
        ledger.judge(id, rejected("하한가 연속이었다"));

        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, disclosed("2026-02-02", "0.1"));

        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.IGNORED);
        assertThat(status("2026-02-02")).isEqualTo("REJECTED");
        assertThat(ratio("2026-02-02")).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("하루 어긋난 공시가 휴리스틱 후보를 제자리로 옮긴다 — 소스 원값은 종류마다 반대로 어긋난다")
    void disclosureRelocatesTheCandidateItDisagreesWith() {
        writer.write(stockId, List.of(
                bar("2026-02-02", "1000"), bar("2026-02-03", "1010"), bar("2026-02-04", "1020")));
        ledger.record(stockId, heuristic("2026-02-03", "0.1"));

        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, disclosed("2026-02-04", "0.1"));

        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.UPGRADED);
        // 행이 둘이 되지 않는 것이 이 검사의 원래 목적이다 — 둘 다 승인되면 계수가 두 번 곱해진다.
        assertThat(rowCount()).isEqualTo(1);
        assertThat(status("2026-02-04")).isEqualTo("APPROVED");
        assertThat(rowCountOn("2026-02-03")).isZero();
    }

    @Test
    @DisplayName("나중에 온 휴리스틱은 공시 행을 옮기지 못한다 — 신뢰도가 낮은 쪽이 정본을 흔들면 안 된다")
    void heuristicCannotRelocateADisclosedRow() {
        writer.write(stockId, List.of(
                bar("2026-02-02", "1000"), bar("2026-02-03", "1010"), bar("2026-02-04", "1020")));
        ledger.record(stockId, disclosed("2026-02-04", "0.1"));

        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, heuristic("2026-02-03", "0.0940731400"));

        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.NEAR_DUPLICATE);
        assertThat(result.conflictingDate()).isEqualTo(LocalDate.parse("2026-02-04"));
        assertThat(rowCount()).isEqualTo(1);
        assertThat(ratio("2026-02-04")).isEqualByComparingTo("0.1");
    }

    @Test
    @DisplayName("사람이 판정한 행은 공시도 옮기지 못한다 — 판정이 되돌려지면 같은 후보가 매번 다시 올라온다")
    void judgedRowIsNeverRelocated() {
        writer.write(stockId, List.of(
                bar("2026-02-02", "1000"), bar("2026-02-03", "1010"), bar("2026-02-04", "1020")));
        long id = ledger.record(stockId, heuristic("2026-02-03", "0.5")).existingId();
        ledger.judge(id, rejected("하한가 연속이었다"));

        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, disclosed("2026-02-04", "0.1"));

        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.NEAR_DUPLICATE);
        assertThat(status("2026-02-03")).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("옮길 자리가 차 있으면 옮기지 않는다 — UNIQUE 위반이면 그 종목이 통째로 롤백된다")
    void relocationStopsWhenTheTargetDateIsTaken() {
        writer.write(stockId, List.of(
                bar("2026-02-02", "1000"), bar("2026-02-03", "1010"), bar("2026-02-04", "1020")));
        ledger.record(stockId, heuristic("2026-02-03", "0.1"));
        // IMPORTANT — 이 행은 직접 넣어야 한다. record()로는 근접중복에 막혀 만들 수 없고,
        // 그래서 **이 가드가 발화하는 유일한 경로가 사람이 원장을 직접 고친 경우**다.
        // 원장은 사람의 승인 경로이므로(ADR-0049) 그 경우가 설계에 들어 있다.
        jdbcTemplate.update("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, type, ratio, detection_source, status)
                VALUES (?, DATE '2026-02-04', 'REVERSE_SPLIT', 0.2, 'HEURISTIC', 'REJECTED')
                """, stockId);

        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, disclosed("2026-02-04", "0.1"));

        // 근접중복 검사가 같은 일자 검사보다 먼저 돈다. 옮길 자리가 차 있어 옮기기가 멈추고,
        // 사람이 이미 판정한 날짜에 공시가 왔다는 사실이 그대로 올라간다.
        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.NEAR_DUPLICATE);
        assertThat(rowCount()).isEqualTo(2);
        assertThat(status("2026-02-03")).isEqualTo("OBSERVED");
        assertThat(status("2026-02-04")).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("거래정지를 사이에 둔 어긋남도 같은 사건으로 본다 — 대한전선은 병합 전 11거래일이 비어 있다")
    void suspensionGapCountsAsNearDuplicate() {
        // 봉이 4/27 다음 5/16으로 건너뛴다 — 그 사이는 거래정지라 거래일이 0이다.
        writer.write(stockId, List.of(bar("2023-04-27", "1492"), bar("2023-05-16", "15860")));

        ledger.record(stockId, heuristic("2023-05-16", "0.0940731400"));
        CorporateActionLedger.RecordResult result =
                ledger.record(stockId, disclosed("2023-05-02", "0.1"));

        // 같은 사건으로 인식했으므로 행이 늘지 않는다. 별개로 봤다면 INSERTED로 두 행이 됐다.
        assertThat(result.outcome()).isEqualTo(CorporateActionLedger.Outcome.UPGRADED);
        assertThat(rowCount()).isEqualTo(1);
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
        // 후보 목록에서도 빠진다 — 어디에도 없는데 아무 에러도 나지 않는다.
        // judge가 그 조합을 거절하는 것도 같은 이유다(rejectsApprovingAnUnknownRow).
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
    @DisplayName("인적분할은 존속법인 기준이라 1보다 작다 — 방향이 뒤집히면 과거가 반대로 움직인다")
    void spinOffRatioIsBelowOne() {
        // 존속법인 1주가 0.7563532970주가 된 것이다(한화 2026-08-25 실측). 1보다 큰 값을 넣으면
        // 계수가 역수로 들어가 과거 봉이 오를 자리에서 내려간다 — 값 자체는 그럴듯하다.
        assertThatThrownBy(() -> new CorporateAction(
                LocalDate.parse("2026-08-25"), CorporateAction.Type.SPIN_OFF,
                new BigDecimal("1.3221334580"), CorporateAction.DetectionSource.HEURISTIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("부호가 어긋난다");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, type, ratio, detection_source, status)
                VALUES (?, ?, 'SPIN_OFF', 1.3221334580, 'HEURISTIC', 'APPROVED')
                """, stockId, LocalDate.parse("2026-08-25")))
                .hasMessageContaining("ck_ca_ratio_sign");
    }

    @Test
    @DisplayName("인적분할은 단순 유리수가 아닌 비율을 받는다 — 스냅을 걸면 진짜 분할이 전부 거부된다")
    void spinOffAcceptsANonSimpleRatio() {
        // 분할비율은 순자산 비율에서 나오므로 ShareRatioSnap이 못 붙는 것이 정상이고,
        // 그 사실 자체가 액면병합과 가르는 증거다(ADR-0117).
        CorporateAction action = new CorporateAction(
                LocalDate.parse("2026-08-25"), CorporateAction.Type.SPIN_OFF,
                new BigDecimal("0.7563532970"), CorporateAction.DetectionSource.HEURISTIC);

        assertThat(ShareRatioSnap.snap(action.ratio()))
                .as("스냅이 붙으면 이 종류를 액면병합과 못 가른다")
                .isEmpty();
        assertThat(ledger.record(stockId, action).outcome())
                .isEqualTo(CorporateActionLedger.Outcome.INSERTED);
    }

    @Test
    @DisplayName("markApplied는 applied_at을 남기고, 되돌려도 그 흔적이 지워지지 않는다")
    void appliedAtSurvivesReversal() {
        long id = ledger.record(stockId, disclosed("2026-02-02", "5")).existingId();
        ledger.markApplied(List.of(id));

        assertThat(status("2026-02-02")).isEqualTo("APPLIED");
        assertThat(appliedAt(id)).isNotNull();

        ledger.judge(id, rejected("오탐이었다"));

        assertThat(status("2026-02-02")).isEqualTo("REJECTED");
        assertThat(appliedAt(id)).isNotNull();
    }

    // ── 사람의 판정 (ADR-0050) ────────────────────────────────────────────

    @Test
    @DisplayName("IMPORTANT — 사람이 종류와 비율을 정하면 UNKNOWN 행이 적용 대상이 된다")
    void judgementRevivesAnUnknownRow() {
        // 이것이 이 API의 존재 이유다. 착수 시점 실측으로 불연속 64건 중 46건이 UNKNOWN이고,
        // 상태만 고칠 수 있던 동안에는 그 46건이 원리적으로 되살아날 수 없었다.
        long id = ledger.record(stockId, unknown("2026-05-04", "1.9928",
                CorporateAction.DetectionSource.HEURISTIC)).existingId();
        assertThat(ledger.findApplicable(stockId)).isEmpty();

        ledger.judge(id, new CorporateActionLedger.Judgement(
                CorporateActionStatus.APPROVED, CorporateAction.Type.BONUS_ISSUE,
                new BigDecimal("2.0"), null, LocalDate.parse("2026-05-26"), "신주상장으로 확인"));

        assertThat(ledger.findApplicable(stockId))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.type()).isEqualTo(CorporateAction.Type.BONUS_ISSUE);
                    assertThat(entry.ratio()).isEqualByComparingTo("2.0");
                });
        assertThat(listingDate(id)).isEqualTo(LocalDate.parse("2026-05-26"));
    }

    @Test
    @DisplayName("IMPORTANT — 종류를 모르는 채로 승인할 수 없다. 그 행은 적용에도 큐에도 안 뜬다")
    void rejectsApprovingAnUnknownRow() {
        long id = ledger.record(stockId, unknown("2026-05-04", "1.99",
                CorporateAction.DetectionSource.HEURISTIC)).existingId();

        assertThatThrownBy(() -> ledger.judge(id, approved("승인")))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class)
                .hasMessageContaining("종류를 모르는 행은 승인할 수 없다");
    }

    @Test
    @DisplayName("종류와 비율은 함께 고쳐야 한다 — 하나만 바꾸면 관측값에 종류 딱지가 붙는다")
    void typeAndRatioMustMoveTogether() {
        long id = ledger.record(stockId, unknown("2026-05-04", "1.99",
                CorporateAction.DetectionSource.HEURISTIC)).existingId();

        assertThatThrownBy(() -> ledger.judge(id, new CorporateActionLedger.Judgement(
                null, CorporateAction.Type.BONUS_ISSUE, null, null, null, null)))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class);
        assertThatThrownBy(() -> ledger.judge(id, new CorporateActionLedger.Judgement(
                null, null, new BigDecimal("2.0"), null, null, null)))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class);
    }

    @Test
    @DisplayName("IMPORTANT — 부호와 양수를 둘 다 본다. Type.allows만으로는 음수가 통과해 500이 된다")
    void judgementValidatesSignAndPositivity() {
        long id = ledger.record(stockId, unknown("2026-05-04", "1.99",
                CorporateAction.DetectionSource.HEURISTIC)).existingId();

        // 부호가 어긋난다 — 병합인데 1보다 크다.
        assertThatThrownBy(() -> ledger.judge(id, judgement(
                CorporateAction.Type.REVERSE_SPLIT, new BigDecimal("1.5"))))
                .isInstanceOf(IllegalArgumentException.class);
        // UNKNOWN은 Type.allows가 통과시키므로 여기서 막지 않으면 DB 제약에서 터진다.
        assertThatThrownBy(() -> ledger.judge(id, judgement(
                CorporateAction.Type.UNKNOWN, new BigDecimal("-5"))))
                .isInstanceOf(IllegalArgumentException.class);
        // numeric(18,10)의 정수부를 넘는다.
        assertThatThrownBy(() -> ledger.judge(id, judgement(
                CorporateAction.Type.SPLIT, new BigDecimal("1E+30"))))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class)
                .hasMessageContaining("상한");
    }

    @Test
    @DisplayName("IMPORTANT — 사람이 APPLIED와 OBSERVED를 만들 수 없다")
    void humanCannotForgeMachineStates() {
        long id = ledger.record(stockId, disclosed("2026-02-02", "5")).existingId();

        // APPLIED를 찍으면 계수는 그대로인데 원장이 반영됐다고 말한다.
        assertThatThrownBy(() -> ledger.judge(id, new CorporateActionLedger.Judgement(
                CorporateActionStatus.APPLIED, null, null, null, null, null)))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class);
        // OBSERVED로 되돌리면 다음 수집이 그 판정을 덮을 수 있게 된다.
        assertThatThrownBy(() -> ledger.judge(id, new CorporateActionLedger.Judgement(
                CorporateActionStatus.OBSERVED, null, null, null, null, null)))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class);
    }

    @Test
    @DisplayName("판정을 미루고 값만 고칠 수 있다 — 신주상장일만 채우는 것이 실제로 필요하다")
    void statusMayBeLeftUntouched() {
        long id = ledger.record(stockId, heuristic("2026-02-02", "0.5")).existingId();

        ledger.judge(id, new CorporateActionLedger.Judgement(
                null, null, null, null, LocalDate.parse("2026-02-20"), null));

        assertThat(status("2026-02-02")).isEqualTo("OBSERVED");
        assertThat(listingDate(id)).isEqualTo(LocalDate.parse("2026-02-20"));
    }

    @Test
    @DisplayName("효력일은 빈 자리로만 옮긴다 — 근접한 두 행이 승인되면 계수가 두 번 곱해진다")
    void effectiveDateMovesOnlyIntoAFreeSlot() {
        writer.write(stockId, List.of(
                bar("2026-02-02", "1000"), bar("2026-02-03", "1010"), bar("2026-02-04", "1020"),
                bar("2026-06-01", "1030")));
        long id = ledger.record(stockId, heuristic("2026-02-02", "0.5")).existingId();
        ledger.record(stockId, heuristic("2026-06-01", "0.5"));

        // 같은 날짜에 다른 행이 있다.
        assertThatThrownBy(() -> ledger.judge(id, moveTo("2026-06-01")))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class)
                .hasMessageContaining("이미 다른 행이");
        // 며칠 어긋난 자리도 막는다 — UNIQUE는 이것을 못 막는다.
        assertThatThrownBy(() -> ledger.judge(id, moveTo("2026-06-02")))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class)
                .hasMessageContaining("거래일 안이다");

        ledger.judge(id, moveTo("2026-02-04"));
        assertThat(rowCountOn("2026-02-02")).isZero();
        assertThat(rowCountOn("2026-02-04")).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 행을 판정하면 타입 있는 예외다 — 컨트롤러가 404로 옮긴다")
    void judgingAMissingRowIsTyped() {
        assertThatThrownBy(() -> ledger.judge(999_999L, rejected("없다")))
                .isInstanceOf(CorporateActionLedger.CorporateActionNotFoundException.class);
    }

    // ── 무상증자의 신주상장일 (ADR-0051) ──────────────────────────────────

    @Test
    @DisplayName("IMPORTANT — 신주상장일 없이 무상증자를 승인할 수 없다. 계수만 들어가고 시총은 틀린 채 남는다")
    void rejectsApprovingBonusIssueWithoutListingDate() {
        long id = ledger.record(stockId, unknown("2026-05-04", "1.9928",
                CorporateAction.DetectionSource.HEURISTIC)).existingId();

        assertThatThrownBy(() -> ledger.judge(id, new CorporateActionLedger.Judgement(
                CorporateActionStatus.APPROVED, CorporateAction.Type.BONUS_ISSUE,
                new BigDecimal("2.0"), null, null, "신주상장일을 모른다")))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class)
                .hasMessageContaining("신주상장일이 있어야 한다");
    }

    @Test
    @DisplayName("신주상장일이 권리락보다 빠르면 거부한다 — 구간이 공집합이 되어 조용히 사라진다")
    void rejectsListingDateBeforeExRights() {
        long id = ledger.record(stockId, unknown("2026-05-04", "1.9928",
                CorporateAction.DetectionSource.HEURISTIC)).existingId();

        assertThatThrownBy(() -> ledger.judge(id, new CorporateActionLedger.Judgement(
                CorporateActionStatus.APPROVED, CorporateAction.Type.BONUS_ISSUE,
                new BigDecimal("2.0"), null, LocalDate.parse("2026-04-20"), null)))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class)
                .hasMessageContaining("권리락일보다 빠르다");
    }

    @Test
    @DisplayName("IMPORTANT — 상태를 비운 채 종류만 무상증자로 바꾸는 우회로도 막힌다")
    void gateSeesTheMergedStatusNotJustTheJudgement() {
        // 상태를 비우면 기존 APPROVED가 그대로 남는다(UPDATE가 coalesce다). 판정값만 보는
        // 게이트는 이 경로에서 발화하지 않고, 그 행은 승인된 채 신주상장일이 없다.
        long id = ledger.record(stockId, disclosed("2026-05-04", "2.0")).existingId();
        assertThat(status("2026-05-04")).isEqualTo(CorporateActionStatus.APPROVED.name());

        assertThatThrownBy(() -> ledger.judge(id,
                judgement(CorporateAction.Type.BONUS_ISSUE, new BigDecimal("2.0"))))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class)
                .hasMessageContaining("신주상장일이 있어야 한다");
    }

    @Test
    @DisplayName("액면분할·병합에는 걸지 않는다 — 두 날짜가 같아 비어 있는 것이 정상이다")
    void parValueChangesMayLeaveListingDateEmpty() {
        long id = ledger.record(stockId, unknown("2023-05-16", "10.63",
                CorporateAction.DetectionSource.HEURISTIC)).existingId();

        ledger.judge(id, new CorporateActionLedger.Judgement(
                CorporateActionStatus.APPROVED, CorporateAction.Type.REVERSE_SPLIT,
                new BigDecimal("0.1"), null, null, "주식수 배수로 확인"));

        assertThat(ledger.findApplicable(stockId))
                .singleElement()
                .satisfies(entry -> assertThat(entry.listingDate()).isNull());
    }

    private static CorporateActionLedger.Judgement judgement(CorporateAction.Type type,
                                                             BigDecimal ratio) {
        return new CorporateActionLedger.Judgement(null, type, ratio, null, null, null);
    }

    private static CorporateActionLedger.Judgement moveTo(String date) {
        return new CorporateActionLedger.Judgement(
                null, null, null, LocalDate.parse(date), null, null);
    }

    private LocalDate listingDate(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT listing_date FROM corporate_action WHERE id = ?", LocalDate.class, id);
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

    /** 옮기기가 원래 자리를 비웠는지 본다. {@link #status}는 행이 없으면 예외라 못 쓴다. */
    private int rowCountOn(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                Integer.class, stockId, LocalDate.parse(date));
    }

    private static RawPriceBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null, null, false);
    }
}
