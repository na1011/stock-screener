package com.stockscreener.screening.query;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.jpa.JPAExpressions;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.stock.QIndicatorValueEntity;
import com.stockscreener.stock.QStockEntity;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 조건 그룹을 QueryDSL 술어로 번역한다.
 *
 * <p>IMPORTANT — 이 클래스에는 <b>지표 코드에 대한 분기가 없다</b>. 분기는 연산자
 * ({@link ComparisonOperator}, 닫힌 소형 집합)에 대해서만 한다. 지표 코드는 바인드 파라미터로
 * 흘러가는 순수한 데이터다. 이것이 "새 지표를 등록해도 번역기를 고치지 않는다"의 구조적 근거이며,
 * {@code backend.md}가 금지한 하드코딩 분기를 애초에 불가능하게 만든다.
 *
 * <p>조건 하나가 상관 {@code EXISTS} 서브쿼리 하나가 된다. JOIN이 아니라 EXISTS인 이유:
 * <ul>
 *   <li>OR 그룹이 {@code (EXISTS(..) OR EXISTS(..))}로 자연스럽게 확장된다. JOIN은
 *       OUTER JOIN + null 검사로 변형되어야 하므로 트리로 확장할 수 없는 형태가 된다.
 *   <li>행 증식이 없어 {@code DISTINCT}가 불필요하다.
 *   <li>조건마다 독립적으로 커버링 인덱스를 타 가지치기된다 — 지금 그것은 PK 자신이고
 *       {@code (trade_date, indicator_code, stock_id) INCLUDE (value)}다(ADR-0140).
 *       {@code value}가 키가 아니라 INCLUDE라 Index Cond가 아니라 Filter로 걸리는데,
 *       앞 두 키가 슬라이스를 1,500행 안으로 좁혀 유계다.
 * </ul>
 *
 * <p>재귀로 작성한다. v1은 깊이 1만 만들지만 v2의 OR 도입 시 이 클래스를 고치지 않는다.
 */
@Component
public class ConditionTranslator {

    /**
     * 조건 그룹을 하나의 술어로 번역한다.
     *
     * @param group 조건 그룹
     * @param stock 바깥 쿼리의 종목 별칭
     * @param asOf  기준 일자
     */
    public BooleanExpression toPredicate(ConditionGroup group, QStockEntity stock, LocalDate asOf) {
        return translateGroup(group, stock, asOf, new AtomicInteger());
    }

    private BooleanExpression translateGroup(ConditionGroup group, QStockEntity stock,
                                             LocalDate asOf, AtomicInteger aliasCounter) {
        List<BooleanExpression> parts = new ArrayList<>();

        for (IndicatorCondition condition : group.conditions()) {
            parts.add(translateCondition(condition, stock, asOf, aliasCounter));
        }
        // v1에서는 비어 있다. v2의 OR 트리가 여기로 들어온다.
        for (ConditionGroup child : group.childGroups()) {
            parts.add(translateGroup(child, stock, asOf, aliasCounter));
        }

        BooleanExpression[] array = parts.toArray(BooleanExpression[]::new);
        return group.logicalOperator() == ConditionGroup.LogicalOperator.AND
                ? Expressions.allOf(array)
                : Expressions.anyOf(array);
    }

