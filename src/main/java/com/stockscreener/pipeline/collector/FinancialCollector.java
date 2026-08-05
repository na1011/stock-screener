package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.FinancialDataSource;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 한 종목의 재무제표를 수집해 저장한다. 벤더를 모른다.
 */
@Component
public class FinancialCollector {

    private final DataSourceRegistry registry;
    private final FinancialWriter writer;

    public FinancialCollector(DataSourceRegistry registry, FinancialWriter writer) {
        this.registry = registry;
        this.writer = writer;
    }

    /**
     * {@code fromFiscalYear} 이후의 보고서를 수집한다.
     *
     * <p>연간과 분기가 섞여 돌아오며 그대로 저장한다. FY와 Q4를 합치거나 유도하지 않는 이유는
     * 소스가 준 형태를 보존해야 지표 계층이 판단할 수 있기 때문이다.
     *
     * @return 저장한 행 수. 이미 있는 보고서는 세지 않으므로 재수집 시 0이 정상이다.
     */
    public long collect(SecurityRef security, int fromFiscalYear) throws DataSourceException {
        FinancialDataSource source = registry.financialSource(security.marketCode());
        List<RawFinancialReport> reports = source.fetchReports(security, fromFiscalYear);
        return writer.write(security.stockId(), reports);
    }
}
