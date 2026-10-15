package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 클래스 축 드라이런 3-5 — 관문을 켠 작은 DB의 판정 표로 순위 판독기의 보류 재료({@code classHolds})와 그날 주식수를 찍는다.
 * 드라이런 2(엔진 스윕 TSV로 돈 명부 재생)의 상태와 맞댄다. 읽기 전용({@code readOnlyMode=always}) · 외부 호출 0.
 *
 * <p>스위치: {@code SHARE_CLASS_HOLD_DB}(작은 DB 이름 — {@code stock_screener_sc_d3}로 시작해야 한다) · {@code SHARE_CLASS_HOLD_OUT}.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SHARE_CLASS_HOLD_DB", matches = "stock_screener_sc_d3.*")
class ShareClassHoldReplayLiveTest {

    private static final List<LocalDate> DATES = List.of(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 4),
            LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 25),
            LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 9));

    @Test
    void holds() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/" + System.getenv("SHARE_CLASS_HOLD_DB") + "?readOnlyMode=always");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        StoredShareCounts on = new JdbcStoredShareCounts(jdbc, new ShareClassStore(jdbc), true);
        List<String> lines = new ArrayList<>();
        lines.add("date\tticker\tstate\theld_shares\tshown_shares");
        for (LocalDate d : DATES) {
            Map<String, BigDecimal> shown = new java.util.HashMap<>();
            on.activeByMarket("US", d.minusYears(2), d).forEach(c -> shown.put(c.ticker(), c.shares()));
            Map<String, StoredShareCounts.ClassHold> holds = on.classHolds("US", d);
            java.util.Set<String> tickers = new java.util.TreeSet<>(holds.keySet());
            jdbc.queryForList("SELECT DISTINCT s.ticker FROM financial_share_class_filing v JOIN stock s ON s.id = v.stock_id",
                    String.class).forEach(tickers::add);
            for (String t : tickers) {
                StoredShareCounts.ClassHold h = holds.get(t);
                lines.add(String.join("\t", d.toString(), t, h == null ? "SHOWN_OR_NONE" : h.state().name(),
                        h == null || h.heldShares() == null ? "" : h.heldShares().toBigInteger().toString(),
                        shown.containsKey(t) ? shown.get(t).toBigInteger().toString() : ""));
            }
        }
        Path out = Path.of(System.getenv().getOrDefault("SHARE_CLASS_HOLD_OUT", "build/share-class-holds.tsv"));
        Files.write(out, lines);
        System.out.printf(Locale.ROOT, "share-class holds: rows=%d -> %s%n", lines.size() - 1, out);
    }
}
