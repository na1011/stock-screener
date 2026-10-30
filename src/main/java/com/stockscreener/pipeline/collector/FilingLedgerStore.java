package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.FilingListSource.ListedDay;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedFiling;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedRelease;
import com.stockscreener.pipeline.datasource.FilingListSource.ListedStock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 원장 공시 목록의 커서({@code filing_listing_day})와 소비 장부({@code filing_consumption})를 쓴다(ADR-0176 · V67).
 *
 * <p>MUST: 하루치 장부 행과 그날을 닫는 행은 한 트랜잭션이다({@link #recordDay}) — 따로 쓰면 장부 행 없이 닫힌 날이 생겨 그 공시가
 * 영영 안 들어온다. 장부 행은 {@code (시장, 접수번호, 종목)}이 같으면 다시 넣지 않는다 — 국내의 「오늘」은 닫지 않고 다음 실행이
 * 다시 받으므로 같은 줄이 두 번 온다.
 */
@Component
public class FilingLedgerStore {

    private static final Logger log = LoggerFactory.getLogger(FilingLedgerStore.class);

    private final JdbcTemplate jdbc;
    private final EarningsReleaseStore releases;

    public FilingLedgerStore(JdbcTemplate jdbc, EarningsReleaseStore releases) {
        this.jdbc = jdbc;
        this.releases = releases;
    }

    /** DB 시각 — 시도 시각({@code last_attempt_at = now()})과 같은 시계로 「이번 실행 뒤」를 가른다. */
    public Instant now() {
        return jdbc.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();
    }

    /** 그 시장에서 닫은 마지막 날. 한 번도 안 닫았으면 비어 있다. */
    public Optional<LocalDate> cursor(String market) {
        LocalDate last = jdbc.queryForObject(
                "SELECT max(listing_date) FROM filing_listing_day WHERE market = ?", LocalDate.class, market);
        return Optional.ofNullable(last);
    }

    /**
     * 장부 행을 넣고 그날을 닫는다. 넣은(새) 장부 행 수를 낸다.
     *
     * <p>{@code kept_rows}는 그날 장부에 붙은 <b>서로 다른 공시 수</b>다 — 원본 줄({@code source_rows})과 같은 단위라야
     * {@code kept_rows <= source_rows}(V67 제약)가 선다. 종목 행 수로 세면 CIK 하나에 행이 여럿인 발행사(복수 클래스 · 등록인 시대)의
     * 공시가 줄보다 많이 세져 제약을 넘고, 그날을 못 닫아 <b>두 시장의 커서가 함께 멈춘다</b>(2026-10-19 미국 90줄 → 92행 ·
     * 실행 869·887). V67 컬럼 주석의 「종목마다 센다」는 그 제약과 처음부터 모순이었다.
     */
    @Transactional(rollbackFor = Exception.class)
    public int recordDay(String market, ListedDay day) {
        Inserted inserted = insert(market, day.filings());
        // 실적 발표 줄도 같은 트랜잭션이다(ADR-0195) — 닫힌 날은 그날의 발표 이력도 닫혔다는 뜻이라, 따로 쓰면 발표 없이 닫힌 날이 생긴다.
        releases.insert(market, day.releases());
        jdbc.update("""
                INSERT INTO filing_listing_day (market, listing_date, outcome, source_rows, kept_rows)
                VALUES (?, ?, ?, ?, ?)
                """, market, Date.valueOf(day.day()), day.outcome().name(), day.sourceRows(), inserted.keptFilings());
        return inserted.rows();
    }

    /** 장부 행만 넣는다 — 닫지 않는 날(국내의 오늘)이 쓴다. 넣은(새) 장부 행 수를 낸다. */
    @Transactional(rollbackFor = Exception.class)
    public int recordOpen(String market, List<ListedFiling> filings) {
        return recordOpen(market, filings, List.of());
    }

    /** 장부 행과 실적 발표 줄을 넣는다 — 닫지 않는 날(국내의 오늘). 넣은(새) 장부 행 수를 낸다. */
    @Transactional(rollbackFor = Exception.class)
    public int recordOpen(String market, List<ListedFiling> filings, List<ListedRelease> openReleases) {
        int rows = insert(market, filings).rows();
        releases.insert(market, openReleases);
        return rows;
    }

    /** @param rows 넣은(새) 장부 행 · {@code keptFilings} 시대 안 종목 행이 하나라도 붙은(새로 넣었든 이미 있었든) 서로 다른 공시 수 */
    private record Inserted(int rows, int keptFilings) {
    }

    private Inserted insert(String market, List<ListedFiling> filings) {
        int inserted = 0;
        List<String> outsideEra = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (ListedFiling f : filings) {
            // 행의 시대가 그 공시일을 안 덮으면 그 행의 공시가 아니다(ADR-0180) — 등록인 교체로 닫힌 행에 옛 등록인이 시대 끝 뒤에 낸
            // 공시(WKEY 6-K 10-06 · 10-14)나 새 등록인 행의 시대 시작 전 공시다. 붙이면 소비가 영영 못 먹는 열린 행이 된다.
            Boolean covered = jdbc.queryForObject("SELECT " + StockRegistrar.eraCovers("s", "?::date")
                    + " FROM stock s WHERE s.id = ?", Boolean.class, Date.valueOf(f.filedOn()), Date.valueOf(f.filedOn()), f.stockId());
            if (!Boolean.TRUE.equals(covered)) {
                outsideEra.add(f.stockId() + ":" + f.filingNo() + "(" + f.filedOn() + ")");
                continue;
            }
            kept.add(f.filingNo());
            inserted += jdbc.update("""
                    INSERT INTO filing_consumption
                        (market, filing_no, stock_id, cik, corp_code, form, report_name, filed_on, bsns_year, reprt_code,
                         last_reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (market, filing_no, stock_id) DO NOTHING
                    """, market, f.filingNo(), f.stockId(), f.cik(), f.corpCode(), f.form(), f.reportName(),
                    Date.valueOf(f.filedOn()), f.bsnsYear(), f.reprtCode(), f.lastReason());
        }
        if (!outsideEra.isEmpty()) {
            log.info("행의 시대 밖이라 장부에 안 붙인 공시 {}건: {}", outsideEra.size(), outsideEra);
        }
        return new Inserted(inserted, kept.size());
    }

    /**
     * 그 시장의 우리 종목 전부를 티커로. <b>편입 여부는 거르지 않는다</b> — 재편입 종목의 공시를 잃지 않게(ADR-0176). 소비 단계가
     * 소비 시점의 편입으로 고른다.
     */
    public Map<String, ListedStock> stocksByTicker(String market) {
        String where = switch (market) {
            case "US" -> "market_code = 'US'";
            case "KR" -> "market_code IN ('KOSPI', 'KOSDAQ')";
            default -> throw new IllegalArgumentException("장부의 시장이 아니다: " + market);
        };
        Map<String, ListedStock> out = new HashMap<>();
        jdbc.query("SELECT s.id, s.ticker, s.accounting_month, s.sec_cik, " + EarningsReleaseStore.inScope("s.id")
                + " AS release_scope FROM stock s WHERE " + where.replace("market_code", "s.market_code"), rs -> {
            int month = rs.getInt("accounting_month");
            Integer accountingMonth = rs.wasNull() ? null : month;
            out.put(rs.getString("ticker"), new ListedStock(rs.getLong("id"), accountingMonth,
                    rs.getObject("sec_cik", Long.class), rs.getBoolean("release_scope")));
        });
        return out;
    }

    // ── 소비(ADR-0176 2차) ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * 소비 단계가 읽는 열린 장부 행. {@code frOpen}·{@code fsrOpen}은 그 몫이 아직 안 끝났는가(미국은 FSR 몫이 없다).
     *
     * <p>{@code dueFrom}은 소비가 그 행을 맡기 시작한 시각 — 처음 본 시각과 지금 편입이 생긴 시각 중 늦은 쪽이다. 편입 밖에서
     * 잠든 동안은 아무도 먹이지 않으므로, 그 기간을 지연으로 세면 편입되는 날 첫 소비 기회도 없이 경보가 난다.
     */
    public record OpenRow(long id, String market, long stockId, String ticker, String filingNo, Long cik, String form,
                          LocalDate filedOn, Integer bsnsYear, String reprtCode, boolean frOpen, boolean fsrOpen,
                          Instant firstSeenAt, Instant dueFrom, String lastReason) {
    }

    private static final String OPEN_COLUMNS = """
            c.id, c.market, c.stock_id, s.ticker, c.filing_no, c.cik, c.form, c.filed_on, c.bsns_year, c.reprt_code,
            c.fr_consumed_at IS NULL AS fr_open,
            (c.market = 'KR' AND c.fsr_consumed_at IS NULL) AS fsr_open,
            c.first_seen_at,
            GREATEST(c.first_seen_at, (SELECT max(m.created_at) FROM universe_membership m
                                       JOIN universe u ON u.code = m.universe_code
                                       WHERE m.stock_id = c.stock_id AND m.effective_to IS NULL AND u.is_active)) AS due_from,
            c.last_reason
            """;

    /**
     * 그 시장의 열린 행 중 <b>지금 활성 편입</b>인 종목의 것 — 소비 대상이다. 편입 밖 행은 잠든 채 남는다(재편입하면 다시 잡힌다).
     * 활성 편입의 술어는 {@code StockRegistrar.activeUniverseMemberIds}와 같다.
     */
    public List<OpenRow> openForActiveMembers(String market) {
        return jdbc.query("SELECT " + OPEN_COLUMNS + """
                FROM filing_consumption c JOIN stock s ON s.id = c.stock_id
                WHERE c.market = ?
                  AND (c.fr_consumed_at IS NULL OR (c.market = 'KR' AND c.fsr_consumed_at IS NULL))
                  AND s.is_active
                  AND %s
                  AND %s
                ORDER BY c.stock_id, c.filing_no
                """.formatted(StockRegistrar.activeMembershipExists("c.stock_id"), StockRegistrar.eraCovers("s", "c.filed_on")),
                (rs, i) -> openRow(rs), market);
    }

    /**
     * 미국 <b>순위 후보</b>의 열린 FR 행 중 시도가 {@code maxAttempts} 미만인 것(ADR-0178) — 후보 소비 대상이다. 술어는
     * {@link StockRegistrar#usRankingCandidateIdsByTicker}와 같다(미국 · 활성 종목 · 활성 편입 없음).
     *
     * <p>시도 상한이 있는 이유: 후보 행은 화면 값이 아니라 순위 재료라 지연 경보가 없고, 소스가 같은 답을 주는 실패는 매 평일 다시
     * 물어도 같다 — 상한 없이 두면 그 종목이 날마다 부분 실패 알림을 내 진짜 장애를 묻는다. 새 공시가 오면 새 행(시도 0)이 그
     * 종목을 다시 깨우고, 소비 판정은 그 종목의 열린 행 전부를 다시 본다.
     */
    public List<OpenRow> openForCandidates(int maxAttempts) {
        return jdbc.query("SELECT " + OPEN_COLUMNS + """
                FROM filing_consumption c JOIN stock s ON s.id = c.stock_id
                WHERE c.market = 'US' AND s.market_code = 'US'
                  AND c.fr_consumed_at IS NULL AND c.attempts < ?
                  AND s.is_active
                  AND NOT %s
                  AND %s
                ORDER BY c.stock_id, c.filing_no
                """.formatted(StockRegistrar.activeMembershipExists("c.stock_id"), StockRegistrar.eraCovers("s", "c.filed_on")),
                (rs, i) -> openRow(rs), maxAttempts);
    }

    /** 후보의 열린 FR 행 중 {@code since} 뒤의 시도로 상한에 막 닿은 것 — 그 실행이 한 번 알린다(같은 행은 다시 안 닿는다). */
    public List<OpenRow> candidatesReachingCap(int maxAttempts, Instant since) {
        return jdbc.query("SELECT " + OPEN_COLUMNS + """
                FROM filing_consumption c JOIN stock s ON s.id = c.stock_id
                WHERE c.market = 'US' AND s.market_code = 'US'
                  AND c.fr_consumed_at IS NULL AND c.attempts = ? AND c.last_attempt_at >= ?
                  AND s.is_active
                  AND NOT %s
                  AND %s
                ORDER BY c.stock_id, c.filing_no
                """.formatted(StockRegistrar.activeMembershipExists("c.stock_id"), StockRegistrar.eraCovers("s", "c.filed_on")),
                (rs, i) -> openRow(rs),
                maxAttempts, java.sql.Timestamp.from(since));
    }

    /** 한 종목의 FR 몫이 열린 행 — 소비 훅이 그 종목의 쓰기 트랜잭션 안에서 읽는다. */
    public List<OpenRow> openFrOf(String market, long stockId) {
        return jdbc.query("SELECT " + OPEN_COLUMNS + """
                FROM filing_consumption c JOIN stock s ON s.id = c.stock_id
                WHERE c.market = ? AND c.stock_id = ? AND c.fr_consumed_at IS NULL
                ORDER BY c.filing_no
                """, (rs, i) -> openRow(rs), market, stockId);
    }

    /** 한 종목의 FSR 몫만 열린 행(FR 몫은 끝남 · 국내만). */
    public List<OpenRow> openFsrOf(long stockId) {
        return jdbc.query("SELECT " + OPEN_COLUMNS + """
                FROM filing_consumption c JOIN stock s ON s.id = c.stock_id
                WHERE c.market = 'KR' AND c.stock_id = ? AND c.fr_consumed_at IS NOT NULL AND c.fsr_consumed_at IS NULL
                ORDER BY c.filing_no
                """, (rs, i) -> openRow(rs), stockId);
    }

    /** 보충 표의 (접수번호 → 상태) — 그 CIK의 주어진 접수번호만. 같은 트랜잭션이면 이번 FR이 막 쓴 행도 보인다. */
    public Map<String, String> supplementStatus(long cik, Collection<String> accessions) {
        Map<String, String> out = new HashMap<>();
        if (accessions.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT accession, status FROM sec_filing_supplement WHERE cik = ? AND accession = ANY (?)",
                rs -> {
                    out.put(rs.getString("accession"), rs.getString("status"));
                }, cik, accessions.toArray(String[]::new));
        return out;
    }

    /** FR 몫을 소비됨으로 표시한다. 이미 끝난 행이면 아무것도 안 바꾸고 0을 낸다. */
    public int markFrConsumed(long id, String ground, String evidence, long executionId) {
        return jdbc.update("""
                UPDATE filing_consumption
                SET fr_consumed_at = now(), fr_ground = ?, fr_evidence = ?, consumed_by_execution = ?,
                    attempts = attempts + 1, last_attempt_at = now(), last_reason = NULL
                WHERE id = ? AND fr_consumed_at IS NULL
                """, ground, evidence, executionId, id);
    }

    /** FSR 몫을 소비됨으로 표시한다(국내 · FR 몫이 끝난 행만). */
    public int markFsrConsumed(long id, String evidence, long executionId) {
        return jdbc.update("""
                UPDATE filing_consumption
                SET fsr_consumed_at = now(), fsr_evidence = ?, fsr_by_execution = ?,
                    attempts = attempts + 1, last_attempt_at = now(), last_reason = NULL
                WHERE id = ? AND market = 'KR' AND fr_consumed_at IS NOT NULL AND fsr_consumed_at IS NULL
                """, evidence, executionId, id);
    }

    /**
     * 소비하지 못한 시도를 남긴다 — 행은 열린 채이고 사유만 바뀐다.
     *
     * <p>{@code attempts}는 <b>시도한 날(KST) 수</b>다 — 같은 날 두 번째 시도는 사유와 시각만 바꾼다. 소비가 평일 13:30 앞과 14:16에
     * 두 번 돌고, 후보 상한({@code FilingConsumptionJob.CANDIDATE_MAX_ATTEMPTS})이 「편입 지연 경보와 같은 평일 수」라서다.
     */
    public int recordAttempt(long id, String reason) {
        return jdbc.update("""
                UPDATE filing_consumption
                SET attempts = attempts + CASE
                        WHEN last_attempt_at IS NULL
                          OR (last_attempt_at AT TIME ZONE 'Asia/Seoul')::date < (now() AT TIME ZONE 'Asia/Seoul')::date
                        THEN 1 ELSE 0 END,
                    last_attempt_at = now(), last_reason = ?
                WHERE id = ?
                """, reason, id);
    }

    private static OpenRow openRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        long cik = rs.getLong("cik");
        Long cikOrNull = rs.wasNull() ? null : cik;
        int year = rs.getInt("bsns_year");
        Integer yearOrNull = rs.wasNull() ? null : year;
        return new OpenRow(rs.getLong("id"), rs.getString("market"), rs.getLong("stock_id"), rs.getString("ticker"),
                rs.getString("filing_no"), cikOrNull, rs.getString("form"), rs.getDate("filed_on").toLocalDate(),
                yearOrNull, rs.getString("reprt_code"), rs.getBoolean("fr_open"), rs.getBoolean("fsr_open"),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("due_from").toInstant(),
                rs.getString("last_reason"));
    }
}
