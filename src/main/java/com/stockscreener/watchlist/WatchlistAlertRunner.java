package com.stockscreener.watchlist;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionEvaluator;
import com.stockscreener.screening.MarketThresholdMissingException;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.query.ScreeningRepository;
import com.stockscreener.screening.store.ScreenDefinition;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.stock.StockReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 워치리스트의 조건 충족 여부를 판정하고 <b>거짓→참으로 넘어간 것만</b> 알린다.
 *
 * <p>spec §7이 "일일 갱신 배치는 워치리스트의 조건 충족 여부도 함께 확인해야 한다"고 요구한다.
 * 사용자가 추적 중인 종목의 변화를 알게 되는 유일한 경로이므로, 빠지면 워치리스트 기능
 * 자체가 성립하지 않는다({@code data-pipeline.md}).
 *
 * <p>판정을 여기서 새로 만들지 않는다. {@link ConditionEvaluator}가 스크리닝과 같은 판정을
 * 하고, 지표 값은 {@link ScreeningRepository#loadIndicatorValues}가 같은 경로로 읽는다.
 * 별도 판정을 만들면 화면이 "충족"이라 말한 종목과 알림이 온 종목이 갈리는데, 그 차이는
 * 사용자가 손으로 대조하기 전까지 드러나지 않는다.
 *
 * <p>IMPORTANT — 판정 방식은 화면의 {@code evaluationMode}와 <b>무관하게 언제나
 * {@link EvaluationMode#FILTER}}다.</b> SCORING으로 판정하면 {@code included}가 항상 참이라
 * (점수 모드는 좁히지 않는다) 그 화면을 알림 조건으로 건 모든 종목이 <b>매일</b> 알림을 받는다.
 * 워치리스트가 묻는 것은 "조건을 충족했는가"이지 "몇 점인가"가 아니다(ADR-0026).
 */
@Component
public class WatchlistAlertRunner {

    private static final Logger log = LoggerFactory.getLogger(WatchlistAlertRunner.class);

    /** 알림 본문에 이름을 나열하는 최대 종목 수. 넘으면 "외 N건"으로 접는다. */
    private static final int MAX_LISTED_STOCKS = 10;

    private final WatchlistStore store;
    private final ScreenStore screenStore;
    private final ScreeningRepository screeningRepository;
    private final ConditionEvaluator evaluator;
    private final StockReader stockReader;
    private final AlertPort alertPort;

    public WatchlistAlertRunner(WatchlistStore store, ScreenStore screenStore,
                                ScreeningRepository screeningRepository,
                                ConditionEvaluator evaluator, StockReader stockReader,
                                AlertPort alertPort) {
        this.store = store;
        this.screenStore = screenStore;
        this.screeningRepository = screeningRepository;
        this.evaluator = evaluator;
        this.stockReader = stockReader;
        this.alertPort = alertPort;
    }

    /**
     * 알림 조건이 걸린 항목을 전부 판정하고 통지한다.
     *
     * @param asOfByMarket 시장 코드 → 판정에 쓸 지표 일자. 일일 갱신이 그날 계산을 마친 뒤
     *                     넘긴다 — 지표가 저장되기 전에 판정하면 어제 값으로 오늘을 판정하게
     *                     되고, 그것은 조용히 그럴듯하다.
     *                     <p><b>MUST: 날짜 하나로 되돌리지 않는다.</b> 두 시장의 휴장일이
     *                     다르므로 한 날짜로 전부 판정하면, 그날 휴장이던 시장의 종목은 값이
     *                     없어 <b>전 조건 판정불가 → 불충족</b>이 된다. 그러면
     *                     {@code recordEvaluation}이 {@code last_satisfied}를 내려
     *                     <b>다음 거래일에 같은 알림이 새 충족인 것처럼 다시 나간다</b>(ADR-0026).
     */
    @Transactional
    public Result run(Map<String, LocalDate> asOfByMarket) {
        List<WatchlistItem> items = store.findAlerting();
        if (items.isEmpty()) {
            return Result.empty();
        }
        Map<Long, String> marketByStock = store.marketsOf(
                items.stream().map(WatchlistItem::stockId).distinct().toList());
        Map<String, String> currencyByMarket = store.currencyByMarket();

        // 화면 단위로 묶는다. 같은 조건을 건 종목이 여럿이면 조건 로딩과 지표 조회가
        // 한 번으로 끝난다 — 항목마다 화면을 다시 읽으면 그것이 N+1이다.
        Map<Long, List<WatchlistItem>> byScreen = items.stream()
                .collect(Collectors.groupingBy(WatchlistItem::screenId, LinkedHashMap::new,
                        Collectors.toList()));

        List<Evaluated> evaluated = new ArrayList<>();
        int failedScreens = 0;

        for (Map.Entry<Long, List<WatchlistItem>> entry : byScreen.entrySet()) {
            try {
                evaluated.addAll(evaluateScreen(entry.getKey(), entry.getValue(),
                        marketByStock, currencyByMarket, asOfByMarket));
            } catch (RuntimeException e) {
                // 한 화면의 실패가 나머지를 막지 않는다. 실패 격리의 단위가 종목인 것과 같은
                // 원칙이다. MUST NOT: 조용히 넘기지 않는다 — error로 남겨야 Sentry가 잡는다.
                failedScreens++;
                log.error("워치리스트 조건 판정에 실패했다. screenId={}, 항목 {}건",
                        entry.getKey(), entry.getValue().size(), e);
            }
        }

        List<Evaluated> triggered = evaluated.stream().filter(Evaluated::triggered).toList();
        int notifiedUsers = notify(triggered, marketByStock, asOfByMarket);

        // 알림을 보낸 뒤에 기록한다. 순서를 뒤집으면 기록에 실패했을 때 알림 자체가
        // 나가지 않고, 그 손실은 아무 데도 드러나지 않는다. 이 순서라면 최악이
        // "내일 한 번 더 온다"이고 그것은 눈에 보인다.
        for (Evaluated result : evaluated) {
            store.recordEvaluation(result.item().id(), result.satisfied(), result.triggered());
        }

        log.info("워치리스트 판정을 마쳤다. asOf={}, 판정 {}건, 새로 충족 {}건, 통지 {}명, 실패 화면 {}개",
                asOfByMarket, evaluated.size(), triggered.size(), notifiedUsers, failedScreens);
        return new Result(evaluated.size(), triggered.size(), notifiedUsers, failedScreens);
    }

    /**
     * 화면 하나에 걸린 항목들을 판정한다.
     *
     * <p>시장이 섞여 있으면 <b>시장마다 다른 기준일</b>로 지표를 읽는다. 조건 판정 자체는
     * 같으므로 조건 로딩은 한 번이고, 갈라지는 것은 지표 조회뿐이다.
     */
    private List<Evaluated> evaluateScreen(long screenId, List<WatchlistItem> items,
                                           Map<Long, String> marketByStock,
                                           Map<String, String> currencyByMarket,
                                           Map<String, LocalDate> asOfByMarket) {
        Optional<ScreenDefinition> found = screenStore.findById(screenId);
        if (found.isEmpty()) {
            // FK가 있으므로 정상적으로는 불가능하다. 그래도 조용히 건너뛰지 않는다 —
            // 여기 걸린다는 것은 데이터가 어긋났다는 뜻이고, 그 사실이 드러나야 한다.
            throw new IllegalStateException("워치리스트가 참조하는 화면이 없다: screenId=" + screenId);
        }
        ScreenDefinition definition = found.get();

        // 비활성 화면은 판정하지 않는다. 시더가 선언에서 뺀 프리셋은 더 이상 유지되지 않는
        // 전략이며, 그것으로 계속 알리면 사용자는 이미 폐기된 기준으로 매매 판단을 하게 된다.
        if (!definition.active()) {
            log.info("비활성 화면이라 판정을 건너뛴다. screenId={}, name={}", screenId, definition.name());
            return List.of();
        }

        ConditionGroup group = definition.conditions();

        // 시장별로 나눠 읽는다. 그 종목의 시장에 기준일이 없으면(그날 그 시장이 대상이 아니었다면)
        // 판정하지 않는다 — 값이 없는 것을 불충족으로 접으면 last_satisfied가 내려가고,
        // 다음 실행에서 같은 알림이 새 충족인 것처럼 다시 나간다.
        Map<Long, Map<String, BigDecimal>> valuesByStock = new LinkedHashMap<>();
        Map<String, List<Long>> stockIdsByMarket = items.stream()
                .map(WatchlistItem::stockId).distinct()
                .filter(id -> marketByStock.get(id) != null)
                .collect(Collectors.groupingBy(marketByStock::get, LinkedHashMap::new,
                        Collectors.toList()));
        List<WatchlistItem> judgeable = new ArrayList<>();
        for (Map.Entry<String, List<Long>> byMarket : stockIdsByMarket.entrySet()) {
            LocalDate asOf = asOfByMarket.get(byMarket.getKey());
            if (asOf == null) {
                log.info("기준일이 없는 시장이라 워치리스트 판정을 건너뛴다. 시장={}, 항목 {}건",
                        byMarket.getKey(), byMarket.getValue().size());
                continue;
            }
            valuesByStock.putAll(screeningRepository.loadIndicatorValues(
                    byMarket.getValue(), group.referencedIndicatorCodes(), asOf));
            byMarket.getValue().forEach(id -> items.stream()
                    .filter(item -> item.stockId() == id).forEach(judgeable::add));
        }

        // 임계가 시장을 따라간다 — 스크리닝과 같은 해석을 여기서도 쓴다. 이 경로는
        // ScreeningRepository.screen을 안 거치므로 그쪽 해석이 여기까지 오지 않는다.
        //
        // MUST NOT: 그 시장 임계가 없을 때 다른 통화의 값으로 판정하지 않는다 — 근거 없는
        // 알림이 나간다. 건너뛰되 **조용히 빠지지 않게** 수를 세어 로그로 남긴다(같은 메서드가
        // 기준일 없는 시장에 대해 이미 그 형태를 쓴다).
        Map<String, ConditionGroup> resolvedByCurrency = new LinkedHashMap<>();
        Map<String, Integer> skippedByCurrency = new LinkedHashMap<>();

        List<Evaluated> results = new ArrayList<>(judgeable.size());
        for (WatchlistItem item : judgeable) {
            // 시장을 모르면 빈 문자열이 되어 해석이 실패하고, 그 항목은 아래에서
            // 세어져 로그로 드러난다 — 조용히 빠지지 않는다.
            String currency = currencyByMarket.getOrDefault(
                    marketByStock.getOrDefault(item.stockId(), ""), "");
            ConditionGroup resolvedGroup = resolvedByCurrency.get(currency);
            if (resolvedGroup == null) {
                try {
                    resolvedGroup = group.resolveFor(currency);
                    resolvedByCurrency.put(currency, resolvedGroup);
                } catch (MarketThresholdMissingException e) {
                    skippedByCurrency.merge(currency, 1, Integer::sum);
                    continue;
                }
            }
            Map<String, BigDecimal> values = valuesByStock.getOrDefault(item.stockId(), Map.of());
            ScreeningResult outcome = evaluator.evaluate(
                    item.stockId(), resolvedGroup, EvaluationMode.FILTER, values);

            // FILTER에서 included가 곧 "모든 조건을 충족했는가"다. 지표가 없어 판정할 수
            // 없었던 조건은 불충족으로 접히므로, 값이 아직 없는 종목은 알림을 받지 않는다 —
            // 그것이 맞다. 판정할 수 없는 것을 충족으로 읽으면 근거 없는 알림이 나간다.
            results.add(new Evaluated(item, definition, outcome.included()));
        }
        skippedByCurrency.forEach((currency, count) -> log.info(
                "그 시장 임계가 없어 워치리스트 판정을 건너뛴다. screenId={}, 통화={}, 항목 {}건",
                screenId, currency, count));
        return results;
    }



    /**
     * 사용자당 <b>요약 한 통</b>을 보낸다.
     *
     * <p>MUST NOT: 항목마다 보내지 않는다. 5종목이 같은 날 충족되면 5통이 가고, 그러면
     * 곧 이 알림을 읽지 않게 된다. 배치가 850종목 실패에 요약 1건을 보내는 것과 같은
     * 원칙이다({@code BatchExecutionContext}).
     *
     * @return 통지한 사용자 수
     */
    private int notify(List<Evaluated> triggered, Map<Long, String> marketByStock,
                       Map<String, LocalDate> asOfByMarket) {
        if (triggered.isEmpty()) {
            return 0;
        }
        Map<Long, List<Evaluated>> byUser = triggered.stream()
                .collect(Collectors.groupingBy(e -> e.item().userId(), LinkedHashMap::new,
                        Collectors.toList()));

        // 표시용 값도 그 종목의 시장 기준일로 읽는다. 하나로 읽으면 그날 휴장이던 시장의
        // 종목이 값 없는 카드로 나간다.
        Map<Long, StockReader.StockRow> stocks = new LinkedHashMap<>();
        triggered.stream().map(e -> e.item().stockId()).distinct()
                .collect(Collectors.groupingBy(
                        id -> marketByStock.getOrDefault(id, ""), LinkedHashMap::new,
                        Collectors.toList()))
                .forEach((market, ids) -> {
                    LocalDate marketAsOf = asOfByMarket.get(market);
                    if (marketAsOf != null) {
                        stocks.putAll(stockReader.findRows(ids, marketAsOf));
                    }
                });

        // 중복 억제 키와 본문 머리는 **쓰인 기준일 전부**로 짓는다. 하나만 적으면 다른 시장의
        // 종목이 그 날짜에 판정된 것처럼 읽힌다.
        List<LocalDate> usedDates = triggered.stream()
                .map(e -> asOfByMarket.get(marketByStock.get(e.item().stockId())))
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        String asOf = usedDates.stream().map(LocalDate::toString)
                .collect(Collectors.joining("/"));

        byUser.forEach((userId, entries) -> alertPort.send(AlertEvent.toUser(
                userId,
                AlertSeverity.INFO,
                "관심종목 %d개가 조건을 충족했습니다".formatted(entries.size()),
                describe(entries, stocks, asOf),
                // 같은 사용자·같은 날의 판정은 한 번만 나간다. 배치를 두 번 돌려도 마찬가지다.
                "watchlist:%d:%s".formatted(userId, asOf))));

        return byUser.size();
    }

    private static String describe(List<Evaluated> entries,
                                   Map<Long, StockReader.StockRow> stocks, String asOf) {
        StringBuilder sb = new StringBuilder("기준일 ").append(asOf).append('\n');
        entries.stream().limit(MAX_LISTED_STOCKS).forEach(entry -> {
            StockReader.StockRow stock = stocks.get(entry.item().stockId());
            sb.append("- ")
                    .append(stock == null ? "종목 " + entry.item().stockId()
                            : "%s(%s)".formatted(stock.name(), stock.ticker()))
                    .append(" — ").append(entry.definition().name())
                    .append('\n');
        });
        if (entries.size() > MAX_LISTED_STOCKS) {
            sb.append("외 ").append(entries.size() - MAX_LISTED_STOCKS).append("건\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 항목 하나의 판정 결과.
     *
     * <p>{@code triggered}가 엣지 트리거다 — <b>충족했고 직전에는 충족하지 않았을 때</b>만 참이다.
     * "충족 중"이 아니라 "방금 충족되었다"가 사용자가 알고 싶은 사건이고, 참이 유지되는
     * 동안의 침묵이 곧 "아직 그대로다"라는 정보다(ADR-0026).
     */
    private record Evaluated(WatchlistItem item, ScreenDefinition definition, boolean satisfied) {

        boolean triggered() {
            return satisfied && !item.wasSatisfied();
        }
    }

    /**
     * 한 번의 실행 결과.
     *
     * @param failedScreens 판정에 실패한 화면 수. 0이 아니면 호출부가 운영자에게 알린다 —
     *                      워치리스트 판정이 며칠 조용히 안 돌면 사용자는 알림이 없는 것을
     *                      "조건에 안 맞았나 보다"로 읽는다.
     */
    public record Result(int evaluated, int triggered, int notifiedUsers, int failedScreens) {

        static Result empty() {
            return new Result(0, 0, 0, 0);
        }

        public boolean hasFailure() {
            return failedScreens > 0;
        }
    }
}
