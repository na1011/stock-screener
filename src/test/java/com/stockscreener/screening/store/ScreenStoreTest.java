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
import java.util.List;
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
                       ('SMA_200','SMA200','TECHNICAL','PRICE')
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
