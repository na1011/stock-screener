package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q4의 지배주주 귀속 순이익 유도를 고정한다.
 *
 * <p>이 유도가 없으면 <b>이 축 전체가 무력해진다.</b> TTM은 최근 4분기이고 12월 결산 종목의
 * 그 창에 Q4가 반드시 들어가는데, {@code FinancialMath}의 폴백이 창 단위라 한 분기만 비어도
 * 넷 다 연결 총액으로 간다 — <b>실측으로 Q4 채움이 0%일 때 PER이 한 종목도 안 바뀌었다.</b>
 *
 * <p>그리고 무너지는 방식이 조용하다. 빠진 분기를 0으로 보면 Q4가 그만큼 부풀고, 그 값은
 * 다른 분기와 비슷한 크기라 눈으로는 드러나지 않는다.
 */
class FourthQuarterControllingTest {

    @Test
    @DisplayName("FY − (Q1+Q2+Q3)로 유도한다 — 주요계정의 「FY − 3분기 누적」과 같은 값이다")
    void derivesFromAnnualMinusThreeQuarters() {
        // 삼성전자 2025 실측(단위 조). 연결로 같은 산술을 하면 주요계정이 유도해 저장한
        // Q4 19.641745와 일치한다: 45.206805 − (8.222878+5.116435+12.225747) = 19.641745.
        List<RawFinancialReport> year = List.of(
                report("FY", "44.260956"), report("Q1", "8.028407"),
                report("Q2", "4.934034"), report("Q3", "12.006461"));

        assertThat(DartFullStatementAdapter.deriveFourthQuarterControlling(year, 2025))
                .isEqualByComparingTo("19.292054");
    }

    @Test
    @DisplayName("분기가 셋에 못 미치면 유도하지 않는다 — 빠진 것을 0으로 보면 Q4가 부푼다")
    void refusesWhenAQuarterIsMissing() {
        List<RawFinancialReport> year = List.of(
                report("FY", "44.260956"), report("Q1", "8.028407"), report("Q2", "4.934034"));

        assertThat(DartFullStatementAdapter.deriveFourthQuarterControlling(year, 2025)).isNull();
    }

    @Test
    @DisplayName("한 분기의 지배주주 값이 비면 유도하지 않는다 — null은 0이 아니다")
    void refusesWhenAQuarterHasNoControllingValue() {
        List<RawFinancialReport> year = List.of(
                report("FY", "44.260956"), report("Q1", "8.028407"),
                report("Q2", null), report("Q3", "12.006461"));

        assertThat(DartFullStatementAdapter.deriveFourthQuarterControlling(year, 2025)).isNull();
    }

    @Test
    @DisplayName("사업보고서가 없으면 유도하지 않는다")
    void refusesWithoutAnnual() {
        List<RawFinancialReport> year = List.of(
                report("Q1", "8.028407"), report("Q2", "4.934034"), report("Q3", "12.006461"));

        assertThat(DartFullStatementAdapter.deriveFourthQuarterControlling(year, 2025)).isNull();
    }

    @Test
    @DisplayName("다른 회계연도의 행은 섞지 않는다")
    void ignoresOtherFiscalYears() {
        List<RawFinancialReport> mixed = List.of(
                report("FY", "44.260956"), report("Q1", "8.028407"),
                report("Q2", "4.934034"), report("Q3", "12.006461"),
                report(2024, "Q1", "999"), report(2024, "FY", "999"));

        assertThat(DartFullStatementAdapter.deriveFourthQuarterControlling(mixed, 2025))
                .isEqualByComparingTo("19.292054");
    }

    private static RawFinancialReport report(String period, String controlling) {
        return report(2025, period, controlling);
    }

    private static RawFinancialReport report(int year, String period, String controlling) {
        return new RawFinancialReport(
                year, period, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31),
                LocalDate.of(year + 1, 3, 15), "11011", "KRW",
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null,
                controlling == null ? null : new BigDecimal(controlling),
                null, null, null, null, null, null, null, null);
    }
}
