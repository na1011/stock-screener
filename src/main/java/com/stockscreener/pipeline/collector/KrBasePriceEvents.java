package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.Distribution;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.kr.KrSpinOffResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 국내 관문 켬 — <b>거래소가 기준가를 조정한 날은 그 자체로 기업행위 공시다</b>(ADR-0205). 저장 봉의 기준가(V70_13)로 사건을 짓고 원장에 소스
 * 공시로 적는다 — 계수 · 주식수 보정 · 억제 · 화면이 원장 하나를 본다.
 *
 * <p>규칙: 봉 d의 기준가 ≠ 직전 거래일 종가면 가격 배수 m = 직전 종가 ÷ 기준가다. 그 기준가가 배수의 증거가 아닌 날은 짓지 않는다 —
 * <ul>
 *   <li>시장 구분이 바뀐 날(코넥스 → 코스닥 이전상장: 기준가가 새 시장의 평가가격이다 · 5건)</li>
 *   <li>직전 봉이 정지 봉이고 기준가 = 시가(재개 · 재상장의 경매가). 그날 상장주식수가 바뀌었으면 구조 사건이다 — 인적분할은
 *       {@link KrSpinOffResolver}로 짓고, 못 지으면 종류 미상(판정 대기 · ADR-0055 억제)</li>
 *   <li>직전 거래일 봉이 없다(봉 구멍 — 직전 저장 종가가 그 기준가의 짝이 아니다)</li>
 *   <li>{@code vs = 0}(기준가 = 종가)인데 주식수 계단이 확인하지 않는다 — 소스가 대비를 말하지 않은 날일 수 있다(현대건설 2021-09-01:
 *       뒤 150일 주식수 그대로 · 정체 미상). 진짜 사건 7은 계단이 확인한다(코리안리 2023-11-20 · 12-12 ×1.17429 대 1.1731)</li>
 * </ul>
 * 종류: m &lt; 1은 병합(비율 m · 상장일 = 그날) · 그날 상장주식수 배수가 m과 0.5% 안이고 5% 넘게 늘었으면 분할(비율 = 주식수 배수 ·
 * 상장일 = 그날) · 나머지 m &gt; 1은 무상 요소(무상증자 · 주식배당 · 유상증자의 IAS 33 무상 요소 — 비율 m · 상장일은 판독기가 주식수 계단으로
 * 짓는다).
 */
@Component
public class KrBasePriceEvents {

    private static final Logger log = LoggerFactory.getLogger(KrBasePriceEvents.class);

    static final String FORM = "기준가";
    static final String SPIN_OFF_FORM = "cmpDvDecsn";
    /** 기준가가 호가 단위로 반올림돼 원장 정확값과 최대 0.36% 갈린다(api-spec data-go-kr-stock-price.md). */
    static final BigDecimal TICK_TOLERANCE = new BigDecimal("0.005");
    /** 신주 상장 계단을 찾는 창 — 주식배당은 배당락 뒤 110~117일에 상장한다. */
    static final int LISTING_LOOKAHEAD_DAYS = 150;
    /**
     * 분할로 읽는 같은 날 주식수 변화의 하한 — 그보다 작은 같은 날 변화는 우연(전환 · 소각)이다: 주식배당 배당락일에 ×1.0243이 겹친 126340
     * 2025-12-29(진짜 상장은 04-16 ×1.0283). {@code DartCorporateActionAdapter.MIN_SHARE_CHANGE}와 같은 문턱이다.
     */
    static final BigDecimal SPLIT_MIN_SHARE_CHANGE = new BigDecimal("0.05");

    private static final MathContext MC = new MathContext(20);

    private final JdbcTemplate jdbc;
    private final KrSpinOffResolver spinOffs;

    public KrBasePriceEvents(JdbcTemplate jdbc, KrSpinOffResolver spinOffs) {
        this.jdbc = jdbc;
        this.spinOffs = spinOffs;
    }

