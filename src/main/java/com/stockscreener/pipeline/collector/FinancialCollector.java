package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.FinancialDataSource;
import com.stockscreener.pipeline.datasource.FinancialFetch;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.collector.identity.RegistrantContainment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 한 종목의 재무제표를 수집해 저장한다. 벤더를 모른다.
 */
@Component
public class FinancialCollector {

    private static final Logger log = LoggerFactory.getLogger(FinancialCollector.class);

    private final DataSourceRegistry registry;
    private final FinancialWriter writer;
    private final FinancialColumnAbsenceWriter absenceWriter;
    private final JdbcTemplate jdbcTemplate;

    public FinancialCollector(DataSourceRegistry registry, FinancialWriter writer,
                              FinancialColumnAbsenceWriter absenceWriter, JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.writer = writer;
        this.absenceWriter = absenceWriter;
        this.jdbcTemplate = jdbcTemplate;
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
        return collectDetailed(security, fromFiscalYear, mode, false);
    }

    /**
     * 위와 같되 <b>저장본보다 이른 판을 넣을지</b>를 받는다
     * ({@link FinancialWriter#writeDetailed(long, List, FinancialWriter.WriteMode, boolean, boolean)}).
     * 소스가 판 이력을 전부 내지 않으면 참을 줘도 아무 일도 없다.
     */
    public FinancialWriter.Outcome collectDetailed(SecurityRef security, int fromFiscalYear,
                                                   FinancialWriter.WriteMode mode,
                                                   boolean insertEarlierVintages)
            throws DataSourceException {
        FinancialDataSource source = registry.financialSource(security.marketCode());
        FinancialFetch fetch = source.fetchReports(security, fromFiscalYear);
        List<RawFinancialReport> reports = fetch.reports();
        boolean bindRegistrant = registrantGate(security, fetch, source.sourceName());
        // MUST: 보고서보다 먼저 쓰지 않는다 — 부재 판정은 이번 수집의 사실이고, 보고서 쓰기가
        // 실패해 롤백되면 그 판정도 함께 되돌아가야 한다. 같은 트랜잭션은 아니지만 순서가
        // 그 뜻을 지킨다(쓰기가 던지면 부재 행이 안 남는다).
        FinancialWriter.Outcome outcome = writer.writeDetailed(security.stockId(), reports, mode,
                fetch.completeVintageHistory(), insertEarlierVintages,
                new FinancialWriter.VintageGuard(fetch.unconfirmedVintages(), fetch.supplementedVintages(),
                        fetch.vintagesUncertain(), fetch.withheldVintages()));
        absenceWriter.write(security.stockId(), source.sourceName(), fetch.absences(),
                LocalDate.now());
        if (bindRegistrant && !reports.isEmpty()) {
            int bound = jdbcTemplate.update("UPDATE stock SET sec_cik = ?, updated_at = now() WHERE id = ? AND sec_cik IS NULL",
                    fetch.registrant().cik(), security.stockId());
            if (bound == 1) {
                log.info("재무로 등록인을 묶었다. stock_id={} ticker={} cik={}", security.stockId(), security.ticker(),
                        fetch.registrant().cik());
            }
        }
        // 받지 못한 보충은 종목을 세우지 않는다(보충 계약) — 잡이 시장 판정에 쓰도록 결과에 실어 넘긴다(ADR-0174).
        return outcome.withUnfetchedSupplements(fetch.unfetchedSupplements()).withFilingTrace(fetch.filingTrace())
                .withUnfetchedClassFacts(fetch.unfetchedClassFacts());
    }

    /**
     * 등록인이 안 묶인 미국 행(ADR-0180) — 이 수집이 물은 등록인(오늘 티커 파일로 푼 것)이 그 행의 저장 판의 등록인인지 <b>쓰기 전에</b>
     * 가른다. 쓰기 뒤에 재면 방금 쓴 행이 보인다.
     * <ul>
     *   <li>저장 판 0 — 섞일 것이 없다. 쓰고 묶는다.</li>
     *   <li>저장 접수번호가 전부 그 등록인 것({@link RegistrantContainment}) — 같은 등록인이다. 쓰고 묶는다.</li>
     *   <li>하나도 안 든다({@code NONE}) — 오늘 파일이 그 기호에 다른 등록인을 준다(교체 · 재사용 · REAX꼴). 쓰면 옛 등록인 라벨에 새
     *       등록인 판이 섞이고 회수가 옛 판을 지운다(★16). <b>쓰지 않고</b> 그 종목을 소스가 답한 실패로 남긴다 — 신원 판정이 행을 끊거나
     *       사람이 판정한다.</li>
     *   <li>일부만 든다({@code PARTIAL}) — 판정을 못 준다. companyfacts가 실었던 공시를 빼고 그 공시의 보충마저 이번에 실패하면 같은
     *       등록인도 이렇게 나온다. 지금까지처럼 쓰되 <b>묶지 않는다</b>(다음 실행이 다시 잰다).</li>
     * </ul>
     * 묶인 행·국내·시험(등록인을 안 내는 소스)은 이 갈래를 안 탄다.
     *
     * @return 쓴 뒤 묶을지
     */
    private boolean registrantGate(SecurityRef security, FinancialFetch fetch, String sourceName) throws DataSourceException {
        FinancialFetch.Registrant registrant = fetch.registrant();
        if (registrant == null || security.identity().secCik() != null) {
            return false;
        }
        RegistrantContainment.Check check = RegistrantContainment.check(
                RegistrantContainment.storedAccessions(jdbcTemplate, security.stockId()),
                RegistrantContainment.known(jdbcTemplate, registrant.cik(), registrant.accessions()));
        if (check.result() == RegistrantContainment.Result.ALL || check.result() == RegistrantContainment.Result.NO_STORED) {
            return true;
        }
        if (check.result() == RegistrantContainment.Result.PARTIAL) {
            log.warn("등록인을 못 묶는다 — {} CIK {} {}. 지금까지처럼 쓰고 다음 실행이 다시 잰다.", security.ticker(), registrant.cik(),
                    check.describe());
            return false;
        }
        throw DataSourceException.answered(DataSourceException.Kind.NOT_FOUND, sourceName,
                "오늘 티커 파일이 %s에 준 등록인 CIK %d가 저장 판의 등록인이 아니다 — %s. 쓰면 두 등록인의 판이 한 행에 섞인다(ADR-0180). 신원 판정·sec-cik-bind가 가른다."
                        .formatted(security.ticker(), registrant.cik(), check.describe()));
    }
}
