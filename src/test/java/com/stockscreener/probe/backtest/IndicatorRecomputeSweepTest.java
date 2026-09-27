package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.PercentileSpec;
import com.stockscreener.indicator.SectorMetricSpec;
import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * <b>기간 기저 변경의 영향 범위를 DB에 쓰지 않고 재는 스윕</b>(ADR-0164 · 묶음 K).
 *
 * <p>{@link PointInTimeScreener}를 그대로 태워 지표를 <b>인메모리로 다시 계산해</b>
 * {@code (시장, 티커, 기준일, 지표코드, 값)}을 TSV로 찍는다. 변경 전/후로 한 번씩 돌려
 * diff하면 <b>재적재도 백필도 없이</b> 어느 종목의 어느 코드가 생기고 사라지는지 나온다.
 *
 * <p><b>MUST NOT: {@code indicator_value}를 읽지 않는다.</b> 이 스윕이 재는 것은 「지금 저장된
 * 값」이 아니라 「이 코드로 계산하면 나올 값」이다. 저장값을 읽으면 변경 전후가 같게 나와
 * 게이트가 통째로 무력해진다 — {@link PointInTimeScreener}의 같은 MUST가 근거다.
 *
 * <p><b>왜 판정이 아니라 값을 찍는가.</b> 프리셋 통과 집합은
 * {@link PresetResultSnapshotTest}가 찍는다. 그것은 <b>저장된 값</b>으로 판정하므로 쓰기
 * <b>뒤에야</b> 움직이고, 쓰기 앞에서 「무엇이 채워질까」를 답할 수 없다. 이 스윕이 그 앞을 맡는다.
 *
 * <p><b>재구성 코드를 전부 요청한다.</b> {@code addSectorRelatives}·
 * {@code addUniversePercentiles}는 <b>참조된 코드만</b> 만들므로, 조건이 참조하지 않는
 * {@code *_PCTL}·{@code *_SECTOR_*}는 요청하지 않으면 조용히 안 나온다 — 그러면 「백분위까지
 * 채운다」는 수용 기준을 이 게이트로 잴 수 없다.
 *
 * <p>실행:
 * <pre>
 * ./gradlew backtestProbe --tests '*IndicatorRecomputeSweepTest*' \
 *   -Dsweep.out=/tmp/ind-before.tsv \
 *   -Dsweep.dates=2025-09-19,2026-02-12,2026-05-27,2026-08-17,2026-08-31,2026-09-22,2026-09-24
 * </pre>
 * {@code -Dsweep.tickers}를 주면 그 종목만, 안 주면 <b>분기 TTM이 성립하지 않는 종목 전부 +
 * 대조군 셋</b>이다. 스위치 이름은 {@code build.gradle}의 {@code sweepSwitches}에 이미 있다 —
 * <b>없는 이름을 주면 그 펜스가 던진다.</b>
 *
 * <p><b>MUST: 날짜는 그 시장의 거래일로 접는다</b>({@link AsOfCalendar#onOrBefore}).
 * 휴장일을 주면 {@code computeAt}이 전 종목 빈 맵을 내는데, 그것은 <b>결손이 아니라 날짜 탓</b>이고
 * 「값이 안 난다」와 구분되지 않는다. 그리고 <b>종목마다 그날 봉이 있는지는 따로다</b> —
 * 봉이 없는 종목은 같은 이유로 빈 맵이라 TSV에 {@code NO_BAR} 한 줄로 남긴다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class IndicatorRecomputeSweepTest {

    /** 변경이 값을 바꾸면 안 되는 종목. <b>금지 변화 집합</b>의 뿌리다. */
    private static final List<String> CONTROLS = List.of("AAPL", "MSFT", "NVDA", "005930");

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private ScreeningTargets screeningTargets;

    @Autowired
    private AsOfCalendar calendar;

    @Autowired
    private List<PercentileSpec> percentileSpecs;

    @Autowired
    private List<SectorMetricSpec> sectorMetricSpecs;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("분기 TTM이 성립하지 않는 종목의 지표를 기준일마다 다시 계산해 TSV로 찍는다")
    void sweepRecomputedIndicators() throws Exception {
        String out = System.getProperty("sweep.out");
        // backtestProbe가 @Tag("backtest") 전부를 돌므로, 출력 경로 없이 섞여 돌 때 붉히지 않는다.
        Assumptions.assumeTrue(out != null && !out.isBlank(),
                "-Dsweep.out=<경로.tsv> 가 없어 스윕을 돌리지 않는다");

        List<String> wanted = csv("sweep.tickers");
        if (wanted.isEmpty()) {
            wanted = new ArrayList<>(defaultTickers());
        }
        Set<String> wantedSet = new LinkedHashSet<>(wanted);
        List<LocalDate> requested = csv("sweep.dates").stream().map(LocalDate::parse).toList();

        Set<String> codes = reconstructedCodes();
        Map<Long, String> label = new TreeMap<>();
        Map<Long, String> market = new TreeMap<>();
        jdbcTemplate.query("SELECT id, market_code, ticker FROM stock", rs -> {
            label.put(rs.getLong("id"), rs.getString("ticker"));
            market.put(rs.getLong("id"), rs.getString("market_code"));
        });

        int rows = 0;
        int noBar = 0;
        Path path = Path.of(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            w.println(String.join("\t", "target", "market", "ticker", "as_of", "code", "value"));
            for (ScreeningTargets.Target target : screeningTargets.all()) {
                for (LocalDate asOf : datesFor(target, requested)) {
                    PointInTimeScreener.Reconstructed r = screener.reconstruct(
                            screener.computePopulationValues(asOf, target), codes, target);
                    for (Map.Entry<Long, Map<String, BigDecimal>> e : r.ofTarget().entrySet()) {
                        String ticker = label.get(e.getKey());
                        if (ticker == null || !wantedSet.contains(ticker)) {
                            continue;
                        }
                        if (e.getValue().isEmpty()) {
                            // 빈 맵과 「값이 0개 났다」를 구분한다 — 전자는 대개 봉이 없는 날이다.
                            w.println(String.join("\t", target.code(), market.get(e.getKey()),
                                    ticker, asOf.toString(), "NO_BAR", ""));
                            noBar++;
                            continue;
                        }
                        for (Map.Entry<String, BigDecimal> v
                                : new TreeMap<>(e.getValue()).entrySet()) {
                            w.println(String.join("\t", target.code(), market.get(e.getKey()),
                                    ticker, asOf.toString(), v.getKey(),
                                    v.getValue() == null ? "" : v.getValue().toPlainString()));
                            rows++;
                        }
                    }
                }
            }
        }
        System.out.printf("SWEEP 종목=%d 요청코드=%d 행=%d 빈맵=%d out=%s%n",
                wantedSet.size(), codes.size(), rows, noBar, path.toAbsolutePath());
    }

    /**
     * 기본 대상 — <b>최신 4분기 행이 연속 12개월을 덮지 못하는 종목</b>과 대조군.
     *
     * <p>술어를 이 파일에 두는 이유는 그것이 <b>묶음 K의 모집단 정의</b>라서다. 티커를 손으로
     * 나열하면 리밸런싱마다 낡고, 낡은 목록은 「전수를 봤다」고 믿게 만든다.
     */
    private Set<String> defaultTickers() {
        List<String> found = jdbcTemplate.queryForList("""
                WITH mem AS (
                  SELECT DISTINCT m.stock_id, u.market_code mc
                  FROM universe_membership m
                  JOIN universe u ON u.code = m.universe_code
                  WHERE u.is_active AND m.effective_to IS NULL),
                latest AS (
                  SELECT market_code mc, max(trade_date) t FROM indicator_trade_date GROUP BY 1),
                q AS (
                  SELECT mem.stock_id, f.period_start, f.period_end,
                         row_number() OVER (PARTITION BY f.stock_id
                                            ORDER BY f.period_end DESC) rn
                  FROM mem JOIN latest l ON l.mc = mem.mc
                  LEFT JOIN financial_statement f ON f.stock_id = mem.stock_id
                    AND f.is_latest AND f.fiscal_period <> 'FY' AND f.period_end <= l.t),
                top4 AS (SELECT * FROM q WHERE rn <= 4),
                agg AS (
                  SELECT mem.stock_id, count(t.period_end) n,
                         max(t.period_end) - min(t.period_start) span
                  FROM mem LEFT JOIN top4 t ON t.stock_id = mem.stock_id GROUP BY 1)
                SELECT s.ticker FROM agg a JOIN stock s ON s.id = a.stock_id
                WHERE NOT (a.n = 4 AND a.span BETWEEN 330 AND 400)
                ORDER BY s.ticker
                """, String.class);
        Set<String> out = new LinkedHashSet<>(found);
        out.addAll(CONTROLS);
        return out;
    }

    /**
     * 그 대상의 기준일. 요청 날짜는 <b>그 시장 거래일로 접고</b>, 접은 결과가 같은 날로 겹치면
     * 한 번만 돈다. 날짜를 안 주면 그 대상의 최신일 하나다.
     */
    private List<LocalDate> datesFor(ScreeningTargets.Target target, List<LocalDate> requested) {
        if (requested.isEmpty()) {
            return target.asOf() == null ? List.of() : List.of(target.asOf());
        }
        Set<LocalDate> folded = new LinkedHashSet<>();
        for (LocalDate d : requested) {
            LocalDate on = calendar.onOrBefore(d, target.markets());
            if (on != null) {
                folded.add(on);
            }
        }
        return new ArrayList<>(folded);
    }

    /** 읽기 시점에 재구성되는 코드 전부 — 백분위 · 업종 상대값 셋 · 표본 수. */
    private Set<String> reconstructedCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (PercentileSpec spec : percentileSpecs) {
            codes.add(spec.code());
        }
        for (SectorMetricSpec spec : sectorMetricSpecs) {
            codes.add(spec.code());
            codes.add(spec.peerCountCode());
        }
        return codes;
    }

    private static List<String> csv(String key) {
        return Arrays.stream(System.getProperty(key, "").split(","))
                .map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
