package com.stockscreener.screening.query;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.stockscreener.screening.ConditionEvaluator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOutcome;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.stock.QIndicatorValueEntity;
import com.stockscreener.stock.QStockEntity;
import com.stockscreener.stock.QUniverseEntity;
import com.stockscreener.stock.QUniverseMembershipEntity;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>ADR-0142가 그 불변식에 갈래 하나를 냈다.</b> {@link #diagnose}는 FILTER에서 모집단
 * 질의를 하나 더 내고, <b>결과가 0건일 때만</b> 지표 질의를 하나 더 낸다. 둘 다 <b>조건 수와
 * 무관한 상수</b>라 위 문장이 막으려던 것(조건 개수에 비례하는 증가)은 그대로 막힌다.
 * <b>「항상 2개」를 근거로 이 갈래를 걷지 않는다</b> — 걷으면 0건 화면이 다시 아무 이유도
 * 말하지 않는다.
 *
 * <p>IMPORTANT — 선정 이유는 {@link ConditionEvaluator}가 만든다. SQL이 종목을 고른 근거와
 * 화면에 보여주는 설명이 같은 판정 로직({@code IndicatorCondition.evaluate})에서 나오므로
 * 어긋날 수 없다. 이 판정 로직은 Phase 6의 워치리스트 알림에서도 그대로 재사용된다.
 */
@Repository
public class ScreeningRepository {

    private static final Logger log = LoggerFactory.getLogger(ScreeningRepository.class);

    /**
     * 실행 결과와 <b>왜 그 결과인가</b>를 함께 든다(ADR-0142).
     *
     * @param populationCount 그 기준일에 <b>판정 대상</b>이던 종목 수. 조건을 안 건 수이고,
     *                        행이 0개여도 산다
     * @param results         오늘 {@code screen}이 내던 그것
     * @param tallies         조건 순서와 <b>같은 길이</b>. 귀속을 참으로 만들 수 없을 때는 비어 있다
     * @param minimumBlocked  통과한 종목이 없을 때, <b>가장 적게 걸린 종목이 못 넘긴 관문 수</b>.
     *                        한 조건만 풀어서는 안 열린다는 것을 이 수가 말한다. 통과가 있으면 null
     */
    public record ScreeningOutcome(int populationCount, List<ScreeningResult> results,
                                   List<ConditionTally> tallies, Integer minimumBlocked) {
    }

    /**
     * 조건 하나에 대한 귀속.
     *
     * @param conditionIndex    {@code ConditionGroup.flattenConditions()}의 자리. 지표 코드가 아니다 —
     *                          같은 지표의 상·하한 쌍이 한 화면에 설 수 있고 코드로 세면 합쳐진다
     * @param notMatched        조건을 못 넘긴 종목 수
     * @param notEvaluable      <b>값이 없어</b> 판정하지 못한 종목 수. 위와 갈라 세는 것이 요점이다
     * @param wouldPassWithout  이 조건 하나만 뺐을 때의 통과 수(ADR-0070의 한계 기여).
     *                          관문이 아니거나 AND 트리가 아니면 null이다
     */
    public record ConditionTally(int conditionIndex, int notMatched, int notEvaluable,
                                 Integer wouldPassWithout) {
    }

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
     * 조건을 실행한다. 대상은 <b>그 기준일에</b> 활성 유니버스에 편입돼 있던 종목이다.
     *
     * <p>{@code atLatest}를 안 받는 갈래이므로 <b>최신일 조회로 본다.</b> 배치·워치리스트가
     * 언제나 그 시장의 최신 지표 일자로 부르기 때문이다.
     *
     * @param group 조건 그룹
     * @param mode  판정 방식
     * @param asOf  기준 일자
     * @return SCORING이면 점수 내림차순, FILTER면 종목 id 순
     */
    @Transactional(readOnly = true)
    public List<ScreeningResult> screen(ConditionGroup group, EvaluationMode mode, LocalDate asOf) {
        return screen(group, mode, asOf, true, null);
    }

    /**
     * 유니버스를 한정하되 <b>최신일 조회로</b> 실행한다.
     *
     * <p><b>백테스트 하네스가 이 갈래를 쓴다.</b> 과거 {@code asOf}를 주면서 편입은 「지금」으로
     * 보는 것이 <b>그 하네스가 안고 가는 생존 편향</b>이고, ADR-0135가 그것을 <b>일부러 안
     * 바꿨다</b> — {@code ConditionReturnTest}의 앵커 여덟 중 <b>일곱이 복원 창 밖</b>이라
     * 시점 편입으로 바꾸면 그 일곱에서 대상이 0건이 된다. 넘기려면 복원 창을 백테스트 창까지
     * 넓혀야 하고 그것은 P17 축이다.
     *
     * <p><b>MUST NOT: 화면 경로가 이 갈래를 쓰지 않는다.</b> 그쪽은 {@code atLatest}를 실제로
     * 계산해 넘긴다({@code ScreeningService}).
     */
    @Transactional(readOnly = true)
    public List<ScreeningResult> screen(ConditionGroup group, EvaluationMode mode, LocalDate asOf,
                                        String universeCode) {
        return screen(group, mode, asOf, true, universeCode);
    }

    /**
     * 유니버스를 한정해 조건을 실행한다.
     *
     * @param atLatest {@code asOf}가 그 시장의 <b>지표 최신 일자</b>인가. 원장과 지표의 시계가
     *                 달라, 최신일 조회에서는 <b>열린 편입의 하한을 접어야</b> 토요일 리밸런싱
     *                 직후의 신규 편입이 안 사라진다(ADR-0135). 과거 조회에서는 절대 안 접는다
     * @param universeCode 그 기준일에 이 유니버스에 편입돼 있던 종목만 대상으로 한다.
     *                     {@code null}이면 활성 유니버스 전체.
     */
    @Transactional(readOnly = true)
    public List<ScreeningResult> screen(ConditionGroup group, EvaluationMode mode, LocalDate asOf,
                                        boolean atLatest, String universeCode) {
        return diagnose(group, mode, asOf, atLatest, universeCode).results();
    }

    /**
     * 조건을 실행하고 <b>왜 그 결과가 나왔는지</b>를 함께 낸다(ADR-0142).
     *
     * <p>{@link #screen}이 버리던 것을 줍는다 — 탈락 종목의 {@code outcomes}는 이미 계산돼 있고
     * 루프 밖으로 한 바이트도 안 나가고 있었다. <b>SCORING에서는 추가 평가가 0회다.</b>
     *
     * <p>FILTER는 다르다. {@link #selectStockIds}가 조건을 SQL 술어로 내려 좁히므로 탈락 종목이
     * {@code evaluate}에 도달조차 못 한다. 그래서 <b>결과가 0건일 때만</b> 술어 없이 모집단을
     * 다시 돌아 귀속을 만든다.
     *
     * <p><b>MUST: FILTER가 0건이 아니면 귀속을 내지 않는다(빈 목록).</b> 통과분만 보고 세면
     * 「이 조건에 걸린 종목이 0이다」가 나오는데 <b>그것은 거짓이다</b> — 탈락분을 안 봤을 뿐이다.
     */
    @Transactional(readOnly = true)
    public ScreeningOutcome diagnose(ConditionGroup group, EvaluationMode mode, LocalDate asOf,
                                     boolean atLatest, String universeCode) {
        List<Long> matching = selectStockIds(group, mode, asOf, atLatest, universeCode);
        // 모집단은 「조건을 안 걸었을 때의 대상」이다. SCORING은 그 자체이고, FILTER만 다시 묻는다.
        List<Long> population = mode == EvaluationMode.FILTER
                ? selectStockIds(group, EvaluationMode.SCORING, asOf, atLatest, universeCode)
                : matching;
        // FILTER 0건이면 귀속을 만들려고 모집단을 돈다. 그 외에는 오늘과 같은 집합을 돈다.
        boolean secondPass = mode == EvaluationMode.FILTER && matching.isEmpty();
        List<Long> stockIds = secondPass ? population : matching;
        if (stockIds.isEmpty()) {
            return new ScreeningOutcome(population.size(), List.of(), List.of(), null);
        }

        Map<Long, Map<String, BigDecimal>> valuesByStock =
                loadIndicatorValues(stockIds, group.referencedIndicatorCodes(), asOf);

        List<IndicatorCondition> conditions = group.flattenConditions();
        Tally tally = new Tally(conditions.size());
        List<ScreeningResult> results = new ArrayList<>(stockIds.size());
        for (Long stockId : stockIds) {
            Map<String, BigDecimal> values = valuesByStock.getOrDefault(stockId, Map.of());
            ScreeningResult result = evaluator.evaluate(stockId, group, mode, values);
            tally.add(result, mode);
            if (result.included()) {
                results.add(result);
            }
        }

        if (secondPass && !results.isEmpty()) {
            // MUST: 삼키지 않는다. SQL이 0건을 냈는데 인메모리가 통과분을 찾았다면 두 경로가
            // 갈린 것이고, 그때 화면에 뜨는 진단은 사용자가 방금 본 결과를 설명하지 못한다.
            log.warn("FILTER가 SQL로 0건인데 인메모리 재판정은 {}종목을 통과시켰다 — "
                    + "ConditionTranslator와 IndicatorCondition.evaluate가 갈렸다.", results.size());
        }
        if (mode == EvaluationMode.SCORING) {
            results.sort(Comparator.comparing(ScreeningResult::score).reversed()
                    .thenComparing(ScreeningResult::stockId));
        }
        boolean tallyIsTrue = mode == EvaluationMode.SCORING || secondPass;
        return new ScreeningOutcome(population.size(),
                secondPass ? List.of() : results,
                tallyIsTrue ? tally.toTallies(group, mode, results.size()) : List.of(),
                tallyIsTrue ? tally.minimumBlocked() : null);
    }

    /**
     * 한 번의 판정 패스에서 조건별 귀속을 줍는다.
     *
     * <p><b>관문의 정의가 모드마다 다르다</b> — SCORING은 {@code required()}만, FILTER는
     * {@code included}가 {@code passes}이므로 <b>전 조건</b>이다({@code ConditionEvaluator}).
     * {@code GateDiagnosticsTest}는 언제나 {@code required}만 세므로 <b>두 수를 견주지 않는다.</b>
     *
     * <p><b>「정확히 하나」만 귀속한다.</b> 여럿에 걸린 종목은 어디에도 안 센다 — 그 종목에서
     * 어느 쪽이 원인인지는 이 자료로 정해지지 않는다.
     */
    private static final class Tally {

        private final int[] notMatched;
        private final int[] notEvaluable;
        private final int[] soleBlocker;
        private int minimumBlocked = Integer.MAX_VALUE;

        Tally(int conditionCount) {
            this.notMatched = new int[conditionCount];
            this.notEvaluable = new int[conditionCount];
            this.soleBlocker = new int[conditionCount];
        }

        void add(ScreeningResult result, EvaluationMode mode) {
            List<ConditionOutcome> outcomes = result.outcomes();
            int blocked = 0;
            int blockedAt = -1;
            for (int i = 0; i < outcomes.size() && i < notMatched.length; i++) {
                ConditionOutcome outcome = outcomes.get(i);
                if (outcome.status() == ConditionOutcome.Status.NOT_MATCHED) {
                    notMatched[i]++;
                } else if (outcome.status() == ConditionOutcome.Status.NOT_EVALUABLE) {
                    notEvaluable[i]++;
                }
                if (isGate(outcome.condition(), mode) && !outcome.passesFilter()) {
                    blocked++;
                    blockedAt = i;
                }
            }
            if (blocked == 1) {
                soleBlocker[blockedAt]++;
            }
            if (blocked > 0) {
                minimumBlocked = Math.min(minimumBlocked, blocked);
            }
        }

        /**
         * 이 조건이 <b>관문</b>인가 — 못 넘기면 결과에서 빠지는가.
         *
         * <p>SCORING은 {@code satisfiesRequired}가 정하므로 {@code required()}만 관문이고,
         * FILTER는 {@code included}가 {@code passes}라 <b>전 조건</b>이 관문이다
         * ({@code ConditionEvaluator}). <b>이 술어가 한 벌이어야 한다</b> — 세는 자리와
         * 내는 자리가 갈리면 한쪽이 조용히 비어도 아무 시험이 안 붉는다.
         */
        private static boolean isGate(IndicatorCondition condition, EvaluationMode mode) {
            return mode != EvaluationMode.SCORING || condition.required();
        }

        /** 통과가 하나도 없을 때만 뜻이 있다. 그 외에는 null이다. */
        Integer minimumBlocked() {
            return minimumBlocked == Integer.MAX_VALUE ? null : minimumBlocked;
        }

        List<ConditionTally> toTallies(ConditionGroup group, EvaluationMode mode, int includedCount) {
            List<IndicatorCondition> conditions = group.flattenConditions();
            // MUST: AND 트리에서만 한계 기여를 낸다. 「정확히 하나」는 논리곱에 대한 정리다.
            boolean conjunctive = group.childGroups().isEmpty()
                    && group.logicalOperator() == ConditionGroup.LogicalOperator.AND;
            List<ConditionTally> tallies = new ArrayList<>(conditions.size());
            for (int i = 0; i < conditions.size(); i++) {
                // MUST: add()와 **같은 술어**를 쓴다. 둘이 갈리면 FILTER에서 전 조건이
                // 「관문이 아니다」가 되어 한계 기여가 통째로 null이 되는데, 화면에는 그 칸이
                // 그냥 안 보일 뿐이라 시험도 타입검사도 안 붉는다(실측으로 그렇게 났다).
                Integer marginal = conjunctive && isGate(conditions.get(i), mode)
                        ? includedCount + soleBlocker[i] : null;
                tallies.add(new ConditionTally(i, notMatched[i], notEvaluable[i], marginal));
            }
            return tallies;
        }
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
                                      boolean atLatest, String universeCode) {
        QStockEntity stock = QStockEntity.stockEntity;

        var query = queryFactory.select(stock.id).from(stock);
        // MUST: stock.active를 최신일에서만 건다. 「오늘 상장돼 있는가」는 오늘의 사실이라,
        // 과거 기준일 질의에 끼우면 그 뒤 상장폐지된 종목이 조용히 빠진다 — 이 결정이 지우려는
        // 편향과 같은 모양이다(ADR-0135). 과거에는 편입 구간이 그 보증을 대신한다: 그날 상위
        // N이었다는 것은 그날 상장돼 있었다는 뜻이다.
        if (atLatest) {
            query.where(stock.active.isTrue());
        }
        query.where(universeCode != null
                ? memberOn(universeCode, stock, asOf, atLatest)
                : inAnyActiveUniverseOn(stock, asOf, atLatest));
        if (mode == EvaluationMode.FILTER) {
            query.where(translator.toPredicate(group, stock, asOf));
        }
        return query.orderBy(stock.id.asc()).fetch();
    }

    /**
     * 그 기준일에 편입돼 있었는가 — {@code [effective_from, effective_to)} 포함 판정.
     *
     * <p><b>{@code gt}이지 {@code goe}가 아니다.</b> 구간이 반열림이라 끝점은 배타이고,
     * {@code goe}로 쓰면 <b>제외 당일이 편입으로 잡힌다</b> — 값이 그럴듯해 안 드러난다.
     *
     * <p><b>MUST: 최신일 조회에서는 열린 행의 하한을 접는다.</b> 원장의 시계와 지표의 시계가
     * 다르기 때문이다 — 토요일 리밸런싱이 {@code effective_from = 금요일}을 찍는데 그 금요일
     * 지표는 다음 평일에야 생긴다. 그 41시간 동안 {@code effective_from > asOf}인 열린 행이
     * 생기고, 하한을 그대로 걸면 <b>신규 편입이 화면에서 조용히 사라진다</b>(실측: 지표
     * 최신일이 2026-09-03이었다면 47행). <b>과거 조회에서는 절대 접지 않는다</b> — 접으면
     * 오늘 편입 중인 종목이 과거 전 날짜에 실려 이 결정이 지우려는 편향 그 자체가 된다.
     */
    private static BooleanExpression memberOn(String universeCode, QStockEntity stock,
                                              LocalDate asOf, boolean atLatest) {
        QUniverseMembershipEntity membership = QUniverseMembershipEntity.universeMembershipEntity;
        return JPAExpressions.selectOne()
                .from(membership)
                .where(membership.stockId.eq(stock.id)
                        .and(membership.universeCode.eq(universeCode))
                        .and(covers(membership, asOf, atLatest)))
                .exists();
    }

    /**
     * 활성 유니버스 중 하나에라도 그 기준일에 편입돼 있었는가.
     *
     * <p>{@code universe}를 조인하는 것이 요점이다. 편입 행은 유니버스를 비활성으로 내려도
     * 남으므로, {@code universe_membership}만 보면 <b>꺼 둔 유니버스의 종목이 계속 걸린다.</b>
     * {@code PercentileCalculator}가 같은 조인을 갖고 있어 모집단이 그쪽과 어긋나지 않는다 —
     * <b>MUST: 그래서 이 술어와 그 계산기는 한 배포로 함께 움직인다</b>(ADR-0135).
     */
    private static BooleanExpression inAnyActiveUniverseOn(QStockEntity stock, LocalDate asOf,
                                                           boolean atLatest) {
        QUniverseMembershipEntity membership = QUniverseMembershipEntity.universeMembershipEntity;
        QUniverseEntity universe = QUniverseEntity.universeEntity;
        return JPAExpressions.selectOne()
                .from(membership)
                .join(universe).on(universe.code.eq(membership.universeCode))
                .where(membership.stockId.eq(stock.id)
                        .and(covers(membership, asOf, atLatest))
                        .and(universe.active.isTrue()))
                .exists();
    }

    private static BooleanExpression covers(QUniverseMembershipEntity membership,
                                            LocalDate asOf, boolean atLatest) {
        BooleanExpression open = membership.effectiveTo.isNull();
        BooleanExpression within = membership.effectiveFrom.loe(asOf)
                .and(open.or(membership.effectiveTo.gt(asOf)));
        return atLatest ? within.or(open) : within;
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
