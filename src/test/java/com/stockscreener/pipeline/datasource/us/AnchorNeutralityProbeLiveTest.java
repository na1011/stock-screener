package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * R1 — 닻 중립성(ADR-0180 · 계획 v3.1 §4 R1). 닻을 붙인 시세 어댑터(asof = 행이 생긴 미국 동부 날짜)가 받는 봉이 <b>운영에 저장된 봉</b>과
 * 같은가. 기준은 저장 {@code price_daily}다 — 그 봉들은 지금까지의 코드(asof 없음)가 그때그때 받은 것이다. 같은 표본을 asof 없이도 받아
 * 「닻이 바꾸는 봉」과 「소스가 그 사이 바꾼 봉」을 가른다.
 *
 * <p>운영 DB는 읽기만. 표본은 의도적으로 고른다(계획 §4 R1) — 기호 그대로 CUSIP 개명 · 접합 행 · 경계일 사건 · 음의 대조 · 시드 고정 무작위
 * 편입. 칠 때: {@code ( set -a; . ./.env; set +a; ANCHOR_PROBE=1 ./gradlew liveTest --tests '*AnchorNeutralityProbeLiveTest*' -PskipFrontend -i )}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANCHOR_PROBE", matches = ".+")
class AnchorNeutralityProbeLiveTest {

    private static final List<String> DELIBERATE = List.of(
            // 기호 그대로 CUSIP 개명
            "ARB", "EVNT", "HELP", "OKE", "SCEC", "TEVA", "XRPN", "XRPNW",
            // 접합 행(docs/reload-gate.md)
            "CAI", "PPLC", "FIG", "FLY", "NIQ", "CR", "ACT", "IOT", "S", "TLN",
            // 경계일 사건
            "PSQL", "XLAB",
            // 음의 대조(분할·거래 많은 대형)
            "AAPL", "NVDA", "BRK.B");

