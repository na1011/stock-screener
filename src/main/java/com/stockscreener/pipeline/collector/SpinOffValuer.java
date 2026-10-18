package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.Distribution;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Optional;

/**
 * 소스가 알린 분사의 가치 비율 v를 같은 날 두 종가로 재서 가격 배수 s/v를 정한다.
 *
 * <p>소스(미국 Alpaca {@code spin_offs})는 자식 심볼과 배정 비율을 주고 <b>가격 배수는 안 준다</b> — 그래서
 * ADR-0090이 분사를 「안 담고 센다」로 뒀다. 이 클래스가 그 빈자리다: 자식의 봉은 가격 포트로, 모체의 봉은
 * 저장된 시세로 읽어 {@link Distribution}의 두 가치를 채운다. 관례는 같은 날 종가의 가치비다
 * ({@link Distribution} javadoc · 탐색 브랜치 {@code explore/spin-off/PLAN.md} §2.1).
 *
 * <h2>값을 재는 날</h2>
 * <p>ex일에 자식 봉이 있으면 그날이다. 없으면 ex 뒤 <b>{@value #VALUE_DATE_TRADING_DAYS}거래일 안의 자식 첫
 * 봉</b>과 그날 모체 종가를 쓴다 — 분배된 워런트는 ex 며칠 뒤에야 거래된다(실측: GME.WS +3거래일 · OPENW·OPENL·OPENZ
 * +4). 두 가치는 여전히 같은 날의 값이다. 이 창은 원장이 「같은 사건의 날짜가 어긋날 수 있는 폭」으로 이미 쓰는
 * 근접중복 창과 같은 값이다 — 새로 고른 임계가 아니다.
 *
 * <p>MUST NOT: 그 창 밖의 봉으로 갈음하지 않는다. 자식 첫 봉이 ex 뒤 12거래일인 MRP(LEN 2025-01-21)는 소스
 * 날짜가 가격과 안 맞는 사건이라(그만한 하루 낙폭이 어느 날에도 없다) 사람이 봐야 한다 — 해석하지 않고 돌려준다.
 *
 * <h2>해석하지 못하면</h2>
 * <p>받은 사건을 그대로 돌려준다 — {@link CorporateAction.Type#UNKNOWN}이고 가격 배수가 없다. 원장은 그것을
 * {@code OBSERVED}로 적고(근접 행이 있으면 NEAR_DUPLICATE) ADR-0055가 그 종목의 창을 억제하며, 잡이 「판정 대기」로
 * 알린다. 사람은 발행사의 배분 비율(Form 8937)로 판정하거나 {@code UNSUPPORTED}로 보낸다.
 */
@Component
public class SpinOffValuer {

    /** 자식 첫 봉을 기다리는 거래일. 원장의 근접중복 창과 같다({@link CorporateActionLedger}). */
    static final int VALUE_DATE_TRADING_DAYS = CorporateActionLedger.NEAR_DUPLICATE_TRADING_DAYS;

    /** 자식 봉을 묻는 달력 폭. 5거래일을 연휴 너머까지 덮는다 — 넘는 봉은 위 거래일 판정이 거른다. */
    private static final int CHILD_LOOKAHEAD_CALENDAR_DAYS = 14;

    private static final Logger log = LoggerFactory.getLogger(SpinOffValuer.class);

