package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.GovernanceDataSource;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 한 종목의 정기보고서 주요정보를 받아 <b>원문 그대로</b> 저장한다. 벤더를 모른다(ADR-0081).
 *
 * <p>유도는 하지 않는다. 표를 원문과 유도로 가른 이유가 <b>매핑을 고칠 때 재적재가 아니라
 * 재유도로 끝나게 하려는 것</b>이라, 이 둘을 한 잡에 묶으면 그 이득이 사라진다.
 */
@Component
public class GovernanceCollector {

    private static final Logger log = LoggerFactory.getLogger(GovernanceCollector.class);

    private final DataSourceRegistry registry;
    private final GovernanceRawWriter writer;

    public GovernanceCollector(DataSourceRegistry registry, GovernanceRawWriter writer) {
        this.registry = registry;
        this.writer = writer;
    }

    /**
     * 표 하나를 회계연도 목록만큼 받아 저장한다.
     *
     * <p>MUST: <b>표를 하나씩</b> 받는다. 한 호출에 여덟을 다 돌면 실패 격리의 단위가 종목이
     * 아니라 「종목 × 여덟」이 되어, 한 표가 깨질 때 이미 받은 일곱을 다시 받게 된다.
     *
     * @return 저장한 행 수
     */
    @Transactional
    public long collect(SecurityRef security, GovernanceEndpoint endpoint, List<Integer> years,
                        String reportCode) throws DataSourceException {
        GovernanceDataSource source = registry.governanceSource(security.marketCode());
        List<GovernanceReportRow> collected = new ArrayList<>();
        for (int year : years) {
            collected.addAll(source.fetchReport(security, endpoint, year, reportCode));
        }
        return writer.write(security.stockId(), stampNoDataRows(security, collected));
    }

    /**
     * 데이터 없음 행에 <b>같은 보고서의</b> 접수번호와 공시일을 찍는다.
     *
     * <p>「그 회사에 그 표가 비어 있다」도 시점이 있는 사실인데 {@code 013} 응답에는
     * {@code rcept_no}가 없다. 같은 종목·같은 사업연도의 <b>다른 행</b>이 같은 정기보고서에서
     * 오므로 그 접수번호가 이 행의 것이기도 하다 — 지어낸 값이 아니다.
     *
     * <p>같은 해에 실제 행이 하나도 없으면 <b>찍지 않고 그대로 둔다.</b> 그때는 공시일을
     * 알 길이 없고, 모르는 것을 채우면 지표가 그 날짜를 「그날 알았다」로 읽는다.
     *
     * <p>표를 하나씩 받으므로 대개 같은 해의 실제 행이 <b>이 목록 안에는 없다.</b> 그래서
     * 채우는 몫의 대부분은 저장 계층이 진다({@link GovernanceRawWriter#stampNoData}).
     */
    private List<GovernanceReportRow> stampNoDataRows(SecurityRef security,
                                                      List<GovernanceReportRow> rows) {
        Map<Integer, GovernanceReportRow> anchorByYear = new java.util.HashMap<>();
        for (GovernanceReportRow row : rows) {
            if (!row.isNoData() && row.publishedAt() != null) {
                anchorByYear.putIfAbsent(row.bsnsYear(), row);
            }
        }
        List<GovernanceReportRow> stamped = new ArrayList<>(rows.size());
        int unresolved = 0;
        for (GovernanceReportRow row : rows) {
            GovernanceReportRow anchor = row.isNoData() ? anchorByYear.get(row.bsnsYear()) : null;
            if (anchor != null) {
                stamped.add(row.stampedWith(anchor.receiptNo(), anchor.publishedAt()));
            } else {
                if (row.isNoData()) {
                    unresolved++;
                }
                stamped.add(row);
            }
        }
        if (unresolved > 0) {
            log.debug("데이터 없음 행 {}건에 같은 해의 접수번호를 못 찍었다. ticker={} — 저장 계층이 "
                    + "다른 표에서 찾는다.", unresolved, security.ticker());
        }
        return stamped;
    }

    /**
     * 그 시장의 소스가 <b>실제로 답하는</b> 표. 잡이 이것으로 좁힌다.
     *
     * <p>안 좁히면 소스가 안 주는 표에 <b>「그 회사에 그 표가 없다」는 거짓 행</b>이 쌓인다 —
     * 미국에 없는 것은 그 회사의 표가 아니라 <b>그 표를 주는 소스</b>이고, 그 구분 위에
     * 커버리지 판정이 선다. 소스가 없는 시장이면 예외가 그대로 올라간다.
     */
    public Set<GovernanceEndpoint> supportedEndpoints(String marketCode)
            throws DataSourceException {
        return registry.governanceSource(marketCode).supportedEndpoints();
    }

    /** 잡이 「그 해의 다른 표」로 데이터 없음 행을 마저 채울 때 쓴다. */
    public long stampNoDataFromSiblings(long stockId, int bsnsYear, String reportCode) {
        return writer.stampNoData(stockId, bsnsYear, reportCode);
    }

    /** 아직 접수번호를 못 찍은 데이터 없음 행이 몇인가. 적재 뒤 판정에 쓴다. */
    public long unstampedNoDataRows() {
        return writer.unstampedNoDataRows();
    }

    /** 마지막 적재 이후 이 종목이 실제로 받은 회계연도. 로그와 진단에 쓴다. */
    public List<LocalDate> publishedDates(long stockId) {
        return writer.publishedDates(stockId);
    }
}
