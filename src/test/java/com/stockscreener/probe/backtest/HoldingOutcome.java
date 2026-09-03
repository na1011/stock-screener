package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 진입 뒤 보유 구간의 성과. <b>커스텀 전략 백테스트 규약 §2를 그대로 옮긴 것</b>이고,
 * 규약이 요구하는 산출물 중 코드에 없던 마지막 조각이다.
 *
 * <p><b>양 끝점 수익률로는 답이 안 된다.</b> {@link ForwardReturns}가 내는 것은
 * {@code end/start - 1} 하나인데, 규약은 <b>경로</b>를 묻는다 — 창 안에서 +20%에 <em>닿았는가</em>,
 * 닿았다면 <em>며칠 걸렸는가</em>, 그 전에 <em>얼마나 빠졌는가</em>. 끝점만으로는 성공과
 * 부분성공을 가를 수 없고 최대하락도 못 낸다.
 *
 * <p><b>{@code adj_close}로 잰다.</b> 액면분할·무상증자가 낀 종목을 {@code close}로 재면
 * 수익률이 −50%나 +100%로 나오는데 값이 그럴듯해 드러나지 않는다.
 *
 * <p><b>미보정 기업 행위는 종목이 아니라 종목·기간으로 뺀다.</b> 원장이 비율을 못 낸 사건
 * ({@code UNSUPPORTED}·{@code OBSERVED})은 그 자리에서 {@code adj_close}가 실제로 끊겨 있어
 * 가짜 수익률을 낸다. 종목을 통째로 빼면 표본이 조용히 좁아지므로 <b>보유 창에 걸린 것만</b>
 * 표시한다 — 지우지 않고 표시하는 이유는 사용자가 그 종목을 보고 판단해야 하기 때문이다.
 */
final class HoldingOutcome {

    /** 목표 수익률. 규약 §1이 정한 값이고 이것을 바꿔 재지 않는다. */
    static final BigDecimal TARGET = new BigDecimal("0.20");

    /** 보유 일수. 진입 <b>다음</b> 거래일부터 센다(규약 §2). */
    static final int HOLD_DAYS = 182;

    private final JdbcTemplate jdbcTemplate;

