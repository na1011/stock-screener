package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;

import java.util.Optional;

/**
 * 재무 지표가 <b>어느 기(期)의 재무로 계산됐는가</b>(ADR-0195) — 화면의 재무 기준 표시가 지표와 같은 판정을 쓰게 하는 창구다.
 *
 * <p>MUST: 기준을 따로 유도하지 않는다 — {@link FinancialMath#seriesOf}를 그대로 부른다. 지표의 기는 둘이다: 유량(PER · ROE · 성장률 …)은
 * 연속 네 분기가 서면 최신 분기, 아니면 최신 FY이고, 잔액(PBR · 부채비율 · 주식수)은 기와 무관하게 가장 최근 판이다
 * ({@link FinancialMath.PeriodSeries#latestPoint}). 「{@code period_end} 최대」 하나로 라벨을 만들면 분기 하나가 빠진 종목에서 라벨은 새
 * 분기인데 PER은 옛 FY 이익으로 돈다(2026-10-30 반증: 05-20 국내 28종목 · 10-29 미국 29종목).
 */
public final class ReportingBasis {

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
}
