package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.identity.RegistrantContainment;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.RegistrantLookup;

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
 * 실행 기록에 콜 수를 남긴다. 후보는 손 후보({@code --ciks=티커:CIK,…} — 출처는 실행 메모에) → 오늘 등록인 파일의 CIK → 그 둘이 없으면
 * 등록인 조회({@link RegistrantLookup} — 행의 이름 · 그 시대 끝 무렵 제거 양식 후보를 사건 전 정기 공시 표지로 확인 · 탐색
 * {@code US-PAST-LISTING-IMPL-1018.md} §2.2)다. 어느 출처든 묶기는 포함 검사가 정한다. 조회는 오늘 파일에 없는 행(닫힌 행 · 파일
 * 밖 행)에만 쓰고 그때만 창의 색인을 받는다.
 */
@Component
public class SecCikBindJob {

    public static final String JOB_NAME = "sec-cik-bind";

    private static final Logger log = LoggerFactory.getLogger(SecCikBindJob.class);

    /** 등록인 조회 창 — 행의 시대 끝(열린 행은 오늘) 앞 이만큼의 색인을 받는다. */
    static final int LOOKUP_WINDOW_DAYS = 400;

    private final ObjectProvider<IdentitySource> sources;
    private final ObjectProvider<RegistrantLookup> registrants;
    private final BatchExecutions batchExecutions;
    private final JdbcTemplate jdbc;

    public SecCikBindJob(ObjectProvider<IdentitySource> sources, ObjectProvider<RegistrantLookup> registrants,
                         BatchExecutions batchExecutions, JdbcTemplate jdbc) {
        this.sources = sources;
        this.registrants = registrants;
        this.batchExecutions = batchExecutions;
        this.jdbc = jdbc;
    }

    private record Target(long id, String ticker, String name, LocalDate eraTo) {

        String symbol() {
            int cut = ticker.indexOf(com.stockscreener.pipeline.datasource.SecurityRef.VACATED);
            return cut < 0 ? ticker : ticker.substring(0, cut);
        }
    }

    /**
     * @param tickers  좁힐 티커(빈 목록이면 대상 전원)
     * @param handCiks 손 후보 — 오늘 파일보다 먼저 본다
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy, List<String> tickers,
                           Map<String, Long> handCiks, boolean apply) {
        List<Target> targets = jdbc.query("""
                SELECT s.id, s.ticker, s.name, s.era_to FROM stock s
                WHERE s.market_code = 'US' AND s.sec_cik IS NULL
                  AND EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id = s.id)
                ORDER BY s.ticker""", (rs, i) -> new Target(rs.getLong("id"), rs.getString("ticker"), rs.getString("name"),
                rs.getObject("era_to", LocalDate.class)));
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
            RegistrantLookup.Session lookup = null;
            for (Target target : targets) {
                execution.abortIfAbandoned();
                Optional<Long> cik = Optional.ofNullable(handCiks.get(target.ticker())).or(() -> file.cikOf(target.ticker()));
                String via = "";
                if (cik.isEmpty() && registrants.getIfAvailable() != null) {
                    if (lookup == null) {
                        lookup = registrants.getIfAvailable().open(asOf.minusDays(LOOKUP_WINDOW_DAYS), asOf);
                    }
                    LocalDate before = target.eraTo() != null ? target.eraTo() : asOf.plusDays(1);
                    RegistrantLookup.Result found = lookup.find(target.symbol(), before, Optional.ofNullable(target.name()),
                            renames(target.id()));
                    cik = Optional.ofNullable(found.cik());
                    via = " · 조회 " + found.outcome() + " " + found.evidence();
                }
                if (cik.isEmpty()) {
                    outcome.merge("후보 없음", 1, Integer::sum);
                    unbound.put(target.ticker(), "후보 없음" + via);
                    continue;
                }
                calls++;
                RegistrantContainment.Check check = RegistrantContainment.check(
                        RegistrantContainment.storedAccessions(jdbc, target.id()),
                        RegistrantContainment.known(jdbc, cik.get(), source.registrantAccessions(cik.get())));
                outcome.merge(check.result().name(), 1, Integer::sum);
                if (check.result() != RegistrantContainment.Result.ALL) {
                    unbound.put(target.ticker(), "CIK " + cik.get() + " " + check.describe() + via);
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
        // 실행 기록의 귀속 재료 — 조회로 찾은 후보는 그 근거가 남아야 다음 판정이 그것을 다시 안 판다.
        unbound.forEach((ticker, why) -> sample.add(ticker + ": " + why));
        log.info("등록인 묶기 — 대상 {} · {} · companyfacts {}콜 · 묶음 {}{} · 못 묶은 것: {}", targets.size(), outcome, calls, bound,
                apply ? "" : "(계획만)", sample);
        execution.countExternally(CollectionPhase.SECURITY_MASTER, targets.size() - unbound.size(), unbound.size(), bound);
        return execution.finish("%s대상 %d · %s · companyfacts %d콜 · 묶음 %d · 못 묶음 %d"
                .formatted(apply ? "" : "계획만 · ", targets.size(), outcome, calls, bound, unbound.size()));
    }

    /** 그 행의 기호 시대 — 옛 기호가 그 시대 끝에 다음 기호가 됐다(등록인 조회의 공시일 기호 걷기 재료). */
    private List<RegistrantLookup.Rename> renames(long stockId) {
        List<String[]> eras = jdbc.query("SELECT symbol, valid_to FROM stock_symbol_era WHERE stock_id = ? ORDER BY valid_to",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, stockId);
        String current = jdbc.queryForObject("SELECT ticker FROM stock WHERE id = ?", String.class, stockId);
        int cut = current.indexOf(com.stockscreener.pipeline.datasource.SecurityRef.VACATED);
        String now = cut < 0 ? current : current.substring(0, cut);
        List<RegistrantLookup.Rename> out = new ArrayList<>();
        for (int i = 0; i < eras.size(); i++) {
            String next = i + 1 < eras.size() ? eras.get(i + 1)[0] : now;
            out.add(new RegistrantLookup.Rename(eras.get(i)[0], next, LocalDate.parse(eras.get(i)[1])));
        }
        return out;
    }
}
