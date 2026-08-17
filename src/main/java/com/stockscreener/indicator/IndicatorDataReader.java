package com.stockscreener.indicator;

import com.stockscreener.pipeline.collector.CorporateActionLedger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

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
                   eps, bps, cost_of_sales, cash_and_equivalents, capex
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
    private final CorporateActionLedger corporateActions;

    public IndicatorDataReader(JdbcTemplate jdbcTemplate, CorporateActionLedger corporateActions) {
        this.jdbcTemplate = jdbcTemplate;
        this.corporateActions = corporateActions;
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

    /**
     * 계산기가 쓰는 종목 속성.
     *
     * @param industryDivision KSIC 중분류(업종코드 앞 2자리). 모르면 null이다.
     *                         <p>지표 계층이 처음으로 <b>종목 속성</b>을 읽은 자리이며 근거는
     *                         ADR-0044다. 중분류만 내는 이유는 계산기가 필요로 하는 것이
     *                         그것뿐이기 때문이다 — 소분류까지 주면 계산기가 업종 <b>비교</b>를
     *                         하기 시작하는데 그것은 {@link SectorRelativeCalculator}의 몫이다.
     * @param marketCode       {@code KOSPI}·{@code KOSDAQ}. 러너가 <b>이 종목의 시장 지수</b>를
     *                         고르는 데 쓴다(ADR-0053·ADR-0054). 계산기에 넘기지 않는 이유는
     *                         "코스닥 종목의 지수는 코스닥"이 지표의 규칙이 아니라 시장의 사실이라
     *                         계산기마다 다시 알 이유가 없기 때문이다
     */
    public record StockAttributes(String industryDivision, String marketCode) {
    }

    /**
     * 종목 속성을 한 번에 읽는다.
     *
     * <p>둘을 한 쿼리로 묶은 이유는 러너가 <b>종목당 한 번</b> 부르기 때문이다. 시장 코드만
     * 따로 물으면 실행마다 350개의 추가 왕복이 생기는데, 그것이 정확히 {@code readRawData}가
     * 피하려는 형태다.
     */
    public StockAttributes readStockAttributes(long stockId) {
        List<StockAttributes> found = jdbcTemplate.query(
                "SELECT industry_division, market_code FROM stock WHERE id = ?",
                (rs, rowNum) -> new StockAttributes(
                        rs.getString("industry_division"), rs.getString("market_code")),
                stockId);
        return found.isEmpty() ? new StockAttributes(null, null) : found.get(0);
    }

    /**
     * {@code asOf} 이하의 최근 {@code bars}봉을 지수마다 읽는다. 날짜 오름차순이다.
     *
     * <p>MUST: <b>종목이 아니라 {@code asOf} 단위로</b> 한 번 부른다. {@code readRawData}는
     * 종목마다 불리므로 거기서 읽으면 350번 같은 쿼리를 던진다 — 잡이 종목 루프 밖에서 한 번
     * 읽어 러너에 넘긴다(ADR-0054).
     *
     * <p>폭이 시세 창과 같아야 한다. 대상일 근처만 읽으면 창 안의 오래된 산출 일자가 전부
     * "지수 결손"으로 접혀 사라지는데, 백필은 몇 일자만 쓰고 <b>SUCCESS로 끝난다.</b>
     *
     * @return 지수 코드 → 거래일별 종가. 어떤 지수도 없으면 빈 맵이다
     */
    public Map<String, NavigableMap<LocalDate, BigDecimal>> readMarketIndexes(
            LocalDate asOf, int bars) {
        if (bars <= 0) {
            return Map.of();
        }
        Map<String, NavigableMap<LocalDate, BigDecimal>> byCode = new HashMap<>();
        jdbcTemplate.query("""
                SELECT index_code, trade_date, close
                FROM (
                    SELECT index_code, trade_date, close,
                           row_number() OVER (PARTITION BY index_code ORDER BY trade_date DESC) rn
                    FROM market_index_daily
                    WHERE trade_date <= ?
                ) recent
                WHERE rn <= ?
                """, rs -> {
            byCode.computeIfAbsent(rs.getString("index_code"), code -> new TreeMap<>())
                    .put(rs.getObject("trade_date", LocalDate.class), rs.getBigDecimal("close"));
        }, asOf, bars);
        return byCode;
    }

    /**
     * 계수에 반영할 기업 행위. 지표 계층이 <b>원자료 밖</b>을 읽는 두 번째 자리다(ADR-0051).
     *
     * <p>첫째는 업종이었고(ADR-0044) 그것은 종목 속성이었는데, 이쪽은 <b>다른 계층의 원장</b>이다.
     * 그럼에도 여기서 읽는 이유는 <b>"적용 대상"의 정의를 하나로 두기 위해서다</b> —
     * {@code status IN (APPROVED, APPLIED) AND type <> UNKNOWN}을 이 리더가 다시 쓰면 두 곳이
     * 어긋나기 시작하는데, 그 어긋남은 계수와 주식수가 다른 사건 집합을 보게 만들고
     * <b>어느 쪽도 에러를 내지 않는다.</b>
     *
     * <p>MUST: {@code listing_date}가 비었으면 <b>효력일로 접는다.</b> 원장은 그 값을 모를 수
     * 있다 — 휴리스틱 후보에는 아예 없고 공시 경로와 사람의 판정에만 있다. 접으면 구간이
     * 공집합이 되어 <b>보정하지 않는 쪽</b>으로 떨어지며, 그것이 "모르는 것을 추측하지 않는다"의
     * 이 자리에서의 형태다. 액면분할·병합은 두 날짜가 실제로 같으므로 접어도 정확하다.
     */
    public List<ShareAdjustment> readShareAdjustments(long stockId) {
        return corporateActions.findApplicable(stockId).stream()
                .map(entry -> new ShareAdjustment(
                        entry.effectiveDate(),
                        entry.listingDate() != null ? entry.listingDate() : entry.effectiveDate(),
                        entry.ratio()))
                .toList();
    }

    /**
     * <b>계수에 반영되지 않은</b> 기업 행위의 효력일(ADR-0055). 오름차순이다.
     *
     * <p>{@link #readShareAdjustments}가 "무엇을 보정했나"를 나르는 것과 짝으로, 이것은
     * "무엇을 보정하지 <b>못했나</b>"를 나른다. 러너가 이 날짜를 창에 대보고 값을 낼지 정한다.
     *
     * <p>{@link CorporateActionLedger#findUnadjusted}를 그대로 부른다 — 여기서 SQL을 다시 쓰면
     * "보정된 사건"과 "보정 못 한 사건"의 정의가 두 곳이 되고, 그 어긋남은 <b>보정 안 된
     * 가격으로 낸 지표가 계속 나가는 것</b>으로만 드러난다.
     */
    public List<LocalDate> readUnadjustedActions(long stockId) {
        return corporateActions.findUnadjusted(stockId);
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
                rs.getBigDecimal("bps"),
                rs.getBigDecimal("cost_of_sales"),
                rs.getBigDecimal("cash_and_equivalents"),
                rs.getBigDecimal("capex"));
    }
}
