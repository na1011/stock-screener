package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.FullStatementDataSource;
import com.stockscreener.pipeline.datasource.FullStatementResult;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StatementPeriod;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 한 종목의 전체 재무제표를 수집해 <b>결손만</b> 채운다. 벤더를 모른다.
 *
 * <p>{@link FinancialCollector}와 나뉜 이유는 쓰기 방식이 다르기 때문이다. 이쪽은
 * {@link FinancialWriter.WriteMode#FILL_WITHOUT_REVISION}으로만 쓴다 — 정정공시를 만나면
 * 개정본을 쌓지 않고 남긴다. 그 근거는 그 enum 상수에 있다.
 *
 * <p>MUST NOT: 이 콜렉터에 {@code APPEND_ONLY}·{@code FILL_MISSING} 경로를 열지 않는다.
 * 모드를 인자로 받는 순간 호출자가 정정 경로를 켤 수 있게 되고, 그러면 "가지 자체가 없다"는
 * 보장이 "아무도 그 인자를 안 쓴다"는 관행으로 내려앉는다.
 */
@Component
public class FullStatementCollector {

    private final DataSourceRegistry registry;
    private final FinancialWriter writer;

    public FullStatementCollector(DataSourceRegistry registry, FinancialWriter writer) {
        this.registry = registry;
        this.writer = writer;
    }

    /**
     * 지정한 회계연도 범위와 기간의 보고서를 받아 결손을 채운다.
     *
     * @return 무엇을 채웠고 무엇을 못 채웠는지, 그리고 그것이 태운 외부 호출 수
     */
    public Collected collect(SecurityRef security, int fromFiscalYear, int toFiscalYear,
                             Set<StatementPeriod> periods) throws DataSourceException {
        FullStatementDataSource source = registry.fullStatementSource(security.marketCode());
        FullStatementResult fetched =
                source.fetchStatements(security, fromFiscalYear, toFiscalYear, periods);
        FinancialWriter.Outcome outcome = writer.writeDetailed(
                security.stockId(), fetched.reports(),
                FinancialWriter.WriteMode.FILL_WITHOUT_REVISION);
        return new Collected(outcome.withFilingTrace(fetched.filingTrace()), fetched.apiCalls(),
                fetched.reports().size(), fetched.missingReports());
    }

    /**
     * 한 종목의 수집 결과.
     *
     * @param outcome        쓰기 내역. {@code deferredRevisions}가 못 채운 기간 수다
     * @param apiCalls       실제로 태운 외부 호출 수. 예산 대조의 근거다
     * @param reportsWritten 쓰기로 넘긴 보고서 수. 소스가 준 것과 유도한 것이 섞여 있다
     * @param missingReports 요청했으나 <b>소스에 공시가 없던</b> 수. 상장 전 회계연도가
     *                       여기로 드러난다
     */
    public record Collected(FinancialWriter.Outcome outcome, int apiCalls,
                            int reportsWritten, int missingReports) {
    }
}
