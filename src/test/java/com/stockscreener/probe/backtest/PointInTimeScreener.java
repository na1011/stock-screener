package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.indicator.SectorMetricSpec;
import com.stockscreener.screening.ConditionEvaluator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.ScreeningResult;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * as-of 하루의 지표를 <b>인메모리로 다시 계산해</b> 프리셋을 판정한다(P17, ADR-0071).
 *
 * <p>MUST NOT: {@code indicator_value}를 읽지 않는다. <b>이것이 이 클래스의 존재 이유이자
 * 가장 어기기 쉬운 규칙이고, 어겨도 결과가 나온다.</b> 저장된 지표는 최신 재무 하나를 읽기 창
 * 전 봉에 펴 놓은 값이라 <b>시간축이 없다</b> — 과거 일자에 게이트를 걸면 그럴듯한 시계열이
 * 나오는데 그 행의 {@code updated_at}이 전부 오늘이다.
 *
 * <p>라이브 경로({@code ScreeningRepository.screen})와 <b>같은 모양을 유지한다</b> — 대상 선정,
 * {@link ConditionEvaluator} 호출, {@code included()} 필터, 정렬 순서까지 같다. 다른 것은
 * <b>값의 출처 하나뿐</b>이고, 그래야 「기준일=오늘로 돌린 통과 집합이 화면과 같은가」가
 * 값의 출처만을 검사하는 물음이 된다.
 */
@Component
public class PointInTimeScreener {

    /**
     * 업종 백분위의 최소 표본. {@code SectorRelativeCalculator.MIN_PEERS}와 같아야 한다 —
     * 그 상수가 package-private이라 값을 옮겨 왔고, 갈리면 하네스만 다른 모집단을 쓴다.
     */
    private static final int MIN_PEERS = 5;

    /**
     * 이 탐침이 인메모리로 다시 만드는 업종 상대값의 종류.
     *
     * <p>{@link SectorMetricSpec.Kind#MEDIAN}과 표본 수는 빠져 있다 — 지금 어떤 조건도 그 둘을
     * 참조하지 않기 때문이고, 참조가 생기면 {@link #addSectorRelatives}가 던진다.
     */
    private static final Set<SectorMetricSpec.Kind> RECONSTRUCTED_KINDS =
            EnumSet.of(SectorMetricSpec.Kind.PERCENTILE, SectorMetricSpec.Kind.MEDIAN_RATIO);

    private final IndicatorRunner runner;
    private final ConditionEvaluator evaluator;
    private final JdbcTemplate jdbcTemplate;

