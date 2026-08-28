package com.stockscreener.screening.query;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.stockscreener.screening.ConditionEvaluator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.stock.QIndicatorValueEntity;
import com.stockscreener.stock.QStockEntity;
import com.stockscreener.stock.QUniverseEntity;
import com.stockscreener.stock.QUniverseMembershipEntity;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 조건을 실행해 종목을 고르고, 조건별 선정 이유를 함께 낸다.
 *
 * <p>조건 개수와 무관하게 쿼리는 <b>항상 2개</b>다.
 * <ol>
 *   <li>조건에 맞는 종목 id를 고른다 (FILTER는 EXISTS AND, SCORING은 전체 종목).
 *   <li>그 종목들의 필요한 지표 값을 한 번에 가져온다.
 * </ol>
 * SELECT 절에 조건 수만큼 CASE 컬럼을 붙이지 않는다 — 조건이 15개면 컬럼이 15개 늘어나고,
 * 그 SQL은 요청마다 모양이 달라져 계획 캐시도 못 탄다.
 *
 * <p>IMPORTANT — 선정 이유는 {@link ConditionEvaluator}가 만든다. SQL이 종목을 고른 근거와
 * 화면에 보여주는 설명이 같은 판정 로직({@code IndicatorCondition.evaluate})에서 나오므로
 * 어긋날 수 없다. 이 판정 로직은 Phase 6의 워치리스트 알림에서도 그대로 재사용된다.
 */
@Repository
public class ScreeningRepository {

    private final JPAQueryFactory queryFactory;
    private final ConditionTranslator translator;
    private final ConditionEvaluator evaluator;

    public ScreeningRepository(EntityManager entityManager,
                               ConditionTranslator translator,
                               ConditionEvaluator evaluator) {
        this.queryFactory = new JPAQueryFactory(entityManager);
        this.translator = translator;
        this.evaluator = evaluator;
    }

    /**
     * 조건을 실행한다. 대상은 활성 종목 전체다.
     *
     * @param group 조건 그룹
     * @param mode  판정 방식
     * @param asOf  기준 일자
     * @return SCORING이면 점수 내림차순, FILTER면 종목 id 순
     */
    @Transactional(readOnly = true)
    public List<ScreeningResult> screen(ConditionGroup group, EvaluationMode mode, LocalDate asOf) {
        return screen(group, mode, asOf, null);
    }

