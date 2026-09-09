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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 커스텀 전략을 <b>시점 재현으로</b> 일별 스윕해 최초 통과일을 낸다.
 *
 * <p><b>왜 저장된 지표를 안 쓰는가.</b> 두 근거 중 둘 다 2026-09-08에 바뀌었다 — 저장 경로가
 * {@code KNOWN_ON}으로 옮겨졌고(ADR-0128) 보존 창이 255거래일이 되어 1년을 덮는다(ADR-0129).
 * <b>남는 이유는 하나뿐이다</b>: 엔진 전 구간이 as-of당 84~90ms라 510 as-of면 약 44초이고
 * 이 저장소의 약 25초와 같은 자릿수다(2026-09-09 실측). 창이 미끄러지는 것이 막는 것은
 * <b>옛 분석의 재현</b>이고 이 하네스가 답하는 물음은 롤링 1년이다.
 * <b>저장 경로로 옮길지는 별도 결정이다</b> — 여기서 조용히 바꾸지 않는다.
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
 * <pre>caffeinate -is ./gradlew backtestLoad</pre>
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class CustomScreenSweepTest {

    /**
     * 스윕할 전략. 이름의 일부이며 사용자 소유 `CUSTOM` 화면을 가리킨다.
     * {@code -Dsweep.screens=v12,v13}으로 갈아끼운다.
     */
    private static List<String> screenNames() {
        return csv("sweep.screens", List.of("v12", "v13"));
    }

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
        // 시장을 좁혀 달라고 했으면 그것만 본다. **판정 모집단은 안 좁힌다** — 아래 참조.
        List<String> wanted = csv("sweep.targets", List.of());
        List<ScreeningTargets.Target> allTargets = targets.all().stream()
                .filter(t -> wanted.isEmpty() || wanted.contains(t.code()))
                .toList();
        assertThat(allTargets)
                .as("조회 대상이 하나도 없다 — -Dsweep.targets=%s 가 아는 이름인가", wanted)
                .isNotEmpty();

        JudgementProvenance provenance = new JudgementProvenance(jdbcTemplate);
        List<PointInTimeScreener.Request> requests = new ArrayList<>();
        Map<String, Long> screenIds = new LinkedHashMap<>();
        for (String name : screenNames()) {
            long id = screenIdOf(name);
            screenIds.put(name, id);
            ScreenDefinition screen = screenStore.findById(id).orElseThrow();
            requests.add(new PointInTimeScreener.Request(
                    name, screen.conditions(), screen.mode()));
        }

        PitStore store = PitStore.fromEnvironment();
        PitSweep sweep = new PitSweep(store, new PitChangeDetector(jdbcTemplate),
                new PitFingerprint(jdbcTemplate), screener, runner, calendar, registry);

        long t0 = System.nanoTime();
        PitSweep.Result result = sweep.sweep(requests, allTargets, MIN_SCORE_RATIO);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        // MUST: 언제·무엇을 읽었는지를 표 안에 남긴다. 시간이 지나면 창 미완이 저절로 닫혀
        // 판정이 바뀌므로, 표만 보고는 그것이 언제 기준인지 알 수 없다.
        System.out.printf("%n===== 저장본 스윕 %,dms · %s =====%n", ms, java.time.LocalDateTime.now()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        System.out.printf("구간 %s · 다시 만든 칸 %d%n", result.rangeText(), result.repairedCells());

        // MUST: 판정이 무엇 위에서 나왔는지 표와 함께 남긴다. 저장본은 안 낡았는데 이것들이
        // 바뀌면 표가 바뀌고, 어느 것도 이력 표가 없어 사후에 알 방법이 없다.
        JudgementProvenance.Snapshot provenanceNow = provenance.capture();
        System.out.printf("업종 키 %s · 편입 %s · 소분류 미달 종목 %s · 달성률 문턱 %s%%%n",
                provenanceNow.sectorKeyFingerprint().substring(0, 8),
                provenanceNow.membershipFingerprint().substring(0, 8),
                provenanceNow.fallbackStocks(),
                MIN_SCORE_RATIO.movePointRight(2).stripTrailingZeros().toPlainString());
        for (Map.Entry<String, Long> e : screenIds.entrySet()) {
            List<String> conditions = provenance.describe(e.getValue());
            System.out.printf("-- %s (screen id=%d · 조건 %d개) --%n",
                    e.getKey(), e.getValue(), conditions.size());
            conditions.forEach(c -> System.out.println("   " + c));
        }
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
            System.out.println("   " + pad("매수일", 11) + pad("종목", 27) + pad("달성률", 8)
                    + pad("결과", 10) + pad("소요일", 8) + pad("최고수익", 10)
                    + pad("최대하락", 10) + "비고");
            List<HoldingOutcome.Result> outcomes = new ArrayList<>();
            // MUST: 티커 좁히기는 **출력에서만** 한다. 판정 모집단을 좁히면 업종·유니버스
            // 백분위의 동료 집합이 달라져 **같은 종목의 달성률이 달라진다** — 전체를 돌린 표와
            // 다른 수가 나오고 그것은 조용한 오답이다.
            List<String> onlyTickers = csv("sweep.tickers", List.of());
            e.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .filter(x -> onlyTickers.isEmpty()
                            || onlyTickers.contains(tickerOf(x.getKey()).split(" ")[0]))
                    .forEach(x -> {
                        HoldingOutcome.Result r = holding.measure(x.getKey(), x.getValue().asOf());
                        outcomes.add(r);
                        System.out.println("   "
                                + pad(r.entry().toString(), 11)
                                + pad(abbreviate(tickerOf(x.getKey()), 26), 27)
                                + pad(percent(ratioOf(x.getValue())), 8)
                                + pad(String.valueOf(r.verdict()), 10)
                                + pad(r.daysToTarget() == null ? "-" : r.daysToTarget() + "일", 8)
                                + pad(percent(r.maxGainPct()), 10)
                                + pad(percent(r.maxDrawPct()), 10)
                                + note(r));
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
            // MUST: 0일 때도 찍는다. 안 찍으면 「창 미완 없음」과 「그 줄을 안 봤다」가
            // 구분되지 않는다.
            System.out.printf("   → 창이 아직 안 닫힌 종목 %d · 보유 창에 미보정 기업행위가 "
                            + "걸린 종목 %d%n", summary.truncated(), summary.corporateGaps());
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
    private long screenIdOf(String namePart) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM screen WHERE screen_type = 'CUSTOM' AND is_active"
                        + " AND lower(name) LIKE lower(?) ORDER BY id",
                Long.class, "%" + namePart + "%");
        if (ids.size() != 1) {
            throw new IllegalStateException(
                    "「" + namePart + "」에 걸리는 활성 CUSTOM 화면이 " + ids.size() + "개다(id=" + ids
                            + ") — 하나만 걸리게 이름을 좁힌다");
        }
        return ids.get(0);
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

    /**
     * <b>터미널 표시 폭</b> 기준 왼쪽 정렬.
     *
     * <p>MUST NOT: {@code %-Ns}로 표를 맞추지 않는다. 그것은 <b>문자 수</b>로 패딩하는데
     * 한글·전각은 한 글자가 <b>두 칸</b>을 차지한다. 그래서 「성공」(2자·4칸)과
     * 「부분성공」(4자·8칸)이 같은 폭으로 채워져 <b>줄마다 열이 어긋난다</b> — 헤더가 전부
     * 한글이라 첫 줄부터 밀리고, 국내 종목명은 폭이 두 배라 더 심하다.
     */
    private static String pad(String s, int width) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += wide(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return w >= width ? s : s + " ".repeat(width - w);
    }

    /** 두 칸을 차지하는 문자인가. 한글 음절·자모, CJK, 전각 기호가 그렇다. */
    private static boolean wide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6);
    }

    /**
     * 표시 폭이 {@code width}를 넘지 않게 자른다. <b>길이로 자르면 안 된다</b> — 국내
     * 종목명은 한 글자가 두 칸이라 26자가 52칸이 되어 뒤 열이 통째로 밀린다.
     */
    private static String abbreviate(String name, int width) {
        int w = 0;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            int cw = wide(cp) ? 2 : 1;
            if (w + cw > width - 1) {
                return out.append('…').toString();
            }
            out.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /** 쉼표로 나눈 스위치. 없으면 기본값. */
    private static List<String> csv(String key, List<String> fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return java.util.Arrays.stream(raw.split(",")).map(String::trim)
                .filter(x -> !x.isEmpty()).toList();
    }

    private String tickerOf(long stockId) {
        return jdbcTemplate.queryForObject(
                "SELECT ticker || ' ' || name FROM stock WHERE id = ?",
                String.class, stockId);
    }

}
