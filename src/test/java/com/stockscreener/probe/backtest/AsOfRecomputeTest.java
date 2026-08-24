package com.stockscreener.probe.backtest;

import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.query.ScreeningRepository;
import com.stockscreener.screening.preset.PresetCatalog;
import com.stockscreener.screening.preset.ScreenPreset;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>시점 경로가 라이브 경로를 오염시키지 않았는가</b>를 회귀로 잡는다(ADR-0071 §검증).
 *
 * <p>ADR-0071이 이 검사를 지목한 이유는 이 phase가 만드는 위험이 <b>재무를 읽는 경로가
 * 둘이 된다</b>는 것이기 때문이다. 둘이 어긋나면 화면과 백테스트가 다른 종목을 고르는데
 * <b>어느 쪽도 에러를 내지 않는다.</b>
 *
 * <p>기준일을 <b>저장된 마지막 거래일</b>로 두면 {@code KNOWN_ON}과 {@code LATEST}가 같은 답을
 * 내야 한다 — 그날 이후의 공시가 없으므로 "그때 알던 것"과 "지금 아는 것"이 일치한다.
 * 즉 이 시점에서의 차이는 전부 <b>경로의 결함</b>이다.
 *
 * <p>MUST: 수가 아니라 <b>집합</b>을 견준다. 수만 맞고 종목이 다른 경우가 실제로 가장 흔한
 * 오염이며, 수를 세는 검사는 그것을 통과시킨다(Phase 7 U-05가 같은 이유로 집합을 본다).
 *
 * <p><b>이 테스트는 운영 DB의 실데이터를 필요로 한다.</b> 테스트 DB는 전 테이블이 0행이라
 * 거기서는 0집합 = 0집합으로 <b>언제나 초록</b>이 된다 — 그래서 {@code @Tag("backtest")}다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class AsOfRecomputeTest {

    @Autowired
    private PointInTimeScreener screener;

    @Autowired
    private AsOfCalendar calendar;

    @Autowired
    private List<ScreenPreset> presets;

    /**
     * 대조군이 부르는 라이브 경로. <b>이 필드가 이 클래스의 유일한 {@code indicator_value}
     * 접점이고, 그것이 의도다</b> — 하네스({@link PointInTimeScreener})에는 없어야 한다.
     */
    @Autowired
    private ScreeningRepository screeningRepository;

    @Test
    @DisplayName("기준일=최신 거래일의 통과 집합이 저장된 지표로 낸 것과 같다")
    void recomputedSetMatchesStoredIndicatorSet() {
        LocalDate asOf = calendar.latestTradingDay();
        ScreenPreset preset = presetByCode(PresetCatalog.BUFFETT_QUALITY_KR);

        // 프리셋이 자기 판정 방식과 유니버스를 들고 있다. 여기서 다시 정하면 화면과 갈린다.
        PointInTimeScreener.Outcome recomputed = screener.screen(
                asOf, preset.conditions(), preset.mode(), preset.universeCode());
        List<ScreeningResult> storedResults = screeningRepository.screen(
                preset.conditions(), preset.mode(), asOf, preset.universeCode());
        Set<Long> fromRecompute = idsOf(recomputed.included());
        Set<Long> fromStored = idsOf(storedResults);

        System.out.printf("%n===== as-of 재계산 회귀 (%s) =====%n", asOf);
        System.out.printf("판정 시도 %d종목%n", recomputed.evaluated());
        System.out.printf("재계산 통과 %d · 저장 지표 통과 %d%n",
                fromRecompute.size(), fromStored.size());

        Set<Long> onlyRecomputed = new TreeSet<>(fromRecompute);
        onlyRecomputed.removeAll(fromStored);
        Set<Long> onlyStored = new TreeSet<>(fromStored);
        onlyStored.removeAll(fromRecompute);
        if (!onlyRecomputed.isEmpty() || !onlyStored.isEmpty()) {
            System.out.println("재계산에만 있음: " + onlyRecomputed);
            System.out.println("저장 지표에만 있음: " + onlyStored);
        }

        // 0 == 0으로 초록이 되는 것을 막는다. 그 상태는 "경로가 같다"가 아니라 "못 쟀다"이다.
        assertThat(recomputed.evaluated())
                .as("판정 대상이 0이면 이 검사는 아무것도 확인하지 않는다")
                .isPositive();
        assertThat(fromStored)
                .as("저장 지표로도 통과가 0이면 대조군이 없다 — 최신 거래일에 지표가 채워져 있는지 본다")
                .isNotEmpty();
        assertThat(fromRecompute)
                .as("기준일이 최신 거래일이면 KNOWN_ON과 LATEST가 같은 답을 내야 한다."
                        + " 다르면 시점 경로가 라이브 경로와 갈린 것이다")
                .isEqualTo(fromStored);

        assertScoresMatch(recomputed.included(), storedResults);
    }

    /**
     * <b>집합만 견주면 배점 조건이 검사되지 않는다.</b> SCORING의 포함 여부는
     * {@code satisfiesRequired}가 정하므로 <b>필수 게이트만</b> 보고, 배점 열은 집합을
     * 한 종목도 바꾸지 않는다. 즉 위 단언이 통과해도 배점 쪽은 아무것도 확인되지 않는다.
     *
     * <p>그중 {@code PER_SECTOR_PCTL}이 특히 그렇다 — 그 값은 {@code computeAt}이 내지 않아
     * {@link PointInTimeScreener}가 {@code PERCENT_RANK}를 <b>손으로 다시 구현한 것</b>이고,
     * 동률 처리나 층 선택({@code MIN_PEERS})이 어긋나도 점수만 조금 달라진다. 점수를 견주는
     * 이 단언이 그 재구현의 유일한 검사다.
     */
    private static void assertScoresMatch(List<ScreeningResult> recomputed,
                                          List<ScreeningResult> stored) {
        Map<Long, BigDecimal> storedScores = stored.stream().collect(
                java.util.stream.Collectors.toMap(ScreeningResult::stockId, ScreeningResult::score));
        for (ScreeningResult result : recomputed) {
            BigDecimal expected = storedScores.get(result.stockId());
            assertThat(result.score())
                    .as("stockId=%d의 점수가 갈렸다 — 배점 조건(특히 업종 백분위 재구현)이"
                            + " 라이브와 다른 값을 냈다", result.stockId())
                    .isEqualByComparingTo(expected);
        }
        System.out.printf("점수도 %d종목 전부 일치한다 — 업종 백분위 재구현이 라이브와 같다%n",
                recomputed.size());
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
