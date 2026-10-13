package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingListSource;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.kr.DartFilingListClient.DayListing;
import com.stockscreener.pipeline.datasource.kr.DartFilingListClient.ListRow;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 국내 공시 목록 — DART 공시검색의 정기보고서 세 종류를 우리 종목에 붙인다(ADR-0176).
 *
 * <p><b>닫는 규칙</b>: 커서 다음 날부터 <b>KST 오늘</b>까지 날마다 묻고, <b>어제까지만 닫는다</b> — 오늘은 오후에도 공시가
 * 접수되므로 받은 줄은 장부에 넣되 닫지 않고 다음 실행이 다시 받는다. 모든 조합이 {@code 013}인 날은 공시 없는 날로 닫는다.
 *
 * <p><b>회사 → 종목</b>: 목록의 {@code stock_code}가 우리 국내 종목의 티커다. 탐침 키(사업연도 · 보고서 코드)는
 * {@link DartReportKey}가 정한다.
 */
@Component
@ConditionalOnApiKey("dart")
public class DartFilingListSource implements FilingListSource {

    /** 하루 목록 받기 — 시험이 바꿔 끼운다. */
    @FunctionalInterface
    interface DayLister {
        DayListing day(LocalDate day) throws DataSourceException;
    }

    private final DayLister lister;

    @Autowired
    public DartFilingListSource(DartFilingListClient client) {
        this((DayLister) client::day);
    }

    DartFilingListSource(DayLister lister) {
        this.lister = lister;
    }

    @Override
    public String market() {
        return "KR";
    }

    @Override
    public FilingListing list(LocalDate cursor, LocalDate todayKst, Map<String, ListedStock> stocks) {
        List<ListedDay> closed = new ArrayList<>();
        for (LocalDate day = cursor.plusDays(1); !day.isAfter(todayKst); day = day.plusDays(1)) {
            DayListing listing;
            try {
                listing = lister.day(day);
            } catch (DataSourceException e) {
                return new FilingListing(closed, List.of(), e);
            }
            List<ListedFiling> filings = toFilings(listing.rows(), stocks);
            if (day.equals(todayKst)) {
                return new FilingListing(closed, filings, null);
            }
            closed.add(new ListedDay(day, listing.answered() ? DayOutcome.LISTED : DayOutcome.EMPTY,
                    listing.rows().size(), filings));
        }
        return new FilingListing(closed, List.of(), null);
    }

    static List<ListedFiling> toFilings(List<ListRow> rows, Map<String, ListedStock> stocks) {
        List<ListedFiling> out = new ArrayList<>();
        for (ListRow row : rows) {
            ListedStock stock = stocks.get(row.stockCode());
            if (stock == null) {
                continue;
            }
            DartReportKey key = DartReportKey.of(row.detailType(), row.reportName(), stock.accountingMonth());
            out.add(new ListedFiling(row.rceptNo(), stock.stockId(), null, row.corpCode(), row.detailType(),
                    row.reportName(), row.rceptDate(), key.bsnsYear(), key.reprtCode(), key.reason()));
        }
        return out;
    }
}
