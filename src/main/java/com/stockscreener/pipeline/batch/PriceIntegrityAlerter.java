package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.PriceObservationWindow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 미국 시세 원장의 무결성 감시(ADR-0181) — 평일 15:30 · 읽기만 · 추가 콜 0 · 운영자 알림.
 *
 * <p>쓰기 술어({@link PriceObservationWindow})는 「이미 아는 경계」를 지킨다. 이 감시는 <b>모르는 새 꼴</b>과 <b>술어를 우회한 쓰기</b>를 본다:
 * <ul>
 *   <li>ⓐ 관측 구간 밖 저장 봉 — 덤프 복원 · 손 SQL이 술어를 우회했다(경계 앞 원장 행이 REJECTED가 아닌 것도).</li>
 *   <li>ⓑ 머리 평봉 — 첫 실거래 앞 거래량 0 · 시고저종 같은 봉(쓰기 술어가 막으므로 0이어야 한다).</li>
 *   <li>ⓒ 이웃 봉 간격 &gt; 5 시장일 — 같은 기호 문자열의 다른 증권이 공백을 사이에 두고 붙은 꼴(CAI · FLY · NIQ · PPLC).</li>
 *   <li>ⓓ 5봉 이상 중간 평봉 구간이 있는데 그 수가 거래 드문 클래스주 성질 밖 — 평봉 다리로 붙은 꼴(VG · SAIL · CART · LB).</li>
 *   <li>ⓔ 두 봉 다 실거래인 ×2 / ×0.5 밖 쌍 중 원장이 ±5봉 안에 없고 표지 주식수가 그 배수를 상쇄하는 것 — 소스가 빠뜨린 원장 사건(UHAL 꼴).</li>
 * </ul>
 * 면제는 {@code price-boundary/exempt.tsv}(근거 필수 · 키 (검사, 표지, 구간 시작일))다 — 같은 증권의 무거래일(SPAC 시절)처럼 판정이 끝난 것.
 *
 * <p>모집단은 보존 창 편입(현 · 과거) 미국 행이다 — 값이 화면에 닿는 행. 같은 (검사, 행)은 7일에 한 번 보낸다({@link UniverseIntegrityAlerter}와
 * 같은 교환 · 메모리 억제).
 *
 * <p>사정거리: 소스가 한 줄로 잇는 새 재사용이 간격·평봉·큰 배수 없이 이어지면(CR ×0.975 · BAM ×1.036) 다섯 다 못 본다 — 새로 생기는 행은
 * 등록인 시대({@code era_from})가 그 앞 봉을 막는다. ⓔ는 표지 주식수가 다음 공시에 실려야 보이므로 그만큼 늦다.
 */
@Component
public class PriceIntegrityAlerter {

    static final String EXEMPT_FILE = "price-boundary/exempt.tsv";
    private static final Duration REPEAT_AFTER = Duration.ofDays(7);
    private static final int ROWS_PER_CHECK = 15;

    /** 클래스주 성질의 문턱 — 5봉 이상 중간 평봉 구간 수(접합 0~7 · 클래스주 56~98 · 2026-10-05 실측). */
    static final int CLASS_SHARE_FLAT_RUNS = 30;

    private static final Logger log = LoggerFactory.getLogger(PriceIntegrityAlerter.class);

    private final JdbcTemplate jdbc;
    private final PriceObservationWindow window;
    private final AlertPort alertPort;
    private final Clock clock;
    private final Set<String> exempt;
    private final Map<String, Instant> lastAlertedAt = new ConcurrentHashMap<>();

    @Autowired
    public PriceIntegrityAlerter(JdbcTemplate jdbc, PriceObservationWindow window, AlertPort alertPort) {
        this(jdbc, window, alertPort, Clock.systemUTC(), loadExempt(EXEMPT_FILE));
    }

    PriceIntegrityAlerter(JdbcTemplate jdbc, PriceObservationWindow window, AlertPort alertPort, Clock clock, Set<String> exempt) {
        this.jdbc = jdbc;
        this.window = window;
        this.alertPort = alertPort;
        this.clock = clock;
        this.exempt = Set.copyOf(exempt);
    }

    /** 검사 하나의 적중 — 표지와 구간 시작일(면제 키)과 설명. */
    public record Hit(String check, long stockId, String ticker, LocalDate start, String detail) {
        String exemptKey() {
            return check + "\t" + ticker + "\t" + start;
        }
    }