    /** 사건과, 짓지 않은 날의 갈래별 수(조용히 버리지 않는다). */
    public record Detection(List<CorporateAction> events, int missingBase, int gaps, int marketChanges, int auctions,
                            int unconfirmedNoChange) {
    }

    private record Bar(LocalDate date, BigDecimal open, BigDecimal close, long volume, BigDecimal shares, BigDecimal base,
                       String market) {
    }

    public Detection detect(SecurityRef security) throws DataSourceException {
        return detect(security, LocalDate.MIN, LocalDate.MAX);
    }

    /** {@code [from, to]} 밖의 날은 짓지 않는다 — 구조 사건마다 소스를 부르므로 매일 도는 좁은 창이 지난 사건을 다시 묻지 않게. */
    public Detection detect(SecurityRef security, LocalDate from, LocalDate to) throws DataSourceException {
        List<Bar> bars = jdbc.query("""
                SELECT trade_date, open, close, volume, listed_shares, base_price, market_category
                FROM price_daily WHERE stock_id = ? ORDER BY trade_date
                """, (rs, n) -> new Bar(rs.getObject("trade_date", LocalDate.class), rs.getBigDecimal("open"),
                rs.getBigDecimal("close"), rs.getLong("volume"), rs.getBigDecimal("listed_shares"),
                rs.getBigDecimal("base_price"), rs.getString("market_category")), security.stockId());
        java.util.Map<LocalDate, String> ledgerDays = new java.util.HashMap<>();
        jdbc.query("SELECT effective_date, status FROM corporate_action WHERE stock_id = ?",
                rs -> { ledgerDays.put(rs.getObject("effective_date", LocalDate.class), rs.getString("status")); }, security.stockId());
        List<CorporateAction> events = new ArrayList<>();
        int missingBase = 0;
        int gaps = 0;
        int marketChanges = 0;
        int auctions = 0;
        int unconfirmed = 0;
        for (int i = 1; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            Bar prev = bars.get(i - 1);
            if (bar.date().isBefore(from) || bar.date().isAfter(to)) {
                continue;
            }
            if (bar.base() == null) {
                missingBase++;
                continue;
            }
            if (bar.base().signum() <= 0 || bar.base().compareTo(prev.close()) == 0) {
                continue;
            }
            if (bar.market() != null && prev.market() != null && !bar.market().equals(prev.market())) {
                marketChanges++;
                continue;
            }
            if (missingTradingDayBetween(prev.date(), bar.date())) {
                gaps++;
                continue;
            }
            BigDecimal m = prev.close().divide(bar.base(), MC);
            BigDecimal sameDay = shareMultiple(prev, bar);
            if (prev.volume() == 0 && bar.base().compareTo(bar.open()) == 0) {
                if (sameDay == null || sameDay.compareTo(BigDecimal.ONE) == 0) {
                    auctions++;
                    continue;
                }
                String held = ledgerDays.get(bar.date());
                if (held != null && !CorporateActionStatus.OBSERVED.name().equals(held)) {
                    continue;   // 그날 원장 행이 이미 판정 · 적용됐다 — 같은 날 기록은 덮지 못하니(record) 소스에 다시 묻지 않는다
                }
                events.add(structural(security, prev, bar, m, sameDay));
                continue;
            }
            if (bar.base().compareTo(bar.close()) == 0 && !close(sameDay, m) && !laterStepConfirms(bars, i, m)) {
                unconfirmed++;
                continue;
            }
            events.add(event(bar.date(), m, sameDay));
        }
        if (missingBase + gaps + unconfirmed > 0) {
            log.warn("기준가로 사건을 짓지 못한 봉 — ticker={} 기준가 없음 {} · 봉 구멍 {} · 대비 0 미확인 {}(시장 이동 {} · 재개 경매 {})",
                    security.ticker(), missingBase, gaps, unconfirmed, marketChanges, auctions);
        }
        return new Detection(List.copyOf(events), missingBase, gaps, marketChanges, auctions, unconfirmed);
    }

