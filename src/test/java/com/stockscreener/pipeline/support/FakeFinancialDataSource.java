package com.stockscreener.pipeline.support;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FinancialDataSource;
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

    @Override
    public Set<String> supportedMarketCodes() {
        return markets;
    }

    @Override
    public String sourceName() {
        return "fake-financial";
    }

    @Override
    public List<RawFinancialReport> fetchReports(SecurityRef security, int fromFiscalYear)
            throws DataSourceException {
        fetchedStockIds.add(security.stockId());
        DataSourceException failure = failures.get(security.stockId());
        if (failure != null) {
            throw failure;
        }
        return reportsByStock.getOrDefault(security.stockId(), List.of()).stream()
                .filter(report -> report.fiscalYear() >= fromFiscalYear)
                .toList();
    }
}
