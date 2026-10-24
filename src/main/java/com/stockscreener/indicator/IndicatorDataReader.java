package com.stockscreener.indicator;

import com.stockscreener.pipeline.collector.CorporateActionLedger;
import com.stockscreener.pipeline.datasource.us.UsMarket;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassLine;
import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 *
 * <h2>통화 게이트 (2026-08-30)</h2>
 *
 * <p><b>재무의 통화가 시장의 통화와 다른 종목이 실재한다.</b> {@code market.currency_code}
 * (KOSPI·KOSDAQ = KRW, US = USD)와 {@code financial_statement.currency_code}가 그 둘이고,
 * <b>{@link #CURRENCY_GATE}가 그 종목의 재무를 통째로 안 준다</b> — 계약과 비용은 그 상수의
 * javadoc이 정본이다.
 *
 * <p>걸리는 종목이 셋이다 — 두산밥캣(241560) · 코오롱티슈진(950160) ·
 * 인제니아테라퓨틱스(950260). 두산밥캣의 저장된 값:
 * {@code PER 17,386} · {@code PBR 1,163} · {@code PSR 937} · {@code PEG 1,265} ·
 * {@code ALTMAN_Z 960} · {@code PER_DISCOUNT_5Y −163,019} · {@code PER_PCTL 1.0}.
 * 주가를 안 쓰는 둘도 오염된다 — {@code EXEC_PAY_TO_NET_INCOME 1,873}(보수 KRW ÷ 순이익 USD) ·
 * {@code DIVIDEND_PAYOUT 57,522}. <b>비율 지표는 멀쩡하다</b>({@code OPM_MIN_5Y 7.8}).
 *
 * <p><b>행 단위가 아니라 종목 단위인 이유는 실측이다.</b> 처음에 「행 단위여야 KRW 시절의
 * 정상값을 안 지운다」고 적었는데 <b>재보니 거짓이었다</b>. 두산밥캣은 KRW 34행(2016~2023)과
 * USD 14행(2023~2026)을 함께 갖고 <b>5년 창 지표가 그 둘을 섞는다</b> — 저장된
 * {@code EPS_CAGR_5Y}가 <b>−75.2%</b>이고 {@code OCF_SUM_5Y}는 원화 3년과 달러 2년의 단순
 * 합이다. USD 행만 빼면 2023-09 재무가 최신인 척하고, KRW 행만 빼면 5년 창이 2년치가 된다.
 * <b>어느 쪽도 「정상값 보존」이 아니다.</b>
 *
 * <p><b>파급은 쟀다</b>: {@code EPS_CAGR_5Y}가 린치 프리셋 둘의 <b>필수 게이트</b>라
 * (`requiredBetween` 20~50 · 10~20) 그 셋은 지금 데이터 때문에 탈락하고 있고, 화면에서는
 * 「조건 미달」과 구별되지 않는다. 값을 지우면 백분위 모집단이 바뀌어 국내 350종목의
 * {@code *_PCTL}이 전부 다시 매겨지는데, <b>알림이 걸린 워치리스트는 1건</b>이고 그 하나가
 * {@code PER_PCTL}·{@code PBR_PCTL}을 건다(ADR-0026의 엣지 트리거가 거기 닿는다).
 *
 * <p><b>미국을 켜도 뜻이 유지된다</b> — 판정의 축이 「USD인가」가 아니라 「시장 통화와 같은가」라,
 * USD가 정상인 시장이 생겨도 그 종목은 통과한다. <b>MUST NOT: 이 판정을 통화 리터럴로
 * 바꾸지 않는다</b> — 「USD를 버린다」로 만들면 미국에서 전 종목이 지워진다.
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

    /** 두 질의가 같은 목록을 읽어야 한다 — 갈리면 시점 경로만 컬럼이 비고 값이 그럴듯하다. */
    private static final String FINANCIAL_COLUMNS = """
            fiscal_year, fiscal_period, period_start, period_end, published_at,
            currency_code, revenue, operating_income, net_income, total_assets,
            total_equity, total_equity_controlling, total_liabilities,
            current_assets, current_liabilities,
            retained_earnings, operating_cash_flow, shares_outstanding,
            eps, bps, cost_of_sales, cash_and_equivalents, capex, inventories,
            net_income_controlling,
            interest_and_dividend_income, noninterest_income, interest_expense,
            noninterest_expense, benefits_losses_and_expenses,
            gross_profit, operating_expenses, costs_and_expenses,
            income_tax_expense, profit_before_tax,
            share_basis_multiple, share_basis_status, report_code
            """;

    /**
     * 중단영업 두 컬럼(F2b · ADR-0184). <b>그 행 시장의 관문이 켜졌을 때만 읽는다</b> — 컬럼이 그 관문의 마이그레이션과 함께
     * 생기고, 꺼진 시장의 행은 이 축이 없던 때와 바이트까지 같아야 한다(「us 켬 · kr 끔에서 국내 행 무변화」).
     */
    private static final String DISCONTINUED_COLUMNS = """
            , discontinued_income, discontinued_income_controlling, discontinued_rule
            """;

    /**
     * 보통주 귀속 이익(주당 지표 분자 · 탐색 {@code PERSHARE-NUM-PLAN-1024.md} v2.1). <b>미국 관문이 켜졌을 때만 읽는다</b> — 표가 그 관문의
     * 마이그레이션과 함께 생긴다. 붙일 때의 {@code report_code}가 지금 행과 같을 때만 — 판이 바뀌었는데 유도가 아직 안 돈 행은 옛 값 대신
     * 「붙은 것 없음」(그 창 미표시)이다.
     */
    private static final String COMMON_COLUMNS = """
            , (SELECT ci.common FROM financial_common_income ci WHERE ci.financial_statement_id = financial_statement.id
                 AND ci.report_code = financial_statement.report_code) AS common_income
            , (SELECT ci.basis FROM financial_common_income ci WHERE ci.financial_statement_id = financial_statement.id
                 AND ci.report_code = financial_statement.report_code) AS common_basis
            , (SELECT ci.branch FROM financial_common_income ci WHERE ci.financial_statement_id = financial_statement.id
                 AND ci.report_code = financial_statement.report_code) AS common_branch
            """;

    /**
     * 재무를 어느 시점의 눈으로 볼 것인가(ADR-0071).
     *
     * <p>MUST: 호출부가 <b>반드시 고른다.</b> 기본값을 주거나 시점용 메서드를 옆에 따로 두면
     * 새 호출부가 라이브 쪽을 골라도 <b>아무 증상이 없다</b> — 값이 나오고, 더 좋게 나온다.
     * ADR-0045가 러너의 2인자 {@code run()}을 지운 것과 같은 이유다.
     */
    public enum FinancialVisibility {
        /**
         * 저장된 <b>최신 개정본</b>. 라이브 스크리닝이 쓴다.
         *
         * <p>{@code is_latest}로 거르는 이유는 정정공시 때문이다. 같은 기(期)에 여러 판이
         * 쌓이며 이것이 없으면 낡은 판과 최신 판이 함께 잡혀 TTM이 중복 합산된다.
         */
        LATEST,
        /**
         * {@code asOf}에 <b>공시돼 있던 판</b>. 시점 재현이 쓴다.
         *
         * <p>{@code LATEST}와 두 곳이 다르다. {@code published_at <= asOf}로 <b>미래 공시</b>를
         * 막고(실측 공시 지연 중앙값이 분기 45일·연간 80일, p90 152일), {@code is_latest}를 안 본다 —
         * {@code is_latest}는 <b>오늘의</b> 최신이라 과거 as-of에 정정 후 값이 샌다. 판 이력을 통째로
         * 주고({@code period_end DESC, published_at DESC}) 봉마다 공시일로 접는 것은
         * {@link IndicatorContext}다.
         *
         * <p>{@code period_end <= asOf}는 <b>그대로 남긴다.</b> 기말 전에 공시된 행이 실제로
         * 있고(실측 5행) 그것을 들이면 완결되지 않은 기(期)를 확정 실적처럼 쓰게 된다.
         */
        KNOWN_ON
    }

    /**
     * 재무는 {@code period_end} <b>내림차순</b>(최신 우선)이다({@link IndicatorContext} 계약).
     * TTM 합산이 "최근 4분기"를 앞에서부터 가져간다.
     *
     * <p>{@code period_end <= ?}는 미래 <b>기말</b>을 막는다. <b>미래 공시는 막지 못한다</b> —
     * 그것은 {@link FinancialVisibility#KNOWN_ON}의 몫이다.
     */
    /**
     * 재무의 통화가 <b>시장의 통화와 다른 종목</b>을 재무 읽기에서 통째로 뺀다.
     *
     * <p><b>MUST: 종목 단위다.</b> 행 단위로 거르면 창이 두 통화를 섞은 채 계산된다 —
     * 두산밥캣이 KRW 34행(2016~2023)과 USD 14행(2023~2026)을 함께 갖고, 5년 창 지표가
     * 그 둘을 더한다. 어느 쪽을 남겨도 「정상값 보존」이 아니므로 <b>전부 안 준다.</b>
     *
     * <p><b>MUST: 시장 통화와 대조한다.</b> 「USD를 버린다」로 만들면 미국을 켰을 때
     * 전 종목이 지워진다. 판정의 축은 {@code market.currency_code}다.
     *
     * <p><b>MUST: 여기(리더)에서 막는다.</b> 상위에서 거르면 ADR-0080의 「계산이 그만 난
     * 지표의 옛 행을 걷는다」가 안 걸려 <b>이미 저장된 오염값이 남는다.</b> 리더가 빈 목록을
     * 주면 계산기가 값을 못 내고, 그때 걷기가 옛 행을 지운다.
     *
     * <p><b>NULL 갈래를 두지 않는다.</b> {@code financial_statement.currency_code}가 <b>NOT NULL</b>이라
     * (컬럼 제약이 막는다) 「모른다」가 원리적으로 없다 — 실측 15,695행이 전부 KRW 아니면 USD다.
     * NULL 방어를 적으면 <b>없는 방어선을 보증하는 문장</b>이 된다.
     *
     * <p>실측(2026-08-30): 재무가 있는 377종목 중 <b>3종목</b>이 걸린다 — 두산밥캣(241560) ·
     * 코오롱티슈진(950160) · 인제니아테라퓨틱스(950260). 비용은 종목당 <b>약 +0.7ms</b>
     * (1.178ms → 1.898ms, 삼성전자 연간 8행 기준).
     */
    private static final String CURRENCY_GATE = """
              AND NOT EXISTS (
                  SELECT 1
                  FROM financial_statement mismatched
                  JOIN stock gated ON gated.id = mismatched.stock_id
                  JOIN market gate_market ON gate_market.code = gated.market_code
                  WHERE mismatched.stock_id = financial_statement.stock_id
                    AND mismatched.currency_code <> gate_market.currency_code)
            """;

    private static final String SELECT_FINANCIALS_LATEST_TEMPLATE = """
            SELECT %s
            FROM financial_statement
            WHERE stock_id = ? AND is_latest AND period_end <= ?
              AND fiscal_period %%s 'FY'
            """ + CURRENCY_GATE + """
            ORDER BY period_end DESC, published_at DESC
            LIMIT ?
            """;

    private static final String SELECT_FINANCIALS_LATEST =
            SELECT_FINANCIALS_LATEST_TEMPLATE.formatted(FINANCIAL_COLUMNS);

    private static final String SELECT_FINANCIALS_LATEST_DISCONTINUED =
            SELECT_FINANCIALS_LATEST_TEMPLATE.formatted(FINANCIAL_COLUMNS + DISCONTINUED_COLUMNS);

    private static final String SELECT_FINANCIALS_LATEST_COMMON =
            SELECT_FINANCIALS_LATEST_TEMPLATE.formatted(FINANCIAL_COLUMNS + DISCONTINUED_COLUMNS + COMMON_COLUMNS);

    /**
     * 그 시점까지 공시된 <b>판 이력 전부</b>를 읽는다({@link FinancialVisibility#KNOWN_ON}).
     *
     * <p><b>기(期)마다 하나로 접지 않는다</b>(ADR-0128). 접으면 {@code asOf} 하나에 대해서만
     * 판이 정해져 <b>봉마다 다른 판을 고를 수 없다</b> — 재무 지표의 시간축이 거기서 죽는다.
     * 접는 것은 {@link IndicatorContext}가 하고, 어느 판을 볼지는 <b>봉이 정한다</b>
     * ({@link IndicatorContext#visibleAt}). {@link #readGovernanceFacts}가 같은 이유로 같은
     * 모양이다.
     *
     * <p><b>MUST NOT: 연수나 행 수로 자르지 않는다.</b> {@code LIMIT}은 <b>행</b> 수라
     * 창 앞부분에서는 최신 기(期)가 아직 공시되지 않아 <b>더 오래된 행으로 채워진다</b> —
     * 그러면 요구한 연수가 조용히 한 해씩 미끄러지고, 자르면 그 깊은 해가 잘려
     * <b>창 앞부분이 통째로 비는데 최신 일자는 멀쩡하다.</b> 거버넌스가 같은 자리에서 같은
     * 결론에 도달했다({@link #readGovernanceFacts} javadoc의 「회계연도로 상한을 두지 않는다」).
     *
     * <p>자르지 않아도 싸다 — 실측(2026-09-07)으로 활성 유니버스 종목당 <b>평균 44.9행 ·
     * 최대 114행</b>이고, 자르던 시절이 연간 7 + 분기 32 = 39행이었다.
     *
     * <p>인자가 셋이다({@code stock_id}, {@code published_at}, {@code period_end}).
     * {@code LATEST}는 {@code LIMIT}을 써서 <b>인자 수가 다르다</b> — 한 배열로 합치면
     * 조용히 어긋난다.
     */
    private static final String SELECT_FINANCIALS_KNOWN_ON_TEMPLATE = """
            SELECT %1$s
            FROM financial_statement
            WHERE stock_id = ? AND published_at <= ? AND period_end <= ?
              AND fiscal_period %%s 'FY'
            """ + CURRENCY_GATE + """
            ORDER BY period_end DESC, published_at DESC
            """;

    private static final String SELECT_FINANCIALS_KNOWN_ON =
            SELECT_FINANCIALS_KNOWN_ON_TEMPLATE.formatted(FINANCIAL_COLUMNS);

    private static final String SELECT_FINANCIALS_KNOWN_ON_DISCONTINUED =
            SELECT_FINANCIALS_KNOWN_ON_TEMPLATE.formatted(FINANCIAL_COLUMNS + DISCONTINUED_COLUMNS);

    private static final String SELECT_FINANCIALS_KNOWN_ON_COMMON =
            SELECT_FINANCIALS_KNOWN_ON_TEMPLATE.formatted(FINANCIAL_COLUMNS + DISCONTINUED_COLUMNS + COMMON_COLUMNS);

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
    private final ShareClassStore shareClasses;
    /**
     * 미국 관문 설정 — 켜졌으면 미국 종목의 재무 행에 주식수 클래스 축 판정을 입힌다({@link ShareClassLine}). 꺼졌으면 판정 표를
     * 부르지 않고 행은 이 축이 없던 때와 같다.
     */
    private final boolean usGate;
    /** 국내 관문 설정 — 켜졌으면 국내 종목의 재무 행에 중단영업 두 컬럼을 입힌다(F2b). */
    private final boolean krGate;

    public IndicatorDataReader(JdbcTemplate jdbcTemplate, CorporateActionLedger corporateActions,
                               ShareClassStore shareClasses, @Value("${stockscreener.gate.us}") boolean usGate,
                               @Value("${stockscreener.gate.kr}") boolean krGate) {
        this.jdbcTemplate = jdbcTemplate;
        this.corporateActions = corporateActions;
        this.shareClasses = shareClasses;
        this.usGate = usGate;
        this.krGate = krGate;
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
     * @param industryDivisionKey KSIC 중분류(업종코드 앞 2자리). 모르면 null이다.
     *                         <p>지표 계층이 처음으로 <b>종목 속성</b>을 읽은 자리이며 근거는
     *                         ADR-0044다. 중분류만 내는 이유는 계산기가 필요로 하는 것이
     *                         그것뿐이기 때문이다 — 소분류까지 주면 계산기가 업종 <b>비교</b>를
     *                         하기 시작하는데 그것은 {@link SectorRelativeCalculator}의 몫이다.
     * @param marketCode       {@code KOSPI}·{@code KOSDAQ}. 러너가 <b>이 종목의 시장 지수</b>를
     *                         고르는 데 쓴다(ADR-0053·ADR-0054). 계산기에 넘기지 않는 이유는
     *                         "코스닥 종목의 지수는 코스닥"이 지표의 규칙이 아니라 시장의 사실이라
     *                         계산기마다 다시 알 이유가 없기 때문이다
     * @param financialCurrencyMismatched 재무의 통화가 <b>시장의 통화와 다른가</b>.
     *                         참이면 {@link #CURRENCY_GATE}가 그 종목의 재무를 한 행도 안 준다 —
     *                         러너는 그것을 <b>「값이 없다」로 읽어 옛 행을 걷는다</b>(ADR-0080·0094).
     *                         이 필드가 없으면 러너가 「우리가 막은 것」과 「아직 안 들어온 것」을
     *                         구별할 수단이 없어, <b>오염된 값이 영영 남는다.</b>
     */
    public record StockAttributes(
            String industryDivisionKey, String marketCode, boolean financialCurrencyMismatched) {
    }

    /**
     * 종목 속성을 한 번에 읽는다.
     *
     * <p>셋을 한 쿼리로 묶은 이유는 러너가 <b>종목당 한 번</b> 부르기 때문이다. 시장 코드만
     * 따로 물으면 실행마다 350개의 추가 왕복이 생기는데, 그것이 정확히 {@code readRawData}가
     * 피하려는 형태다.
     *
     * <p><b>MUST: 통화 판정의 술어를 {@link #CURRENCY_GATE}와 같게 유지한다.</b> 그쪽이
     * {@code is_latest}를 보지 않으므로 여기도 보지 않는다 — 통화가 다른 행이 <b>개정 전 판에만</b>
     * 있는 종목이 실재하고(실측 6종목, 전부 US), 둘이 갈리면 <b>게이트는 막는데 러너는
     * 「불일치 아님」으로 읽어</b> 옛 행이 영영 안 걷힌다. 미국 지표가 생기는 날 드러난다.
     */
    public StockAttributes readStockAttributes(long stockId) {
        List<StockAttributes> found = jdbcTemplate.query("""
                SELECT s.industry_division_key,
                       s.market_code,
                       EXISTS (SELECT 1
                                 FROM financial_statement f
                                 JOIN market m ON m.code = s.market_code
                                WHERE f.stock_id = s.id
                                  AND f.currency_code <> m.currency_code) AS currency_mismatched
                  FROM stock s
                 WHERE s.id = ?
                """,
                (rs, rowNum) -> new StockAttributes(
                        rs.getString("industry_division_key"), rs.getString("market_code"),
                        rs.getBoolean("currency_mismatched")),
                stockId);
        return found.isEmpty() ? new StockAttributes(null, null, false) : found.get(0);
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

    /**
     * 연간 재무를 최신 우선으로 읽는다.
     *
     * @param visibility 어느 시점의 눈으로 볼 것인가. <b>기본값이 없다</b> — 이유는
     *                   {@link FinancialVisibility} javadoc에 있다
     */
    public List<FinancialFigures> readAnnualFigures(long stockId, LocalDate asOf, int years,
                                                    FinancialVisibility visibility) {
        if (years <= 0) {
            return List.of();
        }
        return queryFigures("=", stockId, asOf, years, visibility);
    }

    /**
     * 분기 재무를 최신 우선으로 읽는다.
     *
     * <p>연수를 분기 수로 환산할 때 <b>여유를 둔다.</b> 정확히 {@code years × 4}만 읽으면
     * TTM 4분기 합산이 경계에서 딱 맞아, 한 분기라도 누락된 종목은 값이 조용히 사라진다.
     * 재무 공시는 실제로 빠지는 일이 있으므로 한 해치를 더 읽는다.
     */
    public List<FinancialFigures> readQuarterlyFigures(long stockId, LocalDate asOf, int years,
                                                       FinancialVisibility visibility) {
        if (years <= 0) {
            return List.of();
        }
        return queryFigures("<>", stockId, asOf, (years + 1) * 4, visibility);
    }

    /**
     * 거버넌스 사실을 <b>회계연도 내림차순 · 공시일 내림차순</b>으로 읽는다(ADR-0081).
     *
     * <p>IMPORTANT — 같은 회계연도의 여러 판을 <b>하나로 접지 않는다.</b> 접으면 「가장 최근에
     * 접수된 판」만 남는데, 그것은 <b>계단 함수를 무력화한다</b>: FY2024 배당은 2025년 3월
     * 보고서가 이미 말했는데 2026년 보고서에도 (전기 열로) 다시 실리므로, 접으면 그 값을
     * <b>2026년 3월에야 알게 된 것</b>이 되어 창 앞부분이 통째로 빈다. 실측으로 그 앞부분이
     * 쓰기 창 255거래일 중 137이다(2026-09-08).
     *
     * <p>어느 판을 볼지는 <b>봉이 정한다</b> — {@code GovernanceMath.on}이 그 거래일에 공시돼
     * 있던 것 중 가장 최신 판을 고른다. 정정본은 자기 공시일부터 이기고, 그것이 곧 정정 반영이다.
     *
     * <p>IMPORTANT — <b>회계연도로 상한을 두지 않는다.</b> 계산기가 선언하는 연수는 「그 지표가
     * 몇 해를 보는가」인데, 창 앞부분에서는 <b>최신 회계연도가 아직 공시되지 않아</b> 기준 해가
     * 뒤로 밀린다 — 3년 차분이 실제로는 최신 해에서 다섯째 해까지를 본다. 선언 값으로 자르면
     * 그 깊은 해가 잘려 <b>창 앞부분이 통째로 비는데 최신 일자는 멀쩡하다.</b> 실제로 그 상태가
     * 났고, 세 지표가 창 시작에서 1종목이었다.
     *
     * <p>자르지 않아도 싸다 — 실측으로 <b>종목당 평균 64행 · 최대 78행</b>이다. {@code years}는
     * 「읽을지 말지」만 정하고, 그 판정은 {@code DataRequirement.needsGovernance}가 소유한다.
     *
     * <p>모르는 {@code metric_key}는 <b>던지지 않고 건너뛴다.</b> {@code IndicatorCatalog}가
     * {@code valueOf}를 그대로 불러 비활성 행 하나로 카탈로그 조회가 통째로 터지던 형태를
     * 되풀이하지 않는다 — 그 사고를 드러내는 자리는 유도 잡의 집계다.
     */
    public List<GovernanceFigures> readGovernanceFacts(long stockId, LocalDate asOf, int years,
                                                       FinancialVisibility visibility) {
        if (years <= 0) {
            return List.of();
        }
        String publishedFilter = visibility == FinancialVisibility.KNOWN_ON
                ? "AND published_at <= ?" : "";
        String sql = """
                SELECT fiscal_year, receipt_no, metric_key, value, published_at
                  FROM governance_fact
                 WHERE stock_id = ? %s
                 ORDER BY fiscal_year DESC, published_at DESC, receipt_no DESC
                """.formatted(publishedFilter);

        Object[] args = visibility == FinancialVisibility.KNOWN_ON
                ? new Object[]{stockId, asOf} : new Object[]{stockId};

        // 키가 (회계연도, 접수번호)다 — 같은 해의 판 여럿이 각각 하나의 묶음이 된다.
        Map<String, Map<GovernanceMetric, GovernanceValue>> byReport = new LinkedHashMap<>();
        Map<String, Integer> yearOfReport = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            GovernanceMetric metric = GovernanceMetric.find(rs.getString("metric_key"));
            if (metric == null) {
                return;
            }
            int fiscalYear = rs.getInt("fiscal_year");
            String key = fiscalYear + "|" + rs.getString("receipt_no");
            yearOfReport.putIfAbsent(key, fiscalYear);
            byReport.computeIfAbsent(key, k -> new EnumMap<>(GovernanceMetric.class))
                    .put(metric, new GovernanceValue(rs.getBigDecimal("value"),
                            rs.getObject("published_at", LocalDate.class)));
        }, args);

        List<GovernanceFigures> figures = new ArrayList<>(byReport.size());
        byReport.forEach((key, values) ->
                figures.add(new GovernanceFigures(yearOfReport.get(key), values)));
        return figures;
    }

    /**
     * 두 가시성의 인자 배열이 다르다는 사실을 여기 한 곳에 가둔다.
     *
     * <p>{@code KNOWN_ON}은 {@code asOf}를 <b>두 번</b> 쓴다({@code published_at}과
     * {@code period_end}). 호출부마다 배열을 만들면 하나만 넘겨 <b>파라미터가 밀리는데</b>,
     * JDBC는 개수가 맞으면 타입만 보고 통과시키므로 값이 그럴듯하게 틀린다.
     */
    private List<FinancialFigures> queryFigures(String periodOperator, long stockId,
                                                LocalDate asOf, int limit,
                                                FinancialVisibility visibility) {
        String market = usGate || krGate ? marketOf(stockId) : null;
        boolean us = UsMarket.CODE.equals(market);
        // 그 행 시장의 관문만 본다 — 한쪽 관문을 연 상태에서 다른 시장 행이 이 축을 타면 그 시장 값이 관문 없이 바뀐다.
        boolean discontinued = market != null && (us ? usGate : krGate);
        ShareClassLine line = usGate && us ? shareClasses.line(stockId) : null;
        RowMapper<FinancialFigures> mapper = (rs, rowNum) -> mapFigures(rs, line, discontinued);
        return switch (visibility) {
            case LATEST -> jdbcTemplate.query(
                    (line != null ? SELECT_FINANCIALS_LATEST_COMMON
                            : discontinued ? SELECT_FINANCIALS_LATEST_DISCONTINUED : SELECT_FINANCIALS_LATEST)
                            .formatted(periodOperator),
                    mapper,
                    stockId, asOf, limit);
            // MUST: limit을 넘기지 않는다 — 이 갈래는 판 이력을 통째로 준다(위 javadoc).
            case KNOWN_ON -> jdbcTemplate.query(
                    (line != null ? SELECT_FINANCIALS_KNOWN_ON_COMMON
                            : discontinued ? SELECT_FINANCIALS_KNOWN_ON_DISCONTINUED : SELECT_FINANCIALS_KNOWN_ON)
                            .formatted(periodOperator),
                    mapper,
                    stockId, asOf, asOf);
        };
    }

    /** 종목의 시장 코드. 종목이 없으면 {@code null}이다. */
    private String marketOf(long stockId) {
        List<String> market = jdbcTemplate.queryForList("SELECT market_code FROM stock WHERE id = ?", String.class, stockId);
        return market.isEmpty() ? null : market.get(0);
    }

    /**
     * @param line {@code null}이면 행이 실은 주식수 그대로(관문 꺼짐 · 국내). 있으면 클래스 축 판정을 입힌다 — 주식수는 그 판정이
     *             정한 값, 숨김이면 상태로 막고, 분모 n을 싣는다.
     * @param discontinued 그 행 시장의 관문이 켜졌는가 — 켜졌을 때만 질의가 중단영업 두 컬럼을 싣는다
     */
    private static FinancialFigures mapFigures(ResultSet rs, ShareClassLine line, boolean discontinued)
            throws SQLException {
        BigDecimal shares = rs.getBigDecimal("shares_outstanding");
        ShareClassLine.State state = null;
        BigDecimal weighted = null;
        if (line != null) {
            ShareClassLine.Read read = line.read(rs.getString("report_code"),
                    rs.getObject("published_at", LocalDate.class), shares);
            shares = read.shares();
            state = read.state();
            weighted = read.denominator();
        }
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
                rs.getBigDecimal("total_equity_controlling"),
                rs.getBigDecimal("total_liabilities"),
                rs.getBigDecimal("current_assets"),
                rs.getBigDecimal("current_liabilities"),
                rs.getBigDecimal("retained_earnings"),
                rs.getBigDecimal("operating_cash_flow"),
                shares,
                rs.getBigDecimal("eps"),
                rs.getBigDecimal("bps"),
                rs.getBigDecimal("cost_of_sales"),
                rs.getBigDecimal("cash_and_equivalents"),
                rs.getBigDecimal("capex"),
                rs.getBigDecimal("inventories"),
                rs.getBigDecimal("net_income_controlling"),
                rs.getBigDecimal("interest_and_dividend_income"),
                rs.getBigDecimal("noninterest_income"),
                rs.getBigDecimal("interest_expense"),
                rs.getBigDecimal("noninterest_expense"),
                rs.getBigDecimal("benefits_losses_and_expenses"),
                rs.getBigDecimal("gross_profit"),
                rs.getBigDecimal("operating_expenses"),
                rs.getBigDecimal("costs_and_expenses"),
                rs.getBigDecimal("income_tax_expense"),
                rs.getBigDecimal("profit_before_tax"),
                rs.getBigDecimal("share_basis_multiple"),
                rs.getString("share_basis_status"),
                state, weighted,
                discontinued ? rs.getBigDecimal("discontinued_income") : null,
                discontinued ? rs.getBigDecimal("discontinued_income_controlling") : null,
                discontinued ? rs.getBigDecimal("discontinued_rule") : null,
                line == null ? null : new CommonIncomeRead(rs.getBigDecimal("common_income"), rs.getString("common_basis"),
                        rs.getString("common_branch")));
    }
}
