package com.stockscreener.probe.backtest;

import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.MarketThresholdMissingException;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.preset.ScreenPreset;
import com.stockscreener.screening.query.ScreeningRepository;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>재적재·백필 전후를 대조할 기준선</b> — 전 프리셋·저장 화면 × 조회 대상 × 기준일 여럿의
 * <b>결과 집합</b>을 TSV로 찍는다.
 *
 * <p>통과 <b>수</b>만 찍으면 구성이 바뀐 것을 못 본다. 그리고 <b>프리셋 하나만</b> 찍으면
 * 나머지의 전후 비교가 원리적으로 불가능해진다 — 2026-09-17 A37 재적재가 버핏 우량주 하나만
 * 찍어 `추세 상승`·`2국면 돌파`가 얼마나 달라졌는지 못 쟀다.
 *
 * <p><b>판정은 화면과 같은 경로가 진다</b>({@link ScreeningRepository#screen}). 최신일 여부도
 * 화면과 같게 준다 — 그 시장 지표 달력의 최신 일자일 때만 열린 편입의 하한을 접는다(ADR-0135).
 * 값을 직접 읽거나 다시 매기지 않는다.
 *
 * <p>실행: {@code ./gradlew backtestProbe --tests '*PresetResultSnapshotTest*'
 * -Dsweep.dates=2026-09-14,2026-09-07 -Dsweep.out=<경로.tsv>}. 날짜를 안 주면 대상마다 최신일 하나다.
 *
 * <p>MUST: 운영 전후 대조라면 두 스냅숏 사이에 평일 14:20 화해가 끼었는지 본다. 앱이 떠 있으면 그 화해가
 * <b>과거 기준일의 파생층까지</b> 다시 쓰므로(ADR-0166) 전후 차이에 화해 몫이 섞인다 — 앱을 내린 채 찍거나,
 * 그 사이의 {@code indicator-origin-rewrite}·{@code indicator-derived-rebuild} 실행 기록으로 귀속한다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class PresetResultSnapshotTest {

    @Autowired
    private List<ScreenPreset> presets;

    @Autowired
    private ScreeningRepository screeningRepository;

    @Autowired
    private ScreenStore screenStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScreeningTargets screeningTargets;

    private record Strategy(String label, EvaluationMode mode, ConditionGroup conditions) {
    }

    @Test
    @DisplayName("전 프리셋·저장 화면의 결과 집합을 기준일마다 TSV로 찍는다")
    void snapshotResultSets() throws Exception {
        String out = System.getProperty("sweep.out");
        // backtestProbe가 @Tag("backtest") 전부를 돌므로, 출력 경로 없이 섞여 돌 때 붉히지 않는다 —
        // 붉은 수가 늘면 「backtestProbe의 붉은 셋」 같은 기존 신호가 묻힌다.
        org.junit.jupiter.api.Assumptions.assumeTrue(out != null && !out.isBlank(),
                "-Dsweep.out=<경로.tsv> 가 없어 기준선을 찍지 않는다");
        List<LocalDate> dates = Arrays.stream(System.getProperty("sweep.dates", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(LocalDate::parse).toList();

        Map<Long, String> tickers = new HashMap<>();
        jdbcTemplate.query("SELECT id, market_code, ticker FROM stock", rs -> {
            tickers.put(rs.getLong("id"), rs.getString("market_code") + ":" + rs.getString("ticker"));
        });

        List<ScreeningTargets.Target> targets = screeningTargets.all();
        assertThat(targets).as("조회 대상이 하나도 없다").isNotEmpty();
        List<Strategy> strategies = strategies();

        long rows = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(out), StandardCharsets.UTF_8))) {
            w.println("as_of\ttarget\tstrategy\tstock\tincluded\tscore\tmax_score\tmatched");
            for (ScreeningTargets.Target target : targets) {
                if (target.asOf() == null) {
                    System.out.printf("[%s] 지표 달력이 비었다 — 건너뛴다%n", target.code());
                    continue;
                }
                List<LocalDate> asOfs = dates.isEmpty() ? List.of(target.asOf()) : dates;
                for (LocalDate asOf : asOfs) {
                    if (asOf.isAfter(target.asOf())) {
                        System.out.printf("[%s] %s는 최신 지표일 %s 뒤라 건너뛴다%n",
                                target.code(), asOf, target.asOf());
                        continue;
                    }
                    // 휴장일을 기준일로 주면 결과가 비는데, 그 0은 「통과 0」과 구분되지 않는다.
                    Integer onCalendar = jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM indicator_trade_date WHERE market_code = ? AND trade_date = ?",
                            Integer.class, target.marketCode(), asOf);
                    if (onCalendar == null || onCalendar == 0) {
                        System.out.printf("[%s] %s는 그 시장 지표 달력에 없다 — 건너뛴다%n",
                                target.code(), asOf);
                        continue;
                    }
                    boolean atLatest = asOf.equals(target.asOf());
                    for (Strategy strategy : strategies) {
                        List<ScreeningResult> results;
                        try {
                            results = screeningRepository.screen(
                                    strategy.conditions().resolveFor(target.currency()),
                                    strategy.mode(), asOf, atLatest, target.code());
                        } catch (MarketThresholdMissingException e) {
                            w.printf("%s\t%s\t%s\t(거절:%s 임계 없음)\t\t\t\t%n",
                                    asOf, target.code(), strategy.label(), e.currencyCode());
                            continue;
                        }
                        long included = 0;
                        for (ScreeningResult r : results) {
                            w.printf("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%d%n", asOf, target.code(),
                                    strategy.label(), tickers.getOrDefault(r.stockId(), "#" + r.stockId()),
                                    r.included(), r.score() == null ? "" : r.score().toPlainString(),
                                    r.maxScore() == null ? "" : r.maxScore().toPlainString(),
                                    r.matchedCount());
                            rows++;
                            if (r.included()) {
                                included++;
                            }
                        }
                        System.out.printf("[%s] %s %-34s 결과 %5d · 필수통과 %5d%n",
                                target.code(), asOf, strategy.label(), results.size(), included);
                    }
                }
            }
        }
        System.out.printf("%n기준선 %,d행 → %s%n", rows, out);
    }

    /** 활성 프리셋 + 활성 저장 화면({@code PresetAcrossUniversesTest}와 같은 집합). */
    private List<Strategy> strategies() {
        List<Strategy> all = new ArrayList<>(presets.stream()
                .map(p -> new Strategy(p.code(), p.mode(), p.conditions()))
                .toList());
        List<Long> savedIds = jdbcTemplate.queryForList(
                "SELECT id FROM screen WHERE is_active AND screen_type = 'CUSTOM' ORDER BY id", Long.class);
        for (Long id : savedIds) {
            screenStore.findById(id).ifPresent(d ->
                    all.add(new Strategy("[저장] " + d.name(), d.mode(), d.conditions())));
        }
        return all;
    }
}
