package com.stockscreener.probe.backtest;

import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.MarketThresholdMissingException;
import com.stockscreener.screening.ScreeningResult;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>하네스가 어떤 전략을 재현할 수 있는가</b>를 전수로 잰다. 저장소 이전의 질문이다.
 *
 * <p>왜 원점인가: 저장소는 값의 <b>출처</b>만 바꾼다. 하네스가 애초에 재현 못 하는 전략은
 * 저장소를 붙여도 못 한다. 그런데 지금까지 하네스의 재현 능력은 <b>프리셋 하나</b>
 * ({@code AsOfRecomputeTest}의 버핏 우량주)로만 확인돼 있었고, 그 하나가 초록이라는 것이
 * <b>전부 된다</b>로 읽혔다.
 *
 * <p>기준일을 <b>저장된 마지막 거래일</b>로 두면 {@code KNOWN_ON}과 {@code LATEST}가 같은 답을
 * 내야 한다 — 그날 이후 공시가 없기 때문이다. 그러므로 여기서의 차이는 전부 <b>하네스의 재현
 * 한계</b>이고, 시점성이나 저장소와 무관하다.
 *
 * <p>MUST: 결과를 「몇 개가 맞았다」로 접지 않는다. <b>어느 전략이 어떤 지표에서</b> 갈리는지가
 * 다음 결정을 정한다 — 재현 가능한 축과 아닌 축을 가르는 것이 이 탐침의 산출물이다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class HarnessCoverageProbeTest {

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private ScreeningRepository screeningRepository;

    @Autowired
    private ScreeningTargets targets;

    @Autowired
    private List<ScreenPreset> presets;

    @Test
    @DisplayName("프리셋 전수에서 하네스가 라이브를 재현하는가 — 되는 것과 안 되는 것을 가른다")
    void measureReproducibilityAcrossAllPresets() {
        List<String> ok = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        List<String> notComparable = new ArrayList<>();
        Map<String, Integer> mismatchByCode = new TreeMap<>();

        for (ScreenPreset preset : presets) {
            for (ScreeningTargets.Target target : targets.all()) {
                String key = preset.code() + " · " + target.code();
                Verdict v = compareOne(preset, target, mismatchByCode);
                switch (v.kind()) {
                    case OK -> ok.add(key + " (" + v.detail() + ")");
                    case MISMATCH -> mismatched.add(key + " — " + v.detail());
                    case NOT_COMPARABLE -> notComparable.add(key + " — " + v.detail());
                }
            }
        }

        System.out.printf("%n===== 하네스 재현 능력 (프리셋 %d × 대상 %d) =====%n",
                presets.size(), targets.all().size());
        System.out.printf("일치 %d · 불일치 %d · 대조불가 %d%n",
                ok.size(), mismatched.size(), notComparable.size());

        System.out.println("\n-- 불일치 --");
        mismatched.forEach(m -> System.out.println("   " + m));
        System.out.println("\n-- 대조불가 (통과 0 등) --");
        notComparable.stream().limit(12).forEach(m -> System.out.println("   " + m));
        System.out.println("\n-- 일치 --");
        ok.forEach(m -> System.out.println("   " + m));

        if (!mismatchByCode.isEmpty()) {
            System.out.println("\n-- 불일치가 난 전략이 참조하는 교차단면 지표 --");
            mismatchByCode.forEach((c, n) -> System.out.printf("   %-28s %d%n", c, n));
        }

        assertThat(ok.size() + mismatched.size())
                .as("어느 조합도 대조하지 못했다 — 이 탐침은 아무것도 재지 않았다")
                .isPositive();
        // MUST: 불일치를 단언한다. 세어 출력만 하면 **어긋나도 초록**이고, 이 탐침을 회귀로
        // 쓰는 순간 그 초록이 「하네스가 라이브를 재현한다」로 읽힌다. 실제로 착수 시점에
        // 24 중 17만 맞았고, 그것을 고친 것이 이 파일의 존재 이유다.
        assertThat(mismatched)
                .as("하네스가 라이브를 재현하지 못하는 조합이 생겼다")
                .isEmpty();
    }

    private enum Kind { OK, MISMATCH, NOT_COMPARABLE }

    private record Verdict(Kind kind, String detail) {
    }

    private Verdict compareOne(ScreenPreset preset, ScreeningTargets.Target target,
                               Map<String, Integer> mismatchByCode) {
        if (target.asOf() == null) {
            return new Verdict(Kind.NOT_COMPARABLE, "지표 달력이 비었다");
        }
        ConditionGroup resolved;
        try {
            resolved = preset.conditions().resolveFor(target.currency());
        } catch (MarketThresholdMissingException e) {
            return new Verdict(Kind.NOT_COMPARABLE, e.currencyCode() + " 임계 없음");
        }
        LocalDate asOf = target.asOf();

        PointInTimeScreener.Outcome mine;
        try {
            mine = screener.screen(asOf, preset.conditions(), preset.mode(), target);
        } catch (RuntimeException e) {
            return new Verdict(Kind.MISMATCH, "하네스가 던졌다: " + e.getMessage());
        }
        List<ScreeningResult> live =
                screeningRepository.screen(resolved, preset.mode(), asOf, target.code());

        Map<Long, BigDecimal> a = new LinkedHashMap<>();
        mine.included().forEach(r -> a.put(r.stockId(), r.scoreRatio()));
        Map<Long, BigDecimal> b = new LinkedHashMap<>();
        live.forEach(r -> b.put(r.stockId(), r.scoreRatio()));

        if (a.isEmpty() && b.isEmpty()) {
            return new Verdict(Kind.NOT_COMPARABLE, "양쪽 통과 0");
        }

        Set<Long> onlyMine = new LinkedHashSet<>(a.keySet());
        onlyMine.removeAll(b.keySet());
        Set<Long> onlyLive = new LinkedHashSet<>(b.keySet());
        onlyLive.removeAll(a.keySet());
        int scoreDiff = 0;
        for (Map.Entry<Long, BigDecimal> e : a.entrySet()) {
            BigDecimal other = b.get(e.getKey());
            if (other != null && e.getValue().compareTo(other) != 0) {
                scoreDiff++;
            }
        }
        if (onlyMine.isEmpty() && onlyLive.isEmpty() && scoreDiff == 0) {
            return new Verdict(Kind.OK, "통과 " + a.size());
        }

        // 어느 교차단면 축을 쓰는 전략이 갈리는지 센다 — 그것이 다음 결정을 정한다.
        for (String code : resolved.referencedIndicatorCodes()) {
            if (code.endsWith("_PCTL") || code.contains("_SECTOR_")) {
                mismatchByCode.merge(code, 1, Integer::sum);
            }
        }
        return new Verdict(Kind.MISMATCH, String.format(
                "하네스 %d · 라이브 %d · 하네스만 %d · 라이브만 %d · 점수차 %d",
                a.size(), b.size(), onlyMine.size(), onlyLive.size(), scoreDiff));
    }
}
