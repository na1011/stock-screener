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
    private final PitFingerprint fingerprint;
    private final PointInTimeScreener screener;
    private final IndicatorRunner runner;
    private final AsOfCalendar calendar;
    private final com.stockscreener.indicator.IndicatorRegistry registry;

    PitSweep(PitStore store, PitChangeDetector detector, PitFingerprint fingerprint,
             PointInTimeScreener screener, IndicatorRunner runner, AsOfCalendar calendar,
             com.stockscreener.indicator.IndicatorRegistry registry) {
        this.store = store;
        this.detector = detector;
        this.fingerprint = fingerprint;
        this.screener = screener;
        this.runner = runner;
        this.calendar = calendar;
        this.registry = registry;
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
     * @param coverages    통화별로 실제로 읽은 구간. <b>날짜 「수」가 아니라 「양 끝」을 들고
     *                     다닌다</b> — 수만 보면 두 통화의 시작일이 어긋난 것이 가려지고(실측
     *                     KRW 2025-08-18 · USD 2025-08-28로 열흘 차이인데 둘 다 256일),
     *                     무엇보다 <b>구간의 앞쪽이 침식되는 것을 아무도 못 본다.</b>
     *                     {@code coverageOf}는 저장된 양 끝 <em>사이만</em> 보므로 첫 파일이
     *                     사라지면 구간이 조용히 줄고 결손은 0으로 읽힌다 — 그런데 최초
     *                     통과일이 뒤로 밀리는 것이 이 스윕의 유일한 산출물이다
     * @param repairedCells 저장본이 낡아 그 자리에서 다시 만든 (종목, 날짜) 수
     */
    record Result(Map<String, Map<Long, FirstPass>> firstPasses,
                  Map<String, String> rejections,
                  Map<String, Coverage> coverages,
                  int repairedCells) {

        /** 통화별 날짜 수. 단언에 쓴다 — 사람이 읽는 표에는 양 끝을 쓴다. */
        Map<String, Integer> datesByCcy() {
            Map<String, Integer> out = new LinkedHashMap<>();
            coverages.forEach((ccy, c) -> out.put(ccy, c.dates().size()));
            return out;
        }

        /** 사람이 읽는 구간 표기. */
        String rangeText() {
            List<String> parts = new ArrayList<>();
            coverages.forEach((ccy, c) -> parts.add(c.dates().isEmpty() ? ccy + " 없음"
                    : ccy + " " + c.dates().get(0) + "~" + c.dates().get(c.dates().size() - 1)
                            + "(" + c.dates().size() + "일)"));
            return String.join(" · ", parts);
        }
    }

    /**
     * 저장소가 담은 것. 스윕 전에 이것부터 사람이 본다 — 없는 것을 스윕할 수는 없다.
     *
     * @param lagDays 저장 마지막 날짜 뒤로 <b>몇 거래일이 더 있는가.</b>
     *                <b>MUST: 이것은 「시장 최신」이 아니다</b> — 기준이
     *                {@code max(price_daily.trade_date)}이므로 일일 배치가 멈추면 저장본과
     *                기준이 함께 멈춰 <b>뒤처짐이 0으로 읽힌다.</b> 「적재가 배치를 따라잡고
     *                있는가」를 재는 것이지 「현실을 따라잡고 있는가」가 아니다
     */
    record Coverage(String currency, List<LocalDate> dates, Set<String> codes, int lagDays,
                    Set<LocalDate> sharedMoved) {
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
            return new Coverage(currency, List.of(), Set.of(), 0, Set.of());
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
        // MUST: 코드 집합을 첫 날짜 하나에서 읽지 않는다. 가장 이른 날짜는 재무 깊이가 얕아
        // 코드가 덜 나올 수 있고, 그러면 그 코드를 쓰는 전략이 **1년 전체에서** 거절된다.
        // MUST: 완료 여부를 여기서 본다. 파일이 있다는 것과 그 날짜가 끝났다는 것은 다르다 —
        // 실패한 종목이 든 날짜를 그냥 읽으면 생존분만으로 판정하게 된다.
        String calculatorNow = PitFingerprint.forCalculators(registry);
        Set<String> codes = new TreeSet<>();
        // MUST: 지수가 움직인 날짜를 **비용을 쓰기 전에** 센다. 날짜 루프 안에서 세면 한 날짜를
        // 보는 중에 다른 날짜가 흔들렸는지 알 수 없어 「k날짜」를 낼 수 없고, 누적 카운터로
        // 문턱을 걸면 **재실행할 때마다 k가 문턱만큼 줄어** 같은 명령을 반복하면 문이 저절로
        // 열린다. 여기서는 매니페스트를 어차피 한 번 순회한다.
        Set<LocalDate> sharedMoved = new java.util.LinkedHashSet<>();
        List<String> incomplete = new ArrayList<>();
        Set<String> otherCalculators = new TreeSet<>();
        for (LocalDate asOf : stored) {
            Map<String, String> mf = store.readManifest(currency, asOf);
            // MUST: 계산기 판이 다르면 조용히 재계산하지 않고 던진다. 다시 만들면 그 자리에서
            // 몇 시간이 나가고, 그대로 쓰면 **옛 공식으로 만든 값**으로 표가 나온다.
            // 무엇을 할지는 사람이 정한다.
            String storedCalculator = mf.get("calculatorFp");
            if (storedCalculator == null || !storedCalculator.equals(calculatorNow)) {
                otherCalculators.add(String.valueOf(storedCalculator));
            }
            if (!"true".equals(mf.get("complete"))) {
                incomplete.add(asOf + "(실패 " + mf.getOrDefault("failed", "?") + ")");
                continue;
            }
            if (!fingerprint.forShared(currency, asOf).equals(mf.get("indexFp"))) {
                sharedMoved.add(asOf);
            }
            String declared = mf.get("codes");
            if (declared != null && !declared.isBlank()) {
                codes.addAll(List.of(declared.split(" ")));
            }
        }
        if (!otherCalculators.isEmpty()) {
            throw new IllegalStateException("저장본을 만든 지표 계산기가 지금과 다르다"
                    + "(저장본 " + otherCalculators + " · 지금 " + calculatorNow + ") — "
                    + "옛 공식으로 만든 값으로 표를 내게 된다. 그 구간을 다시 적재하거나 "
                    + "지표 변경을 되돌린다");
        }
        if (!incomplete.isEmpty()) {
            throw new IllegalStateException("저장소에 " + currency + " 미완료 날짜가 "
                    + incomplete.size() + "개다(예: "
                    + incomplete.subList(0, Math.min(5, incomplete.size())) + ") — "
                    + "생존한 종목만으로 판정하게 된다. 그 날짜를 다시 적재한다");
        }
        // 뒤처짐. 던지는 것은 호출부가 정한다 — 여기서는 재기만 한다.
        LocalDate last = stored.get(stored.size() - 1);
        LocalDate latest = calendar.latestTradingDay(markets);
        int lag = latest == null || !latest.isAfter(last) ? 0
                : calendar.everyTradingDay(last, latest, markets).size() - 1;
        return new Coverage(currency, stored, codes, lag, sharedMoved);
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
        Map<String, Coverage> coverages = new LinkedHashMap<>();
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
            // MUST: 「신고만 한다」로 두지 않는다. 이 저장소에 신고만 하는 형태가 이미 둘
            // 있는데(읽은 날짜 · 창 미완) 그것이 안 읽혔다는 증거가 결과 표가 조용히 낡은
            // 것이다. 문턱을 넘으면 던진다 — 넘기 전에는 값으로 실어 표에 찍힌다.
            if (coverage.lagDays() > maxLag()) {
                throw new IllegalStateException(currency + " 저장본이 "
                        + coverage.lagDays() + "거래일 뒤처졌다(허용 " + maxLag() + ") — 마지막 저장 "
                        + coverage.dates().get(coverage.dates().size() - 1)
                        + ". 그만큼의 최근 진입을 못 본 표가 나온다. 적재를 먼저 돌린다");
            }
            // MUST: 문턱은 **통화당**이다. 두 통화를 합쳐 세면 평시 4~6이 되어 기본 5를
            // 매일 넘고, 그러면 싸고 잦은 것에까지 사람을 부른다 — 실측으로 평시에 흔들리는
            // 날짜는 통화당 2~4이고(마지막 1~2 거래일의 전날 정정), 대량 지수 적재에서만 270이다.
            if (coverage.sharedMoved().size() > maxRecomputeDates()) {
                int perDate = "USD".equals(currency) ? 37 : 15;
                throw new IllegalStateException(currency + " 지수가 움직여 "
                        + coverage.sharedMoved().size() + "날짜의 값이 낡았다(자동 복구 상한 "
                        + maxRecomputeDates() + ") — 다시 만들면 약 "
                        + (coverage.sharedMoved().size() * perDate / 60) + "분이다. "
                        + "적재를 돌리거나 -Dsweep.maxRecomputeDates를 올려 정한다");
            }
            if (coverage.dates().isEmpty()) {
                for (ScreeningTargets.Target t : ofCurrency) {
                    for (PointInTimeScreener.Request r : requests) {
                        rejections.put(key(r.label(), t), "저장소에 " + currency + " 날짜가 없다");
                    }
                }
                continue;
            }
            coverages.put(currency, coverage);

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
                repaired += repair(currency, asOf, population, values,
                        coverage.sharedMoved().contains(asOf));

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
                            // 조건이 전부 필수인 전략은 만점이 0이라 달성률도 0이 된다
                            // (`ScreeningResult.scoreRatio`). 그때 문턱을 그대로 걸면 통과한
                            // 종목이 통째로 버려지고, 거절 사유도 없어 「아무것도 안 걸린
                            // 전략」으로 읽힌다. 만점이 0이면 통과 자체가 답이다.
                            if (row.maxScore() != null && row.maxScore().signum() > 0
                                    && row.scoreRatio().compareTo(minScoreRatio) < 0) {
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
        return new Result(firstPasses, rejections, coverages, repaired);
    }

    /**
     * 적재 뒤에 원자료가 움직인 종목을 <b>그 날짜에 한해</b> 다시 만들어 메운다.
     *
     * <p><b>두 단계인 이유가 이 저장소의 존폐다.</b> 싼 훑기 하나로 판정하면 정상 운영에서
     * <b>과검출이 100%로 접힌다</b> — 실측으로 최근 10일 중 사흘에 걸쳐
     * {@code price_daily.updated_at}이 전 종목·전 이력({@code min(trade_date) = 2019-09-03})을
     * 가리켰다. 그대로 믿으면 매 스윕이 전량 재계산이 되어 저장소가 값을 0으로 돌려준다.
     *
     * <ol>
     *   <li><b>싼 훑기</b>({@link PitChangeDetector}, 2초대)가 <b>의심</b>을 고른다. 과검출이다.</li>
     *   <li><b>내용 지문</b>이 의심만 확정한다. 같은 24시간에 {@code updated_at}은 1,350종목
     *       전부에서 움직였지만 <b>값이 바뀐 종목은 1개</b>였다 — 확정에서 대부분 걸러진다.</li>
     * </ol>
     *
     * <p><b>확정한 뒤에는 매니페스트를 다시 찍는다.</b> 안 찍으면 다음 스윕이 같은 확정을
     * 처음부터 되풀이한다 — 전략을 여러 번 고치는 것이 이 저장소의 용도이므로 그 되풀이가
     * 곧 비용이다. 값이 실제로 바뀐 날짜는 데이터 파일도 다시 쓴다.
     *
     * @return 값을 다시 만든 (종목, 날짜) 수
     */
    private int repair(String currency, LocalDate asOf, List<Long> population,
                       Map<Long, Map<String, BigDecimal>> values, boolean sharedMoved)
            throws Exception {
        Map<String, String> mf = store.readManifest(currency, asOf);
        String writtenAt = mf.get("writtenAt");
        if (writtenAt == null) {
            throw new IllegalStateException(currency + " " + asOf
                    + " 매니페스트에 writtenAt이 없다 — 그 뒤에 무엇이 움직였는지 물을 수 없다");
        }

        // MUST: 완전성 검사를 의심 여부보다 **앞에** 둔다. 뒤에 두면 원자료가 안 움직인 날에는
        // 아예 실행되지 않고, 빠진 종목이 Map.of()로 판정돼 **전 조건 판정불가 → 영원히 통과
        // 안 함**이 된다. 예외도 안 나고 표만 조용히 틀린다.
        Set<Long> unknown = new LinkedHashSet<>(population);
        unknown.removeAll(values.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(currency + " " + asOf + ": 모집단 " + unknown.size()
                    + "종목이 저장본에 없다(예: " + unknown.stream().limit(5).toList()
                    + ") — 적재가 덜 됐다");
        }
        // MUST: 지금 모집단에 없는 종목은 **걷어낸다**. 남겨 두면 편출·상장폐지 종목이 업종
        // 동료 집합과 중앙값에 계속 끼어 남은 종목의 백분위까지 달라진다. 라이브는 그것을
        // `effective_to IS NULL AND u.is_active`로 뺀다.
        values.keySet().retainAll(new LinkedHashSet<>(population));

        // 1단계 — 싼 훑기. 과검출이다.
        PitChangeDetector.Changes changes = changesSince(Instant.parse(writtenAt));
        Set<Long> suspects = new LinkedHashSet<>(
                changes.suspectsOn(asOf, population, mf.get("inPlaceDigest")));

        String sharedNow = fingerprint.forShared(currency, asOf);
        if (sharedMoved) {
            suspects.addAll(population);
        }
        if (suspects.isEmpty()) {
            return 0;
        }

        // 2단계 — 내용으로 확정한다. 여기서 대부분이 걸러진다.
        Map<Long, String> fresh = fingerprint.forCurrency(currency, asOf, suspects);
        Set<Long> stale = new LinkedHashSet<>();
        // MUST: 지수가 움직였으면 **종목 지문의 확정을 거치지 않는다.** 2단계는 「종목별 입력이
        // 바뀌었나」를 묻는 장치이고 지수는 종목별 입력이 아니다 — 종목 지문에 지수가 없으므로
        // 전부 일치로 나오고, 그러면 **재계산 0회로 낡은 값에 새 지문이 찍힌다.** 주석이
        // 「전 종목이 낡은 것이 맞다」고 적어 두고 코드가 그대로 안 하던 자리다.
        if (sharedMoved) {
            stale.addAll(population);
        }
        for (Map.Entry<Long, String> e : fresh.entrySet()) {
            String stored = mf.get("fp:" + e.getKey());
            if (stored == null || !stored.equals(e.getValue())) {
                stale.add(e.getKey());
            }
        }

        if (!stale.isEmpty()) {
            Map<String, NavigableMap<LocalDate, BigDecimal>> indexes =
                    runner.marketIndexesFor(asOf);
            for (Long stockId : stale) {
                values.put(stockId, new LinkedHashMap<>(
                        runner.computeAt(stockId, asOf, indexes, FinancialVisibility.KNOWN_ON)));
            }
        }
        restamp(currency, asOf, mf, values, fresh, sharedNow, !stale.isEmpty());
        return stale.size();
    }

    /**
     * 확정 결과를 매니페스트에 되쓴다. <b>다음 스윕이 같은 확정을 되풀이하지 않게 하는 것</b>이
     * 목적이다.
     *
     * <p>기준 시각은 <b>싼 훑기를 돌린 시점보다 앞</b>이어야 한다 — 뒤로 잡으면 그 사이에
     * 들어온 변경을 영영 못 본다. 그래서 이 스윕이 시작한 시각을 쓴다.
     */
    private void restamp(String currency, LocalDate asOf, Map<String, String> mf,
                         Map<Long, Map<String, BigDecimal>> values,
                         Map<Long, String> refreshed, String sharedNow, boolean dataChanged)
            throws Exception {
        Map<Long, String> merged = new java.util.TreeMap<>();
        for (Map.Entry<String, String> e : mf.entrySet()) {
            if (e.getKey().startsWith("fp:")) {
                merged.put(Long.parseLong(e.getKey().substring(3)), e.getValue());
            }
        }
        merged.putAll(refreshed);
        Set<String> codes = new TreeSet<>();
        String declared = mf.get("codes");
        if (declared != null && !declared.isBlank()) {
            codes.addAll(List.of(declared.split(" ")));
        }
        // MUST: `failed`를 새로 만들지 않고 **이어받는다.** `Set.of()`를 넘기면
        // `writeManifest`가 `complete = failed.isEmpty()`로 적어 **미완료 날짜를 조용히 완료로
        // 승격**시킨다. 지금은 `coverageOf`가 `repair`보다 먼저 던져 안 닿지만, 그 순서 하나에만
        // 의지하는 자리다.
        Set<Long> failed = new java.util.TreeSet<>();
        String storedFailed = mf.get("failed");
        if (storedFailed != null && !storedFailed.isBlank()) {
            for (String id : storedFailed.trim().split("\\s+")) {
                failed.add(Long.parseLong(id));
            }
        }
        PitStore.Stamp stamp = new PitStore.Stamp(
                Integer.parseInt(mf.getOrDefault("attempted", String.valueOf(values.size()))),
                codes, merged, sharedNow, detectorDigest(),
                PitFingerprint.forCalculators(registry), startedAt, failed);
        if (dataChanged) {
            store.writeDate(currency, asOf, values, stamp);
        } else {
            store.rewriteManifest(currency, asOf, values.size(), stamp);
        }
    }

    /** 스윕 하나에 훑기 하나. 날짜마다 다시 재면 그것이 스윕 시간을 지배한다(실측 2초대). */
    private final Instant startedAt = Instant.now();
    private String cachedDigest;

    private String detectorDigest() {
        if (cachedDigest == null) {
            cachedDigest = detector.inPlaceDigest(startedAt);
        }
        return cachedDigest;
    }

    /** 같은 시각을 여러 날짜가 묻는다. 훑기가 2초대라 캐시하지 않으면 그것이 지배한다. */
    private final Map<Instant, PitChangeDetector.Changes> changeCache = new LinkedHashMap<>();

    private PitChangeDetector.Changes changesSince(Instant since) {
        return changeCache.computeIfAbsent(since, detector::since);
    }

    /** 스윕이 스스로 메울 날짜 수 상한. <b>통화당</b>이다. */
    private static int maxRecomputeDates() {
        String raw = System.getProperty("sweep.maxRecomputeDates");
        return (raw == null || raw.isBlank()) ? 5 : Integer.parseInt(raw.trim());
    }

    /** 허용 뒤처짐. 적재 직전 스윕이 하루 이틀로 막히지 않게 기본을 2거래일로 둔다. */
    private static int maxLag() {
        String raw = System.getProperty("sweep.maxLag");
        return (raw == null || raw.isBlank()) ? 2 : Integer.parseInt(raw.trim());
    }

    private static String key(String label, ScreeningTargets.Target target) {
        return label + " · " + target.code();
    }
}
