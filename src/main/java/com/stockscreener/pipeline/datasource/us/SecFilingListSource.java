package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingListSource;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient.IndexLine;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient.QuarterListing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * 미국 공시 목록 — EDGAR 일일 폼 색인에서 정기 공시({@link SecEdgarFacts#PERIODIC_FORMS})를 골라 우리 종목에 붙인다(ADR-0176).
 *
 * <p><b>닫는 규칙</b>: 그 분기들의 색인 목록에 있는 날은 그날 색인을 끝까지 받아 닫고, <b>목록의 마지막 날보다 앞인데 목록에 없는
 * 날</b>은 색인이 없는 날(주말·연방 공휴일)로 닫는다. 마지막 날보다 뒤는 아직 안 올라온 날이라 기다린다. 색인은 그날 미 동부 22시께
 * 날짜 순서대로 올라온다(2026-10 저장분 7일 · 22:01~22:04) — 늦게 올라온 날을 「없는 날」로 닫는 경우는 재지 않았다.
 *
 * <p><b>회사 → 종목</b>: CIK를 운영 매핑({@link SecTickerCikCache} — {@code company_tickers_exchange.json})으로 우리 티커에 붙인다.
 * 갱신에 실패한 낡은 매핑으로는 받지 않는다({@link SecTickerCikCache#freshSnapshot}) — 낡은 판의 「없음」은 소스의 답이 아니다.
 * 한 CIK에 우리 종목이 여럿이면 종목마다 한 줄이다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecFilingListSource implements FilingListSource {

    /** 일일 색인 받기 — 시험이 바꿔 끼운다. */
    interface DailyIndex {
        QuarterListing quarter(int year, int quarter) throws DataSourceException;

        List<IndexLine> formIndex(LocalDate day, int expectedKb) throws DataSourceException;
    }

    /** 티커 → CIK 매핑의 신선한 판 — 시험이 바꿔 끼운다. */
    @FunctionalInterface
    interface Snapshots {
        SecTickerCikCache.Snapshot fresh() throws DataSourceException;
    }

    private final DailyIndex index;
    private final Snapshots snapshots;

    @Autowired
    public SecFilingListSource(SecDailyIndexClient index, SecTickerCikCache cikCache) {
        this(index, cikCache::freshSnapshot);
    }

    SecFilingListSource(DailyIndex index, Snapshots snapshots) {
        this.index = index;
        this.snapshots = snapshots;
    }

    @Override
    public String market() {
        return "US";
    }

    @Override
    public FilingListing list(LocalDate cursor, LocalDate todayKst, Map<String, ListedStock> stocks) {
        List<ListedDay> closed = new ArrayList<>();
        LocalDate start = cursor.plusDays(1);
        if (start.isAfter(todayKst)) {
            return new FilingListing(closed, List.of(), null);
        }
        Map<Long, List<Long>> stocksByCik;
        try {
            stocksByCik = stocksByCik(snapshots.fresh(), stocks);
        } catch (DataSourceException e) {
            return new FilingListing(closed, List.of(), e);
        }

        NavigableMap<LocalDate, Integer> available = new TreeMap<>();
        DataSourceException failure = null;
        for (int[] quarter : quarters(start, todayKst)) {
            try {
                available.putAll(index.quarter(quarter[0], quarter[1]).formIndexKb());
            } catch (DataSourceException e) {
                // 뒤 분기를 못 받았다 — 앞 분기들의 마지막 날까지만 닫는다(그 뒤 날이 없는 날인지 기다릴 날인지 모른다).
                failure = e;
                break;
            }
        }
        LocalDate last = available.isEmpty() ? null : available.lastKey();
        for (LocalDate day = start; last != null && !day.isAfter(last); day = day.plusDays(1)) {
            Integer kb = available.get(day);
            if (kb == null) {
                closed.add(new ListedDay(day, DayOutcome.NO_INDEX, 0, List.of()));
                continue;
            }
            List<IndexLine> lines;
            try {
                lines = index.formIndex(day, kb);
            } catch (DataSourceException e) {
                return new FilingListing(closed, List.of(), e);
            }
            closed.add(toDay(day, lines, stocksByCik));
        }
        return new FilingListing(closed, List.of(), failure);
    }

    /** 정기 공시 줄을 골라 우리 종목에 붙인다. */
    static ListedDay toDay(LocalDate day, List<IndexLine> lines, Map<Long, List<Long>> stocksByCik) {
        int periodic = 0;
        List<ListedFiling> filings = new ArrayList<>();
        for (IndexLine line : lines) {
            if (!SecEdgarFacts.PERIODIC_FORMS.contains(line.form())) {
                continue;
            }
            periodic++;
            for (long stockId : stocksByCik.getOrDefault(line.cik(), List.of())) {
                filings.add(new ListedFiling(line.accession(), stockId, line.cik(), null, line.form(), null,
                        line.filed(), null, null, null));
            }
        }
        return new ListedDay(day, DayOutcome.LISTED, periodic, filings);
    }

    /**
     * 등록인 → 우리 행. <b>행에 묶인 등록인이 먼저다</b>(ADR-0180) — 오늘 티커 파일은 교체·재사용 기호에 새 등록인을 준다(REAX ·
     * XRPN). 파일로 풀면 새 등록인의 공시가 옛 등록인 행에 붙고, 그 소비 FR이 옛 행에 새 등록인 재무를 섞는다(★16). 묶이지 않은 행
     * (재무 행 0 · 이름 축)만 오늘 파일로 푼다 — 저장된 판이 없어 섞일 것이 없다.
     */
    static Map<Long, List<Long>> stocksByCik(SecTickerCikCache.Snapshot snapshot, Map<String, ListedStock> stocks) {
        Map<Long, List<Long>> out = new HashMap<>();
        stocks.forEach((ticker, stock) -> (stock.secCik() != null ? Optional.of(stock.secCik()) : snapshot.findCik(ticker))
                .ifPresent(cik -> out.computeIfAbsent(cik, k -> new ArrayList<>()).add(stock.stockId())));
        return out;
    }

    /** {@code from}~{@code to}가 걸친 (연도, 분기)를 순서대로. */
    static List<int[]> quarters(LocalDate from, LocalDate to) {
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        for (LocalDate d = from.withDayOfMonth(1); !d.isAfter(to); d = d.plusMonths(1)) {
            int quarter = (d.getMonthValue() - 1) / 3 + 1;
            if (seen.add(d.getYear() + "Q" + quarter)) {
                out.add(new int[] {d.getYear(), quarter});
            }
        }
        return out;
    }
}
