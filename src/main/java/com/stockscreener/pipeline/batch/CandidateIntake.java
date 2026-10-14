package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.us.SecTickerCikCache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 리밸런싱이 막 만든 미국 종목 행에 업종과 재무를 한 번 붙인다(ADR-0178) — 후보 풀({@code US_LISTED})을 화해한 실행의 꼬리다.
 *
 * <p><b>왜 한 번은 여기서</b>: 공시 장부는 목록을 받을 때 {@code stock}에 있는 종목에만 공시를 붙인다
 * ({@code FilingLedgerStore.stocksByTicker}) — 행이 생기기 전에 나간 정기 공시는 장부에 영영 안 오고, 그러면 새 상장의 첫 10-Q가
 * 행보다 먼저 나간 경우 다음 공시(약 한 분기)까지 주식수가 없어 순위 후보가 못 된다. 이미 있던 행(재상장·티커가 돌아옴)은 장부가
 * 공시를 놓치지 않으므로 안 부른다.
 *
 * <p><b>업종이 먼저다</b>: 순위 어댑터의 비사업회사 거름(리츠·스팩 SIC)은 SIC가 비면 통과시킨다 — 주식수가 먼저 생기면 그 사이
 * 스팩이 후보가 된다.
 *
 * <p>재무는 SEC 매핑에 있는 티커만 부른다 — 매핑에 없는 것(워런트·유닛·ETF 꼴)은 소스가 그 티커를 모른다는 답이 이미 있고, 부르면
 * 종목마다 같은 실패가 부분 실패 알림으로 쌓인다. 그 수는 로그에 남긴다.
 *
 * <p><b>던지지 않는다</b> — 리밸런싱은 이미 성공으로 끝났다. 실패는 알린다({@code MembershipGapFiller}와 같은 자리). 종료 신호만 되던진다.
 */
@Component
public class CandidateIntake {

    private static final Logger log = LoggerFactory.getLogger(CandidateIntake.class);

    private final JdbcTemplate jdbc;
    private final CompanyProfileJob companyProfileJob;
    private final FinancialRefillJob financialRefillJob;
    private final SecTickerCikCache cikCache;
    private final AlertPort alertPort;

    public CandidateIntake(JdbcTemplate jdbc, CompanyProfileJob companyProfileJob, FinancialRefillJob financialRefillJob,
                           SecTickerCikCache cikCache, AlertPort alertPort) {
        this.jdbc = jdbc;
        this.companyProfileJob = companyProfileJob;
        this.financialRefillJob = financialRefillJob;
        this.cikCache = cikCache;
        this.alertPort = alertPort;
    }

    /** 이 실행의 시작 시각 — {@code stock.created_at}과 같은 DB 시계로 「이번 실행이 만든 행」을 가른다. */
    public Instant now() {
        return jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    /**
     * {@code since} 뒤에 만들어진 미국 종목 행에 업종 → 재무(기본 창)를 붙인다. 둘 다 {@code GAP_FILL}이다 — 꼬리가 부른 좁힌 실행이라
     * 시계에 안 센다.
     */
    public void run(LocalDate asOf, Instant since) {
        List<String> created = jdbc.queryForList(
                "SELECT ticker FROM stock WHERE market_code = 'US' AND created_at >= ? ORDER BY ticker", String.class,
                Timestamp.from(since));
        if (created.isEmpty()) {
            log.info("후보 인입: 이번 실행이 만든 미국 종목 행이 없다.");
            return;
        }
        try {
            companyProfileJob.runForCandidates(asOf, BatchExecutionRecorder.TriggeredBy.GAP_FILL, created);
            List<String> mapped = mapped(created);
            log.info("후보 인입: 새 행 {} · SEC 매핑 있음 {} · 없음(재무 안 부름) {}: {}", created.size(), mapped.size(),
                    created.size() - mapped.size(), created.stream().filter(t -> !mapped.contains(t)).toList());
            if (!mapped.isEmpty()) {
                financialRefillJob.runForCandidates(asOf, BatchExecutionRecorder.TriggeredBy.GAP_FILL, mapped);
            }
        } catch (BatchShutdownException e) {
            throw e;
        } catch (DataSourceException | RuntimeException e) {
            log.error("후보 인입이 실패했다. 새 행 {}종목", created.size(), e);
            alertPort.send(AlertEvent.toOperator(AlertSeverity.ERROR, "후보 인입 실패",
                    ("리밸런싱이 만든 미국 종목 행 %d개에 업종·재무를 못 붙였다: %s — 이 행들은 다음 공시가 장부에 올 때까지 주식수가 없어 "
                            + "순위 후보가 못 된다. 평일 13:30~15:40 밖에서 --job=company-profile·--job=financial-refill을 그 티커로 "
                            + "다시 돌린다(후보 모드 손 실행 경로는 없다 — 아래 티커는 US_LISTED 편입이다: --universe=US_LISTED). 티커: %s")
                            .formatted(created.size(), e.getMessage(), String.join(",", created)),
                    "candidate-intake:" + asOf));
        }
    }

    /** SEC 매핑에 있는 티커 — 신선한 판 하나로 가른다. 판을 못 받으면 던진다(낡은 판의 「없음」은 답이 아니다). */
    private List<String> mapped(List<String> tickers) throws DataSourceException {
        SecTickerCikCache.Snapshot snapshot = cikCache.freshSnapshot();
        return tickers.stream().filter(t -> snapshot.findCik(t).isPresent()).toList();
    }
}
