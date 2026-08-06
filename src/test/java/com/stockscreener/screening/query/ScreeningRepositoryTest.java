package com.stockscreener.screening.query;

import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionEvaluator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.support.JpaSliceTest;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조건 번역과 실행을 실제 PostgreSQL에서 검증한다.
 *
 * <p>인메모리 판정({@code ConditionEvaluatorTest})과 짝을 이룬다. 두 경로가 같은 결과를 내야
 * SQL이 고른 근거와 화면에 보여주는 이유가 일치한다.
 */
@JpaSliceTest
@Import({ConditionTranslator.class, ConditionEvaluator.class, ScreeningRepository.class})
class ScreeningRepositoryTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 4);
    private static final String TEST_UNIVERSE = "TESTUNIV";

    @Autowired
    private ScreeningRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private long cheapStock;      // PER 8,  PBR 0.8 — 둘 다 충족
    private long expensiveStock;  // PER 30, PBR 3.0 — 둘 다 불충족
    private long partialStock;    // PER 8,  PBR 3.0 — 하나만 충족
    private long noDataStock;     // 지표 없음

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                INSERT INTO market (code, country_code, currency_code, timezone)
                VALUES ('TESTMKT','KR','KRW','Asia/Seoul') ON CONFLICT DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, category, value_type)
                VALUES ('PER','PER','FUNDAMENTAL','RATIO'), ('PBR','PBR','FUNDAMENTAL','RATIO'),
                       ('CLOSE','종가','PRICE','PRICE'), ('SMA_200','SMA200','TECHNICAL','PRICE')
                ON CONFLICT DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO universe (code, name, market_code)
                VALUES (?, '테스트 유니버스', 'TESTMKT') ON CONFLICT DO NOTHING
                """, TEST_UNIVERSE);

        cheapStock = insertStock("000001", "저평가주");
        expensiveStock = insertStock("000002", "고평가주");
        partialStock = insertStock("000003", "부분충족주");
        noDataStock = insertStock("000004", "데이터없는주");

        insertIndicator(cheapStock, "PER", "8");
        insertIndicator(cheapStock, "PBR", "0.8");
        insertIndicator(expensiveStock, "PER", "30");
        insertIndicator(expensiveStock, "PBR", "3.0");
        insertIndicator(partialStock, "PER", "8");
        insertIndicator(partialStock, "PBR", "3.0");
        // noDataStock에는 아무 지표도 넣지 않는다.

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("FILTER — 모든 조건을 만족하는 종목만 남는다")
    void filterKeepsOnlyFullMatches() {
        ConditionGroup group = ConditionGroup.and(
                condition("PER", ComparisonOperator.LT, "10"),
                condition("PBR", ComparisonOperator.LT, "1"));

        List<ScreeningResult> results = repository.screen(group, EvaluationMode.FILTER, AS_OF);

        assertThat(results).extracting(ScreeningResult::stockId).containsExactly(cheapStock);
    }

    @Test
    @DisplayName("SCORING — 모든 종목이 남고 점수 내림차순으로 정렬된다")
    void scoringRanksAllStocks() {
        ConditionGroup group = ConditionGroup.and(
                condition("PER", ComparisonOperator.LT, "10").withWeight(new BigDecimal("3")),
                condition("PBR", ComparisonOperator.LT, "1").withWeight(new BigDecimal("2")));

        List<ScreeningResult> results = repository.screen(group, EvaluationMode.SCORING, AS_OF);

        assertThat(results).extracting(ScreeningResult::stockId)
                .containsExactly(cheapStock, partialStock, expensiveStock, noDataStock);
        assertThat(results.get(0).score()).isEqualByComparingTo("5");
        assertThat(results.get(1).score()).isEqualByComparingTo("3");
        assertThat(results.get(2).score()).isEqualByComparingTo("0");
        assertThat(results.get(3).score()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("IMPORTANT — 지표가 없는 종목이 FILTER에서는 빠지고 SCORING에서는 0점으로 남는다")
    void missingIndicatorBehavesDifferentlyPerMode() {
        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "10"));

        List<ScreeningResult> filtered = repository.screen(group, EvaluationMode.FILTER, AS_OF);
        List<ScreeningResult> scored = repository.screen(group, EvaluationMode.SCORING, AS_OF);

        assertThat(filtered).extracting(ScreeningResult::stockId).doesNotContain(noDataStock);

        ScreeningResult noData = scored.stream()
                .filter(r -> r.stockId() == noDataStock)
                .findFirst()
                .orElseThrow();
        assertThat(noData.score()).isEqualByComparingTo("0");
        assertThat(noData.notEvaluableCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("선정 이유가 조건별로 나온다")
    void exposesPerConditionBreakdown() {
        ConditionGroup group = ConditionGroup.and(
                condition("PER", ComparisonOperator.LT, "10").withWeight(new BigDecimal("3")),
                condition("PBR", ComparisonOperator.LT, "1").withWeight(new BigDecimal("2")));

        ScreeningResult result = repository.screen(group, EvaluationMode.SCORING, AS_OF).stream()
                .filter(r -> r.stockId() == partialStock)
                .findFirst()
                .orElseThrow();

        assertThat(result.outcomes()).hasSize(2);
        assertThat(result.outcomes().get(0).actualValue()).isEqualByComparingTo("8");
        assertThat(result.outcomes().get(0).earnedScore()).isEqualByComparingTo("3");
        assertThat(result.outcomes().get(1).actualValue()).isEqualByComparingTo("3.0");
        assertThat(result.outcomes().get(1).earnedScore()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("지표 대 지표 비교가 SQL에서 동작한다 — 종가 > SMA200")
    void translatesIndicatorReference() {
        insertIndicator(cheapStock, "CLOSE", "70000");
        insertIndicator(cheapStock, "SMA_200", "65000");
        insertIndicator(expensiveStock, "CLOSE", "60000");
        insertIndicator(expensiveStock, "SMA_200", "65000");
        entityManager.flush();
        entityManager.clear();

        ConditionGroup group = ConditionGroup.and(IndicatorCondition.of(
                "CLOSE", ComparisonOperator.GT, ConditionOperand.IndicatorRef.of("SMA_200")));

        List<ScreeningResult> results = repository.screen(group, EvaluationMode.FILTER, AS_OF);

        assertThat(results).extracting(ScreeningResult::stockId).containsExactly(cheapStock);
    }

    @Test
    @DisplayName("계수를 적용한 돌파 조건 — 종가 > SMA200 × 1.05")
    void translatesIndicatorReferenceWithMultiplier() {
        insertIndicator(cheapStock, "CLOSE", "70000");     // 65000 × 1.05 = 68250 초과
        insertIndicator(cheapStock, "SMA_200", "65000");
        insertIndicator(partialStock, "CLOSE", "66000");   // 초과하지만 5% 여유엔 못 미침
        insertIndicator(partialStock, "SMA_200", "65000");
        entityManager.flush();
        entityManager.clear();

        ConditionGroup group = ConditionGroup.and(IndicatorCondition.of(
                "CLOSE", ComparisonOperator.GT, ConditionOperand.IndicatorRef.of("SMA_200", "1.05")));

        List<ScreeningResult> results = repository.screen(group, EvaluationMode.FILTER, AS_OF);

        assertThat(results).extracting(ScreeningResult::stockId).containsExactly(cheapStock);
    }

    @Test
    @DisplayName("BETWEEN이 번역된다")
    void translatesBetween() {
        ConditionGroup group = ConditionGroup.and(IndicatorCondition.of(
                "PER", ComparisonOperator.BETWEEN,
                ConditionOperand.Literal.between(new BigDecimal("5"), new BigDecimal("10"))));

        List<ScreeningResult> results = repository.screen(group, EvaluationMode.FILTER, AS_OF);

        assertThat(results).extracting(ScreeningResult::stockId)
                .containsExactlyInAnyOrder(cheapStock, partialStock);
    }

    @Test
    @DisplayName("IMPORTANT — 유니버스를 지정하면 편입 종목만 남는다. 조건을 충족해도 비편입이면 빠진다")
    void universeFilterExcludesNonMembers() {
        joinUniverse(cheapStock);
        // partialStock은 PER 조건을 충족하지만 편입되어 있지 않다.

        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "10"));

        List<ScreeningResult> unrestricted = repository.screen(group, EvaluationMode.FILTER, AS_OF);
        List<ScreeningResult> restricted =
                repository.screen(group, EvaluationMode.FILTER, AS_OF, TEST_UNIVERSE);

        assertThat(unrestricted).extracting(ScreeningResult::stockId)
                .containsExactlyInAnyOrder(cheapStock, partialStock);
        assertThat(restricted).extracting(ScreeningResult::stockId).containsExactly(cheapStock);
    }

    @Test
    @DisplayName("SCORING에서도 유니버스가 적용된다 — 비편입 종목이 0점으로 섞이면 순위가 무의미해진다")
    void universeFilterAppliesToScoringToo() {
        joinUniverse(cheapStock);

        ConditionGroup group = ConditionGroup.and(
                condition("PER", ComparisonOperator.LT, "10").withWeight(new BigDecimal("1")));

        List<ScreeningResult> results =
                repository.screen(group, EvaluationMode.SCORING, AS_OF, TEST_UNIVERSE);

        assertThat(results).extracting(ScreeningResult::stockId).containsExactly(cheapStock);
    }

    @Test
    @DisplayName("편입이 끝난 종목은 제외된다 — effective_to가 채워지면 현재 구성원이 아니다")
    void excludesStocksWhoseMembershipEnded() {
        joinUniverse(cheapStock);
        jdbcTemplate.update("""
                UPDATE universe_membership SET effective_to = ?
                WHERE universe_code = ? AND stock_id = ?
                """, AS_OF.minusDays(1), TEST_UNIVERSE, cheapStock);

        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "10"));

        assertThat(repository.screen(group, EvaluationMode.FILTER, AS_OF, TEST_UNIVERSE)).isEmpty();
    }

    @Test
    @DisplayName("유니버스를 지정하지 않으면 전체가 대상이다")
    void nullUniverseMeansEveryActiveStock() {
        joinUniverse(cheapStock);

        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "10"));

        assertThat(repository.screen(group, EvaluationMode.FILTER, AS_OF, null))
                .extracting(ScreeningResult::stockId)
                .containsExactlyInAnyOrder(cheapStock, partialStock);
    }

    @Test
    @DisplayName("조건이 없어도 빈 그룹은 만들 수 없다 — 잘못된 상태를 표현 불가하게 한다")
    void emptyGroupIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ConditionGroup.and(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private IndicatorCondition condition(String code, ComparisonOperator operator, String value) {
        return IndicatorCondition.of(code, operator, ConditionOperand.Literal.of(value));
    }

    private long insertStock(String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('TESTMKT', ?, ?) RETURNING id
                """, Long.class, ticker, name);
    }

    /** 테스트 유니버스에 편입시킨다. {@code effective_to}가 NULL이면 현재 구성원이다. */
    private void joinUniverse(long stockId) {
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, TEST_UNIVERSE, stockId, AS_OF.minusYears(1));
    }

    private void insertIndicator(long stockId, String code, String value) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET value = EXCLUDED.value
                """, stockId, AS_OF, code, new BigDecimal(value));
    }
}
