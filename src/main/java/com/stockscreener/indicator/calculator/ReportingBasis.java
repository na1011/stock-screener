package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.CommonIncomeRead;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.ProvisionalRelease;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

/**
 * 재무 지표가 <b>어느 기(期)의 재무로 계산됐는가</b>와 <b>그보다 새로운 실적 발표가 공개돼 있는가</b>(ADR-0195) — 지표 계산과 화면의
 * 재무 기준 표시가 같은 판정을 쓰는 단 하나의 자리다.
 *
 * <p>MUST: 기준을 따로 유도하지 않는다 — {@link FinancialMath#seriesOf}를 그대로 부른다. 지표의 기는 둘이다: 유량(PER · ROE · 성장률 …)은
 * 연속 네 분기가 서면 최신 분기, 아니면 최신 FY이고, 잔액(PBR · 부채비율 · 주식수)은 기와 무관하게 가장 최근 판이다
 * ({@link FinancialMath.PeriodSeries#latestPoint}). 「{@code period_end} 최대」 하나로 라벨을 만들면 분기 하나가 빠진 종목에서 라벨은 새
 * 분기인데 PER은 옛 FY 이익으로 돈다(2026-10-30 반증: 05-20 국내 28종목 · 10-29 미국 29종목).
 *
 * <p><b>발표 대기</b>: 유량 기준 기의 <b>다음 기말 뒤</b>에 공개된 실적 발표가 그 봉까지 있다. 그 봉의 재무 지표는 계산하지 않는다
 * ({@link FinancialMath#financialsAt}) — 가격은 새 실적에 반응했는데 재무는 옛 분기라, 값을 내면 모든 재무 조건이 어긋난 판정을
 * 낸다(2026-10-31 사용자: 「새 실적과 재무기준이 다르다면 지표 계산을 skip」). 다음 기말 앞의 발표(정식 공시 뒤에 나온 같은 분기 보도자료
 * · 분기 중 2.02)는 기준 기의 것이라 안 센다.
 */
public final class ReportingBasis {

    /** 미국 정규장 마감 — 이 시각 전에 접수된 8-K는 그날 종가에 반영됐다. */
    private static final LocalTime US_CLOSE = LocalTime.of(16, 0);
    private static final ZoneId US_EAST = ZoneId.of("America/New_York");

    private ReportingBasis() {
    }

    /**
     * @param flow  유량 지표가 쓰는 기 — 분기 기저면 최신 분기 행, 연간 기저면 최신 FY 행
     * @param point 잔액 지표가 쓰는 판 — 유량과 기말이 같으면 같은 기다
     */
    public record Basis(FinancialFigures flow, FinancialFigures point, boolean quarterly) {
    }

    /** 그 컨텍스트(이미 그 시점에 보이는 판만 든)의 기준. 재무가 없으면 비어 있다. */
    public static Optional<Basis> of(IndicatorContext context) {
        FinancialMath.PeriodSeries series = FinancialMath.seriesOf(context);
        if (series.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Basis(series.rows().get(0), series.latestPoint(),
                series.basis() == FinancialMath.PeriodBasis.QUARTERLY));
    }

