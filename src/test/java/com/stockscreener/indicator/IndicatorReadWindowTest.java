package com.stockscreener.indicator;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 읽기 창이 <b>보존 창을 한 번에 채울 만큼</b> 넓은가(ADR-0079).
 *
 * <p>계산기는 워밍업 W일 때 {@code bars.size() − W + 1}개 포인트를 낸다. 러너가 읽어오는 봉이
 * 정확히 <b>가장 긴 워밍업</b>이면 그 지표는 <b>{@code asOf}당 한 일자</b>밖에 못 내고, 보존 창은
 * 하루에 하나씩 보존 일수만큼 실행해야 채워진다. 실제로 {@code EPS_CAGR_5Y}가 그 상태로
 * 172일자를 비운 채 있었다.
 *
 * <p>IMPORTANT — <b>이 회귀는 되돌려도 아무 에러가 안 난다.</b> 러너를
 * {@link IndicatorRegistry#maxEffectiveLookback()}으로 되돌리면 값은 여전히 옳고 배치도 SUCCESS로
 * 끝나며, 화면은 <b>최신 일자만</b> 멀쩡하다. 드러나는 것은 과거 일자를 고른 사용자에게
 * <b>이유 없는 0건</b>뿐이다. 그래서 이 단언이 필요하다.
 *
 * <p>러너에 행동 테스트를 걸지 않은 이유: {@link IndicatorRunnerTest}는 진짜 {@code JdbcTemplate}을
 * 쓰고 테스트 더블이 없어 리더에 닿을 이음매가 없으며, 픽스처가 창보다 짧아 <b>창을 좁혀도 값이
 * 그대로다</b>({@code IchimokuIndicator} javadoc이 같은 한계를 적어 두었다). 레지스트리 단언이
 * 같은 회귀를 훨씬 싸게 잡는다.
 */
class IndicatorReadWindowTest {

    /**
     * 합성 레지스트리로 <b>산식 자체</b>를 못박는다.
     *
     * <p>동어반복이 아니다 — 누가 {@code readWindowBars()}를 {@code max + 90}처럼 보존 창보다 작은
     * 값으로 바꾸면 여기서 떨어진다.
     */
    @Test
    @DisplayName("읽기 창은 가장 긴 워밍업 위에 보존 일수만큼을 더 얹는다")
    void readWindowLeavesRoomForTheWholeRetentionWindow() {
        IndicatorRegistry registry = new IndicatorRegistry(List.of(
                stub("MACD_V", DataRequirement.derivedFrom(10, "MACD", "ATR")),
                stub("MACD", DataRequirement.priceBars(35)),
                stub("ATR", DataRequirement.priceBars(14))));

        assertThat(registry.maxEffectiveLookback())
                .as("워밍업 최댓값의 뜻은 바뀌지 않는다 — 그것은 억제 창의 상한이기도 하다")
                .isEqualTo(45);

        int outputDates = registry.readWindowBars() - registry.maxEffectiveLookback() + 1;
        assertThat(outputDates)
                .as("가장 긴 워밍업 지표도 한 번 실행으로 보존 창 %d거래일을 채워야 한다",
                        IndicatorRetentionPolicy.RETAINED_TRADING_DAYS)
                .isGreaterThanOrEqualTo(IndicatorRetentionPolicy.RETAINED_TRADING_DAYS);
    }

    private static IndicatorCalculator stub(String code, DataRequirement requirement) {
        return new IndicatorCalculator() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public DataRequirement dataRequirement() {
                return requirement;
            }

            @Override
            public List<IndicatorPoint> calculate(IndicatorContext context) {
                return List.of();
            }
        };
    }

    /**
     * 진짜 레지스트리로 <b>다음에 최댓값이 되는 지표</b>까지 잡는다.
     *
     * <p>위 합성 단언은 산식만 지킨다. 굶는 것은 언제나 <b>그때 최댓값을 정하는 지표</b>이고
     * 그것이 누구인지는 지표를 하나 들일 때마다 바뀌므로, 코드 목록을 손으로 적지 않고 전수로 돈다.
     */
    @SpringBootTest
    @ActiveProfiles("test")
    @Import(TestDatabaseGuard.class)
    @DisplayName("등록된 지표 전부가 한 번 실행으로 보존 창을 채울 수 있다")
    static class AllRegisteredIndicators {

        @Autowired
        private IndicatorRegistry registry;

        @Test
        @DisplayName("어떤 지표도 읽기 창을 다 먹지 않는다 — 먹으면 그 지표만 asOf당 한 일자다")
        void noIndicatorStarvesOnTheReadWindow() {
            int window = registry.readWindowBars();
            int retained = IndicatorRetentionPolicy.RETAINED_TRADING_DAYS;

            for (String code : registry.codes()) {
                int warmup = registry.effectiveLookback(code);
                assertThat(window - warmup + 1)
                        .as("%s의 워밍업 %d가 읽기 창 %d를 다 먹어 asOf당 %d일자밖에 못 낸다",
                                code, warmup, window, window - warmup + 1)
                        .isGreaterThanOrEqualTo(retained);
            }
        }

        @Test
        @DisplayName("읽기 창이 조용히 넓어지지 않는다 — 위 단언은 원리적으로 못 잡는다")
        void readWindowDoesNotDriftWiderWhenIndicatorsAreAdded() {
            // 위 두 단언은 창을 지키지 못한다. 합성 단언은 등록된 지표와 무관하고, 바로 위 것은
            // window = max + 보존이라 warmup <= max인 한 언제나 참이다. 즉 워밍업 1,500짜리
            // 지표를 들여도 둘 다 초록이고, 그때 창은 1,516 -> 1,755가 되어 재귀 지표의 저장값이
            // 전부 미세하게 바뀐다(ADR-0079). 그것을 알릴 경로가 이 단언 말고는 없다.
            assertThat(registry.maxEffectiveLookback())
                    .as("워밍업 최댓값이 움직였다 — 새로 들인 지표가 EPS_CAGR_5Y(1,261)를 넘었는지 "
                            + "본다. 넘겼다면 창이 함께 넓어졌으므로 백필 전후 스냅숏을 "
                            + "전 코드로 대조하고(ADR-0079 §MUST) 이 수를 함께 고친다")
                    .isEqualTo(1261);
            assertThat(registry.readWindowBars())
                    .as("읽기 창은 워밍업 최댓값 + 보존 일수다")
                    .isEqualTo(1261 + IndicatorRetentionPolicy.RETAINED_TRADING_DAYS);
        }
    }
}
