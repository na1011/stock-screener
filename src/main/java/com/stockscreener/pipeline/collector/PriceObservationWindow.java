package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.us.UsMarket;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 한 행이 저장할 수 있는 봉의 구간 — 그 행 증권의 관측 구간(ADR-0181).
 *
 * <p>쓸 수 있는 봉 = {@code max(경계, era_from) ≤ 날짜 < era_to} <b>그리고</b> 미국이면 머리 평봉이 아님. 셋의 뜻:
 * <ul>
 *   <li><b>경계</b>({@code price-boundary/boundaries.tsv}) — 같은 기호 문자열의 다른 증권(재사용 · 새 발행사 · 옛 지분이 소각된 파산 재편)이
 *       그 앞에 있다. 소스는 지금도 그 봉을 준다 — 행의 닻(ADR-0180)은 지금 실체를 고정할 뿐 그 앞에 붙은 다른 실체 봉과 체결 없는 채움을
 *       거르지 않는다(실측 {@code VG}: 닻 · 문자열 · 오늘 asof 모두 Vonage 봉 + 평봉 630 + Venture Global).</li>
 *   <li><b>등록인 시대</b>({@code era_from} · {@code era_to}) — 행 = 실체 × 등록인 시대라 그 밖의 봉은 다른 행의 것이다. 신원 판정이 행을 끊으면
 *       새 행은 닻이 달라 경계 파일 키에 안 걸리지만 시대가 그 앞을 막는다 — 경계는 언제나 그 시대보다 앞이다.</li>
 *   <li><b>머리 평봉</b>(미국만) — 첫 실거래 봉 앞의 거래량 0 · 시고저종 같은 봉. 평봉 값은 「마지막 관측 가격」인데 머리에서는 그 관측이
 *       우리 이력에 없어 확인할 길이 없고, 실측 8행 전부 다른 실체의 값이었다(SN 0.3607 옛 Sanchez · MBLY 62.67 옛 Mobileye). 중간 평봉은
 *       이력 안의 관측을 잇는 것이라 둔다(거래 드문 클래스주 · 같은 증권의 무거래일). 국내는 거래정지 기준가가 같은 증권의 공식 값이라 이
 *       규칙에서 뺀다.</li>
 * </ul>
 *
 * <p>MUST: {@code price_daily}에 쓰는 자리는 전부 이 술어를 지난다 — {@link PriceWriter}(모든 적재 경로) · 접기({@code StockFold}). 하나라도
 * 빠지면 그 경로가 지운 봉을 다시 넣는다.
 */
@Component
public class PriceObservationWindow {

    static final String BOUNDARY_FILE = "price-boundary/boundaries.tsv";

    private final JdbcTemplate jdbc;
    private final Map<AnchorKey, Boundary> boundaries;

    @org.springframework.beans.factory.annotation.Autowired
    public PriceObservationWindow(JdbcTemplate jdbc) {
        this(jdbc, load(BOUNDARY_FILE));
    }

    PriceObservationWindow(JdbcTemplate jdbc, Map<AnchorKey, Boundary> boundaries) {
        this.jdbc = jdbc;
        this.boundaries = Map.copyOf(boundaries);
    }

    /** 시험용 — DB도 경계도 없다. 모든 행이 구간 제한 없는 국내 꼴로 보인다. */
    public static PriceObservationWindow none() {
        return new PriceObservationWindow(null, Map.of());
    }

    /** 경계 파일의 키 — 행의 닻. */
    public record AnchorKey(String market, String anchorSymbol, LocalDate anchorDate) {
    }

    /** 경계 파일 한 줄. */
    public record Boundary(AnchorKey key, LocalDate date, String form, String evidence) {
    }

    /**
     * 한 행의 관측 구간.
     *
     * @param floor   이 날짜 앞은 안 쓴다(경계와 {@code era_from} 중 늦은 것 · 없으면 null)
     * @param ceiling 이 날짜부터는 안 쓴다({@code era_to} · 없으면 null)
     * @param us      머리 평봉 규칙이 서는가
     */
    public record Window(long stockId, LocalDate floor, LocalDate ceiling, boolean us, Optional<Boundary> boundary) {
        public boolean admits(LocalDate date) {
            return (floor == null || !date.isBefore(floor)) && (ceiling == null || date.isBefore(ceiling));
        }
    }

    /** 거른 결과 — 남긴 봉과 버린 사유별 수. */
    public record Screened(List<RawPriceBar> kept, int outsideWindow, int headFlat) {
        public int dropped() {
            return outsideWindow + headFlat;
        }
    }

