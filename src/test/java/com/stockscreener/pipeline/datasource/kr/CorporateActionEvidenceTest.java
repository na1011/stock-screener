package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.StoredPriceSeries;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시계열이 기업 행위에 대해 무엇을 말하는가. <b>봉과 주식수는 전부 {@code price_daily}
 * 실측이다</b>(2026-08-12).
 */
class CorporateActionEvidenceTest {

    @Test
    @DisplayName("분할 엿새 전의 잔변동을 건너뛰고 분할일을 집는다 — 카카오 2021은 그 간격이 이틀 차다")
    void skipsSmallShareChangesBeforeTheSplit() {
        // 04-09의 ×1.00017은 전환권행사류다. 크기를 안 보고 "첫 변동일"을 집으면 그 봉이
        // 효력일이 되고, 소스 원값 2021.04.14에서 사흘 물러 잡은 창 시작(04-11)과 이틀 차다.
        CorporateActionEvidence evidence = evidenceOf(
                bar("2021-04-08", "548000", 88_746_712L),
                bar("2021-04-09", "558000", 88_761_861L),
                bar("2021-04-15", "120500", 443_809_305L),
                bar("2021-04-16", "119000", 443_809_305L));

        Optional<CorporateActionEvidence.ParValueChange> change = evidence.firstSnappedShareChange(
                LocalDate.parse("2021-04-05"), LocalDate.parse("2021-05-29"));

        assertThat(change).isPresent();
        assertThat(change.get().effectiveDate()).isEqualTo(LocalDate.parse("2021-04-15"));
        assertThat(change.get().ratio()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("거래정지 19일을 넘어 병합일을 집고 배수를 정확히 0.1로 되돌린다")
    void crossesASuspensionGapAndSnapsTheMultiple() {
        // 대한전선 2023은 04-27 다음 봉이 05-16이다(11거래일 정지). 관측 배수는
        // 0.0999999993이고 종가 배수의 역수(0.09407)와 6% 다르다 — 주식수 쪽이 정본이다.
        CorporateActionEvidence evidence = evidenceOf(
                bar("2023-04-27", "1492", 1_244_473_009L),
                bar("2023-05-16", "15860", 124_447_300L),
                bar("2023-05-17", "15180", 124_447_300L));

        Optional<CorporateActionEvidence.ParValueChange> change = evidence.firstSnappedShareChange(
                LocalDate.parse("2023-04-29"), LocalDate.parse("2023-06-13"));

        assertThat(change).isPresent();
        assertThat(change.get().effectiveDate()).isEqualTo(LocalDate.parse("2023-05-16"));
        assertThat(change.get().ratio()).isEqualByComparingTo("0.1");
    }

    @Test
    @DisplayName("주식수 배수가 단순 유리수가 아니면 효력일을 집지 않는다 — 근사로 메우지 않는다")
    void refusesWhenTheMultipleIsNotSimple() {
        // 전환권행사류 잔변동만 있는 창이다. 창 안에 변동이 있다는 이유로 그것을 효력일로
        // 집으면 틀린 비율이 아무 에러 없이 들어간다.
        CorporateActionEvidence evidence = evidenceOf(
                bar("2026-01-05", "10000", 100_000_000L),
                bar("2026-01-06", "10000", 100_017_069L));

        assertThat(evidence.firstSnappedShareChange(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"))).isEmpty();
    }

    @Test
    @DisplayName("신주 수 정수 완전 일치로 상장일을 집는다 — 사이에 낀 별개 증가에 걸리지 않는다")
    void locatesListingDateByExactShareDelta() {
        // 루닛 2023. 권리락 11-09에는 주식수가 그대로고, 11-24에 별개로 1,857,150주가
        // 늘었다가, 12-01에 무상증자분 14,238,154주가 상장된다.
        CorporateActionEvidence evidence = evidenceOf(
                bar("2023-11-08", "170500", 12_381_004L),
                bar("2023-11-09", "90200", 12_381_004L),
                bar("2023-11-10", "96600", 12_381_004L),
                bar("2023-11-24", "93100", 14_238_154L),
                bar("2023-11-30", "99800", 14_238_154L),
                bar("2023-12-01", "96300", 28_476_308L));

        Optional<CorporateActionEvidence.ShareIssue> issue = evidence.shareIncreaseOf(
                new BigDecimal("14238154"),
                LocalDate.parse("2023-11-07"), LocalDate.parse("2024-01-24"));

        assertThat(issue).isPresent();
        assertThat(issue.get().listingDate()).isEqualTo(LocalDate.parse("2023-12-01"));
        assertThat(issue.get().base())
                .as("분모는 권리락일(12,381,004)이 아니라 상장 직전이다 — 전자로 내면 2.15가 나온다")
                .isEqualByComparingTo("14238154");
        assertThat(issue.get().ratio()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("자사주 때문에 안 떨어지는 무상증자 배수를 그대로 낸다 — 여기에 스냅을 걸면 안 된다")
    void keepsTheBonusIssueMultipleUnrounded() {
        // 씨젠 2021. 52,225,994 = 2 × 26,234,020 − 242,046이고 그 242,046이 자기주식이다.
        // KRX 권리락 기준가가 총주식수 기준이므로 1.9907735833이 정본이고 2가 아니다.
        CorporateActionEvidence evidence = evidenceOf(
                bar("2021-04-22", "195000", 26_234_020L),
                bar("2021-04-23", "107500", 26_234_020L),
                bar("2021-05-18", "80100", 26_234_020L),
                bar("2021-05-20", "75300", 52_225_994L));

        Optional<CorporateActionEvidence.ShareIssue> issue = evidence.shareIncreaseOf(
                new BigDecimal("25991974"),
                LocalDate.parse("2021-04-23"), LocalDate.parse("2021-07-08"));

        assertThat(issue).isPresent();
        assertThat(issue.get().ratio()).isEqualByComparingTo("1.9907735833");
    }

    @Test
    @DisplayName("수량이 안 맞으면 상장일을 집지 않는다 — 우선주 배정 행이 여기서 걸린다")
    void doesNotGuessTheListingDateWhenTheQuantityDisagrees() {
        CorporateActionEvidence evidence = evidenceOf(
                bar("2023-11-30", "99800", 14_238_154L),
                bar("2023-12-01", "96300", 28_476_308L));

        assertThat(evidence.shareIncreaseOf(new BigDecimal("14238100"),
                LocalDate.parse("2023-11-01"), LocalDate.parse("2024-01-01"))).isEmpty();
    }

    @Test
    @DisplayName("권리락은 소스 원값 직전 거래일이고, 그날 종가 배수로 교차확인한다")
    void findsExRightsDayAndItsPriceGap() {
        CorporateActionEvidence evidence = evidenceOf(
                bar("2023-11-08", "170500", 12_381_004L),
                bar("2023-11-09", "90200", 12_381_004L),
                bar("2023-11-10", "96600", 12_381_004L));

        // 소스 원값 2023.11.10(신주배정기준일로 보인다)의 직전 거래일이 권리락 11-09다.
        assertThat(evidence.lastTradingDayBefore(LocalDate.parse("2023-11-10")))
                .contains(LocalDate.parse("2023-11-09"));
        assertThat(evidence.closeMultipleOn(LocalDate.parse("2023-11-09")).orElseThrow()
                .setScale(6, java.math.RoundingMode.HALF_UP))
                .as("170,500 → 90,200. 1:1 무상증자의 이론값 0.5에서 5.8% 벗어나 있고, 그것이 정상이다")
                .isEqualByComparingTo("0.529032");
    }

    @Test
    @DisplayName("시계열의 첫 봉에는 직전이 없으므로 배수도 없다 — 창 앞쪽 여유가 필요한 이유다")
    void firstBarHasNoPreviousBar() {
        CorporateActionEvidence evidence = evidenceOf(
                bar("2023-11-09", "90200", 12_381_004L),
                bar("2023-11-10", "96600", 12_381_004L));

        assertThat(evidence.closeMultipleOn(LocalDate.parse("2023-11-09"))).isEmpty();
        assertThat(evidence.lastTradingDayBefore(LocalDate.parse("2023-11-09"))).isEmpty();
    }

    private static CorporateActionEvidence evidenceOf(StoredPriceSeries.Bar... bars) {
        return new CorporateActionEvidence(List.of(bars));
    }

    private static StoredPriceSeries.Bar bar(String date, String close, long listedShares) {
        return new StoredPriceSeries.Bar(LocalDate.parse(date), new BigDecimal(close),
                BigDecimal.valueOf(listedShares));
    }
}
