package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;

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
    public static LocalDate effectiveOn(LocalDate filedOn, Instant acceptedAt) {
        if (acceptedAt == null) {
            return filedOn;
        }
        ZonedDateTime east = acceptedAt.atZone(US_EAST);
        return east.toLocalTime().isBefore(US_CLOSE) ? east.toLocalDate() : east.toLocalDate().plusDays(1);
    }
}
