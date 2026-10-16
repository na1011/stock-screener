package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.StockRegistrar;
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
 * <p>재무는 SEC 매핑에 있거나 재무 행이 이미 있는 티커만 부른다({@link #financialTargets}) — 매핑에 없는 새 행(워런트·유닛·ETF
 * 꼴)은 소스가 그 티커를 모른다는 답이 이미 있고, 부르면 종목마다 같은 실패가 부분 실패 알림으로 쌓인다. 그 수는 로그에 남긴다.
 * 재무 행이 있는데 매핑에서 빠진 종목은 부른다 — 거르면 옛 규칙의 행이 기록 없이 순위에 남는다(후보 손 FR 계획 v2 · 반증).
 *
 * <p>후보 모드 손 FR({@link #refill} · {@code --job=financial-refill --population=candidates})도 같은 순서(업종 → 재무)와 같은 대상
 * 술어를 탄다 — 관문의 후보 재적재가 그 길이다({@code docs/reload-gate.md} 순서 10).
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
    private final StockRegistrar stockRegistrar;

    public CandidateIntake(JdbcTemplate jdbc, CompanyProfileJob companyProfileJob, FinancialRefillJob financialRefillJob,
                           SecTickerCikCache cikCache, AlertPort alertPort, StockRegistrar stockRegistrar) {
        this.jdbc = jdbc;
        this.stockRegistrar = stockRegistrar;
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
            List<String> mapped = financialTargets(created);
            log.info("후보 인입: 새 행 {} · 재무 대상(SEC 매핑 또는 재무 행 있음) {} · 아님(재무 안 부름) {}: {}", created.size(),
                    mapped.size(), created.size() - mapped.size(), created.stream().filter(t -> !mapped.contains(t)).toList());
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
                            + "다시 돌린다(후보 모드: --job=financial-refill --population=candidates --tickers=… — 업종을 먼저 붙인다). 티커: %s")
                            .formatted(created.size(), e.getMessage(), String.join(",", created)),
                    "candidate-intake:" + asOf));
        }
    }

    /**
     * 후보 모드 손 FR — 미국 순위 후보를 업종(SIC 없는 것만) → 재무 순으로 다시 받는다. 관문의 후보 재적재가 이 길이다
     * ({@code docs/reload-gate.md} 순서 10 · 탐색 {@code CANDIDATE-FR-PLAN-1016.md} v2).
     *
     * <p>업종이 먼저인 이유는 인입과 같다(클래스 javadoc). 실행 범위는 언제나 좁힘이다 — 후보는 정기 FR의 모집단이 아니므로 시계를
     * 밀지 않는다({@code FinancialRefillJob.runForCandidatesManually}).
     *
     * @param tickers 비면 후보 전원. 값이 있으면 그 티커 ∩ 후보.
     * @throws IllegalArgumentException 대상이 0이면 — 대상 0건 성공은 이 잡이 고치려는 결함과 같은 모양이다
     * @throws IllegalStateException    SEC 매핑 판을 못 받았으면 — 낡은 판의 「없음」으로 대상을 정하지 않는다
     */
    public BatchStatus refill(LocalDate asOf, Integer yearsBack, List<String> tickers, boolean insertEarlierVintages) {
        List<String> candidates = tickers.isEmpty() ? stockRegistrar.usRankingCandidateTickers() : tickers;
        List<String> targets;
        try {
            targets = financialTargets(candidates);
        } catch (DataSourceException e) {
            throw new IllegalStateException("SEC 매핑 판을 못 받아 후보 대상을 정하지 못했다 — 아무것도 안 받았다: " + e.getMessage(), e);
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("재무 대상인 미국 순위 후보가 0이다(준 티커 " + tickers.size()
                    + "개 · SEC 매핑 또는 재무 행 있음) — 대상 0건으로 성공시키지 않는다.");
        }
        List<String> withoutSector = jdbc.queryForList("""
                SELECT ticker FROM stock WHERE market_code = 'US' AND sic_code IS NULL AND ticker = ANY (?) ORDER BY ticker
                """, String.class, (Object) targets.toArray(String[]::new));
        log.info("후보 모드 손 FR: 후보 {} · 재무 대상 {} · 아님(SEC 매핑도 재무 행도 없음) {} · 업종 먼저 {}",
                candidates.size(), targets.size(), candidates.size() - targets.size(), withoutSector.size());
        if (!withoutSector.isEmpty()) {
            BatchStatus profile = companyProfileJob.runForCandidates(asOf, BatchExecutionRecorder.TriggeredBy.MANUAL,
                    withoutSector);
            if (profile == BatchStatus.FAILED) {
                // 업종 없이 주식수가 먼저 생기면 순위 어댑터의 비사업회사 거름을 통과한다 — 재무로 넘어가지 않는다.
                throw new IllegalStateException("후보 업종 수집이 FAILED로 끝났다 — 재무를 받지 않는다. 업종 " + withoutSector.size() + "종목");
            }
        }
        return financialRefillJob.runForCandidatesManually(asOf, BatchExecutionRecorder.TriggeredBy.MANUAL, yearsBack,
                targets, insertEarlierVintages).status();
    }

    /**
     * 재무를 부를 후보 티커 — SEC 매핑(신선한 판)에 있거나 재무 행이 이미 있는 것. 판을 못 받으면 던진다(낡은 판의 「없음」은 답이
     * 아니다). 매핑 밖인데 재무 행이 있는 것은 어댑터가 그 부재를 종목 실패로 남기게 부른다 — 그 이름을 로그에 싣는다.
     */
    List<String> financialTargets(List<String> tickers) throws DataSourceException {
        SecTickerCikCache.Snapshot snapshot = cikCache.freshSnapshot();
        java.util.Set<String> withRows = new java.util.HashSet<>(jdbc.queryForList("""
                SELECT s.ticker FROM stock s
                WHERE s.market_code = 'US' AND s.ticker = ANY (?)
                  AND EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id = s.id)
                """, String.class, (Object) tickers.toArray(String[]::new)));
        List<String> unmappedWithRows = tickers.stream()
                .filter(t -> withRows.contains(t) && snapshot.findCik(t).isEmpty()).toList();
        if (!unmappedWithRows.isEmpty()) {
            log.warn("SEC 매핑에 없는데 재무 행이 있는 후보 {}종목 — 부른다(부재를 종목 실패로 남긴다): {}",
                    unmappedWithRows.size(), unmappedWithRows);
        }
        return tickers.stream().filter(t -> withRows.contains(t) || snapshot.findCik(t).isPresent()).toList();
    }
}