    @Test
    void probe() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/stock_screener?options=-c%20default_transaction_read_only%3Don",
                System.getProperty("user.name"), "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setApiKey(System.getenv("APCA_API_KEY_ID"));
        properties.getAlpaca().setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        properties.getAlpaca().setReadTimeout(Duration.ofSeconds(60));
        AlpacaPriceAdapter adapter = new AlpacaPriceAdapter(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 150, 0));

        record Row(long id, String ticker, LocalDate anchor, LocalDate from, LocalDate to, int bars) {
        }
        String rowSql = """
                SELECT s.id, s.ticker, (s.created_at AT TIME ZONE 'America/New_York')::date AS anchor,
                       min(p.trade_date) AS f, max(p.trade_date) AS t, count(*) AS n
                FROM stock s JOIN price_daily p ON p.stock_id = s.id
                WHERE s.market_code = 'US' AND s.created_at < TIMESTAMPTZ '2026-10-17 10:59:00+00' %s
                GROUP BY s.id, s.ticker, s.created_at""";
        List<Row> sample = new ArrayList<>();
        java.util.function.Function<java.sql.ResultSet, Row> map = rs -> {
            try {
                return new Row(rs.getLong("id"), rs.getString("ticker"), rs.getObject("anchor", LocalDate.class),
                        rs.getObject("f", LocalDate.class), rs.getObject("t", LocalDate.class), rs.getInt("n"));
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        };
        sample.addAll(jdbc.query(rowSql.formatted("AND s.ticker = ANY (?)"), (rs, i) -> map.apply(rs),
                (Object) DELIBERATE.toArray(String[]::new)));
        // 시드 고정 무작위 편입 50 — 저장 봉이 있는 지금 편입(md5 순서 = 재현 가능한 섞기).
        sample.addAll(jdbc.query(rowSql.formatted("""
                AND EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id = s.id AND m.universe_code = 'US_TOP1000'
                            AND m.effective_to IS NULL) AND NOT (s.ticker = ANY (?))""") + " ORDER BY md5('r1-1017' || s.ticker) LIMIT 50",
                (rs, i) -> map.apply(rs), (Object) DELIBERATE.toArray(String[]::new)));

        Map<String, Integer> summary = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>(DELIBERATE);
        for (Row row : sample) {
            missing.remove(row.ticker());
            Map<LocalDate, Map<String, Object>> stored = new HashMap<>();
            jdbc.query("SELECT trade_date, open, high, low, close, volume FROM price_daily WHERE stock_id = ?", rs -> {
                Map<String, Object> bar = new HashMap<>();
                bar.put("o", rs.getBigDecimal("open"));
                bar.put("h", rs.getBigDecimal("high"));
                bar.put("l", rs.getBigDecimal("low"));
                bar.put("c", rs.getBigDecimal("close"));
                bar.put("v", rs.getLong("volume"));
                stored.put(rs.getObject("trade_date", LocalDate.class), bar);
            }, row.id());
            SecurityRef anchored = new SecurityRef(row.id(), "US", row.ticker(), new SecurityRef.Identity(row.ticker(), row.anchor(),
                    null, null, null, List.of()));
            SecurityRef plain = new SecurityRef(row.id(), "US", row.ticker());
            List<RawPriceBar> withAnchor = adapter.fetchDailyBars(anchored, row.from(), row.to());
            List<RawPriceBar> withoutAnchor = adapter.fetchDailyBars(plain, row.from(), row.to());
            int[] a = compare(stored, withAnchor);
            int[] p = compare(stored, withoutAnchor);
            int anchorVsPlain = diff(withAnchor, withoutAnchor);
            String verdict = a[1] == 0 && a[2] == 0 ? "같음" : "다름";
            summary.merge("닻 " + verdict, 1, Integer::sum);
            summary.merge("닻≠기본 " + (anchorVsPlain == 0 ? "0" : ">0"), 1, Integer::sum);
            lines.add("R1 %s 저장 %d · 닻[같은날 %d 값다름 %d 저장에만 %d 소스에만 %d] · 기본[값다름 %d 저장에만 %d 소스에만 %d] · 닻≠기본 %d"
                    .formatted(row.ticker(), row.bars(), a[0], a[1], a[2], a[3], p[1], p[2], p[3], anchorVsPlain));
        }
        lines.forEach(System.out::println);
        System.out.println("R1 표본 " + sample.size() + " · " + summary + " · 저장 봉 없는 의도 표본 " + missing);
    }

    /** {같은 날 · 값 다름 · 저장에만 · 소스에만}. 값은 시가·고가·저가·종가·거래량. */
    private static int[] compare(Map<LocalDate, Map<String, Object>> stored, List<RawPriceBar> fetched) {
        int same = 0;
        int differ = 0;
        int sourceOnly = 0;
        Set<LocalDate> seen = new java.util.HashSet<>();
        for (RawPriceBar bar : fetched) {
            Map<String, Object> s = stored.get(bar.tradeDate());
            if (s == null) {
                sourceOnly++;
                continue;
            }
            seen.add(bar.tradeDate());
            boolean equal = eq(s.get("o"), bar.open()) && eq(s.get("h"), bar.high()) && eq(s.get("l"), bar.low())
                    && eq(s.get("c"), bar.close()) && ((Long) s.get("v")) == bar.volume();
            if (equal) {
                same++;
            } else {
                differ++;
            }
        }
        int storedOnly = (int) stored.keySet().stream().filter(d -> !seen.contains(d)).count();
        return new int[]{same, differ, storedOnly, sourceOnly};
    }

    private static int diff(List<RawPriceBar> a, List<RawPriceBar> b) {
        Map<LocalDate, RawPriceBar> byDate = new HashMap<>();
        b.forEach(bar -> byDate.put(bar.tradeDate(), bar));
        int n = Math.abs(a.size() - b.size());
        for (RawPriceBar bar : a) {
            RawPriceBar other = byDate.get(bar.tradeDate());
            if (other == null || !eq(bar.close(), other.close()) || bar.volume() != other.volume()) {
                n++;
            }
        }
        return n;
    }

    private static boolean eq(Object stored, BigDecimal fetched) {
        return stored instanceof BigDecimal s && s.compareTo(fetched) == 0;
    }
}
