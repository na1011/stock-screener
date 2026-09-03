package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.MarketThresholdMissingException;
import com.stockscreener.screening.ScreeningResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeSet;

/**
 * 저장본을 읽어 <b>전략 × 대상 × 날짜</b>를 판정한다. 저장소를 만든 값이 여기서 회수된다 —
 * 값을 다시 만들면 1년치가 3.6시간이고, 여기서는 수십 초다.
 *
 * <p>규칙 넷. 전부 <b>조용한 오답</b>을 막으려고 있다.
 *
 * <ol>
 *   <li><b>구간을 매니페스트에서 잡는다.</b> 달력의 「최근 1년」으로 잡으면 그 끝이 <b>매일
 *       움직여</b> 저장소에 없는 날짜를 요구하게 된다. 저장소가 담은 것이 곧 구간이다.</li>
 *   <li><b>빠진 거래일이 있으면 던진다.</b> 최초 통과일을 내는 일이라 하루가 비면 <b>그날
 *       처음 통과한 종목이 다음 통과일로 밀리거나 아예 사라진다</b> — 조용히 틀린 표가 된다.</li>
 *   <li><b>코드 집합은 재구성을 셈에 넣는다.</b> {@code 참조 ⊆ 매니페스트}로 판정하면
 *       {@code _PCTL}·{@code _SECTOR_*}를 쓰는 전략이 전부 거절된다
 *       ({@link PointInTimeScreener#missingCodesOf}).</li>
 *   <li><b>통화 우선으로 돈다.</b> 대상 우선이면 KRW 파일을 코스피·코스닥이 각각 열어
 *       읽기가 741번이 된다. 통화 우선이면 497번이다.</li>
 * </ol>
 *
 * <p><b>적재 뒤에 원자료가 움직인 자리는 그 자리에서 다시 만든다.</b> 저장본을 그냥 쓰면
 * 조용히 낡은 답이 나오고, 전부 다시 만들면 저장한 의미가 없다. 무엇이 움직였는지는
 * {@link PitChangeDetector}가 2초대에 답한다.
 */
final class PitSweep {

    private final PitStore store;
    private final PitChangeDetector detector;
    private final PointInTimeScreener screener;
    private final IndicatorRunner runner;
    private final AsOfCalendar calendar;

    PitSweep(PitStore store, PitChangeDetector detector, PointInTimeScreener screener,
             IndicatorRunner runner, AsOfCalendar calendar) {
        this.store = store;
        this.detector = detector;
        this.screener = screener;
        this.runner = runner;
        this.calendar = calendar;
    }

    /** 종목별 최초 통과. {@code asOf} 오름차순이 자연 순서라 출력이 매수 순서가 된다. */
    record FirstPass(LocalDate asOf, BigDecimal score, BigDecimal maxScore)
            implements Comparable<FirstPass> {
        @Override
        public int compareTo(FirstPass other) {
            return asOf.compareTo(other.asOf);
        }
    }

    /**
     * @param firstPasses  (전략, 대상) → 종목 → 최초 통과
     * @param rejections   (전략, 대상) → 거절 사유. <b>「통과 0」과 구분해 들고 다닌다</b> —
     *                     섞으면 못 돌린 전략이 「아무것도 안 걸린 전략」으로 읽힌다
     * @param datesByCcy   통화별로 실제로 읽은 날짜 수
     * @param repairedCells 저장본이 낡아 그 자리에서 다시 만든 (종목, 날짜) 수
     */
    record Result(Map<String, Map<Long, FirstPass>> firstPasses,
                  Map<String, String> rejections,
                  Map<String, Integer> datesByCcy,
                  int repairedCells) {
    }

    /** 저장소가 담은 것. 스윕 전에 이것부터 사람이 본다 — 없는 것을 스윕할 수는 없다. */
    record Coverage(String currency, List<LocalDate> dates, Set<String> codes) {
    }