    public PointInTimeScreener(IndicatorRunner runner, ConditionEvaluator evaluator,
                               JdbcTemplate jdbcTemplate) {
        this.runner = runner;
        this.evaluator = evaluator;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 한 as-of의 판정 결과.
     *
     * @param included    통과(=포함)한 종목. 라이브와 같은 순서다
     * @param valuesByStock 종목 → 그날 계산된 지표 값. 진단 축이 분위 이동을 여기서 잰다
     * @param evaluated   판정을 시도한 종목 수. <b>통과 0의 원인을 가르는 데 이 수가 필요하다</b>
     * @param resolvedConditions 그 대상의 통화로 해석된 조건. <b>호출부는 이것을 써야 한다</b> —
     *                    원본을 다시 쓰면 {@code MarketLiteral}에서 던지거나, 던지지 않더라도
     *                    임계를 문자열에서 긁게 되어 통화가 뒤바뀐다
     */
    public record Outcome(LocalDate asOf, List<ScreeningResult> included,
                          Map<Long, Map<String, BigDecimal>> valuesByStock, int evaluated,
                          ConditionGroup resolvedConditions) {
    }

    /**
     * MUST: {@code asOf}는 {@code price_daily}에 있는 <b>거래일</b>이어야 한다. 휴장일을 주면
     * {@code computeAt}이 전 종목 빈 맵을 내고 필수 조건이 모두 판정불가가 되어 통과가 0이 되는데,
     * <b>그것은 게이트가 아니라 날짜 탓이다.</b> 호출부가 {@link AsOfCalendar}로 <b>그 대상의
     * 시장에서</b> 고른다.
     *
     * <p><b>모집단이 둘이고 일부러 다르다</b>(ADR-0108).
     * <ul>
     *   <li><b>값 계산·업종 백분위</b> — 활성 유니버스 편입 <b>전체</b>. 라이브
     *       {@code SectorRelativeCalculator}가 그 모집단에서 {@code PARTITION BY
     *       industry_group_key}로 매기므로, 대상 하나로 좁히면 동료 수와 {@code MIN_PEERS}
     *       판정이 갈려 {@code AsOfRecomputeTest}의 점수 대조가 붉는다. 실측으로 활성 편입의
     *       세분류 250개 중 <b>26개가 두 국내 유니버스에 걸쳐 있다.</b>
     *   <li><b>판정</b> — 그 대상의 편입만. 한 결과에 두 시장을 섞지 않는다(ADR-0107).
     * </ul>
     * 시장이 섞일 걱정은 없다 — 업종 키에 체계 접두가 붙어({@code KSIC:}/{@code SIC:},
     * ADR-0092) 두 시장이 같은 버킷에 못 들어간다.
     *
     * <p><b>조건을 그 대상의 통화로 해석한 뒤에 판정한다.</b> 안 하면
     * {@code IndicatorCondition.evaluate}가 {@code MarketLiteral}에 던진다 — 실제로 이 하네스
     * 셋이 전부 그렇게 죽어 있었다(2026-09-01 실측: 6개 중 3개 실패).
     *
     * @param target 조회 대상. 시장·통화·모집단이 여기서 나온다
     * @throws com.stockscreener.screening.MarketThresholdMissingException
     *         그 통화의 임계가 없는 조건이 있다. <b>조건 단위 판정불가로 접지 않는다</b>(ADR-0106)
     */
    public Outcome screen(LocalDate asOf, ConditionGroup group, EvaluationMode mode,
                          ScreeningTargets.Target target) {
        ConditionGroup resolved = group.resolveFor(target.currency());
        List<Long> populationIds = selectSectorPopulationIds(target.currency());
        Set<Long> targetIds = new LinkedHashSet<>(selectStockIds(target.code()));

        // MUST: 종목 루프 밖에서 한 번 읽는다. 안에서 읽으면 종목 수만큼 같은 쿼리를 던진다.
        Map<String, NavigableMap<LocalDate, BigDecimal>> indexes = runner.marketIndexesFor(asOf);

        Map<Long, Map<String, BigDecimal>> valuesByStock = new LinkedHashMap<>();
        for (Long stockId : populationIds) {
            valuesByStock.put(stockId,
                    new LinkedHashMap<>(runner.computeAt(stockId, asOf, indexes,
                            FinancialVisibility.KNOWN_ON)));
        }

        addSectorRelatives(valuesByStock, resolved.referencedIndicatorCodes());

        // 판정과 반환은 대상으로 좁힌다 — 호출부가 「값이 나온 종목 수」를 대상 기준으로 읽는다.
        Map<Long, Map<String, BigDecimal>> ofTarget = new LinkedHashMap<>();
        List<ScreeningResult> included = new ArrayList<>();
        for (Long stockId : targetIds) {
            Map<String, BigDecimal> values = valuesByStock.getOrDefault(stockId, Map.of());
            ofTarget.put(stockId, values);
            ScreeningResult result = evaluator.evaluate(stockId, resolved, mode, values);
            if (result.included()) {
                included.add(result);
            }
        }
        if (mode == EvaluationMode.SCORING) {
            included.sort(Comparator.comparing(ScreeningResult::score).reversed()
                    .thenComparing(ScreeningResult::stockId));
        }
        return new Outcome(asOf, included, ofTarget, targetIds.size(), resolved);
    }

    /**
     * 값 계산·업종 백분위의 모집단 — <b>활성 유니버스에 편입됐고 통화가 같은 종목.</b>
     *
     * <p>라이브 {@code ScreeningRepository.inAnyActiveUniverse}·
     * {@code SectorRelativeCalculator.MEMBER}와 <b>같은 술어에 통화 한정을 더한 것</b>이다.
     * {@code universe}를 조인하는 것이 요점이다 — 편입 행은 유니버스를 비활성으로 내려도
     * 남으므로, {@code universe_membership}만 보면 <b>꺼 둔 유니버스의 종목이 계속 걸린다.</b>
     *
     * <p><b>통화로 좁혀도 업종 백분위가 라이브와 같은 값이다.</b> 업종 키에 체계 접두가
     * 붙어(ADR-0092) 파티션이 통화를 넘지 못하기 때문이다 — 실측으로 {@code KSIC:}가 전부
     * KRW, {@code SIC:}가 전부 USD다. 라이브 모집단 1,350 중 KRW 350·USD 1,000이고,
     * 어느 파티션도 둘에 걸치지 않는다.
     * <b>그 정렬이 깨지면 {@code AsOfRecomputeTest.assertScoresMatch}가 붉는다</b> —
     * 그 단언이 이 좁히기의 펜스다. 가정으로 두지 않고 검사에 걸어 둔 이유가 그것이다.
     *
     * <p><b>왜 좁히는가</b>: 안 좁히면 대상 셋을 도는 데 {@code computeAt}이 56,700회가 되어
     * 실측 <b>25분에도 안 끝났다.</b> 좁히면 18,600회다. 진단을 돌릴 수 없으면 그 진단은 없는
     * 것과 같다.
     */
    private List<Long> selectSectorPopulationIds(String currencyCode) {
        return jdbcTemplate.queryForList("""
                SELECT s.id FROM stock s
                JOIN market mk ON mk.code = s.market_code
                WHERE s.is_active AND mk.currency_code = ? AND EXISTS (
                    SELECT 1 FROM universe_membership m
                    JOIN universe u ON u.code = m.universe_code AND u.is_active
                    WHERE m.stock_id = s.id AND m.effective_to IS NULL)
                ORDER BY s.id
                """, Long.class, currencyCode);
    }

    /**
     * 그 유니버스에 <b>지금</b> 편입된 종목. 라이브의 {@code currentlyIn}과 같은 술어다.
     *
     * <p>{@code effective_to IS NULL}이 <b>오늘의</b> 편입이라는 것이 이 하네스가 안고 가는
     * 생존 편향이며, 그 크기는 탐침이 따로 잰다. 여기서 {@code asOf}로 바꾸면 라이브와 달라져
     * 회귀 검사가 성립하지 않고, 무엇보다 <b>DB에 그 이력이 없다</b>(2026-08-05부터다).
     *
     * <p><b>{@code null} 갈래를 두지 않는다</b>(ADR-0108) — 「전체」는 통화가 갈려 임계를
     * 해석할 수 없다. 대상은 {@link ScreeningTargets}가 정한다.
     */
    private List<Long> selectStockIds(String universeCode) {
        return jdbcTemplate.queryForList("""
                SELECT s.id FROM stock s
                WHERE s.is_active AND EXISTS (
                    SELECT 1 FROM universe_membership m
                    WHERE m.stock_id = s.id AND m.universe_code = ? AND m.effective_to IS NULL)
                ORDER BY s.id
                """, Long.class, universeCode);
    }

    /**
     * {@code *_SECTOR_PCTL}을 인메모리로 만든다.
     *
     * <p><b>{@code computeAt}은 이 값을 내지 않는다</b> — {@code SectorRelativeCalculator}가
     * 종목별 계산기가 아니라 {@code indicator_value}를 읽어 쓰는 <b>단면 배치</b>이기 때문이다.
     * 그 경로를 부르면 ADR-0071의 금지에 걸리므로 규칙만 자바로 옮긴다.
     *
     * <p><b>모집단을 라이브와 일부러 다르게 둔다.</b> 라이브는 {@code effective_to IS NULL}인
     * 오늘의 편입을 모집단으로 쓰는데, 그것을 과거 as-of에 그대로 적용하면 <b>오늘의 멤버십을
     * 과거에 집어넣는 look-ahead</b>가 된다. 여기서는 <b>그 as-of에 값이 실제로 나온 종목</b>이
     * 모집단이다. 업종 자체에는 이력이 없어({@code industry_code}가 오늘 값이다) 그 한계는
     * 남으며, 결과에 적는다.
     *
     * <p>{@code PERCENT_RANK}의 정의를 그대로 옮긴다 — 동률은 <b>최소 순위</b>를 함께 받고
     * 분모가 {@code n-1}이다. 표본이 {@link #MIN_PEERS} 미만인 층은 값을 <b>넣지 않는다</b>
     * (0을 넣으면 "업종 최하위"로 읽혀 조건을 충족시킨다).
     *
     * <p><b>배수({@code _SECTOR_REL})도 함께 만든다.</b> 백분위만 만들면 그 지표를 쓰는 조건이
     * 매 시점 판정불가가 되고, 진단 축은 그것을 <b>커버리지 0 — "원리적으로 못 얻는다"</b>로
     * 찍는다. 저장된 값이 실재하고 라이브가 정상 판정하는데도 그렇게 나오므로, 그 신고를 믿으면
     * 살아 있는 배점 축을 죽은 것으로 오판한다(실측: {@code DEBT_RATIO_SECTOR_REL}이 저장
     * 54,879행인데 세 조회 대상 전부에서 0으로 찍혔다).
     *
     * <p>중앙값이 <b>0 이하면 값을 내지 않는다</b> — 배수가 뜻을 잃기 때문이고,
     * {@code SectorRelativeCalculator.MEDIAN_RATIO_CHOSEN}의 {@code f.med > 0} 가드와 같다.
     */
    private void addSectorRelatives(Map<Long, Map<String, BigDecimal>> valuesByStock,
                                    Set<String> referencedCodes) {
        for (String code : referencedCodes) {
            if (code.endsWith(SectorMetricSpec.MEDIAN_SUFFIX)
                    || code.endsWith(SectorMetricSpec.PEER_COUNT_SUFFIX)) {
                // 조건이 이 둘을 참조하는 날이 오면 재구성 규칙을 여기 더해야 한다. 조용히
                // 판정불가로 두면 위 배수와 똑같이 「원리적으로 못 얻는다」로 찍힌다.
                throw new IllegalStateException(
                        "업종 참조값을 쓰는 조건은 아직 재구성하지 않는다: " + code);
            }
        }
        Map<String, Set<SectorMetricSpec.Kind>> kindsBySource = new TreeMap<>();
        for (String code : referencedCodes) {
            for (SectorMetricSpec.Kind kind : RECONSTRUCTED_KINDS) {
                if (code.endsWith(kind.suffix())) {
                    kindsBySource.computeIfAbsent(
                                    code.substring(0, code.length() - kind.suffix().length()),
                                    k -> EnumSet.noneOf(SectorMetricSpec.Kind.class))
                            .add(kind);
                }
            }
        }
        if (kindsBySource.isEmpty()) {
            return;
        }
        Map<Long, Sector> sectors = readSectors();

        for (Map.Entry<String, Set<SectorMetricSpec.Kind>> source : kindsBySource.entrySet()) {
            String sourceCode = source.getKey();
            Set<SectorMetricSpec.Kind> kinds = source.getValue();
            // 층이 둘이다: 세분류 표본이 충분하면 그 안, 아니면 중분류 안이다.
            Map<String, List<Long>> fine = new TreeMap<>();
            Map<String, List<Long>> broad = new TreeMap<>();
            for (Map.Entry<Long, Map<String, BigDecimal>> entry : valuesByStock.entrySet()) {
                Sector sector = sectors.get(entry.getKey());
                if (sector == null || entry.getValue().get(sourceCode) == null) {
                    continue;
                }
                if (sector.fine() != null) {
                    fine.computeIfAbsent(sector.fine(), k -> new ArrayList<>()).add(entry.getKey());
                }
                if (sector.broad() != null) {
                    broad.computeIfAbsent(sector.broad(), k -> new ArrayList<>()).add(entry.getKey());
                }
            }

            for (Map.Entry<Long, Map<String, BigDecimal>> entry : valuesByStock.entrySet()) {
                Sector sector = sectors.get(entry.getKey());
                BigDecimal value = entry.getValue().get(sourceCode);
                if (sector == null || value == null) {
                    continue;
                }
                List<Long> peers = sector.fine() == null ? null : fine.get(sector.fine());
                if (peers == null || peers.size() < MIN_PEERS) {
                    peers = sector.broad() == null ? null : broad.get(sector.broad());
                }
                if (peers == null || peers.size() < MIN_PEERS) {
                    continue;
                }
                if (kinds.contains(SectorMetricSpec.Kind.PERCENTILE)) {
                    entry.getValue().put(sourceCode + SectorMetricSpec.PERCENTILE_SUFFIX,
                            percentRank(peers, valuesByStock, sourceCode, value));
                }
                if (kinds.contains(SectorMetricSpec.Kind.MEDIAN_RATIO)) {
                    BigDecimal median = medianOf(peers, valuesByStock, sourceCode);
                    if (median.signum() > 0) {
                        entry.getValue().put(sourceCode + SectorMetricSpec.MEDIAN_RATIO_SUFFIX,
                                value.divide(median, 8, RoundingMode.HALF_UP));
                    }
                }
            }
        }
    }

    /**
     * {@code percentile_cont(0.5)}의 정의를 그대로 옮긴다 — 짝수 표본은 가운데 <b>둘의 평균</b>이다.
     * 아래쪽 하나를 고르는 {@code percentile_disc}로 대신하면 배수가 조용히 다른 값이 된다.
     */
    private static BigDecimal medianOf(List<Long> peers,
                                       Map<Long, Map<String, BigDecimal>> valuesByStock,
                                       String sourceCode) {
        List<BigDecimal> sorted = peers.stream()
                .map(id -> valuesByStock.get(id).get(sourceCode))
                .sorted()
                .toList();
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    }

    /** {@code PERCENT_RANK() OVER (ORDER BY value)} — 자기보다 <b>작은</b> 것의 비율이다. */
    private static BigDecimal percentRank(List<Long> peers,
                                          Map<Long, Map<String, BigDecimal>> valuesByStock,
                                          String sourceCode, BigDecimal value) {
        if (peers.size() == 1) {
            return BigDecimal.ZERO;
        }
        long strictlyLess = peers.stream()
                .map(id -> valuesByStock.get(id).get(sourceCode))
                .filter(peer -> peer.compareTo(value) < 0)
                .count();
        return BigDecimal.valueOf(strictlyLess)
                .divide(BigDecimal.valueOf(peers.size() - 1L), 8, RoundingMode.HALF_UP);
    }

    /** 업종 층 둘. 키가 비면 백분위 모집단에서 빠진다(라이브와 같다). 체계 접두가 값에 있다. */
    private record Sector(String fine, String broad) {
    }

    private Map<Long, Sector> readSectors() {
        Map<Long, Sector> byStock = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT id, industry_group_key, industry_division_key
                FROM stock WHERE industry_group_key IS NOT NULL
                """, rs -> {
            byStock.put(rs.getLong("id"),
                    new Sector(rs.getString("industry_group_key"), rs.getString("industry_division_key")));
        });
        return byStock;
    }
}
