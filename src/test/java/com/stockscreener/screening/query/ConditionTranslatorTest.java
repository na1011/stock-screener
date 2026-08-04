package com.stockscreener.screening.query;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.stock.QStockEntity;
import com.stockscreener.support.JpaSliceTest;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 번역된 쿼리의 <b>형태</b>를 검증한다. 결과가 맞는지는 {@link ScreeningRepositoryTest}가 본다.
 *
 * <p>형태를 따로 검증하는 이유는, 결과가 맞더라도 쿼리 모양이 무너지면(예: 조건마다 JOIN이
 * 늘어나거나 DISTINCT가 필요해지면) 확장성과 성능 전제가 깨지기 때문이다.
 */
@JpaSliceTest
@Import(ConditionTranslator.class)
class ConditionTranslatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 4);

    @Autowired
    private ConditionTranslator translator;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("조건 N개가 EXISTS N개로 번역된다")
    void nConditionsProduceNExists() {
        for (int n = 1; n <= 5; n++) {
            String sql = toSql(groupOf(n));
            assertThat(countOccurrences(sql, "exists"))
                    .as("조건 %d개", n)
                    .isEqualTo(n);
        }
    }

    @Test
    @DisplayName("행 증식이 없어 DISTINCT가 필요 없다 — JOIN이 아니라 EXISTS를 쓰는 이유 중 하나")
    void doesNotNeedDistinct() {
        String jpql = toSql(groupOf(3));

        // 조건이 늘어도 바깥 쿼리는 stock 하나만 훑는다. JOIN이었다면 조건마다 조인이 늘고
        // 행이 증식해 DISTINCT가 필요해진다.
        assertThat(jpql).doesNotContain("distinct");
        assertThat(jpql).doesNotContain(" join ");
        assertThat(countOccurrences(jpql, "from stockentity")).isEqualTo(1);
    }

    @Test
    @DisplayName("서브쿼리가 바깥 종목에 상관된다 — 종목마다 독립적으로 판정된다")
    void subqueriesAreCorrelated() {
        String jpql = toSql(groupOf(1));

        // iv0.stockId = stockEntity.id 형태의 상관 조건이 있어야 한다.
        // 이것이 없으면 서브쿼리가 종목과 무관해져 모든 종목이 같은 판정을 받는다.
        assertThat(jpql).containsPattern("iv\\d+\\.stockid = stockentity\\.id");
    }

    @Test
    @DisplayName("지표 코드가 쿼리에 상수로 박히지 않고 바인드 파라미터로 나간다")
    void indicatorCodesAreBoundParameters() {
        String jpql = toSql(groupOf(2));

        // 지표 코드가 리터럴이면 지표마다 다른 쿼리가 되어 계획 캐시를 못 탄다.
        assertThat(jpql).doesNotContain("'ind_0'").doesNotContain("'per'");
        assertThat(jpql).containsPattern("indicatorcode = \\?\\d+");
    }

    @Test
    @DisplayName("OR 그룹은 EXISTS를 or로 묶는다 — v1은 만들지 않지만 번역은 이미 지원한다")
    void orGroupCombinesWithOr() {
        ConditionGroup orGroup = ConditionGroup.or(List.of(
                condition("PER", "10"),
                condition("PBR", "1")));

        String sql = toSql(orGroup);

        assertThat(countOccurrences(sql, "exists")).isEqualTo(2);
        assertThat(sql).contains(" or ");
    }

    @Test
    @DisplayName("중첩 그룹이 재귀적으로 번역된다 — A AND (B OR C)")
    void nestedGroupTranslatesRecursively() {
        ConditionGroup inner = ConditionGroup.or(List.of(condition("PBR", "1"), condition("ROE", "15")));
        ConditionGroup outer = new ConditionGroup(
                ConditionGroup.LogicalOperator.AND,
                List.of(condition("PER", "10")),
                List.of(inner));

        String sql = toSql(outer);

        assertThat(countOccurrences(sql, "exists")).isEqualTo(3);
        assertThat(sql).contains(" or ");
    }

    private String toSql(ConditionGroup group) {
        QStockEntity stock = QStockEntity.stockEntity;
        BooleanExpression predicate = translator.toPredicate(group, stock, AS_OF);
        return new JPAQuery<>(entityManager)
                .select(stock.id)
                .from(stock)
                .where(predicate)
                .toString()
                .toLowerCase();
    }

    private static ConditionGroup groupOf(int conditionCount) {
        List<IndicatorCondition> conditions = new ArrayList<>();
        for (int i = 0; i < conditionCount; i++) {
            conditions.add(condition("IND_" + i, "10"));
        }
        return ConditionGroup.and(conditions);
    }

    private static IndicatorCondition condition(String code, String value) {
        return IndicatorCondition.of(code, ComparisonOperator.LT, ConditionOperand.Literal.of(value));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