    /**
     * 유니버스를 한정해 조건을 실행한다.
     *
     * @param universeCode 현재 이 유니버스에 편입된 종목만 대상으로 한다. {@code null}이면 전체.
     */
    @Transactional(readOnly = true)
    public List<ScreeningResult> screen(ConditionGroup group, EvaluationMode mode, LocalDate asOf,
                                        String universeCode) {
        List<Long> stockIds = selectStockIds(group, mode, asOf, universeCode);
        if (stockIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Map<String, BigDecimal>> valuesByStock =
                loadIndicatorValues(stockIds, group.referencedIndicatorCodes(), asOf);

        List<ScreeningResult> results = new ArrayList<>(stockIds.size());
        for (Long stockId : stockIds) {
            Map<String, BigDecimal> values = valuesByStock.getOrDefault(stockId, Map.of());
            ScreeningResult result = evaluator.evaluate(stockId, group, mode, values);
            if (result.included()) {
                results.add(result);
            }
        }

        if (mode == EvaluationMode.SCORING) {
            results.sort(Comparator.comparing(ScreeningResult::score).reversed()
                    .thenComparing(ScreeningResult::stockId));
        }
        return results;
    }

    /**
     * 1번 쿼리 — 대상 종목 id.
     *
     * <p>FILTER는 조건을 EXISTS 술어로 번역해 DB에서 좁힌다.
     * SCORING은 좁히지 않는다 — 일부 조건만 만족한 종목도 순위에 남아야 하기 때문이다.
     *
     * <p>유니버스 한정은 조건 트리와 무관한 <b>바깥 술어</b>다. 조건 개수에 비례하지 않으므로
     * "쿼리는 항상 2개"가 유지되고, SCORING에서도 적용된다 — 코스피 화면에 코스닥 종목이
     * 0점으로 섞여 나오면 순위의 의미가 무너진다.
     *
     * <p><b>유니버스를 지정하지 않아도 좁힌다</b>(ADR-0087). "전체"는 {@code stock} 전량이
     * 아니라 <b>활성 유니버스 중 하나에라도 편입된 종목</b>이다. 프리셋은 대부분
     * {@code universeCode}가 null이고 SCORING은 좁히지 않으므로, 좁히지 않으면 지표가 하나도
     * 없는 시장의 종목이 <b>0점·전 조건 판정불가로 응답에 그대로 실린다</b> — 그리고 서버가
     * 전량을 내려보내므로(ADR-0016) 그대로 화면까지 간다.
     */
    private List<Long> selectStockIds(ConditionGroup group, EvaluationMode mode, LocalDate asOf,
                                      String universeCode) {
        QStockEntity stock = QStockEntity.stockEntity;

        var query = queryFactory.select(stock.id).from(stock).where(stock.active.isTrue());
        query.where(universeCode != null ? currentlyIn(universeCode, stock) : inAnyActiveUniverse(stock));
        if (mode == EvaluationMode.FILTER) {
            query.where(translator.toPredicate(group, stock, asOf));
        }
        return query.orderBy(stock.id.asc()).fetch();
    }

    /**
     * 현재 이 유니버스에 편입되어 있는가.
     *
     * <p>{@code effective_to IS NULL}이 "현재 편입 중"이다(V1의 반열림 구간 설계).
     * 기준 일자가 아니라 <b>지금</b>을 보는 이유는 스크리닝이 언제나 최신 일자 한 장을 읽고,
     * 과거 시점의 지수 구성으로 되돌아가 조회하는 화면이 v1에 없기 때문이다. 그런 화면이
     * 생기면 여기에 {@code asOf}를 넣어 {@code [from, to)} 포함 여부로 바꾼다.
     */
    private static BooleanExpression currentlyIn(String universeCode, QStockEntity stock) {
        QUniverseMembershipEntity membership = QUniverseMembershipEntity.universeMembershipEntity;
        return JPAExpressions.selectOne()
                .from(membership)
                .where(membership.stockId.eq(stock.id)
                        .and(membership.universeCode.eq(universeCode))
                        .and(membership.effectiveTo.isNull()))
                .exists();
    }

    /**
     * 활성 유니버스 중 하나에라도 지금 편입되어 있는가.
     *
     * <p>{@code universe}를 조인하는 것이 요점이다. 편입 행은 유니버스를 비활성으로 내려도
     * 남으므로, {@code universe_membership}만 보면 <b>꺼 둔 유니버스의 종목이 계속 걸린다.</b>
     * {@code PercentileCalculator}가 같은 조인을 갖고 있어 모집단이 그쪽과 어긋나지 않는다.
     */
    private static BooleanExpression inAnyActiveUniverse(QStockEntity stock) {
        QUniverseMembershipEntity membership = QUniverseMembershipEntity.universeMembershipEntity;
        QUniverseEntity universe = QUniverseEntity.universeEntity;
        return JPAExpressions.selectOne()
                .from(membership)
                .join(universe).on(universe.code.eq(membership.universeCode))
                .where(membership.stockId.eq(stock.id)
                        .and(membership.effectiveTo.isNull())
                        .and(universe.active.isTrue()))
                .exists();
    }

    /**
     * 2번 쿼리 — 필요한 지표 값을 한 번에.
     *
     * <p>계산되지 않은 지표는 행이 없으므로 결과 맵에 키가 없다. 그것이 판정에서
     * "판정 불가"가 되는 경로다.
     *
     * <p>{@code public}인 이유는 결과 표가 <b>조건에 쓰이지 않은 지표</b>(기본 컬럼)도 보여주기
     * 때문이다. 표시용으로 별도 조회 코드를 만들면 같은 EAV 접근이 두 벌이 되고, 한쪽만
     * {@code trade_date} 조건을 놓치면 화면의 값과 판정에 쓴 값이 다른 날짜가 된다.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, BigDecimal>> loadIndicatorValues(
            List<Long> stockIds, Set<String> indicatorCodes, LocalDate asOf) {
        if (stockIds.isEmpty() || indicatorCodes.isEmpty()) {
            return Map.of();
        }
        QIndicatorValueEntity iv = QIndicatorValueEntity.indicatorValueEntity;

        List<Tuple> rows = queryFactory
                .select(iv.stockId, iv.indicatorCode, iv.value)
                .from(iv)
                .where(iv.tradeDate.eq(asOf)
                        .and(iv.stockId.in(stockIds))
                        .and(iv.indicatorCode.in(indicatorCodes)))
                .fetch();

        Map<Long, Map<String, BigDecimal>> byStock = new HashMap<>();
        for (Tuple row : rows) {
            byStock.computeIfAbsent(row.get(iv.stockId), k -> new LinkedHashMap<>())
                    .put(row.get(iv.indicatorCode), row.get(iv.value));
        }
        return byStock;
    }
}
