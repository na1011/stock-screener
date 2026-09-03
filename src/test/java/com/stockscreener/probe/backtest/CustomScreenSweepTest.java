package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorRegistry;
import com.stockscreener.screening.MarketThresholdMissingException;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.store.ScreenDefinition;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 커스텀 전략을 <b>시점 재현으로</b> 일별 스윕해 최초 통과일을 낸다.
 *
 * <p><b>왜 저장된 지표를 안 쓰는가.</b> 저장 경로({@code indicator_value})는 보존 창이
 * 180거래일이라 1년을 못 덮고, 늘리려면 10~25시간짜리 중단 불가 재백필이 필요하다
 * (ADR-0036 §결과가 구간 분할과 중단 복구를 둘 다 금지한다). 게다가 저장된 값은
 * {@code FinancialVisibility.LATEST}로 계산돼 <b>그날 아직 공시되지 않은 재무</b>가 섞인다 —
 * 실측 2026-09-02 기준 2026-02-01에 그런 종목이 코스닥 99.3% · 코스피 99.0% · 미국 80.2%다.
 * 이 하네스는 {@code KNOWN_ON}으로 그날 공시된 판만 읽는다.
 *
 * <p><b>도달 범위는 시세 워밍업이 정한다.</b> {@code HarnessWindow.floorFor}가 재무 깊이로
 * 잡는 하한과 {@link AsOfCalendar#earliestComputableAsOf}가 시세로 잡는 하한 중 <b>늦은
 * 쪽</b>이 실제 하한이고, v12·v13처럼 {@code PROFIT_STREAK_Y}가 5년이면 시세 쪽이 이긴다.
 *
 * <p><b>남아 있는 편향</b>: 유니버스 편입을 as-of로 거르지 않는다
 * ({@code ScreeningTargets}가 {@code effective_to IS NULL}을 쓴다 — 라이브와 같은 모양이다).
 * 편입 이력이 2026-08-05/08-31부터라 고칠 수도 측정할 수도 없다. <b>절대 성공률을 전략
 * 성적으로 인용하지 않는다</b>는 뜻이고, v12 대 v13 비교에는 공통모드로 걸린다.
 *
 * <p>구간은 {@code -Dsweep.from=}·{@code -Dsweep.to=}로 좁힐 수 있다. 기본은 <b>1년</b>이며,
 * 짧게 줘서 먼저 맞는지 보고 전 구간을 돌리는 쪽이 싸다 — 날짜 하나가 세 시장 합쳐 약 1분이다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class CustomScreenSweepTest {

    /** 스윕할 전략. 이름의 일부이며 사용자 소유 `CUSTOM` 화면을 가리킨다. */
    private static final List<String> SCREEN_NAMES = List.of("v12", "v13");

    /** 달성률 문턱. 커스텀 전략 백테스트 규약이 정한 값이고 이것을 빼고 결론을 내지 않는다. */
    private static final BigDecimal MIN_SCORE_RATIO = new BigDecimal("0.80");

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private AsOfCalendar calendar;

    @Autowired
    private ScreeningTargets targets;

    @Autowired
    private ScreenStore screenStore;

    @Autowired
    private IndicatorRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("커스텀 전략을 시점 재현으로 일별 스윕해 종목별 최초 통과일을 낸다")
    void sweepCustomScreensDaily() {
        List<ScreeningTargets.Target> allTargets = targets.all();
        assertThat(allTargets).as("조회 대상이 하나도 없다").isNotEmpty();

        Map<String, ScreenDefinition> screens = new LinkedHashMap<>();
        for (String name : SCREEN_NAMES) {
            screens.put(name, loadByName(name));
        }

        int sweptMarkets = 0;
        for (ScreeningTargets.Target target : allTargets) {
            sweptMarkets += sweepMarket(screens, target) ? 1 : 0;
        }
        assertThat(sweptMarkets)
                .as("어느 시장에서도 스윕하지 못했다 — 이 실행은 아무것도 재지 않았다")
                .isPositive();
    }

    /**
     * 시장 하나에서 <b>전 전략을 한 번에</b> 스윕한다.
     *
     * <p>전략별로 따로 돌지 않는 이유는 비용이 전부 값 계산에 있고 그것이 조건군과 무관하기
     * 때문이다 — 전략 둘을 따로 부르면 일별 격자에서 시간이 그대로 두 배가 된다.
     *
     * @return 실제로 스윕했는가. 통화축 거절이나 워밍업 부족은 실패가 아니라 <b>대상 아님</b>이다
     */
    private boolean sweepMarket(Map<String, ScreenDefinition> screens,
                                ScreeningTargets.Target target) {
        LocalDate latest = calendar.latestTradingDay(target.markets());
        LocalDate warmupFloor =
                calendar.earliestComputableAsOf(registry.maxEffectiveLookback(), target.markets());
        if (latest == null || warmupFloor == null) {
            System.out.printf("%n===== [%s] 시세가 워밍업(%d봉)을 못 채운다 — 대상 아님 =====%n",
                    target.code(), registry.maxEffectiveLookback());
            return false;
        }

        LocalDate from = maxOf(warmupFloor, resolveBound("sweep.from", latest.minusYears(1)));
        LocalDate to = minOf(latest, resolveBound("sweep.to", latest));

        // 통화축 거절은 전략마다 따로 본다 — 묶어서 삼키면 어느 전략이 거절됐는지가 사라진다.
        List<PointInTimeScreener.Request> requests = new ArrayList<>();
        for (Map.Entry<String, ScreenDefinition> e : screens.entrySet()) {
            try {
                e.getValue().conditions().resolveFor(target.currency());
            } catch (MarketThresholdMissingException ex) {
                System.out.printf("%n===== [%s · %s] 실행 거절 — %s 임계 없음: %s =====%n",
                        e.getKey(), target.code(), ex.currencyCode(),
                        String.join(" ", ex.indicatorCodes()));
                continue;
            }
            requests.add(new PointInTimeScreener.Request(
                    e.getKey(), e.getValue().conditions(), e.getValue().mode()));
        }
        if (requests.isEmpty()) {
            return false;
        }

        List<LocalDate> dates = calendar.everyTradingDay(from, to, target.markets());
        if (dates.isEmpty()) {
            System.out.printf("%n===== [%s] 구간에 거래일이 없다 =====%n", target.code());
            return false;
        }

        // 전략 → 종목 → 최초 통과. 규약이 "종목당 한 번"이라 뒤의 통과는 버린다.
        Map<String, Map<Long, Entry>> firstPass = new LinkedHashMap<>();
        requests.forEach(r -> firstPass.put(r.label(), new LinkedHashMap<>()));
        int evaluatedTotal = 0;

        for (LocalDate asOf : dates) {
            Map<String, PointInTimeScreener.Outcome> outcomes =
                    screener.screenAll(asOf, requests, target);
            for (Map.Entry<String, PointInTimeScreener.Outcome> e : outcomes.entrySet()) {
                evaluatedTotal += e.getValue().evaluated();
                Map<Long, Entry> acc = firstPass.get(e.getKey());
                for (ScreeningResult row : e.getValue().included()) {
                    if (row.scoreRatio().compareTo(MIN_SCORE_RATIO) < 0) {
                        continue;
                    }
                    acc.computeIfAbsent(row.stockId(),
                            id -> new Entry(asOf, row.score(), row.maxScore()));
                }
            }
        }

        System.out.printf("%n===== [%s] %s ~ %s · %d거래일 · 판정 시도 연인원 %d =====%n",
                target.code(), from, to, dates.size(), evaluatedTotal);
        for (Map.Entry<String, Map<Long, Entry>> e : firstPass.entrySet()) {
            System.out.printf("-- %s · 달성률 %s%% 이상 최초 통과 %d종목%n",
                    e.getKey(),
                    MIN_SCORE_RATIO.movePointRight(2).stripTrailingZeros().toPlainString(),
                    e.getValue().size());
            e.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(x -> System.out.printf("   %s  %-28s %s/%s%n",
                            x.getValue().asOf(), tickerOf(x.getKey()),
                            x.getValue().score().stripTrailingZeros().toPlainString(),
                            x.getValue().maxScore().stripTrailingZeros().toPlainString()));
        }

        // 0 == 0으로 초록이 되는 것을 막는다. 판정 시도가 0이면 잰 것이 없다.
        assertThat(evaluatedTotal)
                .as("%s: 판정 시도가 0이면 이 스윕은 아무것도 재지 않았다", target.code())
                .isPositive();
        return true;
    }

    /** 종목별 최초 통과 기록. {@code asOf} 오름차순이 자연 순서다 — 출력이 매수 순서가 된다. */
    private record Entry(LocalDate asOf, BigDecimal score, BigDecimal maxScore)
            implements Comparable<Entry> {
        @Override
        public int compareTo(Entry other) {
            return asOf.compareTo(other.asOf);
        }
    }

    /**
     * 이름 조각으로 사용자 화면을 찾는다.
     *
     * <p><b>id로 찾지 않는다</b> — 사용자가 화면을 지웠다 다시 만들면 id가 바뀌는데 이름은
     * 유지된다. 실제로 이 세션에서 v13이 그렇게 두 번 바뀌었다. 여럿이 걸리면 던진다:
     * 조용히 첫 번째를 고르면 <b>다른 전략의 표를 그 전략의 것으로 읽게 된다.</b>
     */
    private ScreenDefinition loadByName(String namePart) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM screen WHERE screen_type = 'CUSTOM' AND is_active"
                        + " AND lower(name) LIKE lower(?) ORDER BY id",
                Long.class, "%" + namePart + "%");
        if (ids.size() != 1) {
            throw new IllegalStateException(
                    "「" + namePart + "」에 걸리는 활성 CUSTOM 화면이 " + ids.size() + "개다(id=" + ids
                            + ") — 하나만 걸리게 이름을 좁힌다");
        }
        return screenStore.findById(ids.get(0)).orElseThrow(() -> new IllegalStateException(
                "screen id=" + ids.get(0) + "을 읽지 못했다"));
    }

    private String tickerOf(long stockId) {
        return jdbcTemplate.queryForObject(
                "SELECT ticker || ' ' || name FROM stock WHERE id = ?",
                String.class, stockId);
    }

    /** 시스템 프로퍼티가 있으면 그 날짜, 없으면 기본값. */
    private static LocalDate resolveBound(String key, LocalDate fallback) {
        String raw = System.getProperty(key);
        return (raw == null || raw.isBlank()) ? fallback : LocalDate.parse(raw.trim());
    }

    private static LocalDate maxOf(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate minOf(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
