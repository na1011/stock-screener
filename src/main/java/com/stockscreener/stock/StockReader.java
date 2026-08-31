package com.stockscreener.stock;

import com.stockscreener.pipeline.collector.CorporateActionLedger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 화면이 필요로 하는 종목 정보를 읽는다.
 *
 * <p>{@link StockEntity}를 늘리지 않고 JdbcTemplate을 쓰는 이유: 엔티티는 스크리닝 쿼리의
 * 구동 테이블이라 QueryDSL Q클래스가 필요해서 존재하는 것이고, 거기에 {@code sector}·
 * {@code name_en} 같은 표시용 컬럼을 더하면 Q클래스가 함께 바뀌는데 얻는 것이 없다.
 * {@code ddl-auto: validate} 아래에서는 컬럼을 하나 더할 때마다 매핑을 정확히 맞춰야 한다.
 *
 * <p>종목 정보와 그날 시세를 <b>한 쿼리로</b> 가져온다. 표에 350행이 오는데 행마다 시세를
 * 따로 물으면 그것이 N+1이다.
 */
@Component
public class StockReader {

    private final JdbcTemplate jdbcTemplate;
    private final CorporateActionLedger corporateActions;

    public StockReader(JdbcTemplate jdbcTemplate, CorporateActionLedger corporateActions) {
        this.jdbcTemplate = jdbcTemplate;
        this.corporateActions = corporateActions;
    }

    /**
     * 계수에 반영되지 않은 기업 행위의 효력일. 오름차순이다(ADR-0055).
     *
     * <p>화면이 <b>"왜 이 종목의 지표가 없는가"</b>에 답할 수 있는 유일한 자리다. 억제된 종목은
     * FILTER 프리셋 결과에 아예 없고, SCORING이라도 필수 조건이면 통째로 빠지므로 조건별
     * "데이터 없음"이 그 이유를 말해 주지 못한다.
     *
     * <p>{@link CorporateActionLedger#findUnadjusted}를 그대로 부른다 — 여기서 SQL을 다시 쓰면
     * 억제하는 쪽과 설명하는 쪽이 서로 다른 사건 집합을 보게 되고, 그 어긋남은
     * <b>"지표는 없는데 이유는 안 보인다"</b>로만 나타난다.
     */
    public List<LocalDate> unadjustedActionsOf(long stockId) {
        return corporateActions.findUnadjusted(stockId);
    }