    /** 닻으로 경계를 찾는다(포트 구현이 행을 만들 때). */
    public Optional<LocalDate> boundaryOf(String market, String anchorSymbol, LocalDate anchorDate) {
        if (anchorSymbol == null || anchorDate == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(boundaries.get(new AnchorKey(market, anchorSymbol, anchorDate))).map(Boundary::date);
    }

    /** 경계 파일 전부(감시 · 교정 잡이 키를 DB 행으로 푼다). */
    public Collection<Boundary> boundaries() {
        return boundaries.values();
    }

    public Window of(long stockId) {
        return of(List.of(stockId)).getOrDefault(stockId, new Window(stockId, null, null, false, Optional.empty()));
    }

    /** 여러 행을 한 질의로. 없는 행은 결과에 없다. */
    public Map<Long, Window> of(Collection<Long> stockIds) {
        Map<Long, Window> out = new HashMap<>();
        if (stockIds.isEmpty() || jdbc == null) {
            return out;
        }
        jdbc.query("""
                SELECT id, market_code, alpaca_anchor_symbol, alpaca_anchor_date, era_from, era_to
                FROM stock WHERE id = ANY (?)
                """, rs -> {
            long id = rs.getLong("id");
            String market = rs.getString("market_code");
            String anchorSymbol = rs.getString("alpaca_anchor_symbol");
            LocalDate anchorDate = rs.getObject("alpaca_anchor_date", LocalDate.class);
            LocalDate eraFrom = rs.getObject("era_from", LocalDate.class);
            LocalDate eraTo = rs.getObject("era_to", LocalDate.class);
            Optional<Boundary> boundary = anchorSymbol == null || anchorDate == null ? Optional.empty()
                    : Optional.ofNullable(boundaries.get(new AnchorKey(market, anchorSymbol, anchorDate)));
            LocalDate floor = later(boundary.map(Boundary::date).orElse(null), eraFrom);
            out.put(id, new Window(id, floor, eraTo, UsMarket.CODE.equals(market), boundary));
        }, (Object) stockIds.toArray(Long[]::new));
        return out;
    }

    /**
     * 쓰기 전에 거른다. 머리 평봉의 「첫 실거래」는 저장된 것과 이번 묶음 중 이른 것이다 — 신규 행의 첫 적재에서 묶음만 보면 중간 정지
     * 평봉까지 머리로 버리고, 저장된 것만 보면 신규 행의 머리를 못 본다.
     */
    public Screened screen(long stockId, List<RawPriceBar> bars) {
        Window window = of(stockId);
        List<RawPriceBar> inWindow = new ArrayList<>(bars.size());
        int outside = 0;
        for (RawPriceBar bar : bars) {
            if (window.admits(bar.tradeDate())) {
                inWindow.add(bar);
            } else {
                outside++;
            }
        }
        if (!window.us() || inWindow.isEmpty()) {
            return new Screened(inWindow, outside, 0);
        }
        LocalDate firstTraded = earlier(storedFirstTraded(window), inWindow.stream().filter(b -> b.volume() > 0)
                .map(RawPriceBar::tradeDate).min(LocalDate::compareTo).orElse(null));
        List<RawPriceBar> kept = new ArrayList<>(inWindow.size());
        int headFlat = 0;
        for (RawPriceBar bar : inWindow) {
            if (flatWithoutTrade(bar) && (firstTraded == null || bar.tradeDate().isBefore(firstTraded))) {
                headFlat++;
            } else {
                kept.add(bar);
            }
        }
        return new Screened(kept, outside, headFlat);
    }

    /**
     * 저장된 봉 중 이 술어 밖인 날짜 — 교정 잡 · 감시 · 접기가 쓴다. 머리 평봉의 첫 실거래는 저장된 관측 구간 안의 첫 실거래다.
     */
    public List<LocalDate> storedOutside(long stockId) {
        if (jdbc == null) {
            return List.of();
        }
        Window window = of(stockId);
        return jdbc.queryForList("""
                WITH ft AS (
                    SELECT min(trade_date) AS d FROM price_daily
                    WHERE stock_id = ? AND volume > 0
                      AND (?::date IS NULL OR trade_date >= ?::date) AND (?::date IS NULL OR trade_date < ?::date))
                SELECT p.trade_date FROM price_daily p, ft
                WHERE p.stock_id = ?
                  AND ((?::date IS NOT NULL AND p.trade_date < ?::date)
                       OR (?::date IS NOT NULL AND p.trade_date >= ?::date)
                       OR (? AND p.volume = 0 AND p.open = p.high AND p.high = p.low AND p.low = p.close
                           AND (ft.d IS NULL OR p.trade_date < ft.d)))
                ORDER BY p.trade_date
                """, LocalDate.class, stockId, window.floor(), window.floor(), window.ceiling(), window.ceiling(),
                stockId, window.floor(), window.floor(), window.ceiling(), window.ceiling(), window.us());
    }

    private LocalDate storedFirstTraded(Window window) {
        return jdbc.queryForObject("""
                SELECT min(trade_date) FROM price_daily
                WHERE stock_id = ? AND volume > 0
                  AND (?::date IS NULL OR trade_date >= ?::date) AND (?::date IS NULL OR trade_date < ?::date)
                """, LocalDate.class, window.stockId(), window.floor(), window.floor(), window.ceiling(), window.ceiling());
    }

    /** 거래량 0이고 시고저종이 같다 — 소스가 직전 가격으로 채운 체결 없는 봉. */
    static boolean flatWithoutTrade(RawPriceBar bar) {
        return bar.volume() == 0 && bar.open().compareTo(bar.high()) == 0 && bar.high().compareTo(bar.low()) == 0
                && bar.low().compareTo(bar.close()) == 0;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    static Map<AnchorKey, Boundary> load(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            // MUST NOT: 조용히 빈 채로 두지 않는다 — 빈 경계는 「지울 다른 증권 봉이 없다」와 구별되지 않고 그 봉을 다시 받는다.
            throw new IllegalStateException("시세 경계 파일이 없다: " + path);
        }
        Map<AnchorKey, Boundary> out = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length != 6 || parts[5].isBlank()) {
                    // 근거 없는 경계는 받지 않는다 — 다음 세션이 그 줄을 지워도 되는지 판정할 재료가 없다.
                    throw new IllegalStateException("시세 경계 파일 %s %d행: 열 여섯과 근거가 필요하다 — %s".formatted(path, number, line));
                }
                AnchorKey key = new AnchorKey(parts[0].trim(), parts[1].trim(), LocalDate.parse(parts[2].trim()));
                if (out.put(key, new Boundary(key, LocalDate.parse(parts[3].trim()), parts[4].trim(), parts[5].trim())) != null) {
                    throw new IllegalStateException("시세 경계 파일 %s %d행: 같은 닻이 두 번 있다 — %s".formatted(path, number, key));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("시세 경계 파일을 못 읽었다: " + path, e);
        }
        return out;
    }
}
