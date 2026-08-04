package com.stockscreener.screening.query;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.stockscreener.screening.ConditionEvaluator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.stock.QIndicatorValueEntity;
import com.stockscreener.stock.QStockEntity;

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
     * 조건을 실행한다.
     *
     * @param group 조건 그룹
     * @param mode  판정 방식
     * @param asOf  기준 일자
     * @return SCORING이면 점수 내림차순, FILTER면 종목 id 순
     */
    @Transactional(readOnly = true)
    public List<ScreeningResult> screen(ConditionGroup group, EvaluationMode mode, LocalDate asOf) {
        List<Long> stockIds = selectStockIds(group, mode, asOf);
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
     */
    private List<Long> selectStockIds(ConditionGroup group, EvaluationMode mode, LocalDate asOf) {
        QStockEntity stock = QStockEntity.stockEntity;

        var query = queryFactory.select(stock.id).from(stock).where(stock.active.isTrue());
        if (mode == EvaluationMode.FILTER) {
            query.where(translator.toPredicate(group, stock, asOf));
        }
        return query.orderBy(stock.id.asc()).fetch();
    }

    /**
     * 2번 쿼리 — 필요한 지표 값을 한 번에.
     *
     * <p>계산되지 않은 지표는 행이 없으므로 결과 맵에 키가 없다. 그것이 판정에서
     * "판정 불가"가 되는 경로다.
     */
    private Map<Long, Map<String, BigDecimal>> loadIndicatorValues(
            List<Long> stockIds, Set<String> indicatorCodes, LocalDate asOf) {
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
