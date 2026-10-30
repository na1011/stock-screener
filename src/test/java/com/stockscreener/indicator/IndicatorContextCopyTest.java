package com.stockscreener.indicator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사본 메서드가 <b>선언된 필드를 하나도 빠뜨리지 않는가.</b>
 *
 * <p>이 펜스가 필요한 이유는 빠뜨림의 증상이 예외가 아니라 <b>값이 없는 지표</b>이기 때문이다 —
 * 필수 조건이면 전 종목 판정불가가 되는데 에러도 경고도 없다. {@code IndicatorContext}의
 * javadoc이 그것을 IMPORTANT로 적고 있으나 <b>그 문장을 지키는 테스트가 없었다.</b>
 *
 * <p>MUST: 필드를 <b>리플렉션으로 전수</b>로 돈다. 손으로 적은 목록은 다음에 필드가 늘 때
 * 함께 늘지 않고, 늘지 않았다는 사실이 드러나지 않는다 — 그것이 정확히 이 결함의 형태다.
 *
 * <p>{@code IndicatorRunnerTest}는 이것을 재지 <b>못한다</b>. 진짜 {@code JdbcTemplate}을 쓰고
 * 테스트 더블이 없어 리더에 닿을 이음매가 없다({@code IndicatorReadWindowTest}가 같은 이유를 적는다).
 */
class IndicatorContextCopyTest {

    /** 사본이 갈아끼우는 필드. 나머지는 전부 그대로 실려야 한다. */
    private static final Map<String, String> REPLACED_BY = Map.of(
            "withDependencies", "dependencies",
            "withMarketIndex", "marketIndex",
            "withGovernanceFacts", "governanceFacts",
            "withEarningsReleaseDates", "earningsReleaseDates");

    @Test
    @DisplayName("withDependencies가 나머지 필드를 전부 나른다")
    void withDependenciesCarriesEveryOtherField() throws Exception {
        IndicatorContext base = fullyPopulated();
        assertCarriesEverythingExcept(base, base.withDependencies(Map.of("OTHER", new TreeMap<>())),
                "withDependencies");
    }

    @Test
    @DisplayName("withMarketIndex가 나머지 필드를 전부 나른다")
    void withMarketIndexCarriesEveryOtherField() throws Exception {
        IndicatorContext base = fullyPopulated();
        NavigableMap<LocalDate, BigDecimal> other = new TreeMap<>();
        other.put(LocalDate.of(2026, 2, 2), BigDecimal.TEN);
        assertCarriesEverythingExcept(base, base.withMarketIndex(other), "withMarketIndex");
    }

    @Test
    @DisplayName("withGovernanceFacts가 나머지 필드를 전부 나른다")
    void withGovernanceFactsCarriesEveryOtherField() throws Exception {
        IndicatorContext base = fullyPopulated();
        IndicatorContext copy = base.withGovernanceFacts(List.of(
                new GovernanceFigures(2020, Map.of())));
        assertCarriesEverythingExcept(base, copy, "withGovernanceFacts");
    }

    @Test
    @DisplayName("withEarningsReleaseDates가 나머지 필드를 전부 나른다")
    void withEarningsReleaseDatesCarriesEveryOtherField() throws Exception {
        IndicatorContext base = fullyPopulated();
        assertCarriesEverythingExcept(base, base.withEarningsReleaseDates(List.of(LocalDate.of(2026, 7, 7))),
                "withEarningsReleaseDates");
    }

    @Test
    @DisplayName("withoutFinancials는 재무 넷만 비우고 나머지를 전부 나른다(ADR-0195) — 시세 · 거버넌스 지표가 막힌 봉에도 산다")
    void withoutFinancialsClearsOnlyTheFinancials() throws Exception {
        IndicatorContext base = fullyPopulated();
        IndicatorContext copy = base.withoutFinancials();
        java.util.Set<String> financial = java.util.Set.of("annualRevisions", "quarterlyRevisions", "annualFigures",
                "quarterlyFigures");
        for (Field field : instanceFields()) {
            field.setAccessible(true);
            if (financial.contains(field.getName())) {
                assertThat(isEmptyish(field.get(copy))).as("%s를 안 비웠다", field.getName()).isTrue();
            } else {
                assertThat(field.get(copy)).as("withoutFinancials가 %s를 안 실었다", field.getName())
                        .isEqualTo(field.get(base));
            }
        }
    }

    @Test
    @DisplayName("사본 메서드가 모든 필드를 덮을 수 있는지 — 새 필드가 늘면 여기서 먼저 걸린다")
    void everyFieldIsPopulatedInTheFixture() throws Exception {
        IndicatorContext base = fullyPopulated();
        List<String> empty = new ArrayList<>();
        for (Field field : instanceFields()) {
            field.setAccessible(true);
            if (isEmptyish(field.get(base))) {
                empty.add(field.getName());
            }
        }
        assertThat(empty)
                .as("픽스처가 안 채운 필드가 있으면 위 세 검사가 그 필드에 대해 아무것도 "
                        + "단언하지 못한다 — 빠뜨림이 통과한다")
                .isEmpty();
    }

    private void assertCarriesEverythingExcept(IndicatorContext base, IndicatorContext copy,
                                               String method) throws Exception {
        String replaced = REPLACED_BY.get(method);
        for (Field field : instanceFields()) {
            field.setAccessible(true);
            Object before = field.get(base);
            Object after = field.get(copy);
            if (field.getName().equals(replaced)) {
                assertThat(after).as("%s가 %s를 안 갈아끼웠다", method, field.getName())
                        .isNotEqualTo(before);
            } else {
                assertThat(after)
                        .as("%s가 %s를 안 실었다 — 증상은 예외가 아니라 「값이 없는 지표」다",
                                method, field.getName())
                        .isEqualTo(before);
            }
        }
    }

    private static List<Field> instanceFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : IndicatorContext.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static boolean isEmptyish(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof Number number) {
            return number.longValue() == 0L;
        }
        return String.valueOf(value).isBlank();
    }

    private static IndicatorContext fullyPopulated() {
        NavigableMap<LocalDate, BigDecimal> index = new TreeMap<>();
        index.put(LocalDate.of(2026, 1, 2), BigDecimal.ONE);
        return new IndicatorContext(
                7L,
                "26",
                List.of(new PriceBar(LocalDate.of(2026, 1, 2), BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L, BigDecimal.ONE,
                        BigDecimal.TEN)),
                List.of(annual()),
                List.of(annual()),
                List.of(new ShareAdjustment(LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 1),
                        BigDecimal.valueOf(5))),
                List.of(LocalDate.of(2025, 6, 1)),
                index,
                List.of(new GovernanceFigures(2025, Map.of(GovernanceMetric.DIRECTOR_COUNT,
                        new GovernanceValue(BigDecimal.valueOf(9), LocalDate.of(2026, 3, 10))))),
                Map.of("PARENT", new TreeMap<>(index)),
                List.of(LocalDate.of(2026, 4, 7)));
    }

    private static FinancialFigures annual() {
        return new FinancialFigures(2025, FinancialFigures.FiscalPeriod.FY,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), LocalDate.of(2026, 3, 10),
                "KRW", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, null, null, null, null);
    }
}