    private static CorporateAction event(LocalDate day, BigDecimal m, BigDecimal sameDay) {
        if (m.compareTo(BigDecimal.ONE) < 0) {
            // 병합 — 비율은 가격 배수다. 같은 날 전환 신주가 함께 상장해 주식수 배수는 병합 비율이 아니다(HLB이노베이션 2026-05-04
            // 주식수 ×0.2013 대 기준가 ×0.2 · api-spec dart-corporate-action.md).
            return new CorporateAction(day, day, day, CorporateAction.Type.REVERSE_SPLIT, m, CorporateAction.DetectionSource.SOURCE_DISCLOSURE, FORM);
        }
        if (close(sameDay, m) && sameDay.subtract(BigDecimal.ONE).compareTo(SPLIT_MIN_SHARE_CHANGE) >= 0) {
            return new CorporateAction(day, day, day, CorporateAction.Type.SPLIT, sameDay, CorporateAction.DetectionSource.SOURCE_DISCLOSURE, FORM);
        }
        return new CorporateAction(day, null, day, CorporateAction.Type.BONUS_ISSUE, m, CorporateAction.DetectionSource.SOURCE_DISCLOSURE, FORM);
    }

    /** 정지 뒤 경매가로 재개한 날 상장주식수가 바뀌었다 — 인적분할이면 짓고, 아니면 종류 미상(판정 대기). */
    private CorporateAction structural(SecurityRef security, Bar prev, Bar bar, BigDecimal m, BigDecimal shareMultiple)
            throws DataSourceException {
        Optional<KrSpinOffResolver.Child> child = spinOffs.resolve(security.ticker(), bar.date(), prev.date());
        if (child.isEmpty()) {
            return CorporateAction.named(bar.date(), bar.date(), m, FORM);
        }
        // 국내 v = 재상장일 존속 · 신설 보통주 시가총액의 비(ADR-0168).
        BigDecimal parentCap = bar.close().multiply(bar.shares());
        Distribution distribution = Distribution.of(child.get().ticker(), null, null, bar.date(), parentCap, child.get().marketCap());
        return CorporateAction.spinOff(bar.date(), bar.date(), shareMultiple, distribution, SPIN_OFF_FORM);
    }

    private static BigDecimal shareMultiple(Bar prev, Bar bar) {
        if (prev.shares() == null || bar.shares() == null || prev.shares().signum() <= 0) {
            return null;
        }
        return bar.shares().divide(prev.shares(), MC);
    }

    private static boolean close(BigDecimal shareMultiple, BigDecimal m) {
        return shareMultiple != null && shareMultiple.compareTo(BigDecimal.ONE) != 0
                && shareMultiple.divide(m, MC).subtract(BigDecimal.ONE).abs().compareTo(TICK_TOLERANCE) <= 0;
    }

    /** 사건 뒤({@code LISTING_LOOKAHEAD_DAYS}일 안) 상장주식수가 m 이상으로 오른 계단이 있는가 — {@code vs = 0}인 날의 확인. */
    private static boolean laterStepConfirms(List<Bar> bars, int at, BigDecimal m) {
        LocalDate until = bars.get(at).date().plusDays(LISTING_LOOKAHEAD_DAYS);
        BigDecimal floor = m.multiply(BigDecimal.ONE.subtract(TICK_TOLERANCE));
        for (int j = at + 1; j < bars.size() && !bars.get(j).date().isAfter(until); j++) {
            BigDecimal step = shareMultiple(bars.get(j - 1), bars.get(j));
            if (step != null && step.compareTo(floor) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 두 저장 봉 사이에 다른 국내 종목의 봉이 있는 날이 있는가(국내 거래일 달력이 따로 없다 — 시장 전체 봉의 날이 거래일이다). */
    private boolean missingTradingDayBetween(LocalDate prev, LocalDate day) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM price_daily p JOIN stock s ON s.id = p.stock_id
                               WHERE p.trade_date > ? AND p.trade_date < ? AND s.market_code IN ('KOSPI', 'KOSDAQ'))
                """, Boolean.class, prev, day);
        return Boolean.TRUE.equals(exists);
    }
}
