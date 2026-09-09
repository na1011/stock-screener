package com.stockscreener.probe.backtest;

import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.MarketThresholdMissingException;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.preset.PresetCatalog;
import com.stockscreener.screening.preset.ScreenPreset;
import com.stockscreener.screening.query.ScreeningRepository;
import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>저장된 지표가 과거 as-of에서도 시점 재현인가</b>를 잰다.
 *
 * <p>{@link AsOfRecomputeTest}가 같은 대조를 하지만 <b>최신 거래일 하나</b>에서만 돈다 —
 * 그 자리에서는 {@code KNOWN_ON}과 {@code LATEST}가 자명히 같아서 <b>시간축을 검사하지
 * 않는다.</b> ADR-0128이 저장 경로를 {@code KNOWN_ON}으로 옮긴 뒤 「저장 값이 곧 시점 재현」이
 * 참인지는 <b>과거 날짜에서만</b> 물을 수 있고, 그것을 재는 것이 여태 시점 재현 저장소
 * (ADR-0123)의 왕복 검증 하나뿐이었다.
 *
 * <p><b>이 탐침은 단언하지 않고 신고한다.</b> 잼의 목적이 「엔진 경로로 갈아탈 수 있는가」를
 * 판단할 수를 내는 것이라, 갈리는 종목이 있으면 <b>그 수와 이름</b>이 답이다. 초록/붉음으로
 * 접으면 그 답이 사라진다. 다만 <b>0 == 0으로 초록이 되는 것</b>은 막는다.
 *
 * <p><b>알려진 예외가 있다.</b> {@code RETAINED_TO_MARKET_4Y}·{@code EPS_CAGR_5Y} 부류는
 * 분모가 {@code asOf}의 주식수를 따라가므로 저장된 과거 값이 시점 재현이 아니다
 * (ADR-0129 §결과 · ADR-0079 §결과). 그것이 <b>통과 집합을 실제로 움직이는지</b>가 이 탐침이
 * 답하는 물음이다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class StoredAsOfParityProbeTest {

    /** 대상마다 볼 과거 as-of 수. 재계산이 날짜당 KRW 14.5초 · USD 37.0초라 이것이 예산이다. */
    private static final int DATES_PER_TARGET = 4;

    /** 표본 간격(거래일). 보존 창 255를 {@link #DATES_PER_TARGET}개로 훑는 폭이다. */
    private static final int STEP_TRADING_DAYS = 60;

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private AsOfCalendar calendar;

    @Autowired
    private ScreeningTargets targets;

    @Autowired
    private ScreeningRepository screeningRepository;

    @Autowired
    private List<ScreenPreset> presets;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("과거 as-of에서 저장 지표로 낸 통과 집합이 시점 재계산과 같은가")
    void storedValuesMatchRecomputationAtPastAsOf() {
        ScreenPreset preset = presetByCode(PresetCatalog.BUFFETT_QUALITY);
        int compared = 0;
        int diverged = 0;

        for (ScreeningTargets.Target target : targets.all()) {
            if (target.asOf() == null) {
                System.out.printf("%n===== [%s] 지표 달력이 비어 있다 — 건너뛴다 =====%n",
                        target.code());
                continue;
            }
            ConditionGroup resolved;
            try {
                resolved = preset.conditions().resolveFor(target.currency());
            } catch (MarketThresholdMissingException e) {
                System.out.printf("%n===== [%s] 실행 거절 — %s 임계 없음: %s =====%n",
                        target.code(), e.currencyCode(), String.join(" ", e.indicatorCodes()));
                continue;
            }
            System.out.printf("%n===== [%s] 저장 대 재계산 · 과거 as-of %d개 =====%n",
                    target.code(), DATES_PER_TARGET);
            for (LocalDate asOf : pastAsOfDates(target)) {
                compared++;
                diverged += compareAt(preset, resolved, target, asOf) ? 0 : 1;
            }
        }

        // 0 == 0으로 초록이 되는 것을 막는다. 그 상태는 「같다」가 아니라 「못 쟀다」이다.
        assertThat(compared)
                .as("과거 as-of를 하나도 대조하지 못했다 — 이 탐침은 아무것도 확인하지 않았다")
                .isPositive();
        System.out.printf("%n===== 합계: 대조 %d · 갈린 날짜 %d =====%n", compared, diverged);
    }

    /**
     * 그 대상의 지표 달력에서 뒤로 훑은 과거 거래일.
     *
     * <p><b>최신 거래일은 넣지 않는다</b> — 그 자리는 {@link AsOfRecomputeTest}가 이미 보고,
     * 여기서 물으려는 것은 시간축이다.
     */
    private List<LocalDate> pastAsOfDates(ScreeningTargets.Target target) {
        List<LocalDate> all = calendar.everyTradingDay(
                target.asOf().minusYears(2), target.asOf(), target.markets());
        List<LocalDate> picked = new ArrayList<>();
        for (int i = 1; i <= DATES_PER_TARGET; i++) {
            int index = all.size() - 1 - i * STEP_TRADING_DAYS;
            if (index >= 0) {
                picked.add(all.get(index));
            }
        }
        return picked;
    }

    /** @return 갈리지 않았는가 */
    private boolean compareAt(ScreenPreset preset, ConditionGroup resolved,
                              ScreeningTargets.Target target, LocalDate asOf) {
        // MUST: 양쪽에 같은 해석본을 넘긴다. 한쪽만 해석하면 임계가 갈린 것을
        // 「시점 경로가 라이브와 다르다」로 읽게 된다 — 원인이 정반대다.
        PointInTimeScreener.Outcome recomputed =
                screener.screen(asOf, preset.conditions(), preset.mode(), target);
        List<ScreeningResult> stored =
                screeningRepository.screen(resolved, preset.mode(), asOf, target.code());

        Set<Long> fromRecompute = idsOf(recomputed.included());
        Set<Long> fromStored = idsOf(stored);
        Set<Long> onlyRecomputed = new TreeSet<>(fromRecompute);
        onlyRecomputed.removeAll(fromStored);
        Set<Long> onlyStored = new TreeSet<>(fromStored);
        onlyStored.removeAll(fromRecompute);

        int scoreGaps = countScoreGaps(recomputed.included(), stored);
        System.out.printf("%s · 판정 %d · 재계산 통과 %d · 저장 통과 %d · 재계산에만 %d · "
                        + "저장에만 %d · 점수 갈림 %d%n",
                asOf, recomputed.evaluated(), fromRecompute.size(), fromStored.size(),
                onlyRecomputed.size(), onlyStored.size(), scoreGaps);
        Set<Long> divergent = new TreeSet<>(onlyRecomputed);
        divergent.addAll(onlyStored);
        // 점수만 갈린 종목도 찍는다. 집합이 맞아도 배점 열이 틀리면 **순위가 달라지고**,
        // 그 원인은 집합 축과 다를 수 있다 — 실제로 달랐다.
        divergent.addAll(scoreGapIds(recomputed.included(), stored));
        for (Long stockId : divergent) {
            reportValueGaps(stockId, asOf, resolved, recomputed,
                    onlyStored.contains(stockId) ? "저장에만"
                            : onlyRecomputed.contains(stockId) ? "재계산에만" : "점수만 갈림");
        }
        return onlyRecomputed.isEmpty() && onlyStored.isEmpty() && scoreGaps == 0;
    }

    /**
     * 갈린 종목의 <b>지표 단위 차이</b>를 찍는다.
     *
     * <p>통과 집합만 보면 「갈렸다」까지만 알고 <b>어느 계산기가 시점 재현이 아닌지</b>를
     * 모른다. 그것이 이 탐침이 답해야 하는 것이라 코드별로 두 값을 나란히 놓는다.
     */
    private void reportValueGaps(long stockId, LocalDate asOf, ConditionGroup resolved,
                                 PointInTimeScreener.Outcome recomputed, String side) {
        Map<String, BigDecimal> mine =
                recomputed.valuesByStock().getOrDefault(stockId, Map.of());
        List<String> codes = new ArrayList<>(resolved.referencedIndicatorCodes());
        Map<String, BigDecimal> storedValues = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT indicator_code, value FROM indicator_value
                 WHERE stock_id = ? AND trade_date = ? AND indicator_code = ANY (?)
                """,
                ps -> {
                    ps.setLong(1, stockId);
                    ps.setObject(2, asOf);
                    ps.setArray(3, ps.getConnection()
                            .createArrayOf("varchar", codes.toArray()));
                },
                rs -> {
                    storedValues.put(rs.getString(1), rs.getBigDecimal(2));
                });

        String ticker = jdbcTemplate.queryForObject(
                "SELECT ticker FROM stock WHERE id = ?", String.class, stockId);
        System.out.printf("   [%s] %s (id=%d) — 코드별 차이%n", side, ticker, stockId);
        for (String code : codes.stream().sorted().toList()) {
            BigDecimal a = mine.get(code);
            BigDecimal b = storedValues.get(code);
            boolean same = a == null ? b == null : b != null && a.compareTo(b) == 0;
            if (!same) {
                System.out.printf("      %-28s 재계산 %-20s 저장 %s%n", code, a, b);
            }
        }
    }

    /**
     * 점수가 갈린 종목 수.
     *
     * <p>집합만 견주면 <b>배점 조건이 검사되지 않는다</b> — 포함 여부는 필수 게이트가 정하므로
     * 배점 열이 통째로 틀려도 집합은 안 움직인다({@code AsOfRecomputeTest.assertScoresMatch}가
     * 같은 근거로 점수를 본다).
     */
    /** 점수가 갈린 종목 id. {@link #countScoreGaps}와 같은 판정이고 목록을 낸다. */
    private static Set<Long> scoreGapIds(List<ScreeningResult> recomputed,
                                         List<ScreeningResult> stored) {
        Map<Long, BigDecimal> storedScores = stored.stream().collect(
                java.util.stream.Collectors.toMap(ScreeningResult::stockId,
                        ScreeningResult::score));
        Set<Long> out = new TreeSet<>();
        for (ScreeningResult result : recomputed) {
            BigDecimal expected = storedScores.get(result.stockId());
            if (expected == null || result.score().compareTo(expected) != 0) {
                out.add(result.stockId());
            }
        }
        return out;
    }

    private static int countScoreGaps(List<ScreeningResult> recomputed,
                                      List<ScreeningResult> stored) {
        Map<Long, BigDecimal> storedScores = stored.stream().collect(
                java.util.stream.Collectors.toMap(ScreeningResult::stockId,
                        ScreeningResult::score));
        int gaps = 0;
        for (ScreeningResult result : recomputed) {
            BigDecimal expected = storedScores.get(result.stockId());
            if (expected == null || result.score().compareTo(expected) != 0) {
                gaps++;
            }
        }
        return gaps;
    }

    private ScreenPreset presetByCode(String code) {
        return presets.stream()
                .filter(preset -> preset.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("프리셋이 없다: " + code));
    }

    private static Set<Long> idsOf(List<ScreeningResult> results) {
        return results.stream().map(ScreeningResult::stockId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }
}
