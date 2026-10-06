package com.stockscreener.pipeline.support;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FinancialDataSource;
import com.stockscreener.pipeline.datasource.FinancialFetch;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 테스트용 인메모리 재무 소스. */
public class FakeFinancialDataSource implements FinancialDataSource {

    private final Set<String> markets;
    private final Map<Long, List<RawFinancialReport>> reportsByStock = new HashMap<>();
    private final Map<Long, DataSourceException> failures = new HashMap<>();
    /** 공시 목록을 못 받아 판 이력의 빈칸을 모르는 종목(SEC 보충의 목록 실패 모양 · ADR-0173). */
    private final Set<Long> uncertain = new java.util.HashSet<>();
    /** 받지 못한 보충의 종류(SEC 공시 보충의 받기 실패 모양 · ADR-0174). */
    private final Map<Long, List<DataSourceException.Kind>> unfetched = new HashMap<>();

    public final List<Long> fetchedStockIds = new ArrayList<>();

    public FakeFinancialDataSource(String... markets) {
        this.markets = Set.of(markets);
    }

    public FakeFinancialDataSource withReports(long stockId, List<RawFinancialReport> reports) {
        reportsByStock.put(stockId, reports);
        return this;
    }

    public FakeFinancialDataSource failingWith(long stockId, DataSourceException exception) {
        failures.put(stockId, exception);
        return this;
    }

    public FakeFinancialDataSource uncertainFor(long stockId) {
        uncertain.add(stockId);
        return this;
    }

    public FakeFinancialDataSource unfetchedFor(long stockId, List<DataSourceException.Kind> kinds) {
        unfetched.put(stockId, kinds);
        return this;
    }

    /** 테스트 사이에 상태를 지운다. 이 빈은 싱글턴이라 안 지우면 앞 테스트의 실패가 샌다. */
    public void reset() {
        reportsByStock.clear();
        failures.clear();
        uncertain.clear();
        unfetched.clear();
        fetchedStockIds.clear();
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return markets;
    }

    @Override
    public String sourceName() {
        return "fake-financial";
    }

    @Override
    public FinancialFetch fetchReports(SecurityRef security, int fromFiscalYear)
            throws DataSourceException {
        fetchedStockIds.add(security.stockId());
        DataSourceException failure = failures.get(security.stockId());
        if (failure != null) {
            throw failure;
        }
        List<RawFinancialReport> reports = reportsByStock.getOrDefault(security.stockId(), List.of()).stream()
                .filter(report -> report.fiscalYear() >= fromFiscalYear)
                .toList();
        if (uncertain.contains(security.stockId())) {
            return new FinancialFetch(reports, List.of(), true, Set.of(), Set.of(), true);
        }
        if (unfetched.containsKey(security.stockId())) {
            return new FinancialFetch(reports, List.of(), true, Set.of(), Set.of(), false,
                    unfetched.get(security.stockId()));
        }
        return FinancialFetch.of(reports);
    }
}