    /** 지금 깨진 것 — 면제를 뺀다. */
    public List<Hit> evaluate() {
        List<Long> population = jdbc.queryForList("""
                SELECT DISTINCT s.id FROM universe_membership m JOIN stock s ON s.id = m.stock_id
                JOIN universe u ON u.code = m.universe_code AND u.is_active
                WHERE s.market_code = 'US' AND m.universe_code <> 'US_LISTED'
                  AND (m.effective_to IS NULL
                       OR m.effective_to > (SELECT min(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = 'US'))
                ORDER BY 1
                """, Long.class);
        List<Hit> hits = new ArrayList<>();
        Map<Long, String> tickers = new LinkedHashMap<>();
        for (long id : population) {
            tickers.put(id, jdbc.queryForObject("SELECT ticker FROM stock WHERE id = ?", String.class, id));
        }
        for (long id : population) {
            String ticker = tickers.get(id);
            List<LocalDate> outside = window.storedOutside(id);
            if (!outside.isEmpty()) {
                hits.add(new Hit("OUTSIDE_WINDOW", id, ticker, outside.get(0), "관측 구간 밖 봉 %d(%s~%s) — price-boundary로 걷는다"
                        .formatted(outside.size(), outside.get(0), outside.get(outside.size() - 1))));
            }
            PriceObservationWindow.Window w = window.of(id);
            if (w.floor() != null) {
                Integer ledger = jdbc.queryForObject("""
                        SELECT count(*) FROM corporate_action WHERE stock_id = ? AND effective_date < ? AND status <> 'REJECTED'
                        """, Integer.class, id, w.floor());
                if (ledger != null && ledger > 0) {
                    hits.add(new Hit("OUTSIDE_WINDOW", id, ticker, w.floor(), "경계 앞 원장 행 %d(REJECTED 아님)".formatted(ledger)));
                }
            }
        }
        hits.addAll(gaps(tickers));
        hits.addAll(lonelyFlatRuns(tickers));
        hits.addAll(offsetJumps(tickers));
        return hits.stream().filter(h -> !exempt.contains(h.exemptKey())).toList();
    }

    /**
     * ⓒ 이웃 봉 간격 &gt; 5 시장일 — 달력은 ADR-0167과 같다(지수 봉이 있는 날 ∪ 미국 봉이 현 편입의 절반 이상인 날 · {@code priceGapRuns}).
     * 5는 원장의 근접중복 창(같은 사건의 날짜가 어긋날 수 있는 폭)과 같은 값이다.
     */
    private List<Hit> gaps(Map<Long, String> tickers) {
        if (tickers.isEmpty()) {
            return List.of();
        }
        List<Hit> out = new ArrayList<>();
        jdbc.query("""
                WITH n AS (
                    SELECT count(DISTINCT m.stock_id) AS n FROM universe_membership m JOIN stock s ON s.id = m.stock_id
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE s.market_code = 'US' AND m.universe_code <> 'US_LISTED' AND m.effective_to IS NULL),
                cal AS (
                    SELECT trade_date, row_number() OVER (ORDER BY trade_date) k FROM (
                        SELECT trade_date FROM market_index_daily WHERE index_code = 'US'
                        UNION
                        SELECT p.trade_date FROM price_daily p JOIN stock s ON s.id = p.stock_id WHERE s.market_code = 'US'
                        GROUP BY p.trade_date HAVING count(*) * 2 >= (SELECT n FROM n)) d),
                b AS (
                    SELECT p.stock_id, p.trade_date, c.k, lag(p.trade_date) OVER w pd, lag(c.k) OVER w pk
                    FROM price_daily p JOIN cal c ON c.trade_date = p.trade_date
                    WHERE p.stock_id = ANY (?) WINDOW w AS (PARTITION BY p.stock_id ORDER BY p.trade_date))
                SELECT stock_id, pd, trade_date, k - pk - 1 AS missing FROM b WHERE pk IS NOT NULL AND k - pk - 1 > 5
                """, rs -> {
            long id = rs.getLong("stock_id");
            out.add(new Hit("GAP", id, tickers.get(id), rs.getObject("pd", LocalDate.class),
                    "봉 공백 %d시장일(%s → %s)".formatted(rs.getInt("missing"), rs.getObject("pd", LocalDate.class),
                            rs.getObject("trade_date", LocalDate.class))));
        }, (Object) tickers.keySet().toArray(Long[]::new));
        return out;
    }

