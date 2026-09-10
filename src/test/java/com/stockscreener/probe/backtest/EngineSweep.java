package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorCalendar;
import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.MarketThresholdMissingException;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.query.ScreeningRepository;
import com.stockscreener.screening.store.ScreenDefinitionValidator;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 전략의 <b>종목별 최초 통과일</b>을 낸다 — <b>화면과 같은 경로로</b>.
 *
 * <p><b>이 클래스가 정하는 것은 날짜 루프와 누적뿐이다.</b> 대상 선정·값 읽기·조건 판정·정렬은
 * 전부 {@link ScreeningRepository#screen}이 진다. 화면(`POST /api/screening`)이 부르는 바로
 * 그 메서드이고, 그래서 <b>표와 화면이 같은 종목을 고른다.</b>
 *
 * <p><b>왜 사본을 걷었는가.</b> 앞선 판은 저장 값을 스스로 읽고 {@code *_SECTOR_*}·{@code *_PCTL}을
 * 인메모리로 다시 매겼다. 그 재구성의 모집단이 <b>오늘의 편입</b>이었고 저장값의 모집단은
 * <b>그날의 편입</b>이라(ADR-0135), 같은 날 같은 종목에 다른 값이 나왔다 — 실측 2026-03-23 ·
 * USD에서 {@code PER_SECTOR_PCTL}이 322종목에서 갈리고 {@code ≤ 0.50} 판정이 73종목에서
 * 뒤집혔다. <b>계산 규칙은 안 갈렸다</b>: 그날의 편입으로 다시 매기면 저장값과 727/727 일치한다.
 * 경위와 그 결정이 여는 금지들은 ADR-0137에 있다.
 *
 * <p><b>MUST NOT: 여기에 값을 직접 계산하거나 다시 매기는 갈래를 만들지 않는다.</b> 그러면
 * 표와 화면이 갈리는데 <b>어느 쪽도 에러를 내지 않는다.</b>
 */
class EngineSweep {

    private final ScreeningRepository screeningRepository;
    private final IndicatorCalendar calendar;
    private final IndicatorCatalog catalog;

    EngineSweep(ScreeningRepository screeningRepository, IndicatorCalendar calendar,
                IndicatorCatalog catalog) {
        this.screeningRepository = screeningRepository;
        this.calendar = calendar;
        this.catalog = catalog;
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
     * @param firstPasses (전략, 대상) → 종목 → 최초 통과
     * @param rejections  (전략, 대상) → 거절 사유. <b>「통과 0」과 구분해 들고 다닌다</b> —
     *                    섞으면 못 돌린 전략이 「아무것도 안 걸린 전략」으로 읽힌다
     * @param ranges      시장별로 실제로 읽은 구간. <b>날짜 「수」가 아니라 「양 끝」이다</b> —
     *                    수만 보면 두 통화의 시작일이 어긋난 것이 가려지고, 무엇보다
     *                    <b>구간 앞쪽이 침식되는 것을 아무도 못 본다.</b> 최초 통과일이 뒤로
     *                    밀리는 것이 이 스윕의 유일한 산출물이다
     */
    record Result(Map<String, Map<Long, FirstPass>> firstPasses,
                  Map<String, String> rejections,
                  Map<String, List<LocalDate>> ranges) {

        /** 통화별 날짜 수. 단언에 쓴다 — 사람이 읽는 표에는 양 끝을 쓴다. */
        Map<String, Integer> datesByCcy() {
            Map<String, Integer> out = new LinkedHashMap<>();
            ranges.forEach((ccy, dates) -> out.put(ccy, dates.size()));
            return out;
        }

        /** 사람이 읽는 구간 표기. */
        String rangeText() {
            List<String> parts = new ArrayList<>();
            ranges.forEach((ccy, dates) -> parts.add(dates.isEmpty() ? ccy + " 없음"
                    : ccy + " " + dates.get(0) + "~" + dates.get(dates.size() - 1)
                            + "(" + dates.size() + "일)"));
            return String.join(" · ", parts);
        }
    }

    Result sweep(List<PointInTimeScreener.Request> requests,
                 List<ScreeningTargets.Target> targets,
                 BigDecimal minScoreRatio) {

        Map<String, Map<Long, FirstPass>> firstPasses = new LinkedHashMap<>();
        Map<String, String> rejections = new LinkedHashMap<>();
        Map<String, List<LocalDate>> ranges = new LinkedHashMap<>();

        // 통화 우선. 같은 통화의 대상들은 달력을 공유하므로 구간을 한 번만 낸다.
        Map<String, List<ScreeningTargets.Target>> byCurrency = new LinkedHashMap<>();
        for (ScreeningTargets.Target t : targets) {
            byCurrency.computeIfAbsent(t.currency(), k -> new ArrayList<>()).add(t);
        }

        Set<String> activeCodes = catalog.activeCodes();

        for (Map.Entry<String, List<ScreeningTargets.Target>> e : byCurrency.entrySet()) {
            String currency = e.getKey();
            List<ScreeningTargets.Target> ofCurrency = e.getValue();
            Set<String> markets = new LinkedHashSet<>();
            ofCurrency.forEach(t -> markets.addAll(t.markets()));

            List<LocalDate> dates = datesOf(markets);
            ranges.put(currency, dates);
            if (dates.isEmpty()) {
                for (ScreeningTargets.Target t : ofCurrency) {
                    for (PointInTimeScreener.Request r : requests) {
                        rejections.put(key(r.label(), t), currency + " 지표 달력이 비었다");
                    }
                }
                continue;
            }

            // MUST: 최신일에서만 열린 편입의 하한을 접는다(ADR-0135). 원장과 지표의 시계가
            // 달라, 리밸런싱 직후에는 `effective_from > asOf`인 열린 행이 생긴다 — 실측으로
            // 2026-08-31에 962행 · 2026-09-04에 36행이었다. `false`로 박으면 스윕의 마지막
            // as-of가 **화면보다 종목을 뺀 표**를 낸다. ScreeningService가 같은 계산을 한다.
            LocalDate latest = dates.get(dates.size() - 1);

            // 대상마다 돌릴 전략을 먼저 가른다. 통화축 거절과 지표 부재는 다른 사유이므로
            // 따로 적는다 — 뭉치면 무엇을 고쳐야 하는지가 사라진다.
            Map<String, List<Runnable0>> runnable = new LinkedHashMap<>();
            for (ScreeningTargets.Target t : ofCurrency) {
                List<Runnable0> ok = new ArrayList<>();
                for (PointInTimeScreener.Request r : requests) {
                    ConditionGroup resolved;
                    try {
                        resolved = r.group().resolveFor(t.currency());
                    } catch (MarketThresholdMissingException ex) {
                        rejections.put(key(r.label(), t), ex.currencyCode() + " 임계 없음: "
                                + String.join(" ", ex.indicatorCodes()));
                        continue;
                    }
                    // 화면과 같은 검사를 쓴다(ScreeningService). 폐기된 지표를 참조하는 전략은
                    // 빈 결과가 아니라 **고쳐야 할 조건**이라는 것이 그 검사의 요점이다.
                    Set<String> missing = missingCodesOf(resolved, activeCodes);
                    if (!missing.isEmpty()) {
                        rejections.put(key(r.label(), t),
                                "카탈로그에 없는 지표: " + String.join(" ", missing));
                        continue;
                    }
                    ok.add(new Runnable0(r, resolved));
                    firstPasses.computeIfAbsent(key(r.label(), t), k -> new LinkedHashMap<>());
                }
                runnable.put(t.code(), ok);
            }
            if (runnable.values().stream().allMatch(List::isEmpty)) {
                continue;
            }

            // MUST: 날짜 루프의 진행을 찍는다. 안 찍으면 이 구간 전체가 무출력이고, 사람이
            // 그것을 「멈췄다」로 읽어 끊는다 — 실측으로 그런 실행이 두 번 취소됐다.
            System.out.printf("   %s %d날짜 × 대상 %d개를 조회한다%n",
                    currency, dates.size(), ofCurrency.size());
            Instant began = Instant.now();
            int seen = 0;
            for (LocalDate asOf : dates) {
                if (++seen % 40 == 0) {
                    long spent = Duration.between(began, Instant.now()).toSeconds();
                    System.out.printf("   [%d/%d] %s %s · 경과 %d초 · 남은 예상 %d초%n",
                            seen, dates.size(), currency, asOf, spent,
                            spent * (dates.size() - seen) / seen);
                }
                for (ScreeningTargets.Target t : ofCurrency) {
                    for (Runnable0 job : runnable.get(t.code())) {
                        List<ScreeningResult> rows = screeningRepository.screen(
                                job.resolved(), job.request().mode(), asOf,
                                asOf.equals(latest), t.code());
                        accumulate(firstPasses.get(key(job.request().label(), t)),
                                rows, asOf, minScoreRatio);
                    }
                }
            }
        }
        return new Result(firstPasses, rejections, ranges);
    }

    /** 그 대상에서 실제로 돌릴 전략 하나. 해석본을 들고 다닌다 — 원본을 다시 쓰면 통화가 뒤바뀐다. */
    private record Runnable0(PointInTimeScreener.Request request, ConditionGroup resolved) {
    }

    /**
     * 카탈로그에 없는 지표를 <b>전부</b> 낸다.
     *
     * <p>{@link ScreenDefinitionValidator#validate}와 같은 판정이되 <b>첫 위반에서 던지지
     * 않는다.</b> 그쪽은 화면 하나를 고치는 사람에게 답하므로 첫 건이면 충분하지만, 여기서는
     * 거절 사유가 표에 한 줄로 남아 <b>다시 돌리기 전에 전부 알아야 한다.</b>
     */
    private static Set<String> missingCodesOf(ConditionGroup resolved, Set<String> activeCodes) {
        Set<String> missing = new LinkedHashSet<>();
        for (String code : resolved.referencedIndicatorCodes()) {
            if (!activeCodes.contains(code)) {
                missing.add(code);
            }
        }
        return missing;
    }

    private static void accumulate(Map<Long, FirstPass> acc, List<ScreeningResult> rows,
                                   LocalDate asOf, BigDecimal minScoreRatio) {
        for (ScreeningResult row : rows) {
            // 조건이 전부 필수인 전략은 만점이 0이라 달성률도 0이 된다
            // (`ScreeningResult.scoreRatio`). 그때 문턱을 그대로 걸면 통과한 종목이 통째로
            // 버려지고, 거절 사유도 없어 「아무것도 안 걸린 전략」으로 읽힌다.
            if (row.maxScore() != null && row.maxScore().signum() > 0
                    && row.scoreRatio().compareTo(minScoreRatio) < 0) {
                continue;
            }
            // 규약이 「종목당 한 번」이라 뒤의 통과는 버린다.
            acc.computeIfAbsent(row.stockId(),
                    id -> new FirstPass(asOf, row.score(), row.maxScore()));
        }
    }

    /**
     * 그 시장들의 지표 달력 — <b>보존 창 전체</b>다.
     *
     * <p>{@code -Dsweep.from}·{@code -Dsweep.to}로 좁힌다. 좁히면 <b>최초 통과일이 그 구간의
     * 시작으로 몰린다</b> — 구간 앞에서 이미 통과 중이던 종목과 그날 처음 통과한 종목이
     * 구분되지 않기 때문이고, 그래서 결과 표에 구간의 양 끝을 함께 찍는다.
     *
     * <p><b>달력과 값이 같은 원본이다</b> — 양 끝도 열거도 {@code indicator_trade_date}이고
     * 값은 {@code indicator_value}다. 그래서 「빠진 거래일」이라는 개념이 없다: 값이 비는 것은
     * 결손이 아니라 <b>그 종목·그 날짜에 값이 없다는 사실</b>이고, 조건 단위로 판정된다.
     *
     * <p><b>MUST NOT: {@code price_daily}로 되돌리지 않는다.</b> 시세는 들어왔는데 지표 배치가
     * 아직 안 돈 날이 실재한다(일일 갱신 13:30 · 지표 사슬 14:00). 그날을 as-of로 잡으면 전
     * 조건이 판정불가가 되고 <b>그 0은 게이트 탓으로 읽힌다.</b> 앞선 판이 상한과 열거를
     * {@code price_daily}로 두고 주석에는 「같은 원본」이라 적고 있었다.
     */
    private List<LocalDate> datesOf(Set<String> markets) {
        LocalDate to = parseDate("sweep.to",
                calendar.latestIndicatorDate(markets).orElse(null));
        LocalDate from = parseDate("sweep.from",
                calendar.earliestIndicatorDate(markets).orElse(null));
        if (from == null || to == null) {
            return List.of();
        }
        return calendar.everyIndicatorDate(from, to, markets);
    }

    private static LocalDate parseDate(String key, LocalDate fallback) {
        String raw = System.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : LocalDate.parse(raw.trim());
    }

    static String key(String label, ScreeningTargets.Target target) {
        return label + " @ " + target.code();
    }
}
