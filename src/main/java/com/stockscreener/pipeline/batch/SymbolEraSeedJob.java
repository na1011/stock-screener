package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.us.AlpacaCorporateActionAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 손 잡 {@code symbol-era-seed}(ADR-0181) — 같은 증권의 개명을 닻이 못 이은 미국 행에 옛 기호 시대({@code stock_symbol_era})를 심는다.
 *
 * <p>닻(ADR-0180)은 대개 개명 앞 이력을 함께 준다(HLX@08-28 = HOS). 그런데 소스의 기호 대응이 개명을 안 잇는 행이 있다 — SPHR ← MSGE
 * (2023-04-21 · CUSIP 55826T102 그대로 · 같은 날 MSGE 문자열을 분사된 회사가 받았다) · UHAL.B ← UHALB(2022-12-19). 그 행은 개명 앞 봉이
 * 0이고(SPHR 758 · UHAL.B 26), {@code TRADING_DAYS_750}과 1,261봉 코드가 그 구간을 못 읽는다. 시대를 심으면 시세 어댑터가 그 기호를 시대
 * 끝 전날 asof로 그 구간만 받는다({@code AlpacaPriceAdapter}).
 *
 * <p>대상: 소스 개명 중 옛·새 CUSIP이 같고 옛 기호가 when-issued가 아니며({@link AlpacaCorporateActionAdapter#fetchSameSecurityRenames}),
 * 새 기호가 그 행의 닻 기호이고, 그 행에 봉이 있으며 첫 봉이 개명일 이후(−3일)인 것. 장외 선행(ASHGY → SUNB)은 CUSIP이 달라 대상이 아니다 —
 * SIP 봉도 0이다(진짜 한계).
 *
 * <p><b>기본은 계획만 낸다</b>({@code --apply=false}). 운영 쓰기라 사용자 승인 뒤 {@code --apply=true}. 심은 뒤 옛 봉은
 * {@code initial-load --tickers=<대상> --reload-existing=true}가 받는다 — 일일 갱신(15일 창)과 구멍 수집(첫 봉 뒤)은 그 앞을 안 묻는다.
 */
@Component
public class SymbolEraSeedJob {

    public static final String JOB_NAME = "symbol-era-seed";

    /** 개명을 찾는 하한 — 미국 초기 적재의 시세 창(7년) 시작보다 앞. */
    static final LocalDate RENAMES_FROM = LocalDate.of(2019, 1, 1);

    /** 개명일과 첫 봉의 허용 어긋남 — 소스 처리일이 거래일과 하루 이틀 갈린다. */
    static final int SLACK_DAYS = 3;

    private static final Logger log = LoggerFactory.getLogger(SymbolEraSeedJob.class);

    private final ObjectProvider<AlpacaCorporateActionAdapter> adapter;
    private final BatchExecutions batchExecutions;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public SymbolEraSeedJob(ObjectProvider<AlpacaCorporateActionAdapter> adapter, BatchExecutions batchExecutions, JdbcTemplate jdbc,
                            PlatformTransactionManager transactionManager) {
        this.adapter = adapter;
        this.batchExecutions = batchExecutions;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 심을 시대 하나. */
    public record Target(long stockId, String ticker, String oldSymbol, LocalDate validTo, LocalDate firstBar) {
    }

    /** 대상 — 개명 목록을 행에 붙인다(쓰지 않는다). */
    public List<Target> plan(List<IdentitySource.SymbolChange> renames) {
        List<Target> out = new ArrayList<>();
        for (IdentitySource.SymbolChange rename : renames) {
            for (Map<String, Object> row : jdbc.queryForList("""
                    SELECT s.id, s.ticker, (SELECT min(p.trade_date) FROM price_daily p WHERE p.stock_id = s.id) AS first_bar
                    FROM stock s
                    WHERE s.market_code = 'US' AND s.alpaca_anchor_symbol = ? AND s.alpaca_anchor_date >= ?
                      AND NOT EXISTS (SELECT 1 FROM stock_symbol_era e WHERE e.stock_id = s.id AND e.symbol = ?)
                    """, rename.to(), rename.date(), rename.from())) {
                LocalDate firstBar = row.get("first_bar") == null ? null : ((java.sql.Date) row.get("first_bar")).toLocalDate();
                if (firstBar == null) {
                    // 봉이 없는 행(순위 후보 목록 행)은 대상이 아니다 — 메울 이력이 없고, 그 이력을 처음 받을 때 이 잡을 다시 돌리면 된다.
                    // 실측(2026-10-19 운영): 같은 CUSIP 개명이 닻에 걸린 862행 중 858이 봉 0이었다.
                    continue;
                }
                if (firstBar.isBefore(rename.date().minusDays(SLACK_DAYS))) {
                    continue; // 닻이 개명 앞 이력을 이미 준다 — 심을 것이 없다
                }
                out.add(new Target(((Number) row.get("id")).longValue(), (String) row.get("ticker"), rename.from(), rename.date(), firstBar));
            }
        }
        return out;
    }

    /** @param apply 거짓이면 계획만 */
    public BatchStatus run(LocalDate asOf, BatchExecutionRecorder.TriggeredBy triggeredBy, boolean apply) {
        AlpacaCorporateActionAdapter source = adapter.getIfAvailable();
        List<IdentitySource.SymbolChange> renames;
        try {
            if (source == null) {
                throw new DataSourceException(DataSourceException.Kind.AUTH, "alpaca:corporate-action", "기업 행위 소스(Alpaca 키)가 없다.");
            }
            renames = source.fetchSameSecurityRenames(RENAMES_FROM, asOf);
        } catch (DataSourceException e) {
            batchExecutions.start(JOB_NAME, asOf, triggeredBy, 0,
                    BatchExecutionRecorder.DeclaredScope.narrow(BatchExecutionRecorder.ScopeReason.NO_SCHEDULED_RANGE)).failFatally(e);
            return BatchStatus.FAILED;
        }
        List<Target> targets = plan(renames);
        BatchExecutionContext execution = batchExecutions.start(JOB_NAME, asOf, triggeredBy, targets.size(),
                BatchExecutionRecorder.DeclaredScope.narrow(BatchExecutionRecorder.ScopeReason.NO_SCHEDULED_RANGE));
        execution.plan(CollectionPhase.SECURITY_MASTER, targets.size());
        targets.forEach(t -> log.info("기호 시대 심기 대상 {}({}) ← {} ~{} · 첫 봉 {}", t.ticker(), t.stockId(), t.oldSymbol(), t.validTo(),
                t.firstBar()));
        String what = "같은 증권 개명 %d · 대상 행 %d %s".formatted(renames.size(), targets.size(),
                targets.stream().map(t -> t.ticker() + "←" + t.oldSymbol()).toList());
        if (!apply) {
            execution.countExternally(CollectionPhase.SECURITY_MASTER, targets.size(), 0, 0);
            return execution.finish("계획만(--apply=false) · " + what);
        }
        transaction.executeWithoutResult(status -> targets.forEach(t -> jdbc.update("""
                INSERT INTO stock_symbol_era (stock_id, symbol, valid_from, valid_to) VALUES (?, ?, NULL, ?)
                ON CONFLICT DO NOTHING""", t.stockId(), t.oldSymbol(), t.validTo())));
        execution.countExternally(CollectionPhase.SECURITY_MASTER, targets.size(), 0, targets.size());
        return execution.finish("반영 · " + what + " — 옛 봉은 initial-load --tickers=<대상> --reload-existing=true가 받는다");
    }
}