    /** 조건 하나 → 상관 EXISTS 서브쿼리 하나. */
    private BooleanExpression translateCondition(IndicatorCondition condition, QStockEntity stock,
                                                 LocalDate asOf, AtomicInteger aliasCounter) {
        // 별칭에 outer/inner 같은 예약어를 쓰지 않는다 — HQL 구문 오류가 난다.
        QIndicatorValueEntity iv = new QIndicatorValueEntity("iv" + aliasCounter.getAndIncrement());

        BooleanExpression valuePredicate = switch (condition.operand()) {
            case ConditionOperand.Literal literal ->
                    literalPredicate(iv.value, condition.operator(), literal);
            case ConditionOperand.IndicatorRef ref ->
                    indicatorRefPredicate(iv, condition, ref, stock, asOf, aliasCounter);
            // IMPORTANT — 여기서 null을 돌려주면 안 된다. Expressions.allOf가 null 술어를
            // **버리므로** 그 조건이 없는 것처럼 종목이 통과한다 — 조용히 넓어지는 결과다.
            // 실행 경로는 ConditionGroup.resolveFor를 먼저 지나고, 그 통화의 임계가 없으면
            // 요청 단위로 거절되므로 여기에는 도달하지 않는다.
            case ConditionOperand.MarketLiteral ignored -> throw new IllegalStateException(
                    "해석되지 않은 시장별 임계를 SQL로 번역하려 했다: " + condition.indicatorCode()
                            + " — 실행 전에 resolveFor를 거쳐야 한다");
        };

        return JPAExpressions.selectOne()
                .from(iv)
                .where(iv.stockId.eq(stock.id)
                        .and(iv.tradeDate.eq(asOf))
                        .and(iv.indicatorCode.eq(condition.indicatorCode()))
                        .and(valuePredicate))
                .exists();
    }

    /**
     * 값 대 상수 비교. 분기의 축이 연산자라는 것이 여기서 드러난다.
     */
    private BooleanExpression literalPredicate(NumberPath<BigDecimal> value,
                                               ComparisonOperator operator,
                                               ConditionOperand.Literal literal) {
        BigDecimal bound = literal.value();
        return switch (operator) {
            case GT -> value.gt(bound);
            case GTE -> value.goe(bound);
            case LT -> value.lt(bound);
            case LTE -> value.loe(bound);
            case EQ -> value.eq(bound);
            case NEQ -> value.ne(bound);
            case BETWEEN -> value.between(bound, literal.upperBound());
        };
    }

    /**
     * 값 대 다른 지표 비교. "종가 &gt; SMA200 × 1.05" 같은 조건이다.
     *
     * <p>참조 지표의 값을 스칼라 서브쿼리로 가져와 비교한다. 참조 지표가 계산되지 않았으면
     * 서브쿼리가 null을 내고 비교가 거짓이 되어, 그 종목은 자연히 제외된다 —
     * 인메모리 판정의 "판정 불가"와 같은 결과다.
     */
    private BooleanExpression indicatorRefPredicate(QIndicatorValueEntity iv,
                                                    IndicatorCondition condition,
                                                    ConditionOperand.IndicatorRef ref,
                                                    QStockEntity stock,
                                                    LocalDate asOf,
                                                    AtomicInteger aliasCounter) {
        QIndicatorValueEntity refIv = new QIndicatorValueEntity("iv" + aliasCounter.getAndIncrement());

        // 참조 지표의 값에 계수를 적용한 실효 기준값.
        NumberExpression<BigDecimal> bound = ref.multiplier() == null
                ? refIv.value
                : refIv.value.multiply(ref.multiplier());

        BooleanExpression comparison = switch (condition.operator()) {
            case GT -> iv.value.gt(bound);
            case GTE -> iv.value.goe(bound);
            case LT -> iv.value.lt(bound);
            case LTE -> iv.value.loe(bound);
            case EQ -> iv.value.eq(bound);
            case NEQ -> iv.value.ne(bound);
            case BETWEEN -> throw new IllegalArgumentException(
                    "BETWEEN은 지표 참조 피연산자를 지원하지 않는다. 상·하한을 각각 조건으로 나누어 표현하라.");
        };

        // 참조 지표 행이 존재하고 비교가 성립해야 한다. 참조 지표가 계산되지 않았으면
        // EXISTS가 거짓이 되어 그 종목은 자연히 제외된다 — 인메모리 판정의 "판정 불가"와 같은 결과다.
        return JPAExpressions.selectOne()
                .from(refIv)
                .where(refIv.stockId.eq(stock.id)
                        .and(refIv.tradeDate.eq(asOf))
                        .and(refIv.indicatorCode.eq(ref.indicatorCode()))
                        .and(comparison))
                .exists();
    }
}
