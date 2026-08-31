package com.stockscreener.probe.backtest;

import com.stockscreener.screening.MarketThresholdMissingException;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.EvaluationMode;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>같은 프리셋이 대상만 바꿔 국내와 미국에서 도는가</b>(ADR-0107).
 *
 * <p>사용자 요구를 그대로 옮긴 시험이다 — <i>"하나의 프리셋에서 국내와 미국 유니버스를 골라
 * 따로따로 스크리닝만 할 수 있으면 돼"</i>(2026-08-31). 무엇이 몇 건 나오는지가 아니라
 * <b>실행이 되는지</b>를 묻는다. 통과 수는 임계가 정하고 임계는 사용자 결정이라
 * (ADR-0044·0061) 여기서 단정하지 않고 <b>표로 낸다.</b>
 *
 * <p><b>운영 DB를 읽는다</b>({@code backtest} 프로파일). 통과 수는 실데이터에서만 뜻이 있고,
 * 테스트 DB는 편입이 0건이라 이 질문 자체가 성립하지 않는다. 그래서 {@code @Tag("backtest")}다
 * — {@code ./gradlew test}는 이것을 안 돌린다.
 *
 * <p><b>지표를 다시 계산하지 않는다.</b> {@link PointInTimeScreener}가 아니라
 * {@link ScreeningRepository}를 부르는 이유가 그것이다 — 라이브 화면이 <b>저장된</b>
 * {@code indicator_value}를 읽으므로, 다시 계산하면 화면과 다른 표가 나온다.
 *
 * <p><b>실패하는 유일한 방식이 「실행이 거절된다」이다.</b> 임계에 그 시장의 값이 없으면
 * {@link MarketThresholdMissingException}이 나고, 그것이 곧 「그 프리셋은 그 대상에서 못 쓴다」다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class PresetAcrossUniversesTest {

    @Autowired
    private List<ScreenPreset> presets;

    @Autowired
    private ScreeningRepository screeningRepository;

    @Autowired
    private ScreenStore screenStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 돌릴 전략 하나. <b>프리셋과 저장된 화면을 같은 자리에서 본다.</b>
     *
     * <p>프리셋만 재면 절반만 재는 것이다 — 사용자가 저장한 화면도 같은 조건 모델을 쓰고
     * 같은 통화 해석을 거치는데, 그쪽이 깨져도 프리셋 표는 초록으로 나온다.
     */
    private record Strategy(String label, EvaluationMode mode, ConditionGroup conditions) {
    }

    /** 화면의 조회 대상 콤보와 <b>같은 술어</b>다 — 활성이고 편입이 있는 것. */
    private record Target(String code, String name, String currency, LocalDate asOf, int members) {
    }

    @Test
    @DisplayName("활성 프리셋이 모든 조회 대상에서 실행된다 — 통과 수를 표로 낸다")
    void everyPresetRunsInEveryUniverse() {
        List<Target> targets = jdbcTemplate.query("""
                SELECT u.code, u.name, mk.currency_code,
                       (SELECT max(d.trade_date) FROM indicator_trade_date d
                         WHERE d.market_code = u.market_code) AS as_of,
                       count(m.stock_id) AS members
                FROM universe u
                JOIN market mk ON mk.code = u.market_code
                JOIN universe_membership m ON m.universe_code = u.code AND m.effective_to IS NULL
                WHERE u.is_active
                GROUP BY u.code, u.name, mk.currency_code, u.market_code
                ORDER BY u.code
                """, (rs, n) -> new Target(rs.getString("code"), rs.getString("name"),
                rs.getString("currency_code"), rs.getObject("as_of", LocalDate.class),
                rs.getInt("members")));

        assertThat(targets)
                .as("조회 대상이 하나도 없다 — 유니버스가 비었거나 편입이 없다")
                .isNotEmpty();

        List<String> unrunnable = new ArrayList<>();
        StringBuilder table = new StringBuilder("\n프리셋 × 조회 대상 — 통과 수\n");

        for (Target target : targets) {
            table.append("\n[%s] %s · %s · %s · 편입 %d\n"
                    .formatted(target.code(), target.name(), target.currency(),
                            target.asOf(), target.members()));
            if (target.asOf() == null) {
                // 그 시장의 지표 달력이 비었다. 실행 불가가 아니라 「아직 준비 안 됨」이고,
                // 라이브에서는 503이다. 통과 0과 구분해서 적는다.
                table.append("  (지표 달력이 비어 있다 — 라이브에서는 503이다)\n");
                continue;
            }
            for (Strategy strategy : strategies()) {
                try {
                    List<ScreeningResult> results = screeningRepository.screen(
                            strategy.conditions().resolveFor(target.currency()),
                            strategy.mode(), target.asOf(), target.code());
                    // 이 수는 **필수 게이트를 통과한 종목 수**다. 모드에 따라 뜻이 갈린다 —
                    // FILTER는 곧 결과이고, SCORING은 순위에 오른 모집단이다.
                    //
                    // MUST NOT: 「필수 게이트가 없는 프리셋의 편입 전량」을 결함으로 읽지 않는다.
                    // VALUE·LOWVOL_QUALITY가 실제로 그렇다(필수 조건 0개 — 실측) — 순위를
                    // 매기는 것이 목적인 프리셋이라 전 종목이 오르는 것이 설계다.
                    long included = results.stream().filter(ScreeningResult::included).count();
                    table.append("  %-32s 필수통과 %5d / 편입 %-5d\n".formatted(
                            strategy.label(), included, target.members()));
                } catch (MarketThresholdMissingException e) {
                    table.append("  %-32s 실행 거절 — %s 임계 없음: %s\n".formatted(
                            strategy.label(), e.currencyCode(),
                            String.join(" ", e.indicatorCodes())));
                    unrunnable.add(strategy.label() + " @ " + target.code());
                }
            }
        }

        System.out.println(table);

        // 저장된 화면에 그 시장 임계가 없는 것은 **사용자가 채울 몫**이라 여기서 붉히지 않는다
        // (임계를 정하는 것이 사용자 결정이다 — ADR-0044·0061). 프리셋은 저장소 책임이라 붉힌다.
        assertThat(unrunnable.stream().filter(it -> !it.startsWith(SAVED)).toList())
                .as("이 프리셋들이 그 대상에서 실행되지 않는다 — 임계를 그 시장 분포로 재서 "
                        + "채운다(ADR-0073 §규칙). 비우고 넘어가면 「미국을 포기한 것」이 된다")
                .isEmpty();
    }

    private static final String SAVED = "[저장] ";

    /** 활성 프리셋 + 활성 저장 화면. 둘 다 같은 조건 모델·같은 통화 해석을 거친다. */
    private List<Strategy> strategies() {
        List<Strategy> all = new ArrayList<>(presets.stream()
                .map(p -> new Strategy(p.code(), p.mode(), p.conditions()))
                .toList());
        List<Long> savedIds = jdbcTemplate.queryForList(
                """
                SELECT id FROM screen WHERE is_active AND screen_type = 'CUSTOM' ORDER BY id
                """, Long.class);
        for (Long id : savedIds) {
            screenStore.findById(id).ifPresent(d ->
                    all.add(new Strategy(SAVED + d.name(), d.mode(), d.conditions())));
        }
        return all;
    }
}
