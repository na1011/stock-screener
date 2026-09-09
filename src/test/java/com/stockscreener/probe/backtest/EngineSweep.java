package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorRegistry;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.MarketThresholdMissingException;
import com.stockscreener.screening.ScreeningResult;

import org.springframework.jdbc.core.JdbcTemplate;

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
 * 저장된 지표({@code indicator_value})를 날짜별로 읽어 전략의 <b>종목별 최초 통과일</b>을 낸다.
 *
 * <p><b>왜 저장 지표를 읽는가.</b> ADR-0123이 값을 파일로 남긴 근거 넷이 전부 무너졌다 —
 * 보존 창이 1년을 덮게 됐고(ADR-0129), 저장 경로가 {@code KNOWN_ON}이 됐으며(ADR-0128),
 * 남아 있던 잔재는 걷혔고(ADR-0130), 비용이 같은 자릿수로 좁혀졌다. 자세한 것은 ADR-0131.
 *
 * <p><b>이 클래스는 값을 만들지 않는다.</b> 만드는 것은 배치이고, 여기서는 <b>읽는 것만</b>
 * 한다. 그래서 지문·매니페스트·따라잡기가 통째로 없다 — 낡음이라는 개념 자체가 없기 때문이다.
 * <b>MUST NOT: 여기에 직접 계산으로 떨어지는 갈래를 만들지 않는다</b> — 그러면 이 스윕이
 * 경고 없이 몇 시간짜리가 되고, 무엇보다 <b>화면과 다른 값으로 표가 나온다.</b>
 *
 * <p><b>판정은 {@link PointInTimeScreener#screenAllFromValues}가 진다.</b> 업종 상대값
 * 재구성·유니버스 백분위·대상 좁히기·정렬이 라이브와 한 벌이고, 이 클래스가 정하는 것은
 * <b>값의 출처와 날짜 루프</b>뿐이다.
 */
class EngineSweep {

    private final PointInTimeScreener screener;
    private final StoredValueSource values;
    private final AsOfCalendar calendar;
    private final IndicatorRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    EngineSweep(PointInTimeScreener screener, StoredValueSource values, AsOfCalendar calendar,
                IndicatorRegistry registry, JdbcTemplate jdbcTemplate) {
        this.screener = screener;
        this.values = values;
        this.calendar = calendar;
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
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
     * @param ranges      통화별로 실제로 읽은 구간. <b>날짜 「수」가 아니라 「양 끝」이다</b> —
     *                    수만 보면 두 통화의 시작일이 어긋난 것이 가려지고, 무엇보다
     *                    <b>구간의 앞쪽이 침식되는 것을 아무도 못 본다.</b> 최초 통과일이 뒤로
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

        // 통화 우선. 대상 우선이면 같은 날짜의 같은 모집단을 시장 수만큼 다시 읽는다.
        Map<String, List<ScreeningTargets.Target>> byCurrency = new LinkedHashMap<>();
        for (ScreeningTargets.Target t : targets) {
            byCurrency.computeIfAbsent(t.currency(), k -> new ArrayList<>()).add(t);
        }

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

            // 대상마다 돌릴 전략을 먼저 가른다. 통화축 거절과 코드 부족은 다른 사유이므로
            // 따로 적는다 — 뭉치면 무엇을 고쳐야 하는지가 사라진다.
            Set<String> storedCodes = PointInTimeScreener.declaredCodesOf(registry);
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
                            PointInTimeScreener.missingCodesOf(resolved, storedCodes);
                    if (!missing.isEmpty()) {
                        rejections.put(key(r.label(), t),
                                "카탈로그에 없는 지표: " + String.join(" ", missing));
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
            // MUST: 날짜 루프의 진행을 찍는다. 안 찍으면 이 구간 전체가 무출력이고, 사람이
            // 그것을 「멈췄다」로 읽어 끊는다 — 실측으로 그런 실행이 두 번 취소됐다.
            System.out.printf("   %s %d날짜 × 모집단 %d종목을 읽는다%n",
                    currency, dates.size(), population.size());
            Instant began = Instant.now();
            int seen = 0;
            for (LocalDate asOf : dates) {
                Map<Long, Map<String, BigDecimal>> ofDate = values.read(population, asOf);
                if (++seen % 40 == 0) {
                    long spent = Duration.between(began, Instant.now()).toSeconds();
                    System.out.printf("   [%d/%d] %s %s · 경과 %d초 · 남은 예상 %d초%n",
                            seen, dates.size(), currency, asOf, spent,
                            spent * (dates.size() - seen) / seen);
                }
                for (ScreeningTargets.Target t : ofCurrency) {
                    List<PointInTimeScreener.Request> ok = runnable.get(t.code());
                    if (ok.isEmpty()) {
                        continue;
                    }
                    Map<String, PointInTimeScreener.Outcome> outcomes =
                            screener.screenAllFromValues(asOf, ok, t, ofDate);
                    for (PointInTimeScreener.Request r : ok) {
                        accumulate(firstPasses.get(key(r.label(), t)),
                                outcomes.get(r.label()).included(), asOf, minScoreRatio);
                    }
                }
            }
        }
        return new Result(firstPasses, rejections, ranges);
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
     * 구분되지 않기 때문이고, 그래서 §결과 표에 구간의 양 끝을 함께 찍는다.
     *
     * <p><b>빠진 거래일이라는 개념이 없다</b>(ADR-0123이 저장 파일에 대해 두던 검사다).
     * 달력 자체가 {@code indicator_trade_date}이고 값도 거기서 읽으므로 <b>둘이 같은 원본</b>이다.
     * 값이 비는 것은 결손이 아니라 <b>그 종목·그 날짜에 값이 없다는 사실</b>이고, 그 판정은
     * {@code screenAllFromValues}가 조건 단위로 한다.
     */
    private List<LocalDate> datesOf(Set<String> markets) {
        LocalDate to = parseDate("sweep.to", calendar.latestTradingDay(markets));
        // MUST: 시장 목록을 **SQL 배열 하나**로 넘긴다. varargs로 주면 `ANY (?)`의 물음표
        // 하나에 시장 수만큼 바인딩되어 "column index is out of range"로 죽는다.
        List<LocalDate> earliest = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT min(trade_date) FROM indicator_trade_date WHERE market_code = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection()
                        .createArrayOf("varchar", markets.toArray())),
                rs -> {
                    earliest.add(rs.getObject(1, LocalDate.class));
                });
        LocalDate from = parseDate("sweep.from",
                earliest.isEmpty() ? null : earliest.get(0));
        if (from == null || to == null) {
            return List.of();
        }
        return calendar.everyTradingDay(from, to, markets);
    }

    private static LocalDate parseDate(String key, LocalDate fallback) {
        String raw = System.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : LocalDate.parse(raw.trim());
    }

    static String key(String label, ScreeningTargets.Target target) {
        return label + " @ " + target.code();
    }
}
