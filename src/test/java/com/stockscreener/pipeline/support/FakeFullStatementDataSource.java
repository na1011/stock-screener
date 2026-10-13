package com.stockscreener.pipeline.support;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FullStatementDataSource;
import com.stockscreener.pipeline.datasource.FullStatementResult;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StatementPeriod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 테스트용 인메모리 전체 재무제표 소스. */
public class FakeFullStatementDataSource implements FullStatementDataSource {

    private final Set<String> markets;
    private final Map<Long, List<RawFinancialReport>> reportsByStock = new HashMap<>();
    private final Map<Long, DataSourceException> failures = new HashMap<>();

    /** 응답에서 본 공시 번호(공시 소비의 근거 · ADR-0176 2차). */
    private final Map<Long, com.stockscreener.pipeline.datasource.FilingTrace> traces = new HashMap<>();

    public final List<Long> fetchedStockIds = new ArrayList<>();
    /** 마지막 호출이 요청한 기간. 잡이 분기를 함부로 켜지 않는지 보는 데 쓴다. */
    public final List<Set<StatementPeriod>> requestedPeriods = new ArrayList<>();

    public FakeFullStatementDataSource(String... markets) {
        this.markets = Set.of(markets);
    }

    public FakeFullStatementDataSource withReports(long stockId, List<RawFinancialReport> reports) {
        reportsByStock.put(stockId, reports);
        return this;
    }

    public FakeFullStatementDataSource withTrace(long stockId, com.stockscreener.pipeline.datasource.FilingTrace trace) {
        traces.put(stockId, trace);
        return this;
    }

    public FakeFullStatementDataSource failingWith(long stockId, DataSourceException exception) {
        failures.put(stockId, exception);
        return this;
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return markets;
    }

    @Override
    public String sourceName() {
        return "fake-full-statement";
    }

    @Override
    public FullStatementResult fetchStatements(SecurityRef security, int fromFiscalYear,
                                               int toFiscalYear, Set<StatementPeriod> periods)
            throws DataSourceException {
        fetchedStockIds.add(security.stockId());
        requestedPeriods.add(Set.copyOf(periods));
        DataSourceException failure = failures.get(security.stockId());
        if (failure != null) {
            throw failure;
        }
        List<RawFinancialReport> matched =
                reportsByStock.getOrDefault(security.stockId(), List.of()).stream()
                        .filter(report -> report.fiscalYear() >= fromFiscalYear
                                && report.fiscalYear() <= toFiscalYear)
                        .filter(report -> periods.stream()
                                .anyMatch(period -> period.label().equals(report.fiscalPeriod())))
                        .toList();
        // 실제 어댑터는 (연도 × 기간)마다 한 번씩 부르고, 연결이 비면 두 번 부른다.
        // 여기서는 요청한 만큼 불렀다고 본다 — 잡이 그 수를 그대로 실행 기록에 싣는지가 관심사다.
        int requested = (toFiscalYear - fromFiscalYear + 1) * periods.size();
        return new FullStatementResult(matched, requested, requested - matched.size(),
                traces.getOrDefault(security.stockId(), com.stockscreener.pipeline.datasource.FilingTrace.NONE));
    }
}
