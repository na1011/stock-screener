package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 액면분할·병합에 따라 과거 수정주가를 다시 계산한다.
 *
 * <h2>계산 모델</h2>
 * <p>{@code adj_factor}는 <b>해당 거래일 이후 발생한 모든 분할 비율의 누적곱의 역수</b>이며,
 * {@code adj_close = close × adj_factor}다. 분할이 없으면 1이고 최신 구간은 항상 1이다.
 *
 * <pre>
 * 50:1 분할이 2018-05-04에 발생한 경우
 *   2018-05-03 이전 행: adj_factor = 1/50 = 0.02  → adj_close = close × 0.02
 *   2018-05-04 이후 행: adj_factor = 1            → adj_close = close
 * </pre>
 *
 * <p>이 방향을 뒤집으면 과거 시세가 50배로 부풀고, 그 오류는 분할 종목의 차트를 직접 보기
 * 전까지 드러나지 않는다.
 *
 * <h2>왜 종목 전체가 하나의 트랜잭션인가</h2>
 * <p>절반만 조정된 시계열은 조정되지 않은 시계열보다 나쁘다. 후자는 분할일에 명백한 점프가
 * 보이지만, 전자는 임의의 날짜에 이상이 생겨 원인을 찾기 어렵다.
 */
@Component
public class AdjustmentRecalculator {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentRecalculator.class);

    /** {@code adj_factor numeric(18,10)}에 맞춘다. */
    private static final int FACTOR_SCALE = 10;

    private final JdbcTemplate jdbcTemplate;
    private final IndicatorInvalidationPort invalidationPort;

    public AdjustmentRecalculator(JdbcTemplate jdbcTemplate,
                                  IndicatorInvalidationPort invalidationPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.invalidationPort = invalidationPort;
    }

    /**
     * 기업 행위를 반영해 과거 시세의 수정주가를 다시 쓴다.
     *
     * <p>MUST: 휴리스틱으로 탐지한 후보는 여기 넘기지 않는다. 오탐(하한가 연속, 유상증자)으로
     * 2년치를 잘못 재계산하면 되돌리기가 매우 비싸고, 무엇이 잘못됐는지 나중에 알기 어렵다.
     * {@link CorporateAction.DetectionSource#HEURISTIC}인 사건은 거부한다.
     *
     * @return 갱신한 행 수
     */
    @Transactional
    public long apply(long stockId, CorporateAction action) {
        if (action.source() == CorporateAction.DetectionSource.HEURISTIC) {
            throw new IllegalArgumentException(
                    "휴리스틱으로 탐지한 기업 행위는 자동 적용하지 않는다. stockId=" + stockId
                            + " date=" + action.effectiveDate()
                            + " — 사람이 확인한 뒤 SOURCE_DISCLOSURE로 다시 넘겨야 한다.");
        }

        // 분할 전 구간의 기존 계수에 1/ratio를 곱한다. 곱하는 이유는 이미 다른 분할이
        // 반영되어 있을 수 있기 때문이다 — 덮어쓰면 앞선 분할이 사라진다.
        BigDecimal inverseRatio = BigDecimal.ONE.divide(
                action.ratio(), FACTOR_SCALE, RoundingMode.HALF_UP);

        int updated = jdbcTemplate.update("""
                UPDATE price_daily SET
                    adj_factor = adj_factor * ?,
                    adj_close  = close * (adj_factor * ?),
                    updated_at = now()
                WHERE stock_id = ? AND trade_date < ?
                """, inverseRatio, inverseRatio, stockId, action.effectiveDate());

        if (updated == 0) {
            log.info("조정 대상 시세가 없다. stockId={} effectiveDate={} — 상장 이전이거나 이력이 아직 없다.",
                    stockId, action.effectiveDate());
            return 0L;
        }

        log.info("수정주가를 재계산했다. stockId={} type={} ratio={} effectiveDate={} 갱신행={}",
                stockId, action.type(), action.ratio(), action.effectiveDate(), updated);

        // IMPORTANT — 원자료가 소급 변경되었으므로 파생 지표도 무효화해야 한다.
        // 이 호출이 빠지면 저장된 SMA200이 조정 전 가격으로 계산된 채 남는다.
        invalidationPort.invalidate(stockId, earliestTradeDate(stockId),
                IndicatorInvalidationPort.InvalidationReason.PRICE_ADJUSTED);

        return updated;
    }

    /**
     * 시세 급변으로 분할 후보를 탐지한다.
     *
     * <p>MUST NOT: 결과를 그대로 {@link #apply}에 넘기지 않는다. 반환되는 사건은 모두
     * {@link CorporateAction.DetectionSource#HEURISTIC}이며 사람의 확인을 전제로 한다.
     *
     * <p>판정 기준은 전일 대비 종가 비율이 흔한 분할비 근처인 경우다. 거래량이 함께 급증하는
     * 것을 조건에 넣지 않은 이유는, 분할 당일 거래량은 소스마다 조정 여부가 달라
     * 오히려 오탐을 늘리기 때문이다.
     */
    public List<CorporateAction> detectCandidates(long stockId) {
        return jdbcTemplate.query("""
                SELECT trade_date, close, prev_close FROM (
                    SELECT trade_date, close,
                           lag(close) OVER (ORDER BY trade_date) AS prev_close
                    FROM price_daily WHERE stock_id = ?
                ) t
                WHERE prev_close IS NOT NULL AND close > 0
                  AND (prev_close / close >= 1.8 OR close / prev_close >= 1.8)
                ORDER BY trade_date
                """,
                (rs, rowNum) -> {
                    LocalDate date = rs.getObject("trade_date", LocalDate.class);
                    BigDecimal close = rs.getBigDecimal("close");
                    BigDecimal prevClose = rs.getBigDecimal("prev_close");
                    BigDecimal ratio = prevClose.divide(close, FACTOR_SCALE, RoundingMode.HALF_UP);
                    boolean isSplit = ratio.compareTo(BigDecimal.ONE) > 0;
                    return new CorporateAction(
                            date,
                            isSplit ? CorporateAction.Type.SPLIT : CorporateAction.Type.REVERSE_SPLIT,
                            isSplit ? ratio : BigDecimal.ONE.divide(ratio, FACTOR_SCALE, RoundingMode.HALF_UP),
                            CorporateAction.DetectionSource.HEURISTIC);
                },
                stockId);
    }

    private LocalDate earliestTradeDate(long stockId) {
        return jdbcTemplate.queryForObject(
                "SELECT min(trade_date) FROM price_daily WHERE stock_id = ?",
                LocalDate.class, stockId);
    }
}