    HoldingOutcome(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 규약 §2의 세 갈래. 순서가 곧 우선순위다 — 목표 도달이 창끝보다 앞선다. */
    enum Verdict { 성공, 부분성공, 손절, 봉없음 }

    /**
     * @param returnPct     규약대로의 기록값. <b>성공이면 +20.0%로 적는다</b> — 그날 청산으로
     *                      보기 때문이고, 그 뒤에 더 올랐어도 그것은 이 전략의 성과가 아니다
     * @param daysToTarget  진입일에서 목표 도달일까지의 <b>달력일</b>. 미달이면 {@code null}
     * @param maxGainPct    창 안 최고 종가 기준. 규약의 기록값과 다르다 — 참고용이다
     * @param maxDrawPct    최대하락(MAE). 목표 도달 전까지, 미달이면 창 전체의 최저 종가 기준
     * @param truncated     창 끝이 아직 안 왔다. 규약은 이것을 결론에서 빼라고 하지 않는다 —
     *                      사용자가 「완전관측은 의미 없다」고 판단했고 그 판단이 여기 걸린다
     * @param corporateGap  보유 창에 미보정 기업 행위가 걸렸다. 그러면 수익률을 믿을 수 없다
     */
    record Result(long stockId, LocalDate entry, BigDecimal entryPrice, Verdict verdict,
                  BigDecimal returnPct, LocalDate targetDate, Integer daysToTarget,
                  BigDecimal maxGainPct, BigDecimal maxDrawPct, LocalDate windowEnd,
                  int bars, boolean truncated, boolean corporateGap) {
    }

    /** 한 종목의 진입 이후를 잰다. 진입일 종가가 없으면 {@code 봉없음}이다. */
    Result measure(long stockId, LocalDate entry) {
        BigDecimal entryPrice = jdbcTemplate.query(
                "SELECT adj_close FROM price_daily WHERE stock_id = ? AND trade_date = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, stockId, entry);
        LocalDate windowEnd = entry.plusDays(HOLD_DAYS);
        if (entryPrice == null || entryPrice.signum() <= 0) {
            return new Result(stockId, entry, entryPrice, Verdict.봉없음, null, null, null,
                    null, null, windowEnd, 0, false, false);
        }

        // MUST: 진입 **다음** 거래일부터 센다. 진입일을 넣으면 그날의 종가가 최고·최저에
        // 섞여 최대하락이 0으로 깔린다.
        List<Bar> bars = new ArrayList<>();
        jdbcTemplate.query("""
                SELECT trade_date, adj_close FROM price_daily
                WHERE stock_id = ? AND trade_date > ? AND trade_date <= ?
                ORDER BY trade_date
                """, rs -> {
            bars.add(new Bar(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2)));
        }, stockId, entry, windowEnd);

        if (bars.isEmpty()) {
            return new Result(stockId, entry, entryPrice, Verdict.봉없음, null, null, null,
                    null, null, windowEnd, 0, true, false);
        }

        BigDecimal targetPrice = entryPrice.multiply(BigDecimal.ONE.add(TARGET));
        LocalDate targetDate = null;
        BigDecimal high = bars.get(0).close();
        BigDecimal lowBeforeTarget = bars.get(0).close();
        for (Bar bar : bars) {
            if (bar.close().compareTo(high) > 0) {
                high = bar.close();
            }
            if (targetDate == null) {
                if (bar.close().compareTo(lowBeforeTarget) < 0) {
                    lowBeforeTarget = bar.close();
                }
                if (bar.close().compareTo(targetPrice) >= 0) {
                    targetDate = bar.date();
                }
            }
        }

        BigDecimal last = bars.get(bars.size() - 1).close();
        Verdict verdict;
        BigDecimal recorded;
        if (targetDate != null) {
            verdict = Verdict.성공;
            recorded = TARGET;
        } else if (last.compareTo(entryPrice) > 0) {
            verdict = Verdict.부분성공;
            recorded = ratio(last, entryPrice);
        } else {
            verdict = Verdict.손절;
            recorded = ratio(last, entryPrice);
        }

        boolean truncated = bars.get(bars.size() - 1).date().isBefore(windowEnd.minusDays(4));
        Integer daysToTarget = targetDate == null
                ? null : (int) ChronoUnit.DAYS.between(entry, targetDate);

        Integer gap = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM corporate_action
                WHERE stock_id = ? AND status IN ('OBSERVED', 'UNSUPPORTED')
                  AND effective_date >= ? AND effective_date <= ?
                """, Integer.class, stockId, entry, windowEnd);

        return new Result(stockId, entry, entryPrice, verdict, recorded, targetDate, daysToTarget,
                ratio(high, entryPrice), ratio(lowBeforeTarget, entryPrice), windowEnd,
                bars.size(), truncated, gap != null && gap > 0);
    }

    /** (가격/진입가 − 1). 소수 넷째 자리까지 — 백분율로 두 자리다. */
    private static BigDecimal ratio(BigDecimal price, BigDecimal entry) {
        return price.divide(entry, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
    }

    private record Bar(LocalDate date, BigDecimal close) {
    }

    /**
     * 전략 하나의 요약. <b>목표 달성일 평균은 성공한 것만으로 낸다</b> — 미달을 0이나 182로
     * 채우면 그 평균은 「빨리 도달했다」와 「도달 못 했다」를 구분하지 못한다.
     */
    record Summary(int total, int success, int partial, int loss, int noBars,
                   Double avgDaysToTarget, Double avgReturnPct, int truncated,
                   int corporateGaps) {

        static Summary of(List<Result> results) {
            Map<Verdict, Integer> counts = new LinkedHashMap<>();
            for (Verdict v : Verdict.values()) {
                counts.put(v, 0);
            }
            double daySum = 0;
            int dayCount = 0;
            double returnSum = 0;
            int returnCount = 0;
            int truncated = 0;
            int gaps = 0;
            for (Result r : results) {
                counts.merge(r.verdict(), 1, Integer::sum);
                if (r.daysToTarget() != null) {
                    daySum += r.daysToTarget();
                    dayCount++;
                }
                if (r.returnPct() != null) {
                    returnSum += r.returnPct().doubleValue();
                    returnCount++;
                }
                truncated += r.truncated() ? 1 : 0;
                gaps += r.corporateGap() ? 1 : 0;
            }
            return new Summary(results.size(), counts.get(Verdict.성공), counts.get(Verdict.부분성공),
                    counts.get(Verdict.손절), counts.get(Verdict.봉없음),
                    dayCount == 0 ? null : daySum / dayCount,
                    returnCount == 0 ? null : returnSum / returnCount,
                    truncated, gaps);
        }
    }
}
