package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorRegistry;
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
import java.util.ArrayList;
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
 * <p><b>값은 저장소에서 읽는다</b>({@link PitSweep}). 직접 계산하면 1년치가 약 4.5시간인데 —
 * KRW 모집단 350을 코스피·코스닥이 각각 한 번씩 돌아 두 배가 되기 때문이다 — 저장본을 읽으면
 * 날짜당 0.14초다(실측). 전략을 여러 번 고쳐 보는 것이 이 스윕의 용도이므로 그 차이가 곧
 * 쓸 수 있느냐를 가른다.
 *
 * <p>저장소가 비어 있으면 <b>던진다.</b> 조용히 직접 계산으로 떨어지면 몇 시간짜리 실행이
 * 아무 경고 없이 시작된다. 적재는 {@code PitLoadTest}가 진다:
 *
 * <pre>caffeinate -is ./gradlew backtestProbe --tests "*PitLoadTest*"</pre>
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
    private com.stockscreener.indicator.IndicatorRunner runner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("저장본으로 커스텀 전략을 일별 스윕해 종목별 최초 통과일을 낸다")
    void sweepCustomScreensDaily() throws Exception {
        List<ScreeningTargets.Target> allTargets = targets.all();
        assertThat(allTargets).as("조회 대상이 하나도 없다").isNotEmpty();

        List<PointInTimeScreener.Request> requests = new ArrayList<>();
        for (String name : SCREEN_NAMES) {
            ScreenDefinition screen = loadByName(name);
            requests.add(new PointInTimeScreener.Request(
                    name, screen.conditions(), screen.mode()));
        }

        PitStore store = PitStore.fromEnvironment();
        PitSweep sweep = new PitSweep(store, new PitChangeDetector(jdbcTemplate),
                new PitFingerprint(jdbcTemplate), screener, runner, calendar, registry);

        long t0 = System.nanoTime();
        PitSweep.Result result = sweep.sweep(requests, allTargets, MIN_SCORE_RATIO);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("%n===== 저장본 스윕 %,dms · 읽은 날짜 %s · 다시 만든 칸 %d =====%n",
                ms, result.datesByCcy(), result.repairedCells());
        if (!result.rejections().isEmpty()) {
            System.out.println("-- 거절 (통과 0과 다르다) --");
            result.rejections().forEach((k, v) -> System.out.printf("   %-32s %s%n", k, v));
        }
        HoldingOutcome holding = new HoldingOutcome(jdbcTemplate);
        for (Map.Entry<String, Map<Long, PitSweep.FirstPass>> e
                : result.firstPasses().entrySet()) {
            System.out.printf("%n-- %s · 달성률 %s%% 이상 최초 통과 %d종목%n", e.getKey(),
                    MIN_SCORE_RATIO.movePointRight(2).stripTrailingZeros().toPlainString(),
                    e.getValue().size());
            if (e.getValue().isEmpty()) {
                continue;
            }
            System.out.printf("   %-10s %-26s %-6s %-8s %-7s %-8s %-8s %s%n",
                    "매수일", "종목", "달성률", "결과", "소요일", "최고수익", "최대하락", "비고");
            List<HoldingOutcome.Result> outcomes = new ArrayList<>();
            e.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(x -> {
                        HoldingOutcome.Result r = holding.measure(x.getKey(), x.getValue().asOf());
                        outcomes.add(r);
                        System.out.printf("   %-10s %-26s %-6s %-8s %-7s %-8s %-8s %s%n",
                                r.entry(), abbreviate(tickerOf(x.getKey())),
                                percent(ratioOf(x.getValue())), r.verdict(),
                                r.daysToTarget() == null ? "-" : r.daysToTarget() + "일",
                                percent(r.maxGainPct()), percent(r.maxDrawPct()),
                                note(r));
                    });
            HoldingOutcome.Summary summary = HoldingOutcome.Summary.of(outcomes);
            System.out.printf("   → 성공 %d · 부분성공 %d · 손절 %d%s · 목표 달성일 평균 %s · "
                            + "기록 수익률 평균 %s%n",
                    summary.success(), summary.partial(), summary.loss(),
                    summary.noBars() > 0 ? " · 봉없음 " + summary.noBars() : "",
                    summary.avgDaysToTarget() == null
                            ? "-" : String.format("%.0f일", summary.avgDaysToTarget()),
                    summary.avgReturnPct() == null
                            ? "-" : String.format("%+.1f%%", summary.avgReturnPct() * 100));
            if (summary.truncated() > 0 || summary.corporateGaps() > 0) {
                System.out.printf("   → 창이 아직 안 닫힌 종목 %d · 보유 창에 미보정 기업행위가 "
                                + "걸린 종목 %d%n", summary.truncated(), summary.corporateGaps());
            }
        }

        // 0 == 0으로 초록이 되는 것을 막는다. 읽은 날짜가 0이면 잰 것이 없다.
        assertThat(result.datesByCcy().values().stream().mapToInt(Integer::intValue).sum())
                .as("어느 통화에서도 저장 날짜를 읽지 못했다 — 적재부터 돌린다")
                .isPositive();
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

    /** 규약 §2의 기록값이 아니라 **화면의 달성률**이다 — 진입 자격을 보이는 수다. */
    private static BigDecimal ratioOf(PitSweep.FirstPass pass) {
        return pass.maxScore().signum() == 0 ? BigDecimal.ONE
                : pass.score().divide(pass.maxScore(), 4, java.math.RoundingMode.HALF_UP);
    }

    private static String percent(BigDecimal value) {
        return value == null ? "-"
                : String.format("%+.1f%%", value.multiply(new BigDecimal("100")).doubleValue());
    }

    /** 봉이 모자라거나 기업행위가 낀 것은 <b>숫자 옆에 적는다.</b> 안 적으면 그냥 성적이 된다. */
    private static String note(HoldingOutcome.Result r) {
        List<String> notes = new ArrayList<>();
        if (r.truncated()) {
            notes.add("창 미완");
        }
        if (r.corporateGap()) {
            notes.add("미보정 기업행위");
        }
        return String.join(" ", notes);
    }

    private static String abbreviate(String name) {
        return name.length() <= 26 ? name : name.substring(0, 25) + "…";
    }

    private String tickerOf(long stockId) {
        return jdbcTemplate.queryForObject(
                "SELECT ticker || ' ' || name FROM stock WHERE id = ?",
                String.class, stockId);
    }

}
