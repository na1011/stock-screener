package com.stockscreener.pipeline.collector;

import com.stockscreener.indicator.ShareBasis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장된 <b>기업행위 원장과 주식수 계열만</b> 보고 각 재무 판의 기저를 판정한다 —
 * 외부 호출 0 · 원자료 0행 수정.
 *
 * <p><b>왜 필요한가</b>: 시가총액 = 주식수 × 종가인데 <b>그 둘의 기저가 다른 종목이 있다.</b>
 * {@code AKTX}는 SEC 표지가 보통주 155,758,529,533주를 주고 시세는 ADS당 가격이라 저장 시총이
 * 1,565B로 미국 8위에 앉았다. 비율 자체는 소스가 주지 않지만(ADR-0162 §판정),
 * <b>원장은 「기저가 어긋났다」는 것은 말해 준다.</b>
 *
 * <p><b>판정은 세 갈래다</b>({@link ShareBasis}). 선언된 주식 교환이 있었는데 계열이 그 비율을
 * 안 따라갔으면 그 뒤 판은 사건 전 기저이고({@code B}), 그 위에 「다른 배수로 움직인」
 * 사건까지 겹치면 크기를 못 세므로 값을 안 낸다.
 *
 * <p><b>MUST: 판정을 종목 상수로 쓰지 않는다.</b> 배수는 <b>판마다</b> 달라야 한다 — 사건 전에
 * 공시된 판은 1이어야 하고, 상수로 두면 {@code SharesGrowth3yCalculator}처럼 분자·분모에 같은
 * 수를 곱하는 지표에서 <b>약분돼 한 톨도 안 고쳐진다</b>(실측: {@code AMRN}
 * {@code SHARES_GROWTH_3Y} {@code +1,958.36%} · 참값 약 {@code +2.9%}).
 *
 * <p><b>모집단을 재료 사건 보유 종목으로 좁히는 것은 정의상 완전하다</b> — 선언된 주식 교환이
 * 하나도 없는 종목은 이 축에서 {@code OK}일 수밖에 없다.
 */
@Component
public class ShareBasisDeriver {

    private static final Logger log = LoggerFactory.getLogger(ShareBasisDeriver.class);

    /**
     * <b>선언된 주식 교환의 상한</b> — {@code new/old}를 약분했을 때 분자·분모 중 <b>작은 쪽</b>이
     * 이 값 이하라야 주식 교환으로 센다.
     *
     * <p><b>근거는 「주주가 온주로 교환받는다」이다</b> — N-for-1·1-for-N은 작은 쪽이 1,
     * 3-for-2는 2다. {@code 1000-for-1973}은 참여에 1,000주가 필요해 <b>어떤 회사도 선언하지
     * 않는다</b> — 그것은 분사·역합병의 <b>가격계수</b>가 분할 배열에 실린 것이다.
     *
     * <p><b>실측이 이 임계를 정당화한다</b>(2026-09-23 · 미국 130종목 153사건):
     * 작은 쪽이 <b>1인 것 123건 · 2인 것 24건</b>이고 <b>3~96에 한 건도 없다가</b>
     * 97·189·200·1000·1000·1103에서 여섯이 나온다 — {@code QGEN}(합성 자사주) ·
     * {@code DD}(Corteva) · {@code FTV}(Vontier) · {@code DELL}(VMware) ·
     * {@code TT}+{@code IR}(같은 Reverse Morris Trust 양쪽). <b>3에서 96 사이 어디를 골라도
     * 답이 같다</b> — 내 결론만 통과하는 임계가 아니다.
     *
     * <p><b>MUST: 소스의 두 표기를 함께 받는다.</b> 3-for-2가 {@code 2→3}과 {@code 1→1.5} 두
     * 형태로 온다(실측: 전자 19건 · 후자 5건). 「양쪽이 정수인가」로 재면 정당한 분할 다섯이 죽는다.
     */
    private static final int MAX_SMALL_SIDE = 10;

    /** 상대 허용오차. 원장 {@code ratio}가 {@code numeric(18,10)}이라 나눗셈 잔차가 이만큼 남는다. */
    private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("1e-8");

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    /** 계열이 사건을 따라갔다고 볼 범위. {@code 1.5}배 안이면 따라간 것이다. */
    private static final double FOLLOW_BAND = 1.5;

    /** 계열이 꼼짝 안 했다고 볼 범위. {@code 1.10}배 안이면 불변이다. */
    private static final double UNCHANGED_BAND = 1.10;

    private final JdbcTemplate jdbcTemplate;

    public ShareBasisDeriver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 판정 결과 집계. */
    public record Tally(int stocksExamined, int shifted, int unresolved, int rowsWritten,
                        List<String> shiftedTickers, List<String> unresolvedTickers) {
    }

