package com.stockscreener.pipeline.datasource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 원장의 정기 공시 목록을 날마다 받는 포트(ADR-0176). 시장마다 어댑터가 하나다 — 미국 EDGAR 일일 색인 · 국내 DART 공시검색.
 *
 * <p>어댑터가 정하는 것: 어느 날을 <b>지금 닫을 수 있는가</b>(소스마다 「없는 날」과 「아직 안 올라온 날」을 가르는 법이 다르다),
 * 어느 줄이 정기 공시인가, 줄의 회사가 우리 어느 종목인가. 잡은 커서와 장부만 다룬다.
 */
public interface FilingListSource {

    /** 장부의 시장 이름 — {@code US} · {@code KR}(V67). */
    String market();

    /**
     * {@code cursor} 다음 날부터 받는다. 날짜순으로 닫을 수 있는 날을 닫고, <b>첫 실패에서 멈춘다</b> — 실패한 날과 그 뒤 날은
     * 닫지 않는다(커서가 안 넘어간다). 실패는 던지지 않고 {@link FilingListing#failure}로 낸다 — 그 앞에서 닫은 날을 잃지 않게.
     *
     * @param todayKst 실행일(KST). 그 뒤의 날은 묻지 않는다
     * @param stocks   우리 종목 전부를 티커(국내는 종목코드)로 — 편입 여부와 무관하다
     */
    FilingListing list(LocalDate cursor, LocalDate todayKst, Map<String, ListedStock> stocks);

    /** 우리 종목 하나. {@code accountingMonth}는 국내 분기 보고서 코드에만 쓴다. */
    /**
     * @param secCik 미국 행에 묶인 등록인(ADR-0180) — 있으면 장부가 오늘 티커 파일보다 먼저 쓴다. 국내·미결합은 {@code null}
     */
    record ListedStock(long stockId, Integer accountingMonth, Long secCik) {
        public ListedStock(long stockId, Integer accountingMonth) {
            this(stockId, accountingMonth, null);
        }
    }

    /**
     * @param closedDays  날짜순으로 닫은 날
     * @param openFilings 받았지만 아직 닫지 않는 날(국내의 오늘)의 줄 — 장부에는 넣고 커서는 안 넘긴다
     * @param failure     멈춘 사유. 끝까지 갔으면 {@code null}
     */
    record FilingListing(List<ListedDay> closedDays, List<ListedFiling> openFilings, DataSourceException failure) {
    }

    /** 그날을 닫는 사유 — V67 {@code ck_filing_listing_day_outcome}. */
    enum DayOutcome {
        /** 목록을 끝까지 받았다(행이 0일 수 있다). */
        LISTED,
        /** SEC가 그날 색인을 안 냈다 — 분기 목록의 마지막 날보다 앞인데 목록에 없다. */
        NO_INDEX,
        /** DART가 그날 모든 조합에 013 — 공시가 없다. */
        EMPTY
    }

    /**
     * @param sourceRows 거르기 전 정기 공시 줄 수
     * @param filings    우리 종목에 붙은 줄(종목이 여럿인 회사는 종목마다)
     */
    record ListedDay(LocalDate day, DayOutcome outcome, int sourceRows, List<ListedFiling> filings) {
    }

    /**
     * 장부 한 줄. 미국은 {@code cik}, 국내는 {@code corpCode}와 탐침 키를 채운다(V67 {@code ck_filing_consumption_source}).
     *
     * @param lastReason 국내 탐침 키를 못 정한 사유 — 소비가 그 사유로 멈춘다. 정했으면 {@code null}
     */
    record ListedFiling(String filingNo, long stockId, Long cik, String corpCode, String form, String reportName,
                        LocalDate filedOn, Integer bsnsYear, String reprtCode, String lastReason) {
    }
}
