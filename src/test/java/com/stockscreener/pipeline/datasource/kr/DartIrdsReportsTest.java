package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회 창을 덮는 보고서 집합.
 *
 * <p>여기서 지키는 것 둘은 방향이 반대다 — <b>덮을 수 있으면 최소로 덮고</b>(콜 수),
 * <b>못 덮으면 못 덮은 채로 둔다</b>(커버리지를 성립으로 읽지 않는다).
 */
class DartIrdsReportsTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-08-12");

    @Test
    @DisplayName("2020~2026 전량 백필이 종목당 2콜로 끝난다")
    void fullBackfillTakesTwoCalls() {
        List<DartIrdsReports.Report> reports = DartIrdsReports.covering(
                LocalDate.parse("2020-01-01"), LocalDate.parse("2026-08-12"), TODAY);

        assertThat(reports)
                .extracting(DartIrdsReports.Report::businessYear, DartIrdsReports.Report::reportCode)
                // 2024 사업보고서가 2020~2024를, 2026 1분기가 2024~2026-03을 덮는다.
                .containsExactly(tupleOf(2024, "11011"), tupleOf(2026, "11013"));
    }

    @Test
    @DisplayName("최근 며칠짜리 창은 빈 집합이다 — 그 기간을 담는 보고서가 아직 제출되지 않았다")
    void recentWindowHasNothingToCall() {
        // 2026-07-28~08-12를 담는 것은 2026 3분기보고서이고 제출은 11-14다. 2026 반기는
        // 06-30까지만 담고 제출도 08-14라 오늘(08-12) 기준으로 둘 다 없다.
        // MUST NOT: 이것을 실패로 읽지 않는다. 소스가 최대 넉 달 늦다는 사실의 표현이다.
        assertThat(DartIrdsReports.covering(
                LocalDate.parse("2026-07-28"), LocalDate.parse("2026-08-12"), TODAY))
                .isEmpty();
    }

    @Test
    @DisplayName("사흘만 지나 반기보고서가 제출되면 같은 창이 열린다 — 빈 집합의 원인이 지연임을 고정한다")
    void sameWindowOpensOnceTheHalfYearReportIsFiled() {
        assertThat(DartIrdsReports.covering(
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-06-30"), TODAY))
                .as("반기 제출 전이라 4~6월을 덮는 것이 없다")
                .isEmpty();

        assertThat(DartIrdsReports.covering(
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-08-14")))
                .extracting(DartIrdsReports.Report::businessYear, DartIrdsReports.Report::reportCode)
                .containsExactly(tupleOf(2026, "11012"));
    }

    @Test
    @DisplayName("과거 한 달 창은 그 해의 사업보고서 하나로 끝난다")
    void pastMonthTakesOneAnnualReport() {
        // 후보를 창이 걸친 연도로 한정한다. 2021년 4월은 2025 사업보고서에도 담겨 있지만
        // 그것을 후보에 넣으면 창마다 후보가 오늘까지 늘어나고, 콜 수는 어차피 하나다.
        assertThat(DartIrdsReports.covering(
                LocalDate.parse("2021-04-01"), LocalDate.parse("2021-04-30"), TODAY))
                .extracting(DartIrdsReports.Report::businessYear, DartIrdsReports.Report::reportCode)
                .containsExactly(tupleOf(2021, "11011"));
    }

    @Test
    @DisplayName("고른 보고서의 합집합이 창의 덮인 부분을 빠짐없이 덮는다")
    void chosenReportsLeaveNoHoleInsideTheirOwnSpan() {
        List<DartIrdsReports.Report> reports = DartIrdsReports.covering(
                LocalDate.parse("2018-01-01"), LocalDate.parse("2026-08-12"), TODAY);

        // 순서대로 이어 붙였을 때 구멍이 없어야 한다. 구멍이 생기면 그 구간의 사건이
        // 조용히 빠지고, 호출자는 그것을 "사건이 없다"로 읽는다.
        for (int i = 1; i < reports.size(); i++) {
            assertThat(reports.get(i).coversFrom())
                    .as("%s 다음이 %s", reports.get(i - 1), reports.get(i))
                    .isBeforeOrEqualTo(reports.get(i - 1).coversTo().plusDays(1));
        }
        assertThat(reports).isNotEmpty();
    }

    @Test
    @DisplayName("2015년 이전은 부르지 않는다 — DART가 bsns_year를 그때부터만 받는다")
    void doesNotAskForYearsTheSourceRefuses() {
        assertThat(DartIrdsReports.covering(
                LocalDate.parse("2005-01-01"), LocalDate.parse("2010-12-31"), TODAY))
                .isEmpty();

        assertThat(DartIrdsReports.covering(
                LocalDate.parse("2010-01-01"), LocalDate.parse("2019-12-31"), TODAY))
                .allSatisfy(report -> assertThat(report.businessYear()).isGreaterThanOrEqualTo(2015));
    }

    private static org.assertj.core.groups.Tuple tupleOf(int year, String code) {
        return org.assertj.core.groups.Tuple.tuple(year, code);
    }
}
