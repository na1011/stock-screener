package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingListSource.DayOutcome;
import com.stockscreener.pipeline.datasource.FilingListSource.FilingListing;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedDay;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedFiling;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedStock;
import com.stockscreener.pipeline.datasource.kr.DartFilingListClient.DayListing;
import com.stockscreener.pipeline.datasource.kr.DartFilingListClient.ListRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 국내 공시 목록(ADR-0176) — 탐침 키 규칙 · 닫는 규칙 · 줄 해석.
 */
class DartFilingListSourceTest {

    private static final Map<String, ListedStock> STOCKS = Map.of(
            "001720", new ListedStock(1L, 3),
            "009310", new ListedStock(2L, 12),
            "077970", new ListedStock(3L, 12),
            "055550", new ListedStock(4L, 12));

    @Test
    @DisplayName("탐침 키 — 2026-10-12 원장 다섯 사례(001720 사업·분기 · 009310·077970 반기)와 같다")
    void reportKeyMatchesTheLedgerCases() {
        assertThat(DartReportKey.of("A001", "사업보고서 (2026.03)", 3))
                .isEqualTo(new DartReportKey(2026, "11011", null));
        assertThat(DartReportKey.of("A003", "분기보고서 (2026.06)", 3))
                .isEqualTo(new DartReportKey(2026, "11013", null));
        assertThat(DartReportKey.of("A002", "[첨부추가]반기보고서 (2026.06)", 12))
                .isEqualTo(new DartReportKey(2026, "11012", null));
        assertThat(DartReportKey.of("A002", "[기재정정]반기보고서 (2026.06)", 12))
                .isEqualTo(new DartReportKey(2026, "11012", null));
    }

    @Test
    @DisplayName("탐침 키 — 12월 결산의 1·3분기와 사업보고서는 이름의 연도를 쓴다")
    void reportKeyForDecemberYearEnd() {
        assertThat(DartReportKey.of("A003", "분기보고서 (2026.03)", 12))
                .isEqualTo(new DartReportKey(2026, "11013", null));
        assertThat(DartReportKey.of("A003", "분기보고서 (2026.09)", 12))
                .isEqualTo(new DartReportKey(2026, "11014", null));
        assertThat(DartReportKey.of("A001", "[기재정정]사업보고서 (2025.12)", 12))
                .isEqualTo(new DartReportKey(2025, "11011", null));
    }

    @Test
    @DisplayName("탐침 키를 못 정하면 키 없이 사유를 낸다 — 결산월 없음 · 이름에 기간 없음 · 결산월과 안 맞는 분기 월")
    void unresolvedKeysCarryAReason() {
        DartReportKey noMonth = DartReportKey.of("A003", "분기보고서 (2026.09)", null);
        assertThat(noMonth.bsnsYear()).isEqualTo(2026);
        assertThat(noMonth.reprtCode()).isNull();
        assertThat(noMonth.reason()).contains("결산월");

        DartReportKey noPeriod = DartReportKey.of("A001", "사업보고서", 12);
        assertThat(noPeriod.bsnsYear()).isNull();
        assertThat(noPeriod.reason()).contains("(yyyy.mm)");

        assertThat(DartReportKey.of("A003", "분기보고서 (2026.06)", 12).reprtCode())
                .as("12월 결산의 6월은 반기다 — 분기 보고서로 오면 코드를 지어내지 않는다").isNull();
    }

    @Test
    @DisplayName("어제까지 닫고 오늘은 받기만 한다 — 013인 날은 공시 없는 날로 닫는다")
    void closesThroughYesterdayAndListsToday() {
        LocalDate friday = LocalDate.of(2026, 10, 9);   // 한글날 — 013
        LocalDate today = LocalDate.of(2026, 10, 12);
        Map<LocalDate, DayListing> days = new HashMap<>();
        days.put(friday, new DayListing(friday, false, List.of()));
        days.put(today.minusDays(2), new DayListing(today.minusDays(2), false, List.of()));
        days.put(today.minusDays(1), new DayListing(today.minusDays(1), false, List.of()));
        days.put(today, new DayListing(today, true, List.of(row("A002", "009310", "반기보고서 (2026.06)",
                "20261012000001", today))));
        List<LocalDate> asked = new ArrayList<>();
        DartFilingListSource source = new DartFilingListSource(day -> {
            asked.add(day);
            return days.get(day);
        });

        FilingListing listing = source.list(friday.minusDays(1), today, STOCKS);

        assertThat(asked).containsExactly(friday, today.minusDays(2), today.minusDays(1), today);
        assertThat(listing.closedDays()).extracting(d -> d.day() + " " + d.outcome())
                .containsExactly("2026-10-09 EMPTY", "2026-10-10 EMPTY", "2026-10-11 EMPTY");
        assertThat(listing.openFilings()).extracting(ListedFiling::filingNo).containsExactly("20261012000001");
        assertThat(listing.failure()).isNull();
    }

