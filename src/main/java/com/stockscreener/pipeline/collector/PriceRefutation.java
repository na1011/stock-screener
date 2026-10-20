package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.us.UsMarket;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p><b>주식수 렌즈</b>(탐색 {@code SHARE-LENS-PLAN-1019.md} v2): k &lt; {@value #MIN_K}이고 관측이 1에 더 가까우면 가격만으로는 참 사건(QGEN · CBSH ·
 * SCCO)과 오류(GBCI 2021-02-04 「주식배당 1.1」 · SEC 표지 주식수 +0.08%)가 안 갈린다. 그때 저장된 주식수에 묻는다 — 앞 판(사건 앞 공시)과 뒤 판(기말과
 * 공시일이 둘 다 사건 뒤)의 비 s가 <b>|ln s| &lt; |ln 비율| / 2</b>면 보류한다. 두 렌즈의 AND만 쓴다: 주식수 단독은 참 사건 139 중 5에서
 * 「1」을 낸다(ADS · 계열 불변 꼴). 렌즈는 그 종목의 사건 없는 2칸 판 쌍이 {@value #SHARE_MIN_WINDOWS}개 이상이고 그중 비율과 반대 방향으로
 * |ln 비율| / 2를 넘은 몫이 {@value #SHARE_MAX_OPPOSITE} 이하일 때만 말한다 — 그것이 「참 사건 + 반대 잡음 = 1처럼 보임」의 직접 추정이다.
 * 뒤 판이 없거나 · 같은 판 키에 주식수가 둘이거나 · 표본이 모자라면 침묵하고 적용한다(소스 정본 · ADR-0090).
 *
 * <p><b>적용된 행도 매 재구축 다시 본다</b>(사람이 안 본 것만 — {@code judged_at}이 없고, 그 앞의 판정은 ADR-0090 펜스의 정의대로 노트가 없는 것).
 * 원 종가와 저장 판만 읽으므로 상태 없이 멱등이고, 판이 늦게 오거나(새 사건은 대개 다음 10-Q 전에 들어온다) 봉이 늦게 와도(봉 없는 후보가 편입) 그때
 * 판정한다. 적용된 행의 미룸(뒤 봉 없음)은 적용을 그대로 둔다.
 *
 * <p>사정거리: 가격도 1에 가깝지 않고 주식수 판도 없는 작은 비율의 오류는 못 잡는다 · 개념이 섞인 판 쌍(dei 대 가중평균 — DB가 개념을 안 적는다)은
 * 못 가른다(실측 2쌍 · 렌즈 구간 0) · 잘못 보류해도 결과는 사람에게 가는 보류다.
 */
final class PriceRefutation {

    static final double MIN_K = 5.0;
    static final int SIGMA_CALENDAR_DAYS = 100;
    static final String NOTE_MARK = "가격 반증";
    static final int SHARE_MIN_WINDOWS = 8;
    static final double SHARE_MAX_OPPOSITE = 0.05;
    /** 잡음 쌍에서 빼는 사건 창 — 앞 판 공시일 120일 앞(기말 기준 개념이 사건을 못 담는다) ~ 뒤 판 공시일 30일 뒤. */
    private static final int NOISE_EXCLUDE_BEFORE_DAYS = 120;
    private static final int NOISE_EXCLUDE_AFTER_DAYS = 30;

    private final JdbcTemplate jdbc;

    PriceRefutation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    enum Action { APPLY, DEFER, HOLD }

    record Verdict(Action action, String note) {
    }

    /** 적용할 사건만 돌려준다. 보류는 {@code hold}로 넘기고, 미룸은 APPROVED 행만 뺀다(APPROVED로 남는다 · 적용된 행은 그대로 둔다). */
    List<CorporateActionLedger.Entry> screen(long stockId, List<CorporateActionLedger.Entry> events, BiConsumer<Long, String> hold) {
        if (events.isEmpty()) {
            return events;
        }
        List<String> market = jdbc.queryForList("SELECT market_code FROM stock WHERE id = ?", String.class, stockId);
        if (market.isEmpty() || !UsMarket.CODE.equals(market.get(0))) {
            return events;
        }
        // 사람이 본 행은 안 본다 — V69_3 뒤는 judged_at, 그 앞의 판정은 ADR-0090 펜스의 정의(「APPLIED ∧ note NULL = 사람이 안 본 것」).
        // APPROVED는 노트가 있어도 본다: 기계 표지는 OBSERVED에만 붙고, 사람 APPROVED는 judged_at이 있다.
        Map<Long, String> pending = new HashMap<>();
        jdbc.query("""
                SELECT id, status FROM corporate_action
                WHERE stock_id = ? AND detection_source = 'SOURCE_DISCLOSURE' AND judged_at IS NULL
                  AND (status = 'APPROVED' OR (status = 'APPLIED' AND note IS NULL))
                """, rs -> {
            pending.put(rs.getLong("id"), rs.getString("status"));
        }, stockId);
        List<CorporateActionLedger.Entry> kept = new ArrayList<>(events.size());
        for (CorporateActionLedger.Entry entry : events) {
            String status = pending.get(entry.id());
            if (status == null) {
                kept.add(entry);
                continue;
            }
            Verdict verdict = verdict(stockId, entry);
            switch (verdict.action()) {
                case APPLY -> kept.add(entry);
                case DEFER -> {
                    if (CorporateActionStatus.APPLIED.name().equals(status)) {
                        kept.add(entry);
                    }
                }
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
        boolean closerToOne = Math.abs(lnO) < Math.abs(lnO - lnE);
        if (k >= MIN_K && closerToOne) {
            return new Verdict(Action.HOLD, "%s — 관측 ×%.4f(%s 앞 마지막 봉 → 그날 이후 첫 봉) · 기대 ×%.4f · σ %.4f · k %.2f — 관측이 1에 더 가깝다"
                    .formatted(NOTE_MARK, Math.exp(lnO), effective, Math.exp(lnE), sigma, k));
        }
        if (closerToOne) {
            String shares = shareLens(stockId, entry);
            if (shares != null) {
                return new Verdict(Action.HOLD, "%s — 주식수 %s · 가격 관측 ×%.4f · 기대 ×%.4f · k %.2f(문턱 %s 아래)"
                        .formatted(NOTE_MARK, shares, Math.exp(lnO), Math.exp(lnE), k, MIN_K));
            }
        }
        return new Verdict(Action.APPLY, null);
    }

    /** 판 하나 — 같은 판 키에 주식수가 둘이면 {@code lo ≠ hi}다. */
    private record Filing(LocalDate publishedAt, LocalDate periodEnd, double lo, double hi) {
        boolean ambiguous() {
            return lo != hi;
        }
    }

    /**
     * 주식수가 사건을 반박하면 노트에 적을 근거, 아니면(확인 · 침묵) {@code null}. 판 고르기는 {@code ShareBasisDeriver.filingsOf}와 같은 정렬이다 —
     * 다만 뒤 판은 그쪽({@code published_at ≥ 효력일})과 달리 기말도 사건 뒤여야 한다: dei 표지일은 기말 뒤 · 공시일 앞이라 공시일만 보면 사건 앞
     * 표지를 집는다(GBCI 2021-03-01 10-K의 표지 2021-02-02). 그쪽을 이 규칙으로 바꾸지 않는다 — 기저 판정(SHIFTED 6종목)이 거기 서 있다.
     */
    private String shareLens(long stockId, CorporateActionLedger.Entry entry) {
        BigDecimal ratio = entry.ratio();
        if (ratio == null || ratio.signum() <= 0 || entry.type() == CorporateAction.Type.UNKNOWN) {
            return null;
        }
        double lnM = Math.log(ratio.doubleValue());
        if (Math.abs(lnM) < 1e-12) {
            return null;
        }
        List<Filing> filings = jdbc.query("""
                SELECT f.published_at, f.period_end, min(f.shares_outstanding) lo, max(f.shares_outstanding) hi
                FROM financial_statement f
                JOIN (SELECT DISTINCT ON (published_at) published_at, period_end, revision_no FROM financial_statement
                      WHERE stock_id = ? AND shares_outstanding > 0
                      ORDER BY published_at, period_end DESC, revision_no DESC) k
                  ON k.published_at = f.published_at AND k.period_end = f.period_end AND k.revision_no = f.revision_no
                WHERE f.stock_id = ? AND f.shares_outstanding > 0
                GROUP BY f.published_at, f.period_end
                ORDER BY f.published_at
                """, (rs, i) -> new Filing(rs.getObject("published_at", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                rs.getDouble("lo"), rs.getDouble("hi")), stockId, stockId);
        LocalDate effective = entry.effectiveDate();
        Filing before = null;
        Filing after = null;
        for (Filing filing : filings) {
            if (filing.publishedAt().isBefore(effective)) {
                before = filing;
            } else if (after == null && !filing.periodEnd().isBefore(effective)) {
                after = filing;
            }
        }
        if (before == null || after == null || before.ambiguous() || after.ambiguous()) {
            return null;
        }
        double lnS = Math.log(after.lo() / before.lo());
        if (Math.abs(lnS) >= Math.abs(lnM) / 2) {
            return null;
        }
        List<LocalDate> events = jdbc.queryForList("""
                SELECT effective_date FROM corporate_action WHERE stock_id = ? AND status <> 'REJECTED'
                """, LocalDate.class, stockId);
        int windows = 0;
        int opposite = 0;
        for (int i = 0; i + 2 < filings.size(); i++) {
            Filing from = filings.get(i);
            Filing to = filings.get(i + 2);
            if (from.ambiguous() || to.ambiguous()) {
                continue;
            }
            LocalDate start = from.publishedAt().minusDays(NOISE_EXCLUDE_BEFORE_DAYS);
            LocalDate end = to.publishedAt().plusDays(NOISE_EXCLUDE_AFTER_DAYS);
            if (events.stream().anyMatch(d -> d.isAfter(start) && !d.isAfter(end))) {
                continue;
            }
            windows++;
            if (-Math.signum(lnM) * Math.log(to.lo() / from.lo()) > Math.abs(lnM) / 2) {
                opposite++;
            }
        }
        if (windows < SHARE_MIN_WINDOWS || opposite > SHARE_MAX_OPPOSITE * windows) {
            return null;
        }
        return "%s %,.0f → %s(기말 %s) %,.0f = ×%.4f · 비율 ×%s의 반폭 안 · 사건 없는 판 쌍 %d 중 반대 잡음 %d"
                .formatted(before.publishedAt(), before.lo(), after.publishedAt(), after.periodEnd(), after.lo(), Math.exp(lnS),
                        ratio.stripTrailingZeros().toPlainString(), windows, opposite);
    }
}
