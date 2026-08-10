package com.stockscreener.indicator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * 지표 계산의 입력이 되는 원자료를 저장소에서 읽는다.
 *
 * <p>계산기가 직접 조회하지 않고 러너가 이것으로 한 번에 읽어 {@link IndicatorContext}에 담는다.
 * 계산 로직이 순수해져 테스트가 쉽고, 종목당 조회가 지표 개수와 무관해진다.
 *
 * <p>IMPORTANT — 여기서 만드는 {@link PriceBar}·{@link FinancialFigures}는 수집 계층의
 * {@code RawPriceBar}·{@code RawFinancialReport}와 <b>다른 타입이며 합치지 않는다</b>(ADR-0006).
 * 두 타입이 하나가 되는 순간 외부 소스의 스펙 변경이 지표 계산기까지 번진다. 이 리더가
 * 그 경계에 서서 저장된 것을 지표 계층의 말로 옮기는 역할을 한다.
 */
@Component
public class IndicatorDataReader {

    /**
     * 시세는 날짜 <b>오름차순</b>이다({@link IndicatorContext#priceBars()} 계약).
     * 이동평균처럼 창을 미끄러뜨리는 계산이 전부 이 순서를 전제한다.
     *
     * <p>최신 N봉을 얻으려면 내림차순으로 잘라낸 뒤 다시 뒤집어야 한다 — 오름차순
     * {@code LIMIT}은 <b>가장 오래된</b> N봉을 준다. 이 실수는 워밍업이 긴 지표에서
     * "값이 안 나온다"가 아니라 "옛날 값이 나온다"로 드러나 알아채기 어렵다.
     */
    private static final String SELECT_PRICE_BARS = """
            SELECT trade_date, open, high, low, close, adj_close, volume, trade_value, listed_shares
            FROM (
                SELECT trade_date, open, high, low, close, adj_close, volume, trade_value, listed_shares
                FROM price_daily
                WHERE stock_id = ? AND trade_date <= ?
                ORDER BY trade_date DESC
                LIMIT ?
            ) recent
            ORDER BY trade_date
            """;

    /**
     * 재무는 {@code period_end} <b>내림차순</b>(최신 우선)이다({@link IndicatorContext} 계약).
     * TTM 합산이 "최근 4분기"를 앞에서부터 가져간다.
     *
     * <p>{@code is_latest}로 거르는 이유는 정정공시 때문이다. 같은 기(期)에 여러 판이 쌓이며
     * 이것이 없으면 낡은 판과 최신 판이 함께 잡혀 TTM이 중복 합산된다.
     *
     * <p>{@code period_end <= ?}인 이유는 <b>미래 정보 차단</b>이다. 과거 어느 날의 지표를
     * 다시 계산할 때 그 시점에 없던 재무를 쓰면 백테스트가 조용히 낙관적으로 나온다.
     */
    private static final String SELECT_FINANCIALS = """
            SELECT fiscal_year, fiscal_period, period_start, period_end, published_at,
                   currency_code, revenue, operating_income, net_income, total_assets,
                   total_equity, total_liabilities, current_assets, current_liabilities,
                   retained_earnings, operating_cash_flow, shares_outstanding,
                   eps, bps
            FROM financial_statement
            WHERE stock_id = ? AND is_latest AND period_end <= ?
              AND fiscal_period %s 'FY'
            ORDER BY period_end DESC, published_at DESC
            LIMIT ?
            """;

    private static final RowMapper<PriceBar> PRICE_BAR_MAPPER = (rs, rowNum) -> new PriceBar(
            rs.getObject("trade_date", LocalDate.class),
            rs.getBigDecimal("open"),
            rs.getBigDecimal("high"),
            rs.getBigDecimal("low"),
            rs.getBigDecimal("close"),
            rs.getBigDecimal("adj_close"),
            rs.getLong("volume"),
            rs.getBigDecimal("trade_value"),
            rs.getBigDecimal("listed_shares"));

    private final JdbcTemplate jdbcTemplate;

    public IndicatorDataReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@code asOf} 이하의 최근 {@code bars}봉을 날짜 오름차순으로 읽는다.
     *
     * <p>요구한 만큼 없으면 있는 만큼만 온다. 계산기가 워밍업 부족을 스스로 판단해
     * 값을 내지 않는 것이 옳고(ADR-0002), 리더가 부족을 예외로 만들면 신규 상장 종목마다
     * 배치가 실패한다.
     */
    public List<PriceBar> readPriceBars(long stockId, LocalDate asOf, int bars) {
        if (bars <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(SELECT_PRICE_BARS, PRICE_BAR_MAPPER, stockId, asOf, bars);
    }

    /** 연간 재무를 최신 우선으로 읽는다. */
    public List<FinancialFigures> readAnnualFigures(long stockId, LocalDate asOf, int years) {
        if (years <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                SELECT_FINANCIALS.formatted("="),
                IndicatorDataReader::mapFigures,
                stockId, asOf, years);
    }

    /**
     * 분기 재무를 최신 우선으로 읽는다.
     *
     * <p>연수를 분기 수로 환산할 때 <b>여유를 둔다.</b> 정확히 {@code years × 4}만 읽으면
     * TTM 4분기 합산이 경계에서 딱 맞아, 한 분기라도 누락된 종목은 값이 조용히 사라진다.
     * 재무 공시는 실제로 빠지는 일이 있으므로 한 해치를 더 읽는다.
     */
    public List<FinancialFigures> readQuarterlyFigures(long stockId, LocalDate asOf, int years) {
        if (years <= 0) {
            return List.of();
        }
        int quarters = (years + 1) * 4;
        return jdbcTemplate.query(
                SELECT_FINANCIALS.formatted("<>"),
                IndicatorDataReader::mapFigures,
                stockId, asOf, quarters);
    }

    private static FinancialFigures mapFigures(ResultSet rs, int rowNum) throws SQLException {
        return new FinancialFigures(
                rs.getInt("fiscal_year"),
                FinancialFigures.FiscalPeriod.valueOf(rs.getString("fiscal_period")),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getObject("published_at", LocalDate.class),
                rs.getString("currency_code"),
                rs.getBigDecimal("revenue"),
                rs.getBigDecimal("operating_income"),
                rs.getBigDecimal("net_income"),
                rs.getBigDecimal("total_assets"),
                rs.getBigDecimal("total_equity"),
                rs.getBigDecimal("total_liabilities"),
                rs.getBigDecimal("current_assets"),
                rs.getBigDecimal("current_liabilities"),
                rs.getBigDecimal("retained_earnings"),
                rs.getBigDecimal("operating_cash_flow"),
                rs.getBigDecimal("shares_outstanding"),
                rs.getBigDecimal("eps"),
                rs.getBigDecimal("bps"));
    }
}
