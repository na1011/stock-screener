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

    // --- ADR-0142: 0건의 사유 ---------------------------------------------

    @Test
    @DisplayName("모집단은 조건을 안 건 대상 수다 — FILTER에서 통과 수와 다르다")
    void populationCountIsNotTheMatchCount() {
        ConditionGroup group = ConditionGroup.and(
                condition("PER", ComparisonOperator.LT, "10"),
                condition("PBR", ComparisonOperator.LT, "1"));

        ScreeningRepository.ScreeningOutcome outcome =
                repository.diagnose(group, EvaluationMode.FILTER, AS_OF, true, null);

        assertThat(outcome.results()).extracting(ScreeningResult::stockId)
                .containsExactly(cheapStock);
        assertThat(outcome.populationCount())
                .as("옛 evaluatedCount는 여기서 1을 냈다 — 화면이 「평가 1종목」이라고 말하고 있었다")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("FILTER가 0건이면 술어 없이 다시 돌아 조건별 귀속을 만든다")
    void filterWithNoMatchesReconstructsTheAttribution() {
        ConditionGroup group = ConditionGroup.and(
                condition("PER", ComparisonOperator.LT, "1"),
                condition("PBR", ComparisonOperator.LT, "0.1"));

        ScreeningRepository.ScreeningOutcome outcome =
                repository.diagnose(group, EvaluationMode.FILTER, AS_OF, true, null);

        assertThat(outcome.results()).isEmpty();
        assertThat(outcome.populationCount()).isEqualTo(4);
        assertThat(outcome.tallies()).hasSize(2);
        assertThat(outcome.tallies().get(0).notMatched())
                .as("PER을 가진 셋이 못 넘겼다").isEqualTo(3);
        assertThat(outcome.tallies().get(0).notEvaluable())
                .as("값이 없는 종목은 「미충족」이 아니라 「판정불가」다 — 이 가름이 요점이다")
                .isEqualTo(1);
        assertThat(outcome.minimumBlocked())
                .as("가장 가까운 종목도 둘을 못 넘겼다 — 한 조건만 풀어서는 안 열린다")
                .isEqualTo(2);
        assertThat(outcome.tallies().get(0).wouldPassWithout())
                .as("MUST: FILTER에서는 전 조건이 관문이다. 여기가 null이면 화면에 「이 조건만 "
                        + "빼면」 칸이 통째로 안 뜨는데, 안 보이는 것뿐이라 아무 시험도 안 붉는다")
                .isNotNull();
    }

    @Test
    @DisplayName("FILTER 0건 — 한 조건만 빼면 열리는 경우 그 수를 낸다")
    void filterReportsTheGateThatAloneBlocks() {
        // cheapStock(PER 8 · PBR 0.8)만 첫 조건에 걸리게 세운다.
        ConditionGroup group = ConditionGroup.and(
                condition("PER", ComparisonOperator.LT, "5"),
                condition("PBR", ComparisonOperator.LT, "1"));

        ScreeningRepository.ScreeningOutcome outcome =
                repository.diagnose(group, EvaluationMode.FILTER, AS_OF, true, null);

        assertThat(outcome.results()).isEmpty();
        assertThat(outcome.tallies().get(0).wouldPassWithout())
                .as("PER 조건만 빼면 cheapStock이 통과한다").isEqualTo(1);
        assertThat(outcome.minimumBlocked())
                .as("정확히 하나만 못 넘긴 종목이 있다").isEqualTo(1);
    }

    @Test
    @DisplayName("MUST NOT: FILTER가 0건이 아니면 귀속을 내지 않는다 — 통과분만 세면 거짓이다")
    void filterWithMatchesEmitsNoAttribution() {
        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "10"));

        ScreeningRepository.ScreeningOutcome outcome =
                repository.diagnose(group, EvaluationMode.FILTER, AS_OF, true, null);

        assertThat(outcome.results()).isNotEmpty();
        assertThat(outcome.tallies())
                .as("통과분만 보고 세면 「이 조건에 걸린 종목이 0이다」가 나오는데 그것은 거짓이다")
                .isEmpty();
    }

    @Test
    @DisplayName("SCORING — 필수 게이트 0건의 사유가 「판정불가」에 귀속된다")
    void scoringAttributesAnEmptyResultToTheBlockingGate() {
        ConditionGroup group = ConditionGroup.and(
                condition("SMA_200", ComparisonOperator.GT, "0").asRequired(),
                condition("PER", ComparisonOperator.LT, "10").withWeight(new BigDecimal("3")));

        ScreeningRepository.ScreeningOutcome outcome =
                repository.diagnose(group, EvaluationMode.SCORING, AS_OF, true, null);

        assertThat(outcome.results()).isEmpty();
        assertThat(outcome.populationCount()).isEqualTo(4);
        assertThat(outcome.tallies().get(0).notEvaluable())
                .as("게이트 지표가 전 종목에 없다 — 「조건이 빡빡하다」가 아니라 데이터가 없는 것이다")
                .isEqualTo(4);
        assertThat(outcome.tallies().get(0).notMatched()).isZero();
        assertThat(outcome.tallies().get(0).wouldPassWithout())
                .as("이 게이트만 빼면 전 종목이 통과한다 — 한계 기여(ADR-0070)")
                .isEqualTo(4);
        assertThat(outcome.tallies().get(1).wouldPassWithout())
                .as("배점 조건은 아무도 안 자르므로 한계 기여가 없다")
                .isNull();
    }

    @Test
    @DisplayName("MUST: 같은 지표의 상·하한 쌍을 인덱스로 가른다 — 코드로 세면 합쳐진다")
    void sameIndicatorTwiceIsCountedPerCondition() {
        ConditionGroup group = ConditionGroup.and(
                condition("PER", ComparisonOperator.GTE, "20").asRequired(),
                condition("PER", ComparisonOperator.LTE, "5").asRequired());

        ScreeningRepository.ScreeningOutcome outcome =
                repository.diagnose(group, EvaluationMode.SCORING, AS_OF, true, null);

        assertThat(outcome.tallies()).hasSize(2);
        assertThat(outcome.tallies().get(0).notMatched())
                .as("PER 8·8·30 중 20 이상은 하나뿐이라 둘이 못 넘긴다").isEqualTo(2);
        assertThat(outcome.tallies().get(1).notMatched())
                .as("5 이하는 하나도 없어 셋이 못 넘긴다").isEqualTo(3);
        assertThat(outcome.tallies().get(0).notMatched())
                .isNotEqualTo(outcome.tallies().get(1).notMatched());
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
    // ── 시점 편입 (ADR-0135) ─────────────────────────────────────────────────
    //
    // 이 넷이 「대상은 오늘 명부, 지표만 그날 것」이라는 결함을 잠근다. 그 결함은 값이
    // 나오는 방향으로 틀리므로 화면에서 구별되지 않는다.

    @Test
    @DisplayName("IMPORTANT — 과거 기준일에는 그날 편입이던 종목만 나온다")
    void pastAsOfUsesThatDaysMembership() {
        // cheapStock은 그날 편입이었고 나중에 빠졌다. partialStock은 그 뒤에 들어왔다.
        joinClosed(TEST_UNIVERSE, cheapStock, AS_OF.minusYears(1), AS_OF.plusDays(1));
        joinUniverse(TEST_UNIVERSE, partialStock, AS_OF.plusDays(1));

        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "100"));

        assertThat(repository.screen(group, EvaluationMode.FILTER, AS_OF, false, TEST_UNIVERSE))
                .extracting(ScreeningResult::stockId)
                .as("그날 편입이 아니던 종목이 실리면 생존 편향이 그대로 남는다")
                .containsExactly(cheapStock);
    }

    @Test
    @DisplayName("IMPORTANT — 제외 당일은 편입이 아니다. 끝점이 배타라 goe로 쓰면 하루가 샌다")
    void exclusionDateIsNotAMemberDay() {
        joinClosed(TEST_UNIVERSE, cheapStock, AS_OF.minusYears(1), AS_OF);

        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "100"));

        assertThat(repository.screen(group, EvaluationMode.FILTER, AS_OF, false, TEST_UNIVERSE))
                .isEmpty();
    }

    /**
     * 원장과 지표의 시계가 다르다 — 토요일 리밸런싱이 {@code effective_from = 금요일}을 찍는데
     * 그 금요일 지표는 다음 평일에야 생긴다. 그 창에서 하한을 그대로 걸면 <b>신규 편입이
     * 화면에서 조용히 사라진다</b>(실측: 지표 최신일이 2026-09-03이었다면 47행).
     */
    @Test
    @DisplayName("IMPORTANT — 최신일 조회는 편입일이 기준일보다 뒤인 열린 행도 담는다")
    void latestQueryKeepsMembershipOpenedAfterTheIndicatorDate() {
        joinUniverse(TEST_UNIVERSE, cheapStock, AS_OF.plusDays(2));

        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "100"));

        assertThat(repository.screen(group, EvaluationMode.FILTER, AS_OF, true, TEST_UNIVERSE))
                .extracting(ScreeningResult::stockId).containsExactly(cheapStock);
    }

    @Test
    @DisplayName("IMPORTANT — 과거 조회에서는 그 접기를 안 한다. 접으면 오늘 명부가 과거에 실린다")
    void pastQueryDoesNotFoldTheLowerBound() {
        joinUniverse(TEST_UNIVERSE, cheapStock, AS_OF.plusDays(2));

        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "100"));

        assertThat(repository.screen(group, EvaluationMode.FILTER, AS_OF, false, TEST_UNIVERSE))
                .isEmpty();
    }

    /**
     * <b>「오늘 상장돼 있는가」는 오늘의 사실이다.</b> 과거 기준일 질의에 끼우면 그 뒤 상장폐지된
     * 종목이 조용히 빠지고, 그것이 이 결정이 지우려는 편향과 같은 모양이다. 편입 구간이
     * 그 보증을 대신한다 — 그날 상위 N이었다는 것은 그날 상장돼 있었다는 뜻이다.
     */
    @Test
    @DisplayName("IMPORTANT — 과거 조회는 stock.is_active를 안 본다. 보면 폐지 종목이 조용히 빠진다")
    void pastQueryIgnoresTodaysActiveFlag() {
        joinClosed(TEST_UNIVERSE, cheapStock, AS_OF.minusYears(1), AS_OF.plusDays(1));
        jdbcTemplate.update("UPDATE stock SET is_active = false WHERE id = ?", cheapStock);

        ConditionGroup group = ConditionGroup.and(condition("PER", ComparisonOperator.LT, "100"));

        assertThat(repository.screen(group, EvaluationMode.FILTER, AS_OF, false, TEST_UNIVERSE))
                .extracting(ScreeningResult::stockId).containsExactly(cheapStock);
        assertThat(repository.screen(group, EvaluationMode.FILTER, AS_OF, true, TEST_UNIVERSE))
                .as("최신일에서는 여전히 오늘의 사실을 본다").isEmpty();
    }

    private void joinUniverse(String universeCode, long stockId, LocalDate from) {
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, universeCode, stockId, from);
    }

    private void joinClosed(String universeCode, long stockId, LocalDate from, LocalDate to) {
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to)
                VALUES (?, ?, ?, ?)
                """, universeCode, stockId, from, to);
    }

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
