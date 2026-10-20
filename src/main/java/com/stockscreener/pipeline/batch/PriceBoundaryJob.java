package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.PriceObservationWindow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 손 잡 {@code price-boundary}(ADR-0181) — 이미 저장된 봉 중 그 행의 관측 구간({@link PriceObservationWindow}) 밖인 것을 지운다.
 *
 * <p>쓰기 술어는 새로 들어오는 봉만 막는다. 08-31 미국 초기 적재가 넣은 다른 증권 봉과 머리 평봉은 이 잡이 걷는다.
 *
 * <p><b>기본은 계획만 낸다</b>({@code --apply=false}) — 행마다 지울 봉 수 · 첫 · 끝 · 경계 앞 원장 행을 찍고 아무것도 안 쓴다. 운영 쓰기라
 * 덤프({@code ops/dump-db.sh}) 뒤 사용자 승인으로 {@code --apply=true}를 친다. 시작 문은 {@link ManualWriteDoor}다.
 * <ul>
 *   <li>지울 봉이 그 행의 편입 구간과 겹치면 그 행을 거절한다 — 편입된 날의 봉이 다른 증권이라는 것은 경계가 틀렸다는 뜻이다.</li>
 *   <li>지우기 전에 지울 행을 CSV로 영구 위치에 내보낸다 — 「원자료는 지우지 않는다」(ADR-0011)의 사유는 소스가 과거를 계속 준다는
 *       보장이 없다는 것이고, 이 봉은 그 행의 증권이 아니며 소스가 지금도 준다. 되돌리기 재료는 이 CSV와 덤프다.</li>
 *   <li>경계 앞 원장 행은 지우지 않고 {@code REJECTED}(이 행의 사건이 아니다)로 둔다.</li>
 * </ul>
 * 멱등이다 — 두 번째 판은 지울 것이 0이다.
 */
@Component
public class PriceBoundaryJob {

    public static final String JOB_NAME = "price-boundary";

    private static final Logger log = LoggerFactory.getLogger(PriceBoundaryJob.class);

    static final Path EXPORT_DIR = Path.of(System.getProperty("user.home"), "Library", "Application Support", "stock-screener",
            "price-boundary");

    private final PriceObservationWindow window;
    private final BatchExecutions batchExecutions;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Path exportDir;

    @org.springframework.beans.factory.annotation.Autowired
    public PriceBoundaryJob(PriceObservationWindow window, BatchExecutions batchExecutions, JdbcTemplate jdbc,
                            PlatformTransactionManager transactionManager) {
        this(window, batchExecutions, jdbc, transactionManager, EXPORT_DIR);
    }

    PriceBoundaryJob(PriceObservationWindow window, BatchExecutions batchExecutions, JdbcTemplate jdbc,
                     PlatformTransactionManager transactionManager, Path exportDir) {
        this.window = window;
        this.batchExecutions = batchExecutions;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.exportDir = exportDir;
    }

    /** 한 행의 교정 계획. */
    public record Target(long stockId, String ticker, List<LocalDate> dates, List<Long> ledgerRows, List<String> overlappingMemberships) {
        public boolean refused() {
            return !overlappingMemberships.isEmpty();
        }
    }

    /** 대상 — 경계 파일 행 · 시대가 박힌 행 · 머리 평봉 후보(미국 · 첫 실거래 앞 평봉이 있는 행). */
    public List<Target> plan() {
        List<Long> candidates = jdbc.queryForList("""
                SELECT s.id FROM stock s
                WHERE s.market_code = 'US' AND (s.era_from IS NOT NULL OR s.era_to IS NOT NULL OR s.alpaca_anchor_symbol = ANY (?))
                UNION
                SELECT p.stock_id FROM price_daily p JOIN stock s ON s.id = p.stock_id
                WHERE s.market_code = 'US' AND p.volume = 0 AND p.open = p.high AND p.high = p.low AND p.low = p.close
                  AND p.trade_date < coalesce((SELECT min(x.trade_date) FROM price_daily x WHERE x.stock_id = p.stock_id AND x.volume > 0),
                                              DATE '9999-12-31')
                ORDER BY 1
                """, Long.class, (Object) window.boundaries().stream().map(b -> b.key().anchorSymbol()).toArray(String[]::new));
        List<Target> out = new ArrayList<>();
        for (long id : candidates) {
            List<LocalDate> dates = window.storedOutside(id);
            PriceObservationWindow.Window w = window.of(id);
            List<Long> ledger = w.floor() == null ? List.of() : jdbc.queryForList("""
                    SELECT id FROM corporate_action WHERE stock_id = ? AND effective_date < ? AND status <> 'REJECTED' ORDER BY id
                    """, Long.class, id, w.floor());
            if (dates.isEmpty() && ledger.isEmpty()) {
                continue;
            }
            List<String> overlaps = dates.isEmpty() ? List.of() : jdbc.queryForList("""
                    SELECT DISTINCT m.universe_code || ' ' || m.effective_from || '~' || coalesce(m.effective_to::text, '열림')
                    FROM universe_membership m
                    WHERE m.stock_id = ? AND m.universe_code <> 'US_LISTED'
                      AND EXISTS (SELECT 1 FROM unnest(?::date[]) d
                                  WHERE d >= m.effective_from AND (m.effective_to IS NULL OR d < m.effective_to))
                    ORDER BY 1
                    """, String.class, id, dates.stream().map(LocalDate::toString).toArray(String[]::new));
            String ticker = jdbc.queryForObject("SELECT ticker FROM stock WHERE id = ?", String.class, id);
            out.add(new Target(id, ticker, dates, ledger, overlaps));
        }
        return out;
    }

