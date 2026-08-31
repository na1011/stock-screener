package com.stockscreener.screening.store;

import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조건 <b>정의</b>의 왕복. 도메인 트리가 세 테이블로 흩어졌다가 원래 모양으로 돌아오는지를 본다.
 *
 * <p>왕복이 깨지면 저장은 성공하고 실행만 다른 결과를 낸다 — 사용자가 조건을 다시 열어보기
 * 전까지 드러나지 않는 종류의 오류다.
 */
@JpaSliceTest
@Import(ScreenStore.class)
class ScreenStoreTest {

    @Autowired
    private ScreenStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, category, value_type)
                VALUES ('PER','PER','FUNDAMENTAL','RATIO'),
                       ('RSI_14','RSI','TECHNICAL','COUNT'),
                       ('ADJ_CLOSE','수정종가','PRICE','PRICE'),
                       ('SMA_200','SMA200','TECHNICAL','PRICE'),
                       ('MARKET_CAP','시가총액','FUNDAMENTAL','CURRENCY')
                ON CONFLICT DO NOTHING
                """);
        userId = insertUser("sub-owner", "owner@example.com");
        otherUserId = insertUser("sub-other", "other@example.com");
    }

    @Test
    @DisplayName("조건 트리가 그대로 왕복한다 — 리터럴·지표참조·계수·BETWEEN·가중치")
    void roundTripsEveryOperandShape() {
        ConditionGroup conditions = ConditionGroup.and(List.of(
                IndicatorCondition.of("PER", ComparisonOperator.LT, ConditionOperand.Literal.of("10"))
                        .withWeight(new BigDecimal("3")),
                IndicatorCondition.of("RSI_14", ComparisonOperator.BETWEEN,
                        ConditionOperand.Literal.between(new BigDecimal("45"), new BigDecimal("70"))),
                IndicatorCondition.of("ADJ_CLOSE", ComparisonOperator.GT,
                        ConditionOperand.IndicatorRef.of("SMA_200", "1.05"))));

        ScreenDefinition saved = store.create(ScreenDefinition.custom(
                null, "내 화면", "설명", EvaluationMode.SCORING, userId, null, conditions));

        ScreenDefinition loaded = store.findById(saved.id()).orElseThrow();

        assertThat(loaded.name()).isEqualTo("내 화면");
        assertThat(loaded.type()).isEqualTo(ScreenType.CUSTOM);
        assertThat(loaded.mode()).isEqualTo(EvaluationMode.SCORING);
        assertThat(loaded.ownerUserId()).isEqualTo(userId);

        List<IndicatorCondition> restored = loaded.conditions().conditions();
        assertThat(restored).hasSize(3);

        // 순서가 sort_order로 보존된다. 화면에 보이는 순서와 저장 순서가 갈리면 안 된다.
        assertThat(restored.get(0).indicatorCode()).isEqualTo("PER");
        assertThat(restored.get(0).weight()).isEqualByComparingTo("3");
        assertThat(((ConditionOperand.Literal) restored.get(0).operand()).value())
                .isEqualByComparingTo("10");

        assertThat(restored.get(1).operator()).isEqualTo(ComparisonOperator.BETWEEN);
        ConditionOperand.Literal between = (ConditionOperand.Literal) restored.get(1).operand();
        assertThat(between.value()).isEqualByComparingTo("45");
        assertThat(between.upperBound()).isEqualByComparingTo("70");
        assertThat(restored.get(1).weight()).isNull();

        ConditionOperand.IndicatorRef ref = (ConditionOperand.IndicatorRef) restored.get(2).operand();
        assertThat(ref.indicatorCode()).isEqualTo("SMA_200");
        assertThat(ref.multiplier()).isEqualByComparingTo("1.05");
    }

    @Test
    @DisplayName("통화별 임계가 그대로 왕복한다 — 기준 행과 자식 표가 다시 한 조건이 된다")
    void roundTripsMarketLiteral() {
        // mapCondition이 threshold_currency를 안 읽으면 저장은 되는데 복원이 평범한 리터럴이
        // 되고, 그러면 기준 통화의 값이 모든 시장에 걸린다 — 통화 축을 세운 목적의 정반대다.
        // 그 자리를 그 메서드가 스스로 "컴파일러가 잡아주지 않는다"고 적어 뒀다.
        Map<String, ConditionOperand.Literal> byCurrency = new LinkedHashMap<>();
        byCurrency.put("KRW", ConditionOperand.Literal.of("1000000000000"));
        byCurrency.put("USD", ConditionOperand.Literal.of("7940000000"));

        ScreenDefinition saved = store.create(ScreenDefinition.custom(
                null, "통화 축 화면", null, EvaluationMode.FILTER, userId, "KOSPI200",
                ConditionGroup.and(List.of(
                        IndicatorCondition.of("MARKET_CAP", ComparisonOperator.GTE,
                                new ConditionOperand.MarketLiteral(byCurrency)).asRequired()))));

        ConditionOperand restored =
                store.findById(saved.id()).orElseThrow().conditions().conditions().get(0).operand();

        assertThat(restored)
                .as("복원이 평범한 리터럴이면 축이 통째로 사라진 것이다")
                .isInstanceOf(ConditionOperand.MarketLiteral.class);
        ConditionOperand.MarketLiteral market = (ConditionOperand.MarketLiteral) restored;
        assertThat(market.forCurrency("KRW").value()).isEqualByComparingTo("1000000000000");
        assertThat(market.forCurrency("USD").value()).isEqualByComparingTo("7940000000");
    }

    @Test
    @DisplayName("대상을 지정하면 사용자 조건에 그 시장의 통화 축이 붙는다")
    void derivesThresholdCurrencyFromTheChosenUniverse() {
        // 이것이 없으면 프리셋을 복사해 만든 사용자 화면이 원본보다 느슨해진다 —
        // 시더는 PRESET만 갱신하므로 사본은 축 없는 조건을 든 채 남는다(ADR-0072 §규칙).
        ScreenDefinition saved = store.create(ScreenDefinition.custom(
                null, "대상 지정 화면", null, EvaluationMode.FILTER, userId, "KOSPI200",
                ConditionGroup.and(List.of(
                        IndicatorCondition.of("MARKET_CAP", ComparisonOperator.GTE,
                                ConditionOperand.Literal.of("1000000000000"))))));

        ConditionOperand restored =
                store.findById(saved.id()).orElseThrow().conditions().conditions().get(0).operand();

        assertThat(restored).isInstanceOf(ConditionOperand.MarketLiteral.class);
        assertThat(((ConditionOperand.MarketLiteral) restored).forCurrency("KRW").value())
                .isEqualByComparingTo("1000000000000");
    }

    @Test
    @DisplayName("IMPORTANT — 활성 통화가 갈리면 「전체」로 저장한 금액 조건에 축이 안 붙는다")
    void leavesTheAxisEmptyWhenActiveCurrenciesDiffer() {
        // 이 시험은 안전을 단정하지 않는다. **지뢰가 실재한다는 것**을 못박는다 —
        // 축이 없는 조건은 resolveFor가 평범한 리터럴로 그대로 통과시키므로, 이 화면을
        // 실행 시점에 미국으로 지정하면 원화 임계가 달러 값에 걸린다. 지금 그 경로를 막는
        // 것은 화면뿐이고(조건 칩 바가 대상을 채운다) API를 직접 부르면 열려 있다.
        //
        // MUST: 통화를 **편입으로** 갈라 놓는다. 편입 없는 활성 유니버스만 있으면 집합이
        // 비어 size != 1이 되어 **같은 초록이 다른 이유로** 나온다 — 그러면 이 시험은
        // 아무것도 안 재고 있다.
        seedMembership("KOSPI200", "KOSPI", "900101");
        seedMembership("US_TOP1000", "US", "TESTUS1");
        assertThat(activeCurrencyCount())
                .as("이 시험의 전제 — 편입이 있는 활성 유니버스의 통화가 둘이라야 한다")
                .isEqualTo(2);

        ScreenDefinition saved = store.create(ScreenDefinition.custom(
                null, "전체 대상 화면", null, EvaluationMode.FILTER, userId, null,
                ConditionGroup.and(List.of(
                        IndicatorCondition.of("MARKET_CAP", ComparisonOperator.GTE,
                                ConditionOperand.Literal.of("1000000000000"))))));

        ConditionOperand restored =
                store.findById(saved.id()).orElseThrow().conditions().conditions().get(0).operand();

        assertThat(restored)
                .as("축이 안 붙는다 — 이것이 지금의 동작이고, 그래서 실행이 조용히 통과한다")
                .isInstanceOf(ConditionOperand.Literal.class);
    }

    @Test
    @DisplayName("「전체」의 통화는 편입이 있는 활성 유니버스로만 정한다 — 빈 유니버스가 축을 막지 않는다")
    void derivesFromUniversesThatActuallyHaveMembers() {
        // 두 술어가 **갈리는 유일한 자리**다. 편입 조인이 없으면 편입 0건인 활성 미국
        // 유니버스가 통화 집합에 들어와 size가 2가 되고, 그러면 국내밖에 없는데도 축을
        // 안 적는다 — MarketScope는 같은 상황에서 KRW 하나를 보므로 저장과 실행이 갈린다.
        //
        // ADR-0107 §규칙이 정한 것과 같은 술어다: 「전체」의 시장·통화는 편입이 있는
        // 활성 유니버스로만 정한다.
        seedMembership("KOSPI200", "KOSPI", "900102");
        assertThat(activeCurrencyCount())
                .as("이 시험의 전제 — 편입이 있는 것은 국내 하나, 편입 0건인 활성 미국이 함께 있다")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM universe u JOIN market m ON m.code = u.market_code
                WHERE u.is_active AND m.currency_code <> 'KRW'
                """, Integer.class))
                .as("편입 없는 다른 통화의 활성 유니버스가 있어야 옛 술어가 갈린다")
                .isPositive();

        ScreenDefinition saved = store.create(ScreenDefinition.custom(
                null, "편입 기준 화면", null, EvaluationMode.FILTER, userId, null,
                ConditionGroup.and(List.of(
                        IndicatorCondition.of("MARKET_CAP", ComparisonOperator.GTE,
                                ConditionOperand.Literal.of("1000000000000"))))));

        ConditionOperand restored =
                store.findById(saved.id()).orElseThrow().conditions().conditions().get(0).operand();

        assertThat(restored)
                .as("편입 0건인 활성 유니버스가 통화 집합에 끼면 축이 안 붙는다")
                .isInstanceOf(ConditionOperand.MarketLiteral.class);
        assertThat(((ConditionOperand.MarketLiteral) restored).forCurrency("KRW").value())
                .isEqualByComparingTo("1000000000000");
    }

    private void seedMembership(String universeCode, String marketCode, String ticker) {
        Long stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id
                """, Long.class, marketCode, ticker, "종목" + ticker);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, DATE '2020-01-01')
                """, universeCode, stockId);
    }

    /** {@code ScreenStore.deriveThresholdCurrency}·{@code MarketScope}와 같은 술어다. */
    private Integer activeCurrencyCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT m.currency_code) FROM universe u
                JOIN market m ON m.code = u.market_code
                JOIN universe_membership um
                  ON um.universe_code = u.code AND um.effective_to IS NULL
                WHERE u.is_active
                """, Integer.class);
    }

    @Test
    @DisplayName("프리셋은 소유자가 null로 읽힌다 — 0이 아니다")
    void presetOwnerIsNullNotZero() {
        ScreenDefinition saved = store.create(ScreenDefinition.preset(
                "TEST_PRESET", "테스트 프리셋", null, EvaluationMode.FILTER, null,
                ConditionGroup.and(IndicatorCondition.of(
                        "PER", ComparisonOperator.LT, ConditionOperand.Literal.of("10")))));

        // 0으로 읽히면 "id가 0인 사용자의 화면"이 되어 아무에게도 보이지 않는 유령이 된다.
        assertThat(store.findById(saved.id()).orElseThrow().ownerUserId()).isNull();
        assertThat(store.findByCode("TEST_PRESET")).isPresent();
    }

    @Test
    @DisplayName("수정은 조건을 통째로 교체하고 고아 행을 남기지 않는다")
    void updateReplacesAllConditions() {
        ScreenDefinition saved = store.create(ScreenDefinition.custom(
                null, "교체 대상", null, EvaluationMode.FILTER, userId, null,
                ConditionGroup.and(List.of(
                        IndicatorCondition.of("PER", ComparisonOperator.LT, ConditionOperand.Literal.of("10")),
                        IndicatorCondition.of("RSI_14", ComparisonOperator.GT, ConditionOperand.Literal.of("30"))))));

        store.update(new ScreenDefinition(saved.id(), null, "교체됨", null, ScreenType.CUSTOM,
                EvaluationMode.SCORING, userId, null, true,
                ConditionGroup.and(IndicatorCondition.of(
                        "PER", ComparisonOperator.GT, ConditionOperand.Literal.of("20")))));

        ScreenDefinition loaded = store.findById(saved.id()).orElseThrow();
        assertThat(loaded.name()).isEqualTo("교체됨");
        assertThat(loaded.mode()).isEqualTo(EvaluationMode.SCORING);
        assertThat(loaded.conditions().conditions()).hasSize(1);
        assertThat(loaded.conditions().conditions().get(0).operator()).isEqualTo(ComparisonOperator.GT);

        // 옛 조건이 남아 있으면 목록 화면에는 새 조건만 보이는데 실행은 옛 조건까지 적용한다.
        Integer rows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM screen_condition sc
                JOIN condition_group g ON g.id = sc.group_id
                WHERE g.screen_id = ?
                """, Integer.class, saved.id());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제하면 조건 그룹과 조건도 CASCADE로 사라진다")
    void deleteCascades() {
        ScreenDefinition saved = store.create(ScreenDefinition.custom(
                null, "삭제 대상", null, EvaluationMode.FILTER, userId, null,
                ConditionGroup.and(IndicatorCondition.of(
                        "PER", ComparisonOperator.LT, ConditionOperand.Literal.of("10")))));

        assertThat(store.delete(saved.id())).isTrue();

        assertThat(store.findById(saved.id())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM condition_group WHERE screen_id = ?", Integer.class, saved.id()))
                .isZero();
    }

    @Test
    @DisplayName("같은 사용자가 같은 이름을 두 번 쓰면 거절된다")
    void rejectsDuplicateNamePerOwner() {
        store.create(ScreenDefinition.custom(null, "중복 이름", null, EvaluationMode.FILTER,
                userId, null, simpleGroup()));

        assertThatThrownBy(() -> store.create(ScreenDefinition.custom(
                null, "중복 이름", null, EvaluationMode.FILTER, userId, null, simpleGroup())))
                .isInstanceOf(ScreenStore.DuplicateScreenNameException.class);
    }

    @Test
    @DisplayName("다른 사용자는 같은 이름을 쓸 수 있다 — 유니크는 소유자별이다")
    void allowsSameNameForDifferentOwners() {
        store.create(ScreenDefinition.custom(null, "가치주", null, EvaluationMode.FILTER,
                userId, null, simpleGroup()));

        assertThat(store.create(ScreenDefinition.custom(null, "가치주", null, EvaluationMode.FILTER,
                otherUserId, null, simpleGroup())).id()).isPositive();
    }

    @Test
    @DisplayName("목록은 프리셋 전체와 자기 커스텀만 보여준다")
    void listShowsPresetsAndOwnScreensOnly() {
        store.create(ScreenDefinition.preset("LIST_PRESET", "프리셋", null, EvaluationMode.SCORING,
                null, simpleGroup()));
        store.create(ScreenDefinition.custom(null, "내 것", null, EvaluationMode.FILTER,
                userId, null, simpleGroup()));
        store.create(ScreenDefinition.custom(null, "남의 것", null, EvaluationMode.FILTER,
                otherUserId, null, simpleGroup()));

        assertThat(store.listVisibleTo(userId))
                .extracting(ScreenStore.ScreenSummary::name)
                .contains("프리셋", "내 것")
                .doesNotContain("남의 것");
    }

    @Test
    @DisplayName("소유자 없는 커스텀 화면은 DB 제약이 막는다")
    void customScreenRequiresOwnerAtDatabaseLevel() {
        // 도메인 record가 먼저 막지만, 제약이 실제로 걸려 있는지도 확인한다 —
        // 애플리케이션 검증만 믿으면 다른 쓰기 경로가 생겼을 때 조용히 통과한다.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO screen (name, screen_type, evaluation_mode)
                VALUES ('제약 시험', 'CUSTOM', 'FILTER')
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("IMPORTANT — 루트 그룹이 둘 이상이면 조용히 누락하지 않고 실패한다")
    void refusesToLoadNestedGroups() {
        ScreenDefinition saved = store.create(ScreenDefinition.custom(
                null, "중첩 화면", null, EvaluationMode.FILTER, userId, null, simpleGroup()));

        Long rootId = jdbcTemplate.queryForObject(
                "SELECT id FROM condition_group WHERE screen_id = ? AND parent_group_id IS NULL",
                Long.class, saved.id());
        // v2가 저장하기 시작할 형태를 손으로 만든다.
        Long childId = jdbcTemplate.queryForObject("""
                INSERT INTO condition_group (screen_id, parent_group_id, logical_operator)
                VALUES (?, ?, 'OR') RETURNING id
                """, Long.class, saved.id(), rootId);
        jdbcTemplate.update("""
                INSERT INTO screen_condition (group_id, indicator_code, operator, operand_type, literal_value)
                VALUES (?, 'RSI_14', 'GT', 'LITERAL', 70)
                """, childId);

        // 루트만 읽으면 RSI 조건이 사라진 채 실행되어 더 느슨한 결과가 나온다.
        assertThatThrownBy(() -> store.findById(saved.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("중첩 조건 그룹");
    }

    private static ConditionGroup simpleGroup() {
        return ConditionGroup.and(IndicatorCondition.of(
                "PER", ComparisonOperator.LT, ConditionOperand.Literal.of("10")));
    }

    private long insertUser(String sub, String email) {
        Optional<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM app_user WHERE provider_user_id = ?", Long.class, sub)
                .stream().findFirst();
        return existing.orElseGet(() -> jdbcTemplate.queryForObject("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES (?, '테스트', 'GOOGLE', ?, false, true) RETURNING id
                """, Long.class, email, sub));
    }
}