    @Test
    @DisplayName("한 날이 실패하면 그 앞날까지만 닫고 멈춘다 — 실패한 날 뒤를 닫으면 커서가 그날을 건너뛴다")
    void stopsAtTheFirstFailure() {
        LocalDate d1 = LocalDate.of(2026, 10, 6);
        LocalDate d2 = d1.plusDays(1);
        DataSourceException limited = new DataSourceException(DataSourceException.Kind.RATE_LIMITED, "dart", "020");
        List<LocalDate> asked = new ArrayList<>();
        DartFilingListSource source = new DartFilingListSource(day -> {
            asked.add(day);
            if (day.equals(d2)) {
                throw limited;
            }
            return new DayListing(day, true, List.of());
        });

        FilingListing listing = source.list(d1.minusDays(1), d1.plusDays(5), STOCKS);

        assertThat(listing.closedDays()).extracting(d -> d.day()).containsExactly(d1);
        assertThat(listing.failure()).isSameAs(limited);
        assertThat(asked).containsExactly(d1, d2);
    }

    @Test
    @DisplayName("실적 발표 줄은 감지 범위 종목의 실적 공시만 낸다 — 정기 공시 셈에 안 들고, 오늘의 줄은 열린 채 낸다(ADR-0195)")
    void routesEarningsReleasesSeparately() {
        LocalDate day = LocalDate.of(2026, 10, 29);
        Map<String, ListedStock> stocks = Map.of(
                "005930", new ListedStock(5L, 12, null, true),
                "055550", new ListedStock(4L, 12, null, false));
        List<ListRow> rows = List.of(
                row("I002", "005930", "연결재무제표기준영업(잠정)실적(공정공시)              ", "20261029800001", day),
                row("I002", "005930", "결산실적공시예고", "20261029800002", day),
                row("I002", "055550", "영업(잠정)실적(공정공시)", "20261029800003", day),
                row("A003", "005930", "분기보고서 (2026.09)", "20261029000004", day));
        DartFilingListSource source = new DartFilingListSource(d -> new DayListing(d, true, rows));

        FilingListing closed = source.list(day.minusDays(1), day.plusDays(1), stocks);
        FilingListing open = source.list(day.minusDays(1), day, stocks);

        ListedDay listed = closed.closedDays().get(0);
        assertThat(listed.sourceRows()).as("정기 공시만 센다").isEqualTo(1);
        assertThat(listed.filings()).extracting(ListedFiling::filingNo).containsExactly("20261029000004");
        assertThat(listed.releases()).extracting(r -> r.filingNo() + " " + r.stockId() + " " + r.form())
                .as("범위 밖 055550 · 일정 안내는 없다").containsExactly("20261029800001 5 I002");
        assertThat(open.closedDays()).isEmpty();
        assertThat(open.openReleases()).extracting(r -> r.filingNo()).containsExactly("20261029800001");
    }

    @Test
    @DisplayName("우리 종목에 붙은 줄만 장부로 가고, 거르기 전 줄 수는 그대로 센다")
    void keepsOnlyOurStocks() {
        LocalDate day = LocalDate.of(2026, 10, 7);
        DartFilingListSource source = new DartFilingListSource(d -> new DayListing(d, true, List.of(
                row("A002", "055550", "[기재정정]반기보고서 (2026.06)", "20261007000223", day),
                row("A001", "055550", "[기재정정]사업보고서 (2025.12)", "20261007000218", day),
                row("A002", "999999", "반기보고서 (2026.06)", "20261007000999", day))));

        FilingListing listing = source.list(day.minusDays(1), day.plusDays(1), STOCKS);

        assertThat(listing.closedDays()).hasSize(1);
        assertThat(listing.closedDays().get(0).outcome()).isEqualTo(DayOutcome.LISTED);
        assertThat(listing.closedDays().get(0).sourceRows()).isEqualTo(3);
        assertThat(listing.closedDays().get(0).filings())
                .extracting(f -> f.filingNo() + " " + f.stockId() + " " + f.bsnsYear() + "/" + f.reprtCode())
                .containsExactly("20261007000223 4 2026/11012", "20261007000218 4 2025/11011");
    }

    @Test
    @DisplayName("목록 줄 — 접수일이 그날이 아니면 본문 이상이다 · 013은 「없음」이다")
    void parsePageRejectsRowsOfAnotherDay() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LocalDate day = LocalDate.of(2026, 8, 14);
        String ok = """
                {"status":"000","message":"정상","page_no":1,"page_count":100,"total_count":1,"total_page":1,
                 "list":[{"corp_code":"00480455","corp_name":"STX엔진","stock_code":"077970","corp_cls":"Y",
                          "report_nm":"[첨부추가]반기보고서 (2026.06)","rcept_no":"20260814001988","flr_nm":"STX엔진",
                          "rcept_dt":"20260814","rm":""}]}""";
        assertThat(DartFilingListClient.parsePage(mapper.readTree(ok), day, "A002", "t").rows())
                .extracting(ListRow::stockCode, ListRow::rceptNo).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("077970", "20260814001988"));

        assertThatThrownBy(() -> DartFilingListClient.parsePage(mapper.readTree(ok), day.plusDays(1), "A002", "t"))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind()).isEqualTo(DataSourceException.Kind.MALFORMED);

        String holiday = "{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}";
        assertThatThrownBy(() -> DartFilingListClient.parsePage(mapper.readTree(holiday), day, "A002", "t"))
                .isInstanceOf(DataSourceException.class)
                .matches(e -> DartResponse.isNoData((DataSourceException) e));
    }

    private static ListRow row(String type, String stockCode, String name, String rceptNo, LocalDate day) {
        return new ListRow(type, "00000000", "회사", stockCode, "Y", name, rceptNo, day);
    }
}