    /** @param apply 거짓이면 계획만 */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy, boolean apply) {
        List<Target> targets = plan();
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size(),
                BatchExecutionRecorder.DeclaredScope.narrow(BatchExecutionRecorder.ScopeReason.NO_SCHEDULED_RANGE));
        execution.plan(CollectionPhase.PRICE, targets.size());
        int bars = 0;
        int ledgerRows = 0;
        List<String> refused = new ArrayList<>();
        for (Target t : targets) {
            log.info("시세 경계 대상 {}({}) 봉 {}({}~{}) · 경계 앞 원장 {} · 겹치는 편입 {}", t.ticker(), t.stockId(), t.dates().size(),
                    t.dates().isEmpty() ? "-" : t.dates().get(0), t.dates().isEmpty() ? "-" : t.dates().get(t.dates().size() - 1),
                    t.ledgerRows(), t.overlappingMemberships());
            if (t.refused()) {
                refused.add(t.ticker());
            } else {
                bars += t.dates().size();
                ledgerRows += t.ledgerRows().size();
            }
        }
        String what = "행 %d · 지울 봉 %d · 경계 앞 원장 %d · 거절(편입 구간과 겹침) %s".formatted(targets.size() - refused.size(), bars,
                ledgerRows, refused);
        if (!apply) {
            execution.countExternally(CollectionPhase.PRICE, targets.size(), 0, 0);
            return execution.finish("계획만(--apply=false) · " + what);
        }
        Path csv = export(targets, execution.executionId());
        int applied = 0;
        int failed = 0;
        for (Target t : targets) {
            if (t.refused()) {
                continue;
            }
            try {
                transaction.executeWithoutResult(status -> {
                    if (!t.dates().isEmpty()) {
                        jdbc.update("DELETE FROM price_daily WHERE stock_id = ? AND trade_date = ANY (?::date[])", t.stockId(),
                                (Object) t.dates().stream().map(LocalDate::toString).toArray(String[]::new));
                    }
                    for (long ca : t.ledgerRows()) {
                        jdbc.update("""
                                UPDATE corporate_action SET status = 'REJECTED', updated_at = now(),
                                       note = coalesce(note || ' · ', '') || '시세 경계 앞 — 같은 기호의 다른 증권 사건이다(ADR-0181)'
                                WHERE id = ?""", ca);
                    }
                });
                applied++;
            } catch (RuntimeException e) {
                // 행 하나의 실패가 다른 행을 막지 않는다 — 그 행은 트랜잭션째 되돌아갔다. 조용히 넘기지 않는다.
                failed++;
                log.error("시세 경계 교정 실패 — 그 행을 되돌렸다. {}({})", t.ticker(), t.stockId(), e);
            }
        }
        execution.countExternally(CollectionPhase.PRICE, applied, failed, applied);
        return execution.finish("반영 %d · 실패 %d · %s · 내보낸 CSV %s".formatted(applied, failed, what, csv));
    }

    private Path export(List<Target> targets, long executionId) {
        try {
            Files.createDirectories(exportDir);
            Path file = exportDir.resolve("price-boundary-%d-%s.csv".formatted(executionId,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))));
            List<String> lines = new ArrayList<>();
            lines.add("stock_id,ticker,trade_date,open,high,low,close,adj_close,adj_factor,volume,trade_value,listed_shares");
            for (Target t : targets) {
                if (t.refused() || t.dates().isEmpty()) {
                    continue;
                }
                for (Map<String, Object> r : jdbc.queryForList("""
                        SELECT trade_date, open, high, low, close, adj_close, adj_factor, volume, trade_value, listed_shares
                        FROM price_daily WHERE stock_id = ? AND trade_date = ANY (?::date[]) ORDER BY trade_date
                        """, t.stockId(), (Object) t.dates().stream().map(LocalDate::toString).toArray(String[]::new))) {
                    lines.add("%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s".formatted(t.stockId(), t.ticker(), r.get("trade_date"), r.get("open"),
                            r.get("high"), r.get("low"), r.get("close"), r.get("adj_close"), r.get("adj_factor"), r.get("volume"),
                            r.get("trade_value") == null ? "" : r.get("trade_value"),
                            r.get("listed_shares") == null ? "" : r.get("listed_shares")));
                }
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
            log.info("시세 경계 — 지울 행 {}줄을 내보냈다: {}", lines.size() - 1, file);
            return file;
        } catch (IOException e) {
            // 내보내기가 실패하면 지우지 않는다 — 되돌리기 재료 없이 원자료를 지우지 않는다.
            throw new UncheckedIOException("지울 시세를 내보내지 못해 교정을 멈춘다: " + exportDir, e);
        }
    }
}
