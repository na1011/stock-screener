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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>유니버스 백분위 재구성이 라이브와 같은가</b>를 프리셋으로 확인한다.
 *
 * <p>왜 필요한가: 저장소는 <b>통화 단위</b>인데 유니버스 백분위는
 * {@code PercentileCalculator}가 {@code PARTITION BY universe_code}로 매긴다. 파일에 구워 넣을 수
 * 없어 <b>읽기 시점에 유니버스별로</b> 만들기로 했고, 그 재구성이 맞는지는 라이브와 견줘야만 안다.
 *
 * <p>SQL로 값을 직접 대조한 적은 있지만(불일치 0), <b>그것은 읽기 경로가 아니었다.</b> 여기서는
 * {@link PointInTimeScreener}가 실제로 재구성한 값으로 판정한 결과를 라이브 판정과 견준다.
 *
 * <p>기준일을 <b>저장된 마지막 거래일</b>로 두면 {@code KNOWN_ON}과 {@code LATEST}가 같은 답을
 * 내야 하므로(그날 이후 공시가 없다), 남는 차이는 <b>백분위 재구성의 결함뿐</b>이다.
 *
 * <p>MUST: {@code _PCTL}을 <b>실제로 참조하는</b> 프리셋을 쓴다. 안 쓰는 프리셋으로 돌리면
 * 재구성 코드가 아예 안 불려 초록이 아무것도 뜻하지 않는다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class UniversePercentileProbeTest {

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private ScreeningRepository screeningRepository;

    @Autowired
    private ScreeningTargets targets;

    @Autowired
    private List<ScreenPreset> presets;

    @Test
    @DisplayName("_PCTL을 쓰는 프리셋에서 재구성 결과가 라이브 판정과 같다")
    void reconstructedUniversePercentilesMatchLive() {
        List<ScreenPreset> withPctl = presets.stream()
                .filter(p -> p.conditions().referencedIndicatorCodes().stream()
                        .anyMatch(c -> c.endsWith("_PCTL") && !c.endsWith("_SECTOR_PCTL")))
                .toList();
        assertThat(withPctl)
                .as("_PCTL을 참조하는 프리셋이 없다 — 이 검사는 아무것도 확인하지 않는다")
                .isNotEmpty();

        int compared = 0;
        for (ScreenPreset preset : withPctl) {
            for (ScreeningTargets.Target target : targets.all()) {
                compared += compareOne(preset, target) ? 1 : 0;
            }
        }
        assertThat(compared).as("어느 조합에서도 대조하지 못했다").isPositive();
    }

    private boolean compareOne(ScreenPreset preset, ScreeningTargets.Target target) {
        if (target.asOf() == null) {
            return false;
        }
        ConditionGroup resolved;
        try {
            resolved = preset.conditions().resolveFor(target.currency());
        } catch (MarketThresholdMissingException e) {
            System.out.printf("%n[%s · %s] 실행 거절 — %s 임계 없음%n",
                    preset.code(), target.code(), e.currencyCode());
            return false;
        }
        LocalDate asOf = target.asOf();

        PointInTimeScreener.Outcome recomputed =
                screener.screen(asOf, preset.conditions(), preset.mode(), target);
        List<ScreeningResult> live =
                screeningRepository.screen(resolved, preset.mode(), asOf, target.code());

        Map<Long, BigDecimal> mine = new LinkedHashMap<>();
        recomputed.included().forEach(r -> mine.put(r.stockId(), r.scoreRatio()));
        Map<Long, BigDecimal> theirs = new LinkedHashMap<>();
        live.forEach(r -> theirs.put(r.stockId(), r.scoreRatio()));

        Set<String> diffs = new TreeSet<>();
        Set<Long> onlyMine = new LinkedHashSet<>(mine.keySet());
        onlyMine.removeAll(theirs.keySet());
        Set<Long> onlyTheirs = new LinkedHashSet<>(theirs.keySet());
        onlyTheirs.removeAll(mine.keySet());
        onlyMine.forEach(id -> diffs.add("재구성만 " + id));
        onlyTheirs.forEach(id -> diffs.add("라이브만 " + id));
        for (Map.Entry<Long, BigDecimal> e : mine.entrySet()) {
            BigDecimal other = theirs.get(e.getKey());
            if (other != null && e.getValue().compareTo(other) != 0) {
                diffs.add("점수 " + e.getKey() + " 재구성=" + e.getValue() + " 라이브=" + other);
            }
        }

        System.out.printf("%n[%s · %s · %s] 재구성 통과 %d · 라이브 통과 %d · 차이 %d%n",
                preset.code(), target.code(), asOf, mine.size(), theirs.size(), diffs.size());
        diffs.stream().limit(10).forEach(d -> System.out.println("   " + d));

        // 0 == 0으로 초록이 되는 것을 막는다.
        if (theirs.isEmpty() && mine.isEmpty()) {
            System.out.println("   양쪽 0 — 이 조합은 대조가 아니다");
            return false;
        }
        assertThat(diffs)
                .as("%s · %s: 유니버스 백분위 재구성이 라이브와 다르다", preset.code(), target.code())
                .isEmpty();
        return true;
    }
}