    /**
     * 결과 표에 필요한 종목 정보 + 기준일 종가.
     *
     * <p>시세가 없는 종목도 행을 준다({@code close}가 null). {@code LEFT JOIN}인 이유는
     * 지표는 계산됐는데 그날 시세만 없는 경우에 종목이 표에서 통째로 사라지면
     * "왜 결과 수가 다르지"를 추적할 수 없기 때문이다.
     */
    @Transactional(readOnly = true)
    public Map<Long, StockRow> findRows(Collection<Long> stockIds, LocalDate asOf) {
        if (stockIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", stockIds.stream().map(id -> "?").toList());

        Object[] args = new Object[stockIds.size() + 1];
        args[0] = asOf;
        int i = 1;
        for (Long id : stockIds) {
            args[i++] = id;
        }

        List<StockRow> rows = jdbcTemplate.query("""
                SELECT s.id, s.ticker, s.name, s.market_code, mk.currency_code, s.sector, p.close
                FROM stock s
                JOIN market mk ON mk.code = s.market_code
                LEFT JOIN price_daily p ON p.stock_id = s.id AND p.trade_date = ?
                WHERE s.id IN (%s)
                """.formatted(placeholders), ROW_MAPPER, args);

        return rows.stream().collect(Collectors.toMap(
                StockRow::id, r -> r, (a, b) -> a, LinkedHashMap::new));
    }

    /** 종목 상세의 기본 정보. */
    @Transactional(readOnly = true)
    public Optional<StockRow> findById(long stockId, LocalDate asOf) {
        return findRows(List.of(stockId), asOf).values().stream().findFirst();
    }

    /**
     * 종목 상세의 기준일 시세 전체.
     *
     * <p>{@code trade_value}는 소스가 주지 않으면 NULL이고 {@code listed_shares}는 V9에서
     * 추가되어 옛 행에는 없다. 둘 다 그대로 null로 내보낸다 — 0으로 채우면 화면이
     * "거래대금 0원"을 사실로 표시한다.
     */
    @Transactional(readOnly = true)
    public Optional<PriceRow> priceOn(long stockId, LocalDate tradeDate) {
        return jdbcTemplate.query(PRICE_SELECT + " AND trade_date = ?",
                PRICE_ROW_MAPPER, stockId, tradeDate).stream().findFirst();
    }

    /**
     * {@code before} <b>직전</b>의 시세 한 행. 전일 대비 등락의 기준이다.
     *
     * <p>MUST NOT: {@code PriceCalendar.latestTradeDateOnOrBefore}로 대신하지 않는다.
     * 그것은 <b>전 종목 공통</b>의 최근 거래일이라 그 종목이 거래정지였던 구간을 넘지 못하고,
     * 그때 시세가 없는 날짜를 "직전 거래일"로 답한다.
     *
     * <p>IMPORTANT — 반환된 날짜가 <b>달력상 전일이라는 보장이 없다.</b> 거래정지 구간은
     * 봉 자체가 없어(소스가 준 OHLC 0 행을 {@code PriceWriter}가 버린다) 수 주 전이 나올 수
     * 있다. 그래서 화면이 라벨을 정할 수 있도록 <b>날짜를 함께</b> 내보낸다 — 26일 전 종가와의
     * 차이를 "전일 대비"라 부르지 않기 위해서다.
     */
    @Transactional(readOnly = true)
    public Optional<PriceRow> previousPrice(long stockId, LocalDate before) {
        // PK가 (stock_id, trade_date)라 역방향 인덱스 스캔 한 번이다.
        return jdbcTemplate.query(PRICE_SELECT + " AND trade_date < ? ORDER BY trade_date DESC LIMIT 1",
                PRICE_ROW_MAPPER, stockId, before).stream().findFirst();
    }

    private static final String PRICE_SELECT = """
            SELECT trade_date, open, high, low, close, adj_close, volume, trade_value, listed_shares
            FROM price_daily WHERE stock_id = ?""";

    private static final RowMapper<PriceRow> PRICE_ROW_MAPPER = (rs, n) -> new PriceRow(
            rs.getObject("trade_date", LocalDate.class),
            rs.getBigDecimal("open"), rs.getBigDecimal("high"),
            rs.getBigDecimal("low"), rs.getBigDecimal("close"),
            rs.getBigDecimal("adj_close"),
            rs.getObject("volume", Long.class),
            rs.getBigDecimal("trade_value"),
            rs.getBigDecimal("listed_shares"));

    /**
     * 실적 원수치 이력. <b>지표가 아니라 소스가 준(또는 우리가 유도한) 재무제표 값이다.</b>
     *
     * <p>{@code IndicatorDataReader.readQuarterlyFigures}를 재사용하지 않는다. 같은 테이블을
     * 읽지만 답하는 질문이 다르다 — 저쪽은 <b>지표 러너의 읽기 창</b>이라 {@code LIMIT}으로
     * 행 수만 맞추고 연도 구멍이 보이지 않으며, {@code FinancialFigures}에 정정 회차가 없다.
     *
     * <p>MUST: {@code period_start}·{@code period_end}를 <b>내보내지 않는다.</b> ADR-0056이 그
     * 값을 합성 규약({@code 01-01 ~ 12-31})으로 못박았고 결산월이 12월이 아닌 종목도 예외가
     * 아니라, 화면에 그리면 <b>아직 오지 않은 기간</b>이 실적표에 뜬다(신영증권 FY2026이 실제로
     * {@code 2026-01-01 ~ 2026-12-31}이고 공시일이 6월이다). 기간 라벨은 회계연도와 분기로 짓는다.
     *
     * @param limit 최근 몇 기(期)까지. 종목당 평균 30.9행·최대 37행이라 상한이 필요하다.
     */
    @Transactional(readOnly = true)
    public List<FinancialRow> financialsOf(long stockId, int limit) {
        return jdbcTemplate.query("""
                SELECT fiscal_year, fiscal_period, published_at, revision_no, report_code,
                       revenue, operating_income, net_income, operating_cash_flow,
                       total_assets, total_liabilities, total_equity
                FROM financial_statement
                WHERE stock_id = ? AND is_latest
                ORDER BY period_end DESC, fiscal_period DESC
                LIMIT ?
                """, (rs, n) -> new FinancialRow(
                        rs.getInt("fiscal_year"),
                        rs.getString("fiscal_period"),
                        rs.getObject("published_at", LocalDate.class),
                        rs.getInt("revision_no"),
                        rs.getString("report_code"),
                        rs.getBigDecimal("revenue"),
                        rs.getBigDecimal("operating_income"),
                        rs.getBigDecimal("net_income"),
                        rs.getBigDecimal("operating_cash_flow"),
                        rs.getBigDecimal("total_assets"),
                        rs.getBigDecimal("total_liabilities"),
                        rs.getBigDecimal("total_equity")),
                stockId, limit);
    }

    /**
     * 업종 비교의 표본 크기. <b>지표 행이 없는 이유를 답하는 자리다.</b>
     *
     * <p>{@code SectorRelativeCalculator}는 표본이 임계에 못 미치면 상대값뿐 아니라
     * <b>표본 수 행까지 쓰지 않는다.</b> 그래서 그 종목의 상세에는 업종 카드도, 없는 이유도
     * 남지 않고 사용자에게는 수집 실패와 구분되지 않는다. 실측으로 최신 지표 일자에
     * {@code PER}은 269종목인데 {@code PER_SECTOR_N}은 215종목이다.
     *
     * <p>여기서 세는 것은 <b>구조적 업종 크기</b>이지 특정 지표의 표본이 아니다. 둘은 다르다 —
     * 업종에 8종목이 있어도 그중 셋만 PER이 있으면 PER의 표본은 3이다. 그 구분은 지표별
     * {@code _SECTOR_N}이 답하고, 이 값은 <b>업종 자체가 너무 작은가</b>에만 답한다.
     */
    @Transactional(readOnly = true)
    public Optional<SectorPeerRow> sectorPeersOf(long stockId) {
        List<SectorPeerRow> rows = jdbcTemplate.query("""
                WITH member AS (
                    SELECT s.id, coalesce(s.industry_code, s.sic_code) AS industry_code,
                           s.industry_group_key, s.industry_division_key
                    FROM stock s
                    WHERE s.industry_group_key IS NOT NULL
                      AND EXISTS (SELECT 1 FROM universe_membership m
                                  JOIN universe u ON u.code = m.universe_code AND u.is_active
                                  WHERE m.stock_id = s.id AND m.effective_to IS NULL)
                ),
                me AS (SELECT * FROM member WHERE id = ?)
                SELECT me.industry_code,
                       (SELECT count(*) FROM member g
                         WHERE g.industry_group_key = me.industry_group_key) AS fine_peers,
                       (SELECT count(*) FROM member d
                         WHERE d.industry_division_key = me.industry_division_key) AS broad_peers
                FROM me
                """, (rs, n) -> new SectorPeerRow(
                        rs.getString("industry_code"),
                        rs.getInt("fine_peers"),
                        rs.getInt("broad_peers")),
                stockId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 업종 중분류. <b>지표가 그 종목에 적용되지 않는다는 것을 판정하는 데 쓴다.</b>
     *
     * <p>MUST: {@link #sectorPeersOf}를 이 용도로 쓰지 않는다. 그 쿼리는 <b>업종이 있고 활성
     * 유니버스에 편입된</b> 종목으로 좁혀 있어, 편입이 풀린 종목에 빈 결과를 준다 — 그러면
     * "이 지표는 보험·금융서비스에 적용하지 않는다" 같은 사유가 <b>조용히 안 붙는다.</b>
     * 적용 가능성은 편입 여부와 무관한 사실이다.
     *
     * <p>MUST NOT: 업종 원문을 받아 자바에서 앞 두 자리를 자르지 않는다.
     * {@code industry_division_key}는 <b>DB 생성 컬럼</b>이고(V17·V36) 그 자리를 만든 이유가
     * "둘이 갈릴 수 없게"다. 자바에서 다시 자르면 유도 지점이 둘이 된다.
     */
    @Transactional(readOnly = true)
    public Optional<String> industryDivisionKeyOf(long stockId) {
        List<String> found = jdbcTemplate.queryForList(
                "SELECT industry_division_key FROM stock WHERE id = ?", String.class, stockId);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    /** 이 종목이 현재 편입되어 있는 유니버스들. 상세 화면이 "어느 지수 종목인가"를 보여준다. */
    @Transactional(readOnly = true)
    public List<StockUniverse> currentUniversesOf(long stockId) {
        return jdbcTemplate.query("""
                SELECT u.code, u.name, m.effective_from
                FROM universe_membership m
                JOIN universe u ON u.code = m.universe_code
                WHERE m.stock_id = ? AND m.effective_to IS NULL
                ORDER BY u.code
                """, (rs, n) -> new StockUniverse(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getObject("effective_from", LocalDate.class)),
                stockId);
    }

    /**
     * 스크리닝 대상이 될 수 있는 유니버스 목록.
     *
     * <p>{@code JOIN}(≠ {@code LEFT JOIN})이 의도다 — <b>편입 종목이 하나도 없는 유니버스는
     * 내보내지 않는다.</b> 지금 DB에는 시드 유니버스({@code KOSPI200} 등)가 비활성·0건으로
     * 남아 있고, 미국({@code SP500})은 데이터가 없다. 그것들을 선택지로 제시하면 사용자는
     * 고르고 나서야 결과가 비어 있는 이유를 묻게 된다. 미국이 v1.1에서 붙으면 자동으로 나타난다.
     */
    @Transactional(readOnly = true)
    public List<UniverseRow> selectableUniverses() {
        return jdbcTemplate.query("""
                SELECT u.code, u.name, u.market_code, mk.currency_code,
                       count(m.stock_id) AS member_count
                FROM universe u
                JOIN market mk ON mk.code = u.market_code
                JOIN universe_membership m ON m.universe_code = u.code AND m.effective_to IS NULL
                WHERE u.is_active
                GROUP BY u.code, u.name, u.market_code, mk.currency_code
                ORDER BY u.code
                """, (rs, n) -> new UniverseRow(
                rs.getString("code"), rs.getString("name"),
                rs.getString("market_code"), rs.getString("currency_code"),
                rs.getInt("member_count")));
    }

    /**
     * 종목명·티커로 검색한다.
     *
     * <p><b>현재 활성 유니버스에 편입된 종목만</b> 대상이다. 편입되지 않은 종목은 시세도
     * 지표도 없어서, 검색 결과로 들어가면 값이 하나도 없는 상세 화면을 보게 된다.
     * {@link #selectableUniverses()}가 같은 판단을 하고 있으므로 기준을 맞춘다.
     *
     * <p>정렬을 티커 완전일치 → 티커 접두 → 종목명 접두 → 종목명 포함 순으로 두는 이유는
     * "005930"이나 "삼성전자"를 그대로 친 사용자가 그 종목을 첫 줄에서 보게 하기 위해서다.
     * 이름 포함 검색만 하면 "삼성"에 삼성전자가 여섯 번째로 나온다.
     */
    @Transactional(readOnly = true)
    public List<SearchRow> search(String query, int limit) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        String prefix = normalized + "%";
        String contains = "%" + normalized + "%";

        return jdbcTemplate.query("""
                SELECT s.id, s.ticker, s.name, s.market_code,
                       (SELECT string_agg(m.universe_code, ',' ORDER BY m.universe_code)
                          FROM universe_membership m
                         WHERE m.stock_id = s.id AND m.effective_to IS NULL) AS universe_codes
                FROM stock s
                WHERE EXISTS (
                        SELECT 1 FROM universe_membership m
                        JOIN universe u ON u.code = m.universe_code
                        WHERE m.stock_id = s.id AND m.effective_to IS NULL AND u.is_active)
                  AND (lower(s.ticker) LIKE ? OR lower(s.name) LIKE ?)
                ORDER BY CASE
                           WHEN lower(s.ticker) = ?     THEN 0
                           WHEN lower(s.ticker) LIKE ?  THEN 1
                           WHEN lower(s.name)   LIKE ?  THEN 2
                           ELSE 3
                         END,
                         s.name
                LIMIT ?
                """, (rs, n) -> new SearchRow(
                        rs.getLong("id"),
                        rs.getString("ticker"),
                        rs.getString("name"),
                        rs.getString("market_code"),
                        splitCodes(rs.getString("universe_codes"))),
                contains, contains, normalized, prefix, prefix, limit);
    }

    private static List<String> splitCodes(String aggregated) {
        return aggregated == null || aggregated.isBlank() ? List.of() : List.of(aggregated.split(","));
    }

    /** 주어진 유니버스가 스크리닝 대상으로 유효한가. */
    @Transactional(readOnly = true)
    public boolean isSelectableUniverse(String universeCode) {
        return selectableUniverses().stream().anyMatch(u -> u.code().equals(universeCode));
    }

    private static final RowMapper<StockRow> ROW_MAPPER = (rs, rowNum) -> new StockRow(
            rs.getLong("id"),
            rs.getString("ticker"),
            rs.getString("name"),
            rs.getString("market_code"),
            rs.getString("currency_code"),
            rs.getString("sector"),
            rs.getBigDecimal("close"));

    /**
     * @param close        기준일 <b>원주가</b> 종가. 사용자가 실제로 지불하는 가격이다(수정종가가 아니다).
     * @param currencyCode 그 금액의 통화. <b>화면이 「원」을 붙일지 「$」를 붙일지의 유일한 근거다</b> —
     *                     시장 코드에서 유도하면 시장이 늘 때 프론트가 조용히 낡는다.
     *                     이 한 질의가 결과 표와 종목 상세를 함께 먹이므로 두 화면이 같은 답을 쓴다.
     */
    public record StockRow(long id, String ticker, String name, String marketCode,
                           String currencyCode, String sector, BigDecimal close) {
    }

    public record StockUniverse(String code, String name, LocalDate since) {
    }

    /** 재무제표 한 기(期)의 원수치. 기간은 회계연도·분기가 정본이다(위 javadoc). */
    public record FinancialRow(int fiscalYear, String fiscalPeriod, LocalDate publishedAt,
                               int revisionNo, String reportCode,
                               BigDecimal revenue, BigDecimal operatingIncome, BigDecimal netIncome,
                               BigDecimal operatingCashFlow, BigDecimal totalAssets,
                               BigDecimal totalLiabilities, BigDecimal totalEquity) {
    }

    /** 업종 자체의 크기. 지표별 표본은 {@code _SECTOR_N}이 따로 답한다. */
    public record SectorPeerRow(String industryCode, int finePeers, int broadPeers) {
    }

    /**
     * 시세 한 행.
     *
     * <p>{@code adjClose}는 화면에 <b>내보내지 않는다.</b> 표시가는 언제나 {@code close}다
     * (사용자가 실제로 지불하는 가격이다). 이것을 함께 읽는 이유는 <b>전일 대비 등락</b>
     * 하나뿐이다 — 등락은 두 날의 <b>비(比)</b>라 액면분할·무상증자를 지나면 원주가끼리
     * 빼는 순간 −50%가 사실처럼 나온다. {@code adj_close}는 {@code NOT NULL}이라
     * (V1 스키마) null 분기가 필요 없다.
     *
     * @param tradeValue  소스가 주지 않으면 null이다
     * @param listedShares V9에서 추가된 컬럼이라 옛 행에는 없다
     */
    public record PriceRow(LocalDate tradeDate, BigDecimal open, BigDecimal high, BigDecimal low,
                           BigDecimal close, BigDecimal adjClose, Long volume,
                           BigDecimal tradeValue, BigDecimal listedShares) {
    }

    /**
     * @param currencyCode 이 유니버스를 고르면 화면의 금액이 어느 통화로 그려지는가.
     *                     조건 빌더가 임계를 입력받을 때도 이것을 쓴다 — 없으면 미국을 고른
     *                     상태에서 시가총액 칩이 「원」으로 그려진다.
     */
    public record UniverseRow(String code, String name, String marketCode, String currencyCode,
                              int memberCount) {
    }

    /** @param universeCodes 현재 편입되어 있는 유니버스. 화면이 어느 지수 종목인지 보인다. */
    public record SearchRow(long id, String ticker, String name, String marketCode,
                            List<String> universeCodes) {
    }
}
