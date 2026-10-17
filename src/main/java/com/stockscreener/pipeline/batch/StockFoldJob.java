package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.collector.identity.EraClosing;
import com.stockscreener.pipeline.collector.identity.FoldPlanner;
import com.stockscreener.pipeline.collector.identity.StockFold;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 손 잡 {@code stock-fold}(ADR-0180) — 신원 판정이 들어오기 전 토요일 리밸런싱이 개명된 실체를 새 행으로 등록한 것을 접는다.
 *
 * <p><b>기본은 계획만 낸다</b>({@code --apply=false}) — 짝마다 판정(접기 · 시대 닫기 · 멈춤)과 표마다 옮길·버릴·다른 행 수를 찍고 아무것도
 * 안 쓴다. 운영 쓰기라 사용자 승인 뒤 {@code --apply=true}로 친다. 짝마다 트랜잭션 하나이고, 접기 직전에 다시 세어 값이 다른 자식 행이 생겼으면
 * 그 짝만 되돌린다.
 *
 * <p>MUST: 자식 표 중 규칙이 없는 것이 있으면 쓰기 갈래를 거절한다({@link StockFold#uncoveredChildTables}) — 그 표의 새 행 자식이 새 행과
 * 함께 지워진다.
 */
@Component
public class StockFoldJob {

    public static final String JOB_NAME = "stock-fold";

    private static final Logger log = LoggerFactory.getLogger(StockFoldJob.class);

    private final FoldPlanner planner;
    private final StockFold fold;
    private final BatchExecutions batchExecutions;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EraClosing eraClosing;

    public StockFoldJob(FoldPlanner planner, StockFold fold, BatchExecutions batchExecutions, JdbcTemplate jdbc,
                        PlatformTransactionManager transactionManager, EraClosing eraClosing) {
        this.planner = planner;
        this.fold = fold;
        this.batchExecutions = batchExecutions;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.eraClosing = eraClosing;
    }

    /**
     * @param since 이 시각 뒤에 생긴 미국 행을 새 행 후보로 본다(그 토요일 리밸런싱의 시작 전)
     * @param apply 거짓이면 계획만
     */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy, Instant since, boolean apply) {
        FoldPlanner.Result result;
        try {
            result = planner.plan(since, asOf);
        } catch (DataSourceException e) {
            batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0,
                    BatchExecutionRecorder.DeclaredScope.narrow(BatchExecutionRecorder.ScopeReason.NO_SCHEDULED_RANGE))
                    .failFatally(e);
            return BatchStatus.FAILED;
        }
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, asOf, triggeredBy, result.pairs().size(),
                BatchExecutionRecorder.DeclaredScope.narrow(BatchExecutionRecorder.ScopeReason.NO_SCHEDULED_RANGE));
        execution.plan(CollectionPhase.SECURITY_MASTER, result.pairs().size());
        Map<String, Integer> kinds = new TreeMap<>();
        for (FoldPlanner.Pair pair : result.pairs()) {
            kinds.merge(pair.kind().name(), 1, Integer::sum);
            log.info("접기 {} {}({}) ← {}({}) · {}", pair.kind(), pair.targetTicker(), pair.target(), pair.sourceTicker(),
                    pair.source(), pair.evidence());
        }
        log.info("접기 계획 — 짝 {} {} · 짝 없는 새 행 {}: {} · 규칙 없는 자식 표 {}", result.pairs().size(), kinds,
                result.unmatched().size(), result.unmatched(), result.uncoveredTables());
        if (!apply) {
            execution.countExternally(CollectionPhase.SECURITY_MASTER, result.pairs().size(), 0, 0);
            return execution.finish("계획만(--apply=false) · 짝 %s · 짝 없는 새 행 %d · 규칙 없는 자식 표 %s"
                    .formatted(kinds, result.unmatched().size(), result.uncoveredTables()));
        }
        if (!result.uncoveredTables().isEmpty()) {
            IllegalStateException e = new IllegalStateException(
                    "규칙이 없는 자식 표가 있어 접지 않는다(새 행 자식이 함께 지워진다): " + result.uncoveredTables());
            execution.failUnexpectedly(e);
            throw e;
        }
        int applied = 0;
        int failed = 0;
        for (FoldPlanner.Pair pair : result.pairs()) {
            if (pair.kind() == FoldPlanner.Kind.STOP) {
                continue;
            }
            try {
                transaction.executeWithoutResult(status -> {
                    if (pair.kind() == FoldPlanner.Kind.FOLD) {
                        StockFold.Plan done = fold.apply(pair.target(), pair.source(), pair.renamedOn());
                        record(execution.executionId(), pair, "RENAME", "접기 " + pair.evidence() + " · 실제" + done.describe());
                    } else {
                        closeEra(pair);
                        record(execution.executionId(), pair, "SPLIT", "시대 닫기 " + pair.evidence());
                    }
                });
                applied++;
            } catch (RuntimeException e) {
                // 짝 하나의 실패가 다른 짝을 막지 않는다 — 그 짝은 트랜잭션째 되돌아갔다. 조용히 넘기지 않는다(로그 · 실패 수 · 메모).
                failed++;
                log.error("접기 실패 — 그 짝을 되돌렸다. {}({}) ← {}({})", pair.targetTicker(), pair.target(), pair.sourceTicker(),
                        pair.source(), e);
            }
        }
        execution.countExternally(CollectionPhase.SECURITY_MASTER, applied, failed, applied);
        return execution.finish("반영 %d · 실패 %d · 판정 %s · 짝 없는 새 행 %d".formatted(applied, failed, kinds,
                result.unmatched().size()));
    }

    /**
     * 닫힌 시대의 위생(계획 {@code US-PAST-LISTING-PLAN-1017.md} P0-b) — {@code EraClosing}이 생기기 전에 닫힌 행을 같은 모양으로 맞춘다.
     * 둘을 고친다: ① 시대가 닫혔는데 표지가 기호 그대로인 행 — 표지를 비운다(그 기호를 새로 받은 실체가 그 행에 붙는 길을 막는다) ·
     * ② 행의 시대가 그 공시일을 안 덮는데 열려 있는 장부 행 — 그 행의 공시가 아니라 소비가 영영 못 먹는다(지운다 · 장부가 이제 붙이지
     * 않으므로 다시 안 생긴다). 운영 행을 고치는 손 실행이라 {@code --apply}는 사용자 승인 뒤다 — 기본은 대상만 적는다.
     */
    public BatchStatus healClosed(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy, boolean apply) {
        HealTargets targets = healTargets();
        List<Map<String, Object>> labels = targets.labels();
        List<Map<String, Object>> ledger = targets.ledger();
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, asOf, triggeredBy, labels.size() + ledger.size(),
                BatchExecutionRecorder.DeclaredScope.narrow(BatchExecutionRecorder.ScopeReason.NO_SCHEDULED_RANGE));
        execution.plan(CollectionPhase.SECURITY_MASTER, labels.size() + ledger.size());
        labels.forEach(r -> log.info("닫힌 행 표지 비우기 대상 {}", r));
        ledger.forEach(r -> log.info("시대 밖 장부 행 지우기 대상 {}", r));
        String what = "닫힌 행 표지 %d · 시대 밖 장부 행 %d".formatted(labels.size(), ledger.size());
        if (!apply) {
            execution.countExternally(CollectionPhase.SECURITY_MASTER, labels.size() + ledger.size(), 0, 0);
            return execution.finish("계획만(--apply=false) · " + what);
        }
        transaction.executeWithoutResult(status -> {
            for (Map<String, Object> r : labels) {
                eraClosing.close(((Number) r.get("id")).longValue(), ((java.sql.Date) r.get("era_to")).toLocalDate());
            }
            for (Map<String, Object> r : ledger) {
                jdbc.update("DELETE FROM filing_consumption WHERE id = ? AND fr_consumed_at IS NULL", ((Number) r.get("id")).longValue());
            }
        });
        execution.countExternally(CollectionPhase.SECURITY_MASTER, labels.size() + ledger.size(), 0,
                labels.size() + ledger.size());
        return execution.finish("반영 · " + what);
    }

    /** @param labels 시대가 닫혔는데 표지가 기호 그대로인 미국 행 · {@code ledger} 행의 시대가 공시일을 안 덮는 열린 장부 행 */
    public record HealTargets(List<Map<String, Object>> labels, List<Map<String, Object>> ledger) {
    }

    public HealTargets healTargets() {
        List<Map<String, Object>> labels = jdbc.queryForList("""
                SELECT id, ticker, era_to FROM stock
                WHERE market_code = 'US' AND era_to IS NOT NULL AND strpos(ticker, ?) = 0
                ORDER BY id
                """, String.valueOf(SecurityRef.VACATED));
        List<Map<String, Object>> ledger = jdbc.queryForList("""
                SELECT c.id, c.stock_id, s.ticker, c.filing_no, c.form, c.filed_on FROM filing_consumption c
                JOIN stock s ON s.id = c.stock_id
                WHERE c.market = 'US' AND c.fr_consumed_at IS NULL AND NOT (%s)
                ORDER BY c.stock_id, c.filed_on
                """.formatted(StockRegistrar.eraCovers("s", "c.filed_on")));
        return new HealTargets(labels, ledger);
    }

    /**
     * 등록인 교체 짝 — 두 행이 맞다. 신원 판정의 행 끊기와 같은 결과를 남긴다: 앞 행 시대 끝 · 새 행 시대 시작 · 앞 행의 기호 시대 물려받기.
     * 새 행의 등록인은 여기서 안 묶는다 — 그 행의 다음 FR이 포함 검사로 묶는다(오늘 파일이 새 등록인을 주고 저장 판이 0이거나 전부 그 것).
     */
    private void closeEra(FoldPlanner.Pair pair) {
        eraClosing.close(pair.target(), pair.switchDate());
        jdbc.update("UPDATE stock SET era_from = ?, updated_at = now() WHERE id = ?", pair.switchDate(), pair.source());
        jdbc.update("""
                INSERT INTO stock_symbol_era (stock_id, symbol, valid_from, valid_to)
                SELECT ?, symbol, valid_from, valid_to FROM stock_symbol_era WHERE stock_id = ?
                ON CONFLICT DO NOTHING""", pair.source(), pair.target());
        LocalDate validFrom = jdbc.queryForObject("SELECT max(valid_to) FROM stock_symbol_era WHERE stock_id = ?",
                LocalDate.class, pair.source());
        jdbc.update("INSERT INTO stock_symbol_era (stock_id, symbol, valid_from, valid_to) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                pair.source(), pair.targetTicker(), validFrom, pair.renamedOn());
    }

    private void record(long executionId, FoldPlanner.Pair pair, String verdict, String evidence) {
        jdbc.update("INSERT INTO identity_triage (execution_id, stock_id, symbol, verdict, evidence) VALUES (?, ?, ?, ?, ?)",
                executionId, pair.target(), pair.sourceTicker(), verdict, evidence);
    }
}
