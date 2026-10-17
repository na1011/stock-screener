package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.identity.RegistrantContainment;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 손 잡 {@code sec-cik-bind}(ADR-0180) — 재무 행이 있는데 등록인이 안 묶인 미국 행을 포함 검사로 묶는다. 정기 FR도 같은 검사로 묶으므로
 * (추가 콜 0 · 행마다 다음 FR 때) 이 잡은 <b>한 번에</b> 묶고 싶을 때다 — 관문 전 · 신원 판정이 미결합 행의 개명을 보류로 남길 때.
 *
 * <p><b>기본은 계획만 낸다</b>({@code --apply=false}). 둘 다 행마다 후보 등록인의 companyfacts를 한 번 받는다(SEC 콜 · 평균 약 1.3MB) —
 * 실행 기록에 콜 수를 남긴다. 후보는 오늘 등록인 파일의 CIK와 {@code --ciks=티커:CIK,…}(손 후보 — 출처는 실행 메모에)다.
 */
@Component
public class SecCikBindJob {

    public static final String JOB_NAME = "sec-cik-bind";

    private static final Logger log = LoggerFactory.getLogger(SecCikBindJob.class);

    private final ObjectProvider<IdentitySource> sources;
    private final BatchExecutions batchExecutions;
    private final JdbcTemplate jdbc;

    public SecCikBindJob(ObjectProvider<IdentitySource> sources, BatchExecutions batchExecutions, JdbcTemplate jdbc) {
        this.sources = sources;
        this.batchExecutions = batchExecutions;
        this.jdbc = jdbc;
    }

    private record Target(long id, String ticker) {
    }

    /**
     * @param tickers  좁힐 티커(빈 목록이면 대상 전원)
     * @param handCiks 손 후보 — 오늘 파일보다 먼저 본다
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy, List<String> tickers,
                           Map<String, Long> handCiks, boolean apply) {
        List<Target> targets = jdbc.query("""
                SELECT s.id, s.ticker FROM stock s
                WHERE s.market_code = 'US' AND s.sec_cik IS NULL
                  AND EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id = s.id)
                ORDER BY s.ticker""", (rs, i) -> new Target(rs.getLong("id"), rs.getString("ticker")));
        if (!tickers.isEmpty()) {
            targets = targets.stream().filter(t -> tickers.contains(t.ticker())).toList();
        }
        BatchExecutionRecorder.DeclaredScope scope = tickers.isEmpty() ? BatchExecutionRecorder.DeclaredScope.FULL
                : BatchExecutionRecorder.DeclaredScope.byArgs(false);
        IdentitySource source = sources.getIfAvailable();
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size(), scope);
        if (source == null) {
            execution.failFatally(new DataSourceException(DataSourceException.Kind.AUTH, "identity",
                    "신원 판정 소스가 없다(Alpaca·SEC 키)."));
            return BatchStatus.FAILED;
        }
        execution.plan(CollectionPhase.SECURITY_MASTER, targets.size());
        Map<String, Integer> outcome = new TreeMap<>();
        Map<String, String> unbound = new LinkedHashMap<>();
        int calls = 0;
        int bound = 0;
        try {
            IdentitySource.RegistrantFile file = source.registrantFile();
            for (Target target : targets) {
                execution.abortIfAbandoned();
                Optional<Long> cik = Optional.ofNullable(handCiks.get(target.ticker())).or(() -> file.cikOf(target.ticker()));
                if (cik.isEmpty()) {
                    outcome.merge("후보 없음", 1, Integer::sum);
                    unbound.put(target.ticker(), "후보 없음");
                    continue;
                }
                calls++;
                RegistrantContainment.Check check = RegistrantContainment.check(
                        RegistrantContainment.storedAccessions(jdbc, target.id()),
                        RegistrantContainment.known(jdbc, cik.get(), source.registrantAccessions(cik.get())));
                outcome.merge(check.result().name(), 1, Integer::sum);
                if (check.result() != RegistrantContainment.Result.ALL) {
                    unbound.put(target.ticker(), "CIK " + cik.get() + " " + check.describe());
                    continue;
                }
                if (apply) {
                    bound += jdbc.update("UPDATE stock SET sec_cik = ?, updated_at = now() WHERE id = ? AND sec_cik IS NULL",
                            cik.get(), target.id());
                }
            }
        } catch (DataSourceException e) {
            execution.failFatally(e);
            return BatchStatus.FAILED;
        } catch (RuntimeException e) {
            execution.failUnexpectedly(e);
            throw e;
        }
        List<String> sample = new ArrayList<>();
        unbound.forEach((ticker, why) -> sample.add(ticker + ": " + why));
        log.info("등록인 묶기 — 대상 {} · {} · companyfacts {}콜 · 묶음 {}{} · 못 묶은 것: {}", targets.size(), outcome, calls, bound,
                apply ? "" : "(계획만)", sample);
        execution.countExternally(CollectionPhase.SECURITY_MASTER, targets.size() - unbound.size(), unbound.size(), bound);
        return execution.finish("%s대상 %d · %s · companyfacts %d콜 · 묶음 %d · 못 묶음 %d"
                .formatted(apply ? "" : "계획만 · ", targets.size(), outcome, calls, bound, unbound.size()));
    }
}
