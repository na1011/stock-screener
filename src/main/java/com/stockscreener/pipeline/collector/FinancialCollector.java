package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.FinancialDataSource;
import com.stockscreener.pipeline.datasource.FinancialFetch;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 한 종목의 재무제표를 수집해 저장한다. 벤더를 모른다.
 */
@Component
public class FinancialCollector {

    private final DataSourceRegistry registry;
    private final FinancialWriter writer;
    private final FinancialColumnAbsenceWriter absenceWriter;

    public FinancialCollector(DataSourceRegistry registry, FinancialWriter writer,
                              FinancialColumnAbsenceWriter absenceWriter) {
        this.registry = registry;
        this.writer = writer;
        this.absenceWriter = absenceWriter;
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
        return collect(security, fromFiscalYear, FinancialWriter.WriteMode.APPEND_ONLY);
    }

    /**
     * 쓰기 방식을 지정해 수집한다.
     *
     * <p>{@link FinancialWriter.WriteMode#FILL_MISSING}은 <b>결손 보정 잡 전용</b>이다
     * (ADR-0040). 일일·초기 적재는 위 2-인자 경로를 쓰며 그 뜻은 도입 전과 같다.
     *
     * @return <b>실제로 바뀐</b> 행 수 — 신규 + 정정 + 채움
     */
    public long collect(SecurityRef security, int fromFiscalYear,
                        FinancialWriter.WriteMode mode) throws DataSourceException {
        return collectDetailed(security, fromFiscalYear, mode).changed();
    }

    /**
     * 위와 같되 내역을 함께 낸다.
     *
     * <p>행 수만으로는 <b>정정공시를 반영했는지</b>를 알 수 없다 — 신규·정정·채움이
     * {@code changed} 하나에 뭉뚱그려지기 때문이다. 정정을 반영하면 그 종목의 지표가 보존 창
     * 전체에서 지워지므로, 잡이 그 수를 알아야 <b>백필이 남았다</b>는 것을 알릴 수 있다.
     */
    public FinancialWriter.Outcome collectDetailed(SecurityRef security, int fromFiscalYear,
                                                   FinancialWriter.WriteMode mode)
            throws DataSourceException {
        FinancialDataSource source = registry.financialSource(security.marketCode());
        FinancialFetch fetch = source.fetchReports(security, fromFiscalYear);
        List<RawFinancialReport> reports = fetch.reports();
        // MUST: 보고서보다 먼저 쓰지 않는다 — 부재 판정은 이번 수집의 사실이고, 보고서 쓰기가
        // 실패해 롤백되면 그 판정도 함께 되돌아가야 한다. 같은 트랜잭션은 아니지만 순서가
        // 그 뜻을 지킨다(쓰기가 던지면 부재 행이 안 남는다).
        FinancialWriter.Outcome outcome = writer.writeDetailed(security.stockId(), reports, mode);
        absenceWriter.write(security.stockId(), source.sourceName(), fetch.absences(),
                LocalDate.now());
        return outcome;
    }
}