    /**
     * {@code marketCode} 시장 전체를 다시 판정한다. <b>집합으로 교체한다</b> — 대상 종목의 기존
     * 판정을 먼저 지우고 다시 쓰므로, 원장이나 계열이 바뀌어 판정이 뒤집혀도 옛 값이 안 남는다.
     */
    @Transactional
    public Tally derive(String marketCode) {
        Map<Long, String> tickers = new LinkedHashMap<>();
        Map<Long, List<Event>> events = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT c.stock_id, s.ticker, c.effective_date, c.ratio
                FROM corporate_action c
                JOIN stock s ON s.id = c.stock_id
                WHERE c.status = 'APPLIED' AND s.market_code = ? AND c.ratio <> 1
                ORDER BY c.stock_id, c.effective_date
                """, rs -> {
            long stockId = rs.getLong("stock_id");
            tickers.putIfAbsent(stockId, rs.getString("ticker"));
            events.computeIfAbsent(stockId, k -> new ArrayList<>())
                    .add(new Event(rs.getObject("effective_date", LocalDate.class),
                            rs.getBigDecimal("ratio")));
        }, marketCode);

        int rows = clearExisting(marketCode);
        List<String> shifted = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<Long, List<Event>> entry : events.entrySet()) {
            long stockId = entry.getKey();
            List<Event> declared = entry.getValue().stream()
                    .filter(e -> isDeclaredShareExchange(e.ratio()))
                    .sorted(Comparator.comparing(Event::effectiveDate))
                    .toList();
            if (declared.isEmpty()) {
                continue;
            }
            List<Filing> filings = filingsOf(stockId);
            if (filings.size() < 2) {
                continue;
            }
            Verdict verdict = judge(declared, filings);
            if (verdict == null) {
                continue;
            }
            rows += apply(stockId, verdict);
            (verdict.status() == ShareBasis.UNRESOLVED ? unresolved : shifted)
                    .add(tickers.get(stockId));
        }
        log.info("주식수 기저 판정. 시장={} 재료사건보유={} SHIFTED={}{} UNRESOLVED={}{} 쓴행={}",
                marketCode, events.size(), shifted.size(), shifted, unresolved.size(), unresolved, rows);
        return new Tally(events.size(), shifted.size(), unresolved.size(), rows, shifted, unresolved);
    }

    /**
     * 사건 하나가 <b>선언된 주식 교환</b>인가 — {@code ratio}를 약분했을 때 분자·분모 중 작은 쪽이
     * {@link #MAX_SMALL_SIDE} 이하인가.
     *
     * <p>연분수를 쓰지 않는다. {@code min(p,q) <= M}인 {@code p/q}는 <b>「{@code ratio × m}이
     * 정수」이거나 「{@code m / ratio}가 정수」인 {@code m <= M}이 있다</b>는 것과 같으므로,
     * {@code M}번 곱하고 {@code M}번 나누면 전부 걸린다.
     */
    static boolean isDeclaredShareExchange(BigDecimal ratio) {
        if (ratio == null || ratio.signum() <= 0) {
            return false;
        }
        for (int m = 1; m <= MAX_SMALL_SIDE; m++) {
            BigDecimal times = ratio.multiply(BigDecimal.valueOf(m));
            if (nearInteger(times)) {
                return true;
            }
            BigDecimal divided = BigDecimal.valueOf(m).divide(ratio, MC);
            if (nearInteger(divided)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearInteger(BigDecimal value) {
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        if (rounded.signum() <= 0) {
            return false;
        }
        return value.subtract(rounded).abs()
                .compareTo(value.abs().multiply(RELATIVE_TOLERANCE)) <= 0;
    }

    /**
     * 종목 하나의 판정. 판정할 것이 없으면 {@code null}이다.
     *
     * <p><b>MUST NOT: 「판정불가 사건이 있으면 UNRESOLVED」로 쓰지 않는다.</b> 판정불가만 있는
     * 종목 아홉이 전부 정상이고({@code CVNA} $48B · {@code IR} $28B · {@code QXO} $13B 등),
     * 그렇게 읽으면 그들이 통째로 사라진다. <b>「계열 불변」이 기저가 어긋났다는 양성 증거이고
     * 판정불가는 그 크기를 못 세게 만드는 것</b>이라, 둘이 <b>함께</b> 있을 때만 값을 안 낸다.
     */
    private Verdict judge(List<Event> declared, List<Filing> filings) {
        List<Event> unchanged = new ArrayList<>();
        boolean entangled = false;
        for (Event event : declared) {
            Filing before = selectBefore(filings, event.effectiveDate());
            Filing after = selectAfter(filings, event.effectiveDate());
            if (before == null || after == null) {
                continue;
            }
            double observed = after.shares().doubleValue() / before.shares().doubleValue();
            double expected = event.ratio().doubleValue();
            if (within(observed / expected, FOLLOW_BAND)) {
                continue;
            }
            if (within(observed, UNCHANGED_BAND)) {
                unchanged.add(event);
            } else {
                entangled = true;
            }
        }
        if (unchanged.isEmpty()) {
            return null;
        }
        if (entangled) {
            LocalDate from = declared.get(0).effectiveDate();
            return new Verdict(ShareBasis.UNRESOLVED, from, null);
        }
        // 계열이 뒤에서 따라잡으면 이 축이 아니다 — 표지일이 공시일보다 앞선 한 판의 문제이고
        // ADR-0133이 그 축의 두 처방을 이미 순손실로 실측했다. 건드리지 않는다.
        for (Event event : unchanged) {
            if (catchesUp(filings, event)) {
                return null;
            }
        }
        BigDecimal multiple = BigDecimal.ONE;
        for (Event event : unchanged) {
            multiple = multiple.multiply(event.ratio());
        }
        return new Verdict(ShareBasis.SHIFTED, unchanged.get(0).effectiveDate(), multiple);
    }

    /** 사건 뒤 어느 판에서 계열이 그 비율만큼 계단을 이루는가 — 이루면 기저가 거기서 복구된 것이다. */
    private boolean catchesUp(List<Filing> filings, Event event) {
        for (int i = 1; i < filings.size(); i++) {
            Filing prev = filings.get(i - 1);
            Filing cur = filings.get(i);
            if (cur.publishedAt().isBefore(event.effectiveDate())) {
                continue;
            }
            double step = cur.shares().doubleValue() / prev.shares().doubleValue();
            if (within(step / event.ratio().doubleValue(), 1.2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean within(double value, double band) {
        return value > 1 / band && value < band;
    }

    private int apply(long stockId, Verdict verdict) {
        return jdbcTemplate.update("""
                UPDATE financial_statement
                SET share_basis_status = ?, share_basis_multiple = ?
                WHERE stock_id = ? AND published_at >= ?
                """, verdict.status().name(), verdict.multiple(), stockId, verdict.from());
    }

    private int clearExisting(String marketCode) {
        return jdbcTemplate.update("""
                UPDATE financial_statement f
                SET share_basis_status = NULL, share_basis_multiple = NULL
                FROM stock s
                WHERE s.id = f.stock_id AND s.market_code = ?
                  AND (f.share_basis_status IS NOT NULL OR f.share_basis_multiple IS NOT NULL)
                """, marketCode);
    }

    /**
     * 생산 선택 규칙과 <b>같은 정렬</b>로 판을 모은다 — {@code period_end DESC} 우선이다.
     *
     * <p><b>MUST: 이 정렬을 바꾸지 않는다.</b> {@code period_end ASC}로 고르면 같은 공시가 싣는
     * <b>전년 비교열</b>을 집는데, 생산의 {@code publishedIndexOn}·{@code DISTINCT ON}은 그것을
     * 한 번도 안 쓴다(ADR-0133 §범위). 실측으로 그 한 글자가 「계열이 안 따라감」을
     * <b>4건에서 27건으로</b> 부풀렸고 {@code ORLY}·{@code PANW}·{@code CTAS} 같은 정상 대형주가
     * 전부 결함으로 신고됐다.
     */
    private List<Filing> filingsOf(long stockId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT ON (published_at) published_at, shares_outstanding
                FROM financial_statement
                WHERE stock_id = ? AND shares_outstanding IS NOT NULL AND shares_outstanding > 0
                ORDER BY published_at, period_end DESC, revision_no DESC
                """, (rs, n) -> new Filing(rs.getObject("published_at", LocalDate.class),
                rs.getBigDecimal("shares_outstanding")), stockId);
    }

    private static Filing selectBefore(List<Filing> filings, LocalDate date) {
        Filing found = null;
        for (Filing filing : filings) {
            if (filing.publishedAt().isBefore(date)) {
                found = filing;
            }
        }
        return found;
    }

    private static Filing selectAfter(List<Filing> filings, LocalDate date) {
        for (Filing filing : filings) {
            if (!filing.publishedAt().isBefore(date)) {
                return filing;
            }
        }
        return null;
    }

    private record Event(LocalDate effectiveDate, BigDecimal ratio) {
    }

    private record Filing(LocalDate publishedAt, BigDecimal shares) {
    }

    private record Verdict(ShareBasis status, LocalDate from, BigDecimal multiple) {
    }
}
