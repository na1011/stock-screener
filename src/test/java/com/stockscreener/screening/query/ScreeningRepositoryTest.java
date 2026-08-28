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
    /**
     * 좁히지 않았을 때의 모집단. "전체"는 {@code stock} 전량이 아니라 <b>활성 유니버스 편입
     * 종목</b>이므로(ADR-0087), 픽스처가 어느 활성 유니버스에도 없으면 조건과 무관하게 빠진다.
     */
    private static final String TEST_UNIVERSE_ALL = "TESTUNIVALL";

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
        jdbcTemplate.update("""
                INSERT INTO universe (code, name, market_code)
                VALUES (?, '테스트 유니버스(전체)', 'TESTMKT') ON CONFLICT DO NOTHING
                """, TEST_UNIVERSE_ALL);

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
    @DisplayName("유니버스를 지정하지 않으면 활성 유니버스 편입 종목 전체가 대상이다")
    void nullUniverseMeansEveryActiveStock() {
        joinUniverse(cheapStock);

        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "10"));

        assertThat(repository.screen(group, EvaluationMode.FILTER, AS_OF, null))
                .extracting(ScreeningResult::stockId)
                .containsExactlyInAnyOrder(cheapStock, partialStock);
    }

    @Test
    @DisplayName("IMPORTANT — 「전체」가 stock 전량이 아니다. 활성 유니버스 밖 종목은 빠진다(ADR-0087)")
    void nullUniverseExcludesStocksOutsideEveryActiveUniverse() {
        long outsider = insertBareStock("000009", "유니버스밖주");
        insertIndicator(outsider, "PER", "1");
        entityManager.clear();

        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "10"));

        // PER 1이라 조건을 가장 잘 만족하는데도 빠져야 한다. 좁히지 않으면 지표가 하나도 없는
        // 시장의 종목이 0점·전 조건 판정불가로 응답에 실리고, 서버가 전량을 내려보내므로
        // (ADR-0016) 그대로 화면까지 간다.
        assertThat(repository.screen(group, EvaluationMode.FILTER, AS_OF, null))
                .extracting(ScreeningResult::stockId).doesNotContain(outsider);
        assertThat(repository.screen(group, EvaluationMode.SCORING, AS_OF, null))
                .extracting(ScreeningResult::stockId).doesNotContain(outsider);
    }

    @Test
    @DisplayName("조건이 없어도 빈 그룹은 만들 수 없다 — 잘못된 상태를 표현 불가하게 한다")
    void emptyGroupIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ConditionGroup.and(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 필수 게이트 지표가 기준일에 <b>한 행도 없으면</b> SCORING이 통째로 0건이 된다.
     *
     * <p>린치 두 프리셋이 정확히 이 형태다 — {@code EPS_CAGR_5Y}가 필수 게이트인데, 그 지표는
     * 읽기 창이 자기 워밍업과 같던 동안 <b>{@code asOf}당 한 날짜만</b> 채워져 보존 창 180일 중
     * 172일이 비어 있었다. ADR-0079가 읽기 창을 워밍업 최댓값 + 보존 일수로 떼어 그 원인을 닫았고,
     * <b>이 단언이 지키는 성질은 그대로 남는다</b> — 어떤 이유로든(상장 5년 미만, 5년 전 적자,
     * 미보정 기업행위) 그날 그 지표 행이 없는 종목은 SCORING에서 통째로 빠지며, 배치는 SUCCESS로
     * 마감된다. 조건이 나빠서가 아니라 지표 행이 없어서 생기는 0이라 게이트 탓으로 오독하기 쉽다.
     *
     * <p>대조군이 함께 있어야 이 단언이 뜻을 갖는다 — 행을 넣으면 0이 아니어야 한다.
     * 없으면 다른 이유로 난 0에도 초록이 된다.
     */
    @Test
    @DisplayName("IMPORTANT — 필수 게이트 지표가 그날 없으면 SCORING이 0건이 된다")
    void missingRequiredGateEmptiesScoring() {
        ConditionGroup group = ConditionGroup.and(
                condition("SMA_200", ComparisonOperator.GT, "0").asRequired(),
                condition("PER", ComparisonOperator.LT, "10").withWeight(new BigDecimal("3")));

        // SMA_200을 가진 종목이 하나도 없다 — setUp이 PER·PBR만 넣는다.
        List<ScreeningResult> withoutGate = repository.screen(group, EvaluationMode.SCORING, AS_OF);
        assertThat(withoutGate)
                .as("필수 게이트가 전 종목 판정불가면 통과가 0이다 — 판정불가는 충족이 아니다")
                .isEmpty();

        // 대조군: 같은 조건에 게이트 지표만 채우면 0이 아니어야 한다.
        insertIndicator(cheapStock, "SMA_200", "100");
        List<ScreeningResult> withGate = repository.screen(group, EvaluationMode.SCORING, AS_OF);
        assertThat(withGate)
                .as("게이트 행을 넣으면 살아난다 — 위의 0이 데이터 결손 탓임을 이것이 증명한다")
                .extracting(ScreeningResult::stockId)
                .containsExactly(cheapStock);
    }

    private IndicatorCondition condition(String code, ComparisonOperator operator, String value) {
        return IndicatorCondition.of(code, operator, ConditionOperand.Literal.of(value));
    }

    /** 편입까지 함께 한다 — 어느 활성 유니버스에도 없으면 "전체"에서도 빠진다(ADR-0087). */
    private long insertStock(String ticker, String name) {
        long stockId = insertBareStock(ticker, name);
        joinUniverse(TEST_UNIVERSE_ALL, stockId);
        return stockId;
    }

    /** 유니버스에 넣지 않는다. ADR-0087을 재는 테스트만 쓴다. */
    private long insertBareStock(String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('TESTMKT', ?, ?) RETURNING id
                """, Long.class, ticker, name);
    }

    /** 테스트 유니버스에 편입시킨다. {@code effective_to}가 NULL이면 현재 구성원이다. */
    private void joinUniverse(long stockId) {
        joinUniverse(TEST_UNIVERSE, stockId);
    }

    private void joinUniverse(String universeCode, long stockId) {
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, universeCode, stockId, AS_OF.minusYears(1));
    }

    private void insertIndicator(long stockId, String code, String value) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (trade_date, indicator_code, stock_id) DO UPDATE SET value = EXCLUDED.value
                """, stockId, AS_OF, code, new BigDecimal(value));
    }
}