    /**
     * 그 봉에서 기준보다 새로운 실적 발표 중 가장 최근 것의 공개일. 없으면 비어 있다.
     *
     * @param visible 그 봉 시점에 보이는 판만 든 컨텍스트({@link IndicatorContext#visibleAt}) — 실적 발표 공개일을 함께 싣는다
     */
    public static Optional<LocalDate> pendingRelease(IndicatorContext visible, LocalDate barDate) {
        List<LocalDate> releases = visible.earningsReleaseDates();
        if (releases.isEmpty()) {
            return Optional.empty();
        }
        Optional<Basis> basis = of(visible);
        if (basis.isEmpty()) {
            return Optional.empty();
        }
        LocalDate after = nextPeriodEnd(basis.get().flow().periodEnd());
        LocalDate latest = null;
        for (LocalDate release : releases) {
            if (release.isAfter(after) && !release.isAfter(barDate) && (latest == null || release.isAfter(latest))) {
                latest = release;
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * 막힌 봉의 잠정 행 — 분기 행과, 그 공시가 결산 한 해를 덮으면(4분기) 연간 손익 행. 1~3분기 잠정은 연간 값을 안 주므로 {@code year}가 없고 연간 판은
     * 지금처럼 막힌다.
     */
    public record Provisional(FinancialFigures quarter, FinancialFigures year) {
    }

    /**
     * <b>막힌 봉의 잠정 분기 행</b>(국내 잠정 관문 켬 · 탐색 {@code PROVISIONAL-PLAN-1110.md} v2) — 새 실적 발표로 막힌 봉에서, 정식 보고서
     * 전 그 분기의 손익을 잠정실적 공시로 짓는다. 대원칙: <b>잠정실적은 「그 분기의 손익 행」으로만 들어간다. 그 행으로 계산이 서는 지표만 값을
     * 내고 나머지는 정식 보고서까지 빈다. 같은 분기면 정식이 이긴다.</b> 지표 계산({@code FinancialMath.financialsAt})과 재무 기준 표시가
     * 이 한 함수를 쓴다.
     *
     * <p>서는 조건(전부): ① 그 봉의 유량 기준이 분기 기저이고 공시 기간이 그 다음 분기다(연간 기저 · 기말 충돌이면 전체 막기 그대로) ② 양식
     * 기준이 정식 판 기준과 같고 정식 판이 원화다({@link ProvisionalRelease#basisMatches}) ③ 항목마다 자체 검사 — 당해 = 누계 − 같은 해 정식 앞
     * 분기 합(1분기는 당해 = 누계)이 두 값의 반올림 폭 안이다. 4분기는 당해가 없으면 결산(I001 · I002 누계) − 정식 1~3분기다. 같은 분기
     * 공시가 여럿이면 항목마다 그 봉에 보인 것 중 늦은 판(검사를 통과한 것)이다. 한 항목도 안 서면 비어 있다(전체 막기).
     *
     * <p>4분기면 같은 공시의 누계로 연간 손익 행도 짓는다(대원칙 — 결산 공시가 연간 손익을 준다 · 2026-11-10 사용자 「대원칙대로 판단해」 뒤 내 판단).
     * 사본의 연간 판은 그 행 + 앞선 해의 정식 판이다(연간 성장률의 비교 대상). 1~3분기는 연간 판을 비운다(잠정이 연간 값을 안 준다).
     *
     * <p>행은 매출 · 영업이익 · 세전이익 · 순이익 · 지배 순이익만 싣는다 — 잔액 · 현금흐름 · 주식수는 비고(잔액 지표는 기준 판이 이 행이라
     * 빈다), 보통주 몫은 「못 정함」(갈래 {@code none:provisional} — 비우면 지배 계속영업으로 조용히 떨어진다 · 사용자 선택 「(나) 원칙대로
     * 비움」 · 주당 지표는 정식까지 빔), 중단영업 판정 규칙은 직전 정식 분기의 것을 잇는다(규칙이 있으면 중단 몫 모름 → 계속영업 지표가 빈다).
     *
     * @param visible 그 봉 시점 사본({@link IndicatorContext#visibleAt} · 분사 사본 뒤) — 그 봉에 보이는 잠정 공시만 든다
     */
    public static Optional<Provisional> provisionalQuarter(IndicatorContext visible, LocalDate barDate) {
        List<ProvisionalRelease> releases = visible.provisionalReleases();
        if (releases.isEmpty()) {
            return Optional.empty();
        }
        Optional<Basis> basis = of(visible);
        if (basis.isEmpty() || !basis.get().quarterly()) {
            return Optional.empty();
        }
        FinancialFigures previous = basis.get().flow();
        LocalDate start = previous.periodEnd().plusDays(1);
        LocalDate target = nextPeriodEnd(previous.periodEnd());
        List<ProvisionalRelease> candidates = releases.stream()
                .filter(r -> r.basisMatches() && target.equals(r.periodEnd()) && target.equals(r.cumulativeEnd()))
                .sorted(java.util.Comparator.comparing(ProvisionalRelease::filedOn).thenComparing(ProvisionalRelease::rceptNo)
                        .reversed())
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        java.util.EnumMap<ProvisionalRelease.Item, BigDecimal> values = new java.util.EnumMap<>(ProvisionalRelease.Item.class);
        // 결산 한 해 값은 그 항목의 분기 값을 정한 바로 그 공시의 누계다 — 4분기 행과 연간 행이 같은 공시에서 짝을 이룬다.
        java.util.EnumMap<ProvisionalRelease.Item, BigDecimal> yearValues = new java.util.EnumMap<>(ProvisionalRelease.Item.class);
        ProvisionalRelease newestUsed = null;
        ProvisionalRelease newestYear = null;
        for (ProvisionalRelease.Item item : ProvisionalRelease.Item.values()) {
            for (ProvisionalRelease r : candidates) {
                BigDecimal v = quarterValue(r, item, start, visible.quarterlyFigures());
                if (v != null) {
                    values.put(item, v);
                    if (newestUsed == null || r.filedOn().isAfter(newestUsed.filedOn())) {
                        newestUsed = r;
                    }
                    if (coversFiscalYear(r) && r.cumulative().get(item) != null) {
                        yearValues.put(item, r.won(r.cumulative().get(item)));
                        if (newestYear == null || r.filedOn().isAfter(newestYear.filedOn())) {
                            newestYear = r;
                        }
                    }
                    break;
                }
            }
        }
        if (newestUsed == null) {
            return Optional.empty();
        }
        FinancialFigures row = new FinancialFigures(target.getYear(), FinancialFigures.FiscalPeriod.valueOf("Q" + (target.getMonthValue() / 3)),
                start, target, newestUsed.filedOn(), "KRW", values.get(ProvisionalRelease.Item.REVENUE),
                values.get(ProvisionalRelease.Item.OPERATING_INCOME), values.get(ProvisionalRelease.Item.NET_INCOME),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                values.get(ProvisionalRelease.Item.NET_INCOME_CONTROLLING), null, null, null, null, null, null, null, null, null,
                values.get(ProvisionalRelease.Item.PROFIT_BEFORE_TAX), null, null, null, null, false, null, null,
                previous.discontinuedRule(), new CommonIncomeRead(null, null, PROVISIONAL_COMMON_BRANCH), java.util.Map.of(), null,
                newestUsed.visibleOn());
        FinancialFigures year = newestYear == null ? null : new FinancialFigures(target.getYear(), FinancialFigures.FiscalPeriod.FY,
                newestYear.cumulativeStart(), target, newestYear.filedOn(), "KRW", yearValues.get(ProvisionalRelease.Item.REVENUE),
                yearValues.get(ProvisionalRelease.Item.OPERATING_INCOME), yearValues.get(ProvisionalRelease.Item.NET_INCOME),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                yearValues.get(ProvisionalRelease.Item.NET_INCOME_CONTROLLING), null, null, null, null, null, null, null, null, null,
                yearValues.get(ProvisionalRelease.Item.PROFIT_BEFORE_TAX), null, null, null, null, false, null, null,
                previous.discontinuedRule(), new CommonIncomeRead(null, null, PROVISIONAL_COMMON_BRANCH), java.util.Map.of(), null,
                newestYear.visibleOn());
        IndicatorContext withRow = visible.withProvisionalQuarter(row, year);
        // 잠정 행을 넣은 사본이 그 행을 유량 기준으로 삼고(연속 네 분기) 그보다 새 발표가 없을 때만 선다 — 안 서면 지금처럼 전체 막기다.
        Optional<Basis> after = of(withRow);
        if (after.isEmpty() || !after.get().quarterly() || !target.equals(after.get().flow().periodEnd())
                || pendingRelease(withRow, barDate).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new Provisional(row, year));
    }

    /** 그 공시의 누계가 결산 한 해(1월 1일 ~ 12월 말 열두 달)인가 — 4분기 잠정 · 결산 공시(I001)만 참이다. */
    private static boolean coversFiscalYear(ProvisionalRelease r) {
        return r.cumulativeStart().plusYears(1).equals(r.cumulativeEnd().plusDays(1));
    }

    /** 잠정 행의 보통주 몫 갈래 — 잠정은 보통주 귀속 이익을 안 준다(주당 지표는 정식까지 빔). */
    public static final String PROVISIONAL_COMMON_BRANCH = "none:provisional";

    /**
     * 공시 하나가 그 항목의 분기 값(원)을 정하는가. 당해가 있으면 자체 검사를 통과할 때만 당해, 없으면 그 공시가 결산 한 해를 덮을 때(4분기)만
     * 누계 − 같은 해 정식 앞 분기 합이다.
     */
    private static BigDecimal quarterValue(ProvisionalRelease r, ProvisionalRelease.Item item, LocalDate start,
                                           List<FinancialFigures> formalQuarters) {
        BigDecimal quarter = r.current().get(item);
        BigDecimal cumulative = r.cumulative().get(item);
        boolean quarterForm = start.equals(r.periodStart());
        if (quarterForm && quarter != null) {
            if (cumulative == null) {
                return null;
            }
            BigDecimal prior = r.cumulativeStart().equals(start) ? BigDecimal.ZERO
                    : formalSum(formalQuarters, item, r.cumulativeStart(), start);
            if (prior == null) {
                return null;
            }
            BigDecimal gap = r.won(quarter).subtract(r.won(cumulative).subtract(prior)).abs();
            return gap.compareTo(r.roundingWon(quarter).add(r.roundingWon(cumulative))) <= 0 ? r.won(quarter) : null;
        }
        boolean fullYear = r.cumulativeStart().plusYears(1).equals(r.cumulativeEnd().plusDays(1));
        if (cumulative == null || !fullYear || !r.cumulativeStart().isBefore(start)) {
            return null;
        }
        BigDecimal prior = formalSum(formalQuarters, item, r.cumulativeStart(), start);
        return prior == null ? null : r.won(cumulative).subtract(prior);
    }

    /** 정식 분기 행이 {@code [from, until)}을 빈틈없이 덮으면 그 항목의 합 — 하나라도 빠지거나 비면 {@code null}. */
    private static BigDecimal formalSum(List<FinancialFigures> quarters, ProvisionalRelease.Item item, LocalDate from, LocalDate until) {
        List<FinancialFigures> inRange = quarters.stream()
                .filter(q -> q.periodStart() != null && q.periodEnd() != null && !q.periodStart().isBefore(from)
                        && q.periodEnd().isBefore(until))
                .sorted(java.util.Comparator.comparing(FinancialFigures::periodEnd))
                .toList();
        LocalDate expected = from;
        BigDecimal sum = BigDecimal.ZERO;
        for (FinancialFigures q : inRange) {
            BigDecimal v = item.of(q);
            if (!q.periodStart().equals(expected) || v == null) {
                return null;
            }
            sum = sum.add(v);
            expected = q.periodEnd().plusDays(1);
        }
        return expected.equals(until) ? sum : null;
    }

    /** 다음 기의 기말 — 석 달 뒤. 월말 기말은 월말로 맞춘다(06-30 → 09-30 · 03-31 → 06-30). 52/53주 결산은 그 날짜 그대로 석 달 뒤다. */
    public static LocalDate nextPeriodEnd(LocalDate periodEnd) {
        LocalDate next = periodEnd.plusMonths(3);
        return periodEnd.equals(periodEnd.with(TemporalAdjusters.lastDayOfMonth()))
                ? next.with(TemporalAdjusters.lastDayOfMonth()) : next;
    }

    /**
     * 실적 발표가 공개돼 있는 첫 봉의 날짜. 미국은 접수 시각이 동부 16:00 전이면 그 날, 뒤면 다음 날이다(RMD 8-K 2026-10-29 16:05 ET →
     * 10-29 종가는 발표 전). 접수 시각을 모르면(국내 · 미국 미해결) 접수일이다 — 보수적으로 그 봉 하나를 일찍 막는다.
     */
    /** 국내 정규장 마감 — 2016-08-01부터 15:30(그 앞은 15:00 · 한국거래소 거래시간 연장). */
    private static final LocalDate KR_CLOSE_EXTENDED = LocalDate.of(2016, 8, 1);
    private static final ZoneId KR_ZONE = ZoneId.of("Asia/Seoul");
    /** 미국에서 가장 이른 마감(조기 폐장) — 그날 마감을 모르면 이것으로 본다(보이기는 늦는 쪽이 보수). */
    private static final LocalTime US_EARLIEST_CLOSE = LocalTime.of(13, 0);

    /** 그 시장 그날의 정규장 마감 시각 — 모르면 {@code null}(세션 달력 범위 밖 · 휴장 · 아직 안 받음 — 셋을 가르지 않는다). */
    @FunctionalInterface
    public interface SessionClose {
        LocalTime on(LocalDate day);
    }

    /**
     * <b>재무 판 · 거버넌스 사실이 보이기 시작하는 날</b>(관문 켬 · 재고 「공개 시각이 공시일 칸과 갈린다」) — 공개 시각 뒤 처음 마감하는
     * 세션의 날. 시각을 모르면 저장 공시일 다음 날이다(봉은 세션에만 있어 다음 거래일이 된다 — 미래를 안 보고 늦는 쪽만 남는다). 그날의
     * 마감을 모르면 그 시장의 가장 이른 마감으로 본다(미국 13:00 · 국내 정규장 마감). 결과는 저장 공시일보다 이르지 않다.
     *
     * <p>실적 발표 <b>막기</b>({@link #effectiveOn})와 보수의 방향이 반대다 — 막기는 일찍 막는 쪽이, 보이기는 늦게 보이는 쪽이 보수라 두 규칙의
     * 「모를 때」가 다르다.
     *
     * @param us    미국 시장인가 — 시간대와 마감 규칙을 고른다
     * @param close 그날 미국 정규장 마감(세션 달력 {@code core_end}) — 국내는 안 쓴다
     */
    public static LocalDate visibleFrom(LocalDate published, Instant opened, boolean us, SessionClose close) {
        if (published == null) {
            return null;
        }
        if (opened == null) {
            return published.plusDays(1);
        }
        ZonedDateTime local = opened.atZone(us ? US_EAST : KR_ZONE);
        LocalDate day = local.toLocalDate();
        LocalTime sessionClose;
        if (us) {
            LocalTime known = close == null ? null : close.on(day);
            sessionClose = known != null ? known : US_EARLIEST_CLOSE;
        } else {
            sessionClose = day.isBefore(KR_CLOSE_EXTENDED) ? LocalTime.of(15, 0) : LocalTime.of(15, 30);
        }
        LocalDate first = local.toLocalTime().isBefore(sessionClose) ? day : day.plusDays(1);
        return first.isBefore(published) ? published : first;
    }

    /**
     * 국내 실적 발표가 막기 시작하는 날(국내 관문 켬 · ADR-0204) — DART 게시 시각을 알면 재무 보이기와 같은 규칙(15:30 뒤면 다음 날 · 같은 날 장
     * 마감 뒤 잠정실적과 분기보고서가 함께 다음 봉으로 간다), 모르면 저장 효력일(접수일 — 막기는 일찍 막는 쪽이 보수).
     */
    public static LocalDate domesticReleaseEffective(LocalDate stored, Instant opened) {
        return opened == null ? stored : visibleFrom(stored, opened, false, null);
    }

    public static LocalDate effectiveOn(LocalDate filedOn, Instant acceptedAt) {
        if (acceptedAt == null) {
            return filedOn;
        }
        ZonedDateTime east = acceptedAt.atZone(US_EAST);
        return east.toLocalTime().isBefore(US_CLOSE) ? east.toLocalDate() : east.toLocalDate().plusDays(1);
    }
}