    private final DataSourceRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    public SpinOffValuer(DataSourceRegistry registry, JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return 해석했으면 가격 배수가 든 {@link CorporateAction.Type#SPIN_OFF}, 못 했으면 받은 그대로
     * @throws DataSourceException 일시 장애·한도·인증 — 「자식 가격이 없다」와 갈라야 한다. 일시 장애를
     *                             미해석으로 접으면 사람에게 거짓 판정 대기가 가고 그 종목이 억제된다.
     */
    public CorporateAction value(SecurityRef parent, CorporateAction unresolved) throws DataSourceException {
        Distribution distribution = unresolved.distribution();
        if (distribution == null || distribution.valued()) {
            return unresolved;
        }
        if (distribution.children().stream().anyMatch(c -> c.symbol() == null || c.rate() == null)) {
            log.warn("분사의 자식이나 배정 비율을 소스가 안 줬다 — 사람이 판정해야 한다. ticker={} {}",
                    parent.ticker(), unresolved.effectiveDate());
            return unresolved;
        }
        LocalDate ex = unresolved.effectiveDate();
        // 자식마다 ex 뒤 봉을 받는다. 값을 재는 날은 모든 자식이 거래된 첫 날 — 그날 자식 전부와 모체의 종가를 쓴다.
        Map<String, NavigableMap<LocalDate, BigDecimal>> closes = new LinkedHashMap<>();
        LocalDate valueDate = ex;
        for (Distribution.Child child : distribution.children()) {
            NavigableMap<LocalDate, BigDecimal> bars = childCloses(parent.marketCode(), child, ex);
            if (bars.isEmpty()) {
                log.warn("분사 자식의 봉이 ex 뒤 {}일 안에 없다 — 해석하지 않는다. ticker={} 자식={} ex={}",
                        CHILD_LOOKAHEAD_CALENDAR_DAYS, parent.ticker(), child.symbol(), ex);
                return unresolved;
            }
            closes.put(child.symbol(), bars);
            if (bars.firstKey().isAfter(valueDate)) {
                valueDate = bars.firstKey();
            }
        }
        long tradingDaysAfterEx = parentBarsAfter(parent.stockId(), ex, valueDate);
        if (tradingDaysAfterEx > VALUE_DATE_TRADING_DAYS) {
            log.warn("분사 자식의 첫 봉이 ex 뒤 {}거래일이라 같은 사건의 값으로 쓰지 않는다 — 소스 날짜를 의심한다."
                            + " ticker={} 자식={} ex={} 첫 봉={}",
                    tradingDaysAfterEx, parent.ticker(), distribution.newSymbol(), ex, valueDate);
            return unresolved;
        }
        BigDecimal childValue = BigDecimal.ZERO;
        for (Distribution.Child child : distribution.children()) {
            BigDecimal close = closes.get(child.symbol()).get(valueDate);
            if (close == null) {
                log.warn("분사 자식이 값을 재는 날 거래되지 않았다 — 해석하지 않는다. ticker={} 자식={} 날짜={}",
                        parent.ticker(), child.symbol(), valueDate);
                return unresolved;
            }
            childValue = childValue.add(child.rate().multiply(close));
        }
        Optional<BigDecimal> parentClose = parentClose(parent.stockId(), valueDate);
        if (parentClose.isEmpty()) {
            log.warn("분사 모체의 그날 봉이 아직 없다 — 다음 수집이 해석한다. ticker={} 날짜={}",
                    parent.ticker(), valueDate);
            return unresolved;
        }
        BigDecimal shareMultiple = unresolved.ratio();
        Distribution valued = distribution.valuedAt(valueDate, shareMultiple.multiply(parentClose.get()), childValue);
        return CorporateAction.spinOff(ex, unresolved.sourceDate(), shareMultiple, valued, unresolved.sourceForm());
    }

    /**
     * 자식은 그날의 기호로 묻는다 — 소스가 준 자식 기호는 배분 당시의 것이라, asof 없이 물으면 「오늘 그 기호의 실체」가 와서 재사용·개명된
     * 기호가 다른 실체를 준다(ADR-0181 · UHALB). 자식이 asof를 따로 들고 오면(원장 보충 파일) 그것을, 아니면 ex 날짜를 닻으로 쓴다.
     */
    private NavigableMap<LocalDate, BigDecimal> childCloses(String marketCode, Distribution.Child child, LocalDate ex)
            throws DataSourceException {
        String childSymbol = child.symbol();
        LocalDate asOf = child.asOf() != null ? child.asOf() : ex;
        SecurityRef childRef = new SecurityRef(0L, marketCode, childSymbol,
                new SecurityRef.Identity(childSymbol, asOf, null, null, null, List.of()));
        List<RawPriceBar> bars;
        try {
            bars = registry.priceSource(marketCode).fetchDailyBars(
                    childRef, ex, ex.plusDays(CHILD_LOOKAHEAD_CALENDAR_DAYS));
        } catch (DataSourceException e) {
            if (e.kind() == DataSourceException.Kind.NOT_FOUND || e.kind() == DataSourceException.Kind.MALFORMED) {
                log.warn("분사 자식의 봉을 소스가 못 준다. 자식={} ex={} 원인={}", childSymbol, ex, e.getMessage());
                return new TreeMap<>();
            }
            throw e;
        }
        NavigableMap<LocalDate, BigDecimal> closes = new TreeMap<>();
        for (RawPriceBar bar : bars) {
            if (!bar.tradeDate().isBefore(ex) && bar.close() != null && bar.close().signum() > 0) {
                closes.put(bar.tradeDate(), bar.close());
            }
        }
        return closes;
    }

    /** {@code (ex, valueDate]}에 있는 모체 봉 수 — 그 종목의 거래일로 센다(원장의 근접중복과 같은 셈). */
    private long parentBarsAfter(long stockId, LocalDate ex, LocalDate valueDate) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM price_daily WHERE stock_id = ? AND trade_date > ? AND trade_date <= ?
                """, Long.class, stockId, ex, valueDate);
        return count == null ? 0 : count;
    }

    private Optional<BigDecimal> parentClose(long stockId, LocalDate tradeDate) {
        return jdbcTemplate.query("""
                SELECT close FROM price_daily WHERE stock_id = ? AND trade_date = ? AND close > 0
                """, (rs, n) -> rs.getBigDecimal("close"), stockId, tradeDate).stream().findFirst();
    }
}
