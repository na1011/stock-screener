package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.us.UsMarket;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 적용 직전 가격 반증 — 미국 소스 공시 사건이 가격으로 반박되면 적용하지 않고 판정으로 돌린다(ADR-0181).
 *
 * <p>미국은 소스가 원장의 정본이다(ADR-0090) — 그런데 소스 레코드가 틀린 것이 실측으로 넷 있었다: RTX 2020-04-03 「병합 2→1」(그날은 분사 ·
 * 사람 거절) · DRS 2022-11-23 「분할 1.4513」(합병 신주 · 사람 거절) · IR 2020-03-02 「병합 0.8824」(옛 Ingersoll-Rand 주주의 교환 비율을
 * 새 IR 기호에 붙임 · 종가 32.79 → 32.80) · RJF 2023-04-13 「주식배당 1.3」(종가 92.08 → 92.86).
 *
 * <p>규칙: 기대 배수 e = 1 / 가격 배수 · 관측 배수 o = 효력일 앞 마지막 봉 → 효력일 이후 첫 봉의 원 종가 비 · σ = 효력일 앞 {@value #SIGMA_CALENDAR_DAYS}일
 * 일간 로그수익률 표준편차 · k = |ln e| / σ. <b>k ≥ {@value #MIN_K} 이고 |ln o| &lt; |ln o − ln e|</b>(관측이 1에 더 가깝다)면 보류한다.
 * 임계는 두 집단의 간격 안에서 골랐다(미국 원장 207 + 주식배당 18 실측): 소스 오류의 k 최소 7.10 · 참 사건 중 관측이 1에 더 가까운 것의 k 최대
 * 2.76(QGEN 2024-01-30 2.33 · CBSH 2023-12-01 2.76). QGEN 꼴이 「관측이 1에 가깝다 ⇒ 오류」의 반례라 자동 거절이 아니라 보류(OBSERVED — 값을 안
 * 쓰고 걸친 창을 숨긴다 · ADR-0055)로 두고 사람이 판정한다.
 *
 * <p>다른 갈래 둘:
 * <ul>
 *   <li><b>앞 마지막 봉이 체결 없는 채움</b>(거래량 0 · 시고저종 같음)이면 o를 잴 수 없다 — 보류한다(CORZ 2024-01-24: 장외 265봉이 0.0751 고정).</li>
 *   <li><b>효력일 이후 봉이 아직 없으면</b> 이번 판에 적용하지 않고 APPROVED로 둔다 — 사건 전 봉끼리는 서로 정합이라 미뤄도 값이 안 틀리고, 다음
 *       재구축이 다시 본다. <b>앞 봉이 없으면</b>(상장 전 사건 · 봉 없는 순위 후보) 소스 정본대로 적용한다 — 미루면 주식수 이동(
 *       {@code SHIFT_TO_ASOF})이 영영 빠진다.</li>
 * </ul>
 * 사정거리: k &lt; {@value #MIN_K}인 소스 오류(작은 비율)는 못 잡는다 — 그 오차는 비율 이하다.
 */
final class PriceRefutation {

    static final double MIN_K = 5.0;
    static final int SIGMA_CALENDAR_DAYS = 100;
    static final String NOTE_MARK = "가격 반증";

    private final JdbcTemplate jdbc;

    PriceRefutation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    enum Action { APPLY, DEFER, HOLD }

    record Verdict(Action action, String note) {
    }

    /** 적용할 사건만 돌려준다. 보류는 {@code hold}로 넘기고, 미룸은 그냥 뺀다(APPROVED로 남는다). */
    List<CorporateActionLedger.Entry> screen(long stockId, List<CorporateActionLedger.Entry> events, BiConsumer<Long, String> hold) {
        if (events.isEmpty()) {
            return events;
        }
        List<String> market = jdbc.queryForList("SELECT market_code FROM stock WHERE id = ?", String.class, stockId);
        if (market.isEmpty() || !UsMarket.CODE.equals(market.get(0))) {
            return events;
        }
        Set<Long> pending = new HashSet<>(jdbc.queryForList("""
                SELECT id FROM corporate_action
                WHERE stock_id = ? AND status = 'APPROVED' AND detection_source = 'SOURCE_DISCLOSURE' AND judged_at IS NULL
                """, Long.class, stockId));
        List<CorporateActionLedger.Entry> kept = new ArrayList<>(events.size());
        for (CorporateActionLedger.Entry entry : events) {
            if (!pending.contains(entry.id())) {
                kept.add(entry);
                continue;
            }
            Verdict verdict = verdict(stockId, entry);
            switch (verdict.action()) {
                case APPLY -> kept.add(entry);
                case DEFER -> { }
                case HOLD -> hold.accept(entry.id(), verdict.note());
            }
        }
        return kept;
    }

    Verdict verdict(long stockId, CorporateActionLedger.Entry entry) {
        BigDecimal multiple = entry.priceMultiple();
        if (multiple == null || multiple.signum() <= 0) {
            return new Verdict(Action.APPLY, null);
        }
        double lnE = -Math.log(multiple.doubleValue());
        if (Math.abs(lnE) < 1e-12) {
            return new Verdict(Action.APPLY, null);
        }
        LocalDate effective = entry.effectiveDate();
        List<Map<String, Object>> pre = jdbc.queryForList("""
                SELECT trade_date, open, high, low, close, volume FROM price_daily
                WHERE stock_id = ? AND trade_date < ? ORDER BY trade_date DESC LIMIT 1
                """, stockId, effective);
        if (pre.isEmpty()) {
            return new Verdict(Action.APPLY, null);
        }
        List<BigDecimal> post = jdbc.queryForList("""
                SELECT close FROM price_daily WHERE stock_id = ? AND trade_date >= ? ORDER BY trade_date LIMIT 1
                """, BigDecimal.class, stockId, effective);
        if (post.isEmpty()) {
            return new Verdict(Action.DEFER, null);
        }
        Map<String, Object> before = pre.get(0);
        BigDecimal c0 = (BigDecimal) before.get("close");
        boolean filled = ((Number) before.get("volume")).longValue() == 0
                && ((BigDecimal) before.get("open")).compareTo((BigDecimal) before.get("high")) == 0
                && ((BigDecimal) before.get("high")).compareTo((BigDecimal) before.get("low")) == 0
                && ((BigDecimal) before.get("low")).compareTo(c0) == 0;
        if (filled) {
            return new Verdict(Action.HOLD, "%s — 앞 마지막 봉 %s가 체결 없는 채움(%s)이라 관측 배수를 못 잰다 · 기대 ×%.4f"
                    .formatted(NOTE_MARK, before.get("trade_date"), c0.stripTrailingZeros().toPlainString(), Math.exp(lnE)));
        }
        if (c0.signum() <= 0 || post.get(0).signum() <= 0) {
            return new Verdict(Action.APPLY, null);
        }
        Double sigma = jdbc.queryForObject("""
                SELECT stddev_samp(ln(x.close / x.pc)) FROM (
                    SELECT close, lag(close) OVER (ORDER BY trade_date) pc FROM price_daily
                    WHERE stock_id = ? AND trade_date < ? AND trade_date >= ?) x
                WHERE x.pc > 0 AND x.close > 0
                """, Double.class, stockId, effective, effective.minusDays(SIGMA_CALENDAR_DAYS));
        if (sigma == null || sigma <= 0) {
            return new Verdict(Action.APPLY, null);
        }
        double k = Math.abs(lnE) / sigma;
        double lnO = Math.log(post.get(0).doubleValue() / c0.doubleValue());
        if (k >= MIN_K && Math.abs(lnO) < Math.abs(lnO - lnE)) {
            return new Verdict(Action.HOLD, "%s — 관측 ×%.4f(%s 앞 마지막 봉 → 그날 이후 첫 봉) · 기대 ×%.4f · σ %.4f · k %.2f — 관측이 1에 더 가깝다"
                    .formatted(NOTE_MARK, Math.exp(lnO), effective, Math.exp(lnE), sigma, k));
        }
        return new Verdict(Action.APPLY, null);
    }
}