    /**
     * 그 통화의 저장 구간. <b>거래일이 비면 던진다</b> — 최초 통과일이 조용히 틀려지는 자리다.
     *
     * @param markets 빠진 날을 판정할 달력. 저장 구간의 양 끝 사이 거래일이 전부 있어야 한다.
     *                한 통화에 시장이 여럿이면 <b>합집합</b>이다 — 저장본은 통화 단위라 어느
     *                시장에서든 거래일이면 그날이 들어 있어야 한다
     */
    Coverage coverageOf(String currency, Set<String> markets) throws Exception {
        List<LocalDate> stored = store.storedDates(currency);
        if (stored.isEmpty()) {
            return new Coverage(currency, List.of(), Set.of());
        }
        List<LocalDate> expected =
                calendar.everyTradingDay(stored.get(0), stored.get(stored.size() - 1), markets);
        List<LocalDate> missing = new ArrayList<>(expected);
        missing.removeAll(stored);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "저장소에 " + currency + " 거래일 " + missing.size() + "개가 비었다"
                            + "(예: " + missing.subList(0, Math.min(5, missing.size())) + ") — "
                            + "최초 통과일이 그날 통과한 종목을 놓치거나 뒤로 민다. "
                            + "적재를 마저 돌린다");
        }
        Set<String> codes = new TreeSet<>();
        String declared = store.readManifest(currency, stored.get(0)).get("codes");
        if (declared != null && !declared.isBlank()) {
            codes.addAll(List.of(declared.split(" ")));
        }
        return new Coverage(currency, stored, codes);
    }

    /**
     * 저장본으로 스윕한다.
     *
     * @param minScoreRatio 달성률 문턱. 백테스트 규약이 정한 값이고 이것을 빼고 결론을 내지 않는다
     */
    Result sweep(List<PointInTimeScreener.Request> requests,
                 List<ScreeningTargets.Target> targets,
                 BigDecimal minScoreRatio) throws Exception {

        Map<String, Map<Long, FirstPass>> firstPasses = new LinkedHashMap<>();
        Map<String, String> rejections = new LinkedHashMap<>();
        Map<String, Integer> datesByCcy = new LinkedHashMap<>();
        int repaired = 0;

        // 통화 우선. 대상 우선이면 같은 파일을 시장 수만큼 다시 연다.
        Map<String, List<ScreeningTargets.Target>> byCurrency = new LinkedHashMap<>();
        for (ScreeningTargets.Target t : targets) {
            byCurrency.computeIfAbsent(t.currency(), k -> new ArrayList<>()).add(t);
        }

        for (Map.Entry<String, List<ScreeningTargets.Target>> e : byCurrency.entrySet()) {
            String currency = e.getKey();
            List<ScreeningTargets.Target> ofCurrency = e.getValue();
            Set<String> markets = new LinkedHashSet<>();
            ofCurrency.forEach(t -> markets.addAll(t.markets()));
            Coverage coverage = coverageOf(currency, markets);
            if (coverage.dates().isEmpty()) {
                for (ScreeningTargets.Target t : ofCurrency) {
                    for (PointInTimeScreener.Request r : requests) {
                        rejections.put(key(r.label(), t), "저장소에 " + currency + " 날짜가 없다");
                    }
                }
                continue;
            }
            datesByCcy.put(currency, coverage.dates().size());

            // 대상마다 돌릴 전략을 먼저 가른다. 통화축 거절과 코드 부족은 다른 사유이므로
            // 따로 적는다 — 뭉치면 무엇을 고쳐야 하는지가 사라진다.
            Map<String, List<PointInTimeScreener.Request>> runnable = new LinkedHashMap<>();
            for (ScreeningTargets.Target t : ofCurrency) {
                List<PointInTimeScreener.Request> ok = new ArrayList<>();
                for (PointInTimeScreener.Request r : requests) {
                    ConditionGroup resolved;
                    try {
                        resolved = r.group().resolveFor(t.currency());
                    } catch (MarketThresholdMissingException ex) {
                        rejections.put(key(r.label(), t), ex.currencyCode() + " 임계 없음: "
                                + String.join(" ", ex.indicatorCodes()));
                        continue;
                    }
                    Set<String> unservable = PointInTimeScreener.unservableCodesOf(resolved);
                    if (!unservable.isEmpty()) {
                        rejections.put(key(r.label(), t),
                                "하네스가 못 내는 지표: " + String.join(" ", unservable));
                        continue;
                    }
                    Set<String> missing =
                            PointInTimeScreener.missingCodesOf(resolved, coverage.codes());
                    if (!missing.isEmpty()) {
                        rejections.put(key(r.label(), t),
                                "저장본에 없는 지표: " + String.join(" ", missing));
                        continue;
                    }
                    ok.add(r);
                    firstPasses.computeIfAbsent(key(r.label(), t), k -> new LinkedHashMap<>());
                }
                runnable.put(t.code(), ok);
            }
            if (runnable.values().stream().allMatch(List::isEmpty)) {
                continue;
            }

            List<Long> population = screener.populationFor(currency);
            for (LocalDate asOf : coverage.dates()) {
                Map<Long, Map<String, BigDecimal>> values = store.readDate(currency, asOf);
                repaired += repair(currency, asOf, population, values);

                for (ScreeningTargets.Target t : ofCurrency) {
                    List<PointInTimeScreener.Request> ok = runnable.get(t.code());
                    if (ok.isEmpty()) {
                        continue;
                    }
                    Map<String, PointInTimeScreener.Outcome> outcomes =
                            screener.screenAllFromValues(asOf, ok, t, values);
                    for (PointInTimeScreener.Request r : ok) {
                        PointInTimeScreener.Outcome outcome = outcomes.get(r.label());
                        Map<Long, FirstPass> acc = firstPasses.get(key(r.label(), t));
                        for (ScreeningResult row : outcome.included()) {
                            if (row.scoreRatio().compareTo(minScoreRatio) < 0) {
                                continue;
                            }
                            // 규약이 「종목당 한 번」이라 뒤의 통과는 버린다.
                            acc.computeIfAbsent(row.stockId(),
                                    id -> new FirstPass(asOf, row.score(), row.maxScore()));
                        }
                    }
                }
            }
        }
        return new Result(firstPasses, rejections, datesByCcy, repaired);
    }

    /**
     * 적재 뒤에 원자료가 움직인 종목을 <b>그 날짜에 한해</b> 다시 만들어 메운다.
     *
     * <p>MUST NOT: 낡은 채로 판정하지 않는다. MUST NOT: 전부 다시 만들지 않는다. 둘 다
     * 저장소를 만든 이유를 지운다.
     *
     * @return 다시 만든 (종목, 날짜) 수
     */
    private int repair(String currency, LocalDate asOf, List<Long> population,
                       Map<Long, Map<String, BigDecimal>> values) throws Exception {
        Map<String, String> mf = store.readManifest(currency, asOf);
        String writtenAt = mf.get("writtenAt");
        if (writtenAt == null) {
            throw new IllegalStateException(currency + " " + asOf
                    + " 매니페스트에 writtenAt이 없다 — 그 뒤에 무엇이 움직였는지 물을 수 없다");
        }
        PitChangeDetector.Changes changes = changesSince(Instant.parse(writtenAt));
        Set<Long> suspects = changes.suspectsOn(asOf, population, mf.get("inPlaceDigest"));
        if (suspects.isEmpty()) {
            return 0;
        }
        Map<String, NavigableMap<LocalDate, BigDecimal>> indexes = runner.marketIndexesFor(asOf);
        for (Long stockId : suspects) {
            values.put(stockId, new LinkedHashMap<>(
                    runner.computeAt(stockId, asOf, indexes, FinancialVisibility.KNOWN_ON)));
        }
        // 저장본에 없던 종목(적재 뒤 편입)도 여기서 채워진다.
        Set<Long> unknown = new LinkedHashSet<>(population);
        unknown.removeAll(values.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(currency + " " + asOf + ": 모집단 " + unknown.size()
                    + "종목이 저장본에도 없고 의심에도 안 걸렸다(예: "
                    + unknown.stream().limit(5).toList() + ") — 적재가 덜 됐다");
        }
        return suspects.size();
    }

    /** 같은 시각을 여러 날짜가 묻는다. 훑기가 2초대라 캐시하지 않으면 그것이 지배한다. */
    private final Map<Instant, PitChangeDetector.Changes> changeCache = new LinkedHashMap<>();

    private PitChangeDetector.Changes changesSince(Instant since) {
        return changeCache.computeIfAbsent(since, detector::since);
    }

    private static String key(String label, ScreeningTargets.Target target) {
        return label + " · " + target.code();
    }
}