    /** ⓓ 5봉 이상 중간 평봉 구간 — 그 행의 그런 구간 수가 클래스주 성질({@value #CLASS_SHARE_FLAT_RUNS}) 밖이면 하나하나. */
    private List<Hit> lonelyFlatRuns(Map<Long, String> tickers) {
        if (tickers.isEmpty()) {
            return List.of();
        }
        List<Hit> out = new ArrayList<>();
        jdbc.query("""
                WITH b AS (
                    SELECT p.stock_id, p.trade_date,
                           (p.volume = 0 AND p.open = p.high AND p.high = p.low AND p.low = p.close) AS flat,
                           row_number() OVER (PARTITION BY p.stock_id ORDER BY p.trade_date) rn
                    FROM price_daily p WHERE p.stock_id = ANY (?)),
                ft AS (SELECT stock_id, min(trade_date) d FROM b WHERE NOT flat GROUP BY stock_id),
                runs AS (
                    SELECT b.stock_id, min(b.trade_date) s, max(b.trade_date) e, count(*) n
                    FROM (SELECT b.*, b.rn - row_number() OVER (PARTITION BY b.stock_id ORDER BY b.trade_date) g FROM b WHERE b.flat) b
                    JOIN ft ON ft.stock_id = b.stock_id AND b.trade_date > ft.d
                    GROUP BY b.stock_id, b.g HAVING count(*) >= 5),
                counted AS (SELECT r.*, count(*) OVER (PARTITION BY r.stock_id) runs FROM runs r)
                SELECT stock_id, s, e, n FROM counted WHERE runs < ?
                """, rs -> {
            long id = rs.getLong("stock_id");
            out.add(new Hit("FLAT_RUN", id, tickers.get(id), rs.getObject("s", LocalDate.class),
                    "중간 평봉 %d봉(%s~%s)".formatted(rs.getInt("n"), rs.getObject("s", LocalDate.class), rs.getObject("e", LocalDate.class))));
        }, (Object) tickers.keySet().toArray(Long[]::new), CLASS_SHARE_FLAT_RUNS);
        return out;
    }

    /**
     * ⓔ 원장 없는 큰 배수 중 표지 주식수가 그 배수를 상쇄하는 쌍. 주식수 배수는 <b>두 표지의 공시일 사이에 원장이 이미 적용한 배수로 나눈 값</b>이다 —
     * 안 나누면 다른 날의 병합(COGT 2020-11-09 1:4 · OVV 2020-01-24 1:5)이 표지 사이에 껴 상쇄로 보인다. 공시일로 자르는 이유는 표지 주식수의
     * 기준일이 분기 말일이 아니라 공시일 무렵이라서다(분기 말일로 자르면 그 둘이 빠졌다 · 2026-10-19 운영 실측). 주식수는 그 기간의 <b>첫 판</b>(원
     * 공시)이다 — 늦은 판은 뒤 분할을 반영한 표지를 실을 수 있다(GME 2022 4:1 뒤 판이 2021-01 쌍을 ×0.25 상쇄로 보이게 했다).
     */
    private List<Hit> offsetJumps(Map<Long, String> tickers) {
        if (tickers.isEmpty()) {
            return List.of();
        }
        List<Hit> out = new ArrayList<>();
        jdbc.query("""
                WITH b AS (
                    SELECT p.stock_id, p.trade_date, p.close, p.volume,
                           lag(p.close) OVER w pc, lag(p.volume) OVER w pv, lag(p.trade_date) OVER w pd,
                           coalesce(lag(p.trade_date, 5) OVER w, first_value(p.trade_date) OVER w) lo,
                           coalesce(lead(p.trade_date, 5) OVER w, p.trade_date) hi
                    FROM price_daily p WHERE p.stock_id = ANY (?) WINDOW w AS (PARTITION BY p.stock_id ORDER BY p.trade_date)),
                j AS (SELECT * FROM b WHERE b.pc > 0 AND b.volume > 0 AND b.pv > 0 AND (b.close / b.pc >= 2 OR b.close / b.pc <= 0.5)
                        AND NOT EXISTS (SELECT 1 FROM corporate_action ca WHERE ca.stock_id = b.stock_id
                                        AND ca.effective_date BETWEEN b.lo AND b.hi)),
                s AS (
                    SELECT j.*,
                      (SELECT f.shares_outstanding FROM financial_statement f WHERE f.stock_id = j.stock_id AND f.shares_outstanding > 0
                         AND f.period_end < j.trade_date ORDER BY f.period_end DESC, f.published_at LIMIT 1) sb,
                      (SELECT f.published_at FROM financial_statement f WHERE f.stock_id = j.stock_id AND f.shares_outstanding > 0
                         AND f.period_end < j.trade_date ORDER BY f.period_end DESC, f.published_at LIMIT 1) db,
                      (SELECT f.shares_outstanding FROM financial_statement f WHERE f.stock_id = j.stock_id AND f.shares_outstanding > 0
                         AND f.period_end >= j.trade_date ORDER BY f.period_end, f.published_at LIMIT 1) sa,
                      (SELECT f.published_at FROM financial_statement f WHERE f.stock_id = j.stock_id AND f.shares_outstanding > 0
                         AND f.period_end >= j.trade_date ORDER BY f.period_end, f.published_at LIMIT 1) da
                    FROM j),
                adj AS (
                    SELECT s.*, (sa / sb) / coalesce((SELECT exp(sum(ln(ca.ratio))) FROM corporate_action ca
                        WHERE ca.stock_id = s.stock_id AND ca.status = 'APPLIED' AND ca.effective_date > s.db AND ca.effective_date <= s.da), 1) f
                    FROM s WHERE sb > 0 AND sa > 0)
                SELECT stock_id, pd, trade_date, round(close / pc, 4) r, round(f, 4) f FROM adj
                WHERE (f > 1.6 OR f < 0.6) AND f * close / pc > 0.5 AND f * close / pc < 2
                """, rs -> {
            long id = rs.getLong("stock_id");
            out.add(new Hit("OFFSET_JUMP", id, tickers.get(id), rs.getObject("trade_date", LocalDate.class),
                    "가격 ×%s를 표지 주식수 ×%s가 상쇄하는데 원장 ±5봉에 사건이 없다".formatted(rs.getBigDecimal("r"), rs.getBigDecimal("f"))));
        }, (Object) tickers.keySet().toArray(Long[]::new));
        return out;
    }

