package com.stockscreener.stock;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorDataReader;
import com.stockscreener.indicator.IndicatorDataReader.KnownFigures;
import com.stockscreener.indicator.calculator.ReportingBasis;
import com.stockscreener.pipeline.batch.DailyUpdateJob;
import com.stockscreener.pipeline.batch.IndicatorBackfillJob;
import com.stockscreener.pipeline.collector.EarningsReleaseStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 종목 · 기준일마다 <b>지표가 어느 기의 재무로 계산됐는가</b>와 <b>그보다 새로운 실적 발표가 있는가</b>를 낸다(ADR-0195). 결과 표 · 종목 상세
 * · 워치리스트 알림이 같은 이 판정을 쓴다 — 세 곳이 따로 유도하면 갈린다.
 *
 * <p><b>기준 기</b>는 지표 계산과 같은 판정이다({@link ReportingBasis} → {@code FinancialMath.seriesOf}). 행은 지표 리더와 같은 술어로 읽고
 * ({@link IndicatorDataReader#readKnownFigures}), 그 기준일 지표를 쓴 실행이 시작한 뒤 생긴 재무 행은 뺀다 — 소비가 새 분기를 넣은 뒤
 * 화해가 아직 안 돈 동안 라벨만 새 분기를 말하지 않게.
 *
 * <p><b>발표 대기(PENDING)</b>: 유량 기준 기의 <b>다음 기말 뒤</b>에 나온 실적 발표가 기준일까지 있다. 다음 기말 앞의 발표(정식 공시 뒤에
 * 나온 같은 분기 보도자료 · 분기 중 전망)는 기준 기의 것이라 안 센다(2026-10-30 드라이런: 미국 표본 120의 대기 구간 312개가 전부 기준이
 * 다음 기로 넘어가며 닫혔다).
 *
 * <p><b>판정 불가(UNTRACKED)</b>는 「발표 없음」으로 말하지 않는 자리다 — 모르는 구간을 없다고 하지 않는다. 사유는 {@link Untracked}.
 */
@Component
public class FinancialBasisReader {

    /**
     * 지표 기준일을 덮는 지표 쓰기 실행 — 그 종목 시장의 일일 갱신(원본층 창 · 최신일)과 화해 ①(원본층 창 전체 · 두 시장).
     *
     * <p>MUST: 시장마다 묻는다. 일일 갱신이 시장별 잡으로 갈려(국내 13:30 {@code daily-update} · 미국 21:00 {@code us-daily-update})
     * 두 이름을 함께 보면 국내 13:30 실행이 미국 종목의 「지표를 쓴 시각」이 된다 — 그 실행은 미국 지표를 안 썼으므로 그 사이 들어온
     * 미국 재무 행이 라벨에 먼저 실린다.
     */
    private static List<String> indicatorWriters(String marketCode) {
        return DailyUpdateJob.Markets.ofMarket(marketCode)
                .map(markets -> List.of(markets.jobName(), IndicatorBackfillJob.ORIGIN_JOB_NAME))
                // 모으지 않는 시장 — 일일 갱신이 그 시장 지표를 쓰지 않으므로 화해 ①만 덮는다.
                .orElse(List.of(IndicatorBackfillJob.ORIGIN_JOB_NAME));
    }
    /** 외국 발행사의 정기 공시 양식 — 실적 발표가 6-K라 항목 구조가 없다(미국 편입 1,000 중 21종목 · 2026-10-30). */
    private static final Set<String> FOREIGN_ISSUER_FORMS = Set.of("20-F", "20-F/A", "40-F", "40-F/A", "6-K", "6-K/A");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M월 d일");

    private final JdbcTemplate jdbc;
    private final IndicatorDataReader indicatorDataReader;

    public FinancialBasisReader(JdbcTemplate jdbc, IndicatorDataReader indicatorDataReader) {
        this.jdbc = jdbc;
        this.indicatorDataReader = indicatorDataReader;
    }

    public enum Status {
        /** 기준 기보다 새로운 실적 발표가 있다 — 지표에 아직 반영 안 됐다. */
        PENDING,
        /** 기준 기보다 새로운 실적 발표가 없다(원장이 덮는 구간에서). */
        NONE,
        /** 모른다 — 사유는 {@link Untracked}. 「없음」이 아니다. */
        UNTRACKED
    }

    public enum Untracked {
        /** 외국 발행사 — 실적 발표가 6-K라 원장이 항목을 구조로 주지 않는다(진짜 한계). */
        FOREIGN_ISSUER,
        /**
         * 국내 비12월 결산 — 저장된 기말이 12월 결산 합성 규약이라({@code StockFinancialsResponse} javadoc) 기 순서와 다음 기말이 실제와
         * 다르다(2026-10-30 실측: 신영증권 3월 결산 FY2026이 2026-01-01~12-31 · 공시 06-11로 저장).
         */
        NON_DECEMBER_FISCAL_YEAR,
        /** 그 구간에 항목을 아직 못 받은 8-K가 있다. */
        UNRESOLVED,
        /** 그 종목의 실적 발표 이력을 아직 훑지 않았다. */
        NOT_SWEPT,
        /** 기준 기의 다음 기말이 감지 시작일보다 앞이라 그 사이 발표를 모른다. */
        BEFORE_COVERAGE,
        /** 기준일까지의 공시 목록을 아직 받지 않았다. */
        NOT_YET_LISTED
    }

    /**
     * @param flowLabel      유량 지표(PER · ROE …)가 쓰는 기 — 「2026 Q2」 · 「2025년」
     * @param balanceLabel   잔액 지표(PBR · 부채비율 …)가 쓰는 기. 유량과 같으면 {@code null}
     * @param releaseFiledOn PENDING이면 기준일까지 가장 최근 발표의 접수일
     * @param notice         PENDING · UNTRACKED의 설명 — 화면이 이 문구를 그대로 쓴다
     */
    public record Basis(String flowLabel, String balanceLabel, Status status, Untracked untracked,
                        LocalDate releaseFiledOn, String notice) {
    }

    /** 재무가 하나도 안 보이는 종목은 맵에 없다. */
    public Map<Long, Basis> read(Collection<Long> stockIds, LocalDate asOf) {
        if (stockIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, StockFacts> facts = stockFacts(stockIds);
        // 시장마다 지표를 쓴 실행이 다르다(indicatorWriters) — 그 시각으로 재무 행 · 실적 발표를 자른다.
        Map<String, List<Long>> idsByMarket = new HashMap<>();
        for (Long stockId : stockIds) {
            StockFacts stock = facts.get(stockId);
            if (stock != null) {
                idsByMarket.computeIfAbsent(stock.marketCode(), k -> new ArrayList<>()).add(stockId);
            }
        }
        Map<Long, KnownFigures> known = new HashMap<>();
        Map<Long, List<LocalDate>> releaseDates = new HashMap<>();
        idsByMarket.forEach((market, ids) -> {
            Instant writtenBy = indicatorsWrittenBy(asOf, indicatorWriters(market));
            known.putAll(indicatorDataReader.readKnownFigures(ids, asOf, writtenBy));
            releaseDates.putAll(releaseDates(ids, asOf, writtenBy));
        });
        Map<String, LocalDate> cursors = listingCursors();
        Map<Long, IndicatorContext> contexts = new HashMap<>();
        Map<Long, ReportingBasis.Basis> bases = new HashMap<>();
        for (Long stockId : stockIds) {
            KnownFigures figures = known.get(stockId);
            if (figures == null || !facts.containsKey(stockId)) {
                continue;
            }
            IndicatorContext context = new IndicatorContext(stockId, List.of(), figures.annual(), figures.quarterly(), Map.of())
                    .withEarningsReleaseDates(releaseDates.getOrDefault(stockId, List.of()));
            ReportingBasis.of(context).ifPresent(basis -> {
                bases.put(stockId, basis);
                contexts.put(stockId, context);
            });
        }
        Map<Long, LocalDate> after = new HashMap<>();
        bases.forEach((stockId, basis) -> after.put(stockId, ReportingBasis.nextPeriodEnd(basis.flow().periodEnd())));
        Map<Long, LocalDate> unresolved = unresolved(after, asOf);
        Map<Long, Basis> out = new HashMap<>();
        bases.forEach((stockId, basis) -> out.put(stockId, judge(facts.get(stockId), basis, known.get(stockId),
                contexts.get(stockId), asOf, cursors, unresolved.get(stockId))));
        return out;
    }

    private Basis judge(StockFacts stock, ReportingBasis.Basis basis, KnownFigures figures, IndicatorContext context,
                        LocalDate asOf, Map<String, LocalDate> cursors, LocalDate unresolved) {
        String flowLabel = label(basis.flow(), basis.quarterly());
        String balanceLabel = basis.point() == null || basis.point().periodEnd().equals(basis.flow().periodEnd())
                ? null : label(basis.point(), basis.point().fiscalPeriod() != FinancialFigures.FiscalPeriod.FY);
        LocalDate flowEnd = basis.flow().periodEnd();
        if (!stock.us() && !Integer.valueOf(12).equals(stock.accountingMonth())) {
            return untracked(flowLabel, balanceLabel, Untracked.NON_DECEMBER_FISCAL_YEAR,
                    "12월 결산이 아니라 저장된 재무 기간이 실제와 달라 실적 발표와 대조하지 않습니다.");
        }
        Set<String> forms = figures.formsByPeriodEnd().getOrDefault(flowEnd, Set.of());
        if (stock.us() && !forms.isEmpty() && FOREIGN_ISSUER_FORMS.containsAll(forms)) {
            return untracked(flowLabel, balanceLabel, Untracked.FOREIGN_ISSUER,
                    "외국 발행사는 실적 발표(6-K)를 원장이 구조로 주지 않아 새 실적 발표를 감지하지 못합니다.");
        }
        // 지표 계산과 같은 판정이다(ReportingBasis.pendingRelease) — 대기면 그 날의 재무 지표는 계산되지 않았다.
        LocalDate pending = ReportingBasis.pendingRelease(context, asOf).orElse(null);
        if (pending != null) {
            return new Basis(flowLabel, balanceLabel, Status.PENDING, null, pending,
                    "%s 새 실적 발표가 있어 이 날의 재무 지표를 계산하지 않았습니다 — 재무 기준이 %s라 정식 공시가 반영되면 다시 계산합니다."
                            .formatted(DAY.format(pending), flowLabel));
        }
        if (unresolved != null) {
            return untracked(flowLabel, balanceLabel, Untracked.UNRESOLVED,
                    "%s 8-K가 실적 발표인지 아직 확인하지 못했습니다.".formatted(DAY.format(unresolved)));
        }
        if (stock.sweptThrough() == null) {
            return untracked(flowLabel, balanceLabel, Untracked.NOT_SWEPT, "이 종목의 실적 발표 이력을 아직 받지 않았습니다.");
        }
        LocalDate nextEnd = ReportingBasis.nextPeriodEnd(flowEnd);
        if (nextEnd.isBefore(EarningsReleaseStore.FIRST_DATE.minusDays(1))) {
            return untracked(flowLabel, balanceLabel, Untracked.BEFORE_COVERAGE,
                    "재무 기준(%s)이 실적 발표 수집 시작보다 오래돼 그 뒤 발표와 대조하지 못합니다.".formatted(flowLabel));
        }
        LocalDate cursor = cursors.get(stock.us() ? "US" : "KR");
        LocalDate covered = cursor == null || cursor.isBefore(stock.sweptThrough()) ? stock.sweptThrough() : cursor;
        if (asOf.isAfter(covered)) {
            return untracked(flowLabel, balanceLabel, Untracked.NOT_YET_LISTED,
                    "%s까지의 공시 목록을 아직 받지 않아 새 실적 발표 여부를 모릅니다.".formatted(DAY.format(asOf)));
        }
        return new Basis(flowLabel, balanceLabel, Status.NONE, null, null, null);
    }

    private static Basis untracked(String flowLabel, String balanceLabel, Untracked reason, String notice) {
        return new Basis(flowLabel, balanceLabel, Status.UNTRACKED, reason, null, notice);
    }

    /** 화면 라벨 — 연간은 「2025년」, 분기는 「2026 Q2」({@code FinancialsPanel}의 표기와 같다). */
    static String label(FinancialFigures row, boolean quarterly) {
        return quarterly && row.fiscalPeriod() != FinancialFigures.FiscalPeriod.FY
                ? "%d %s".formatted(row.fiscalYear(), row.fiscalPeriod().name())
                : "%d년".formatted(row.fiscalYear());
    }

    /**
     * 종목마다 실적 발표 공개일(지표 리더와 같은 술어 · {@code EarningsReleaseStore.IS_EARNINGS_RELEASE}). 지표를 쓴 실행이 시작한 뒤에
     * 알게 된 발표는 뺀다 — 그 지표는 아직 그 발표로 막히지 않았다.
     */
    private Map<Long, List<LocalDate>> releaseDates(Collection<Long> stockIds, LocalDate asOf, Instant writtenBy) {
        Map<Long, List<LocalDate>> out = new HashMap<>();
        Timestamp bound = writtenBy == null ? null : Timestamp.from(writtenBy);
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT r.stock_id, r.effective_on FROM earnings_release r
                    WHERE r.stock_id = ANY(?) AND r.effective_on <= ? AND """ + EarningsReleaseStore.IS_EARNINGS_RELEASE + """
                      AND (CAST(? AS timestamptz) IS NULL OR coalesce(r.resolved_at, r.first_seen_at) <= CAST(? AS timestamptz))
                    """);
            ps.setArray(1, con.createArrayOf("bigint", stockIds.toArray()));
            ps.setObject(2, asOf);
            ps.setTimestamp(3, bound);
            ps.setTimestamp(4, bound);
            return ps;
        }, rs -> {
            out.computeIfAbsent(rs.getLong("stock_id"), k -> new ArrayList<>()).add(rs.getObject("effective_on", LocalDate.class));
        });
        return out;
    }

    /** 종목마다 다음 기말 뒤 · 기준일까지 항목을 아직 모르는 8-K 중 가장 이른 접수일 — 결과 표가 최대 2,000행이라 한 질의다. */
    private Map<Long, LocalDate> unresolved(Map<Long, LocalDate> after, LocalDate asOf) {
        Map<Long, LocalDate> out = new HashMap<>();
        if (after.isEmpty()) {
            return out;
        }
        List<Long> ids = List.copyOf(after.keySet());
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT w.stock_id, min(r.filed_on) AS unresolved
                    FROM unnest(?::bigint[], ?::date[]) AS w(stock_id, after_day)
                    JOIN earnings_release r ON r.stock_id = w.stock_id AND r.filed_on > w.after_day AND r.filed_on <= ?
                    WHERE r.market = 'US' AND r.items IS NULL
                    GROUP BY w.stock_id
                    """);
            ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
            ps.setArray(2, con.createArrayOf("date", ids.stream().map(after::get).map(java.sql.Date::valueOf).toArray()));
            ps.setObject(3, asOf);
            return ps;
        }, rs -> {
            out.put(rs.getLong("stock_id"), rs.getObject("unresolved", LocalDate.class));
        });
        return out;
    }

    private record StockFacts(long stockId, String marketCode, Integer accountingMonth, LocalDate sweptThrough) {

        boolean us() {
            return "US".equals(marketCode);
        }
    }

    private Map<Long, StockFacts> stockFacts(Collection<Long> stockIds) {
        Map<Long, StockFacts> out = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT s.id, s.market_code, s.accounting_month, w.swept_through
                    FROM stock s LEFT JOIN earnings_release_sweep w ON w.stock_id = s.id
                    WHERE s.id = ANY(?)
                    """);
            ps.setArray(1, con.createArrayOf("bigint", stockIds.toArray()));
            return ps;
        }, rs -> {
            int month = rs.getInt("accounting_month");
            Integer accountingMonth = rs.wasNull() ? null : month;
            out.put(rs.getLong("id"), new StockFacts(rs.getLong("id"), rs.getString("market_code"),
                    accountingMonth, rs.getObject("swept_through", LocalDate.class)));
        });
        return out;
    }

    /** 시장별 공시 목록 커서(닫은 마지막 날) — 그날까지의 실적 발표 줄이 들어와 있다. */
    private Map<String, LocalDate> listingCursors() {
        Map<String, LocalDate> out = new HashMap<>();
        jdbc.query("SELECT market, max(listing_date) AS last FROM filing_listing_day GROUP BY market",
                rs -> {
                    out.put(rs.getString("market"), rs.getObject("last", LocalDate.class));
                });
        return out;
    }

    /**
     * 그 기준일 지표를 쓴 마지막 정기 범위 실행의 시작 시각. 그 뒤 생긴 재무 행은 그 지표가 못 봤다. 덮는 실행이 없으면 {@code null}
     * (빼지 않는다).
     *
     * @param writers 그 종목 시장의 지표 쓰기 잡({@link #indicatorWriters})
     */
    private Instant indicatorsWrittenBy(LocalDate asOf, List<String> writers) {
        Timestamp started = jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT max(started_at) FROM batch_execution
                    WHERE job_name = ANY(?) AND target_date >= ? AND status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                      AND (scope IS NULL OR scope = 'FULL')
                    """);
            ps.setArray(1, con.createArrayOf("varchar", writers.toArray()));
            ps.setObject(2, asOf);
            return ps;
        }, rs -> rs.next() ? rs.getTimestamp(1) : null);
        return started == null ? null : started.toInstant();
    }
}
