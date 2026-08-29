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
import java.util.LinkedHashMap;
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
     */
    public record Outcome(LocalDate asOf, List<ScreeningResult> included,
                          Map<Long, Map<String, BigDecimal>> valuesByStock, int evaluated) {
    }

    /**
     * MUST: {@code asOf}는 {@code price_daily}에 있는 <b>거래일</b>이어야 한다. 휴장일을 주면
     * {@code computeAt}이 전 종목 빈 맵을 내고 필수 조건이 모두 판정불가가 되어 통과가 0이 되는데,
     * <b>그것은 게이트가 아니라 날짜 탓이다.</b> 호출부가 {@link AsOfCalendar}로 고른다.
     *
     * @param universeCode 현재 이 유니버스에 편입된 종목으로 한정한다. {@code null}이면 활성 전체
     */
    public Outcome screen(LocalDate asOf, ConditionGroup group, EvaluationMode mode,
                          String universeCode) {
        List<Long> stockIds = selectStockIds(universeCode);

        // MUST: 종목 루프 밖에서 한 번 읽는다. 안에서 읽으면 종목 수만큼 같은 쿼리를 던진다.
        Map<String, NavigableMap<LocalDate, BigDecimal>> indexes = runner.marketIndexesFor(asOf);

        Map<Long, Map<String, BigDecimal>> valuesByStock = new LinkedHashMap<>();
        for (Long stockId : stockIds) {
            valuesByStock.put(stockId,
                    new LinkedHashMap<>(runner.computeAt(stockId, asOf, indexes,
                            FinancialVisibility.KNOWN_ON)));
        }

        addSectorPercentiles(valuesByStock, group.referencedIndicatorCodes());

        List<ScreeningResult> included = new ArrayList<>();
        for (Long stockId : stockIds) {
            ScreeningResult result = evaluator.evaluate(
                    stockId, group, mode, valuesByStock.getOrDefault(stockId, Map.of()));
            if (result.included()) {
                included.add(result);
            }
        }
        if (mode == EvaluationMode.SCORING) {
            included.sort(Comparator.comparing(ScreeningResult::score).reversed()
                    .thenComparing(ScreeningResult::stockId));
        }
        return new Outcome(asOf, included, valuesByStock, stockIds.size());
    }

    /**
     * 라이브의 {@code selectStockIds}와 같은 술어다 — 활성 종목, 선택적 유니버스 한정,
     * {@code stock.id} 오름차순.
     *
     * <p>{@code effective_to IS NULL}이 <b>오늘의</b> 편입이라는 것이 이 하네스가 안고 가는
     * 생존 편향이며, 그 크기는 탐침이 따로 잰다. 여기서 {@code asOf}로 바꾸면 라이브와 달라져
     * 회귀 검사가 성립하지 않고, 무엇보다 <b>DB에 그 이력이 없다</b>(2026-08-05부터다).
     */
    private List<Long> selectStockIds(String universeCode) {
        if (universeCode == null) {
            return jdbcTemplate.queryForList(
                    "SELECT id FROM stock WHERE is_active ORDER BY id", Long.class);
        }
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
     */
    private void addSectorPercentiles(Map<Long, Map<String, BigDecimal>> valuesByStock,
                                      Set<String> referencedCodes) {
        List<String> sourceCodes = referencedCodes.stream()
                .filter(code -> code.endsWith(SectorMetricSpec.PERCENTILE_SUFFIX))
                .map(code -> code.substring(
                        0, code.length() - SectorMetricSpec.PERCENTILE_SUFFIX.length()))
                .toList();
        if (sourceCodes.isEmpty()) {
            return;
        }
        Map<Long, Sector> sectors = readSectors();

        for (String sourceCode : sourceCodes) {
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
                entry.getValue().put(sourceCode + SectorMetricSpec.PERCENTILE_SUFFIX,
                        percentRank(peers, valuesByStock, sourceCode, value));
            }
        }
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