    /** @return 보냈으면 참 */
    public boolean evaluateAndAlert() {
        List<Hit> hits = evaluate();
        Instant now = clock.instant();
        Map<String, List<Hit>> byCheck = new LinkedHashMap<>();
        for (Hit hit : hits) {
            String key = hit.check() + "|" + hit.stockId();
            Instant last = lastAlertedAt.get(key);
            if (last == null || !now.isBefore(last.plus(REPEAT_AFTER))) {
                byCheck.computeIfAbsent(hit.check(), k -> new ArrayList<>()).add(hit);
            }
        }
        log.info("시세 원장 무결성 — 적중 {}(면제 {}건 뺌) · 보낼 것 {}", hits.size(), exempt.size(),
                byCheck.values().stream().mapToInt(List::size).sum());
        if (byCheck.isEmpty()) {
            return false;
        }
        StringBuilder body = new StringBuilder("미국 시세 원장에 다른 증권 봉이나 빠진 원장 사건으로 보이는 꼴이 있다(ADR-0181). 그 행의 장기 가격 지표가"
                + " 다른 증권 값을 읽는다.\n");
        byCheck.forEach((check, list) -> {
            body.append('\n').append(check).append('\n');
            list.stream().limit(ROWS_PER_CHECK).forEach(h -> body.append("  - ").append(h.ticker()).append(' ').append(h.detail()).append('\n'));
            if (list.size() > ROWS_PER_CHECK) {
                body.append("  나머지 %d행은 로그가 답한다.\n".formatted(list.size() - ROWS_PER_CHECK));
            }
            list.forEach(h -> {
                lastAlertedAt.put(h.check() + "|" + h.stockId(), now);
                log.info("시세 원장 무결성 적중 {} {} {}", h.check(), h.ticker(), h.detail());
            });
        });
        body.append("\n닫는 법: 원장(SEC 서류 · 옛 시절 asof 탐침)으로 행마다 판정해 경계는 price-boundary/boundaries.tsv, 빠진 사건은"
                + " corporate-action/supplement.tsv, 같은 증권의 무거래는 price-boundary/exempt.tsv(근거 필수)에 싣는다.");
        alertPort.send(AlertEvent.toOperator(AlertSeverity.WARNING, "시세 원장 무결성", body.toString(), "price-integrity"));
        return true;
    }

    static Set<String> loadExempt(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("시세 감시 면제 파일이 없다: " + path);
        }
        Set<String> out = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\t", -1);
                if (p.length != 4 || p[3].isBlank()) {
                    throw new IllegalStateException("시세 감시 면제 파일 %s %d행: 열 넷과 근거가 필요하다 — %s".formatted(path, number, line));
                }
                out.add(p[0].trim() + "\t" + p[1].trim() + "\t" + LocalDate.parse(p[2].trim()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("시세 감시 면제 파일을 못 읽었다: " + path, e);
        }
        return out;
    }
}
