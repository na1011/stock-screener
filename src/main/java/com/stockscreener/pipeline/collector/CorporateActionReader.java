package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.PriceDiscontinuity;
import com.stockscreener.pipeline.datasource.ShareRatioSnap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 사람이 원장을 판정하는 데 필요한 <b>증거</b>를 읽는다(ADR-0050).
 *
 * <h2>계산은 하되 고르지는 않는다</h2>
 * <p>효력일 이후의 상장주식수 점프를 <b>전부</b> 나열하고 각각이 관측 가격 배수와 맞는지만
 * 표시한다. 하나를 골라 제안하지 않는 이유는 실측에 있다 — 루닛 2026-05-04는 창 안에 점프가
 * <b>셋</b>이고(05-15 ×1.27 전환권행사 · 05-26 ×2.0 진짜 신주상장 · 06-19 ×1.001 잡음),
 * 제안값 하나를 내면 그 모호함이 화면에서 사라진다. 고르는 규칙이 틀린 날 사람은 그것을 알
 * 방법이 없다.
 *
 * <p>MUST: 일치 판정에 {@link PriceDiscontinuity#explainsPriceGap}(1.35/0.65)을 쓴다.
 * {@link PriceDiscontinuity#supportsShareRatio}(2.0/0.5)는 <b>여럿을 가르지 못한다</b> —
 * 위 루닛 세 점프가 전부 통과한다. 그 폭은 후보가 하나일 때 "가격과 아무 관계도 없는가"를
 * 묻는 자리이고, 좁히면 아시아나항공 2021-01-15(1.4252)이 잘린다.
 *
 * <p>쓰는 쪽({@link CorporateActionLedger})과 나눈 이유는 {@code BatchExecutionReader}와 같다 —
 * 기록과 조회는 트랜잭션 성질이 다르고, 한 클래스에 두면 그 차이가 애너테이션에만 남는다.
 */
@Service
public class CorporateActionReader {

    /** 목록 조회의 기본·최대 건수. 화면이 훑는 단위이지 페이징이 아니다. */
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    /**
     * 효력일 이후 신주상장을 찾는 창.
     *
     * <p>무상증자는 권리락과 신주상장이 14~16거래일 벌어진다(ADR-0049). 넓히면 무관한
     * 유상증자·전환권행사가 후보에 섞이는데 <b>그것은 의도다</b> — 고르는 것은 사람이고,
     * 좁혀서 진짜 신주상장이 창 밖으로 나가는 쪽이 훨씬 나쁘다.
     */
    private static final int LISTING_WINDOW_DAYS = 75;

    private static final MathContext MC = new MathContext(34);

    /**
     * 원장 행에 <b>효력일 당일의 관측값</b>을 붙인 형태.
     *
     * <p>{@code LEFT JOIN LATERAL}인 이유는 봉이 없을 수 있기 때문이다 — 상장 이전 사건이거나
     * 우리가 그날을 수집하지 않았다. 그때 관측값은 null이고 <b>0으로 채우지 않는다.</b> 0은
     * 판정 화면에서 "그날 아무 일도 없었다"로 읽힌다.
     */
    private static final String SELECT_WITH_OBSERVATION = """
            SELECT ca.id, ca.stock_id, s.ticker, s.name AS stock_name,
                   ca.effective_date, ca.listing_date, ca.source_date,
                   ca.type, ca.ratio, ca.detection_source, ca.status, ca.source_form,
                   ca.note, ca.applied_at, ca.created_at, ca.updated_at,
                   o.prev_close, o.close, o.prev_shares, o.listed_shares
            FROM corporate_action ca
            JOIN stock s ON s.id = ca.stock_id
            LEFT JOIN LATERAL (
                SELECT t.prev_close, t.close, t.prev_shares, t.listed_shares
                FROM (
                    SELECT trade_date, close, listed_shares,
                           lag(close) OVER w AS prev_close,
                           lag(listed_shares) OVER w AS prev_shares
                    FROM price_daily WHERE stock_id = ca.stock_id
                    WINDOW w AS (ORDER BY trade_date)
                ) t
                WHERE t.trade_date = ca.effective_date
            ) o ON true
            WHERE 1 = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public CorporateActionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 원장 한 행과 그날의 관측값.
     *
     * @param observedRatio     효력일의 {@code prev_close / close}. {@code ratio}와 같은 방향이다.
     * @param sameDayShareRatio 효력일 당일 상장주식수 배수. <b>1이면 무상증자류 권리락의 표식이다</b>
     *                          (착수 시점 실측 64건 중 39건). 봉이 없으면 null.
     * @param sameDaySnapped    위 배수를 단순 유리수로 스냅한 값. 분할·병합이면 여기서 비율이 나온다.
     * @param sameDayMatchesPrice 스냅된 배수가 가격 움직임과 어긋나지 않는가.
     */
    public record Row(long id, long stockId, String ticker, String stockName,
                      LocalDate effectiveDate, LocalDate listingDate, LocalDate sourceDate,
                      CorporateAction.Type type, BigDecimal ratio,
                      CorporateAction.DetectionSource detectionSource,
                      CorporateActionStatus status, String sourceForm, String note,
                      Instant appliedAt, Instant createdAt, Instant updatedAt,
                      BigDecimal prevClose, BigDecimal close, BigDecimal observedRatio,
                      BigDecimal sameDayShareRatio, BigDecimal sameDaySnapped,
                      Boolean sameDayMatchesPrice) {
    }

    /**
     * 효력일 이후 상장주식수가 바뀐 날.
     *
     * @param relative     관측 가격 배수 × 이 비율. 권리락이 이 비율로 설명되면 1 부근이다.
     * @param matchesPrice {@code relative}가 권리락 기준가의 가격제한폭 안인가.
     *                     <b>참인 것이 둘 이상이면 사람이 봐야 한다</b> — 실측 37건 중 2건이 그렇다.
     */
    public record ShareJump(LocalDate date, BigDecimal sharesBefore, BigDecimal sharesAfter,
                            BigDecimal ratio, BigDecimal relative, boolean matchesPrice) {
    }

    /** 판정에 필요한 것 전부. */
    public record Detail(Row row, List<ShareJump> shareJumps) {
    }

    /** 상태별 집계. {@code unknownType}이 <b>사람이 값을 지어내야 하는</b> 행 수다. */
    public record StatusCount(CorporateActionStatus status, long total, long unknownType) {
    }

    /**
     * 원장을 훑는다. 필터는 전부 선택이다.
     *
     * <p>MUST: {@code type} 필터가 있어야 한다. {@code OBSERVED + type≠UNKNOWN}은 예·아니오만
     * 답하면 되고 {@code OBSERVED + UNKNOWN}은 종류와 비율을 지어내야 한다 — 사람이 할 일이
     * 전혀 다른데 섞어 두면 후자가 앞의 것들 사이에 묻힌다.
     */
    @Transactional(readOnly = true)
    public List<Row> list(CorporateActionStatus status, CorporateAction.Type type, String ticker,
                          LocalDate from, LocalDate to, Integer limit) {

        StringBuilder sql = new StringBuilder(SELECT_WITH_OBSERVATION);
        List<Object> args = new ArrayList<>();

        if (status != null) {
            sql.append(" AND ca.status = ?");
            args.add(status.name());
        }
        if (type != null) {
            sql.append(" AND ca.type = ?");
            args.add(type.name());
        }
        if (ticker != null && !ticker.isBlank()) {
            sql.append(" AND s.ticker = ?");
            args.add(ticker.trim());
        }
        if (from != null) {
            sql.append(" AND ca.effective_date >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND ca.effective_date <= ?");
            args.add(to);
        }

        sql.append(" ORDER BY ca.effective_date DESC, ca.id DESC LIMIT ?");
        args.add(boundedLimit(limit));

        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /**
     * 상태별 행 수와 그중 종류를 모르는 수.
     *
     * <p>ADR-0049가 <b>"이 수가 이 phase의 성공을 가른다"</b>고 적은 값이다 — 원장 행 수가 아니라
     * 공시가 붙어 적용 가능해진 비율이 성패다.
     */
    @Transactional(readOnly = true)
    public List<StatusCount> summary() {
        return jdbcTemplate.query("""
                SELECT status, count(*) AS total,
                       count(*) FILTER (WHERE type = ?) AS unknown_type
                FROM corporate_action GROUP BY status ORDER BY status
                """,
                (rs, rowNum) -> new StatusCount(
                        CorporateActionStatus.valueOf(rs.getString("status")),
                        rs.getLong("total"),
                        rs.getLong("unknown_type")),
                CorporateAction.Type.UNKNOWN.name());
    }

    @Transactional(readOnly = true)
    public Optional<Detail> findById(long id) {
        return jdbcTemplate.query(SELECT_WITH_OBSERVATION + " AND ca.id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst()
                .map(row -> new Detail(row, shareJumpsOf(row)));
    }

    /**
     * 효력일 이후 창 안에서 상장주식수가 바뀐 날 <b>전부</b>.
     *
     * <p>MUST NOT: 여기서 하나를 고르지 않는다. 클래스 javadoc의 루닛 실측이 그 근거다.
     */
    private List<ShareJump> shareJumpsOf(Row row) {
        return jdbcTemplate.query("""
                SELECT trade_date, prev_shares, listed_shares FROM (
                    SELECT trade_date, listed_shares,
                           lag(listed_shares) OVER w AS prev_shares
                    FROM price_daily WHERE stock_id = ?
                    WINDOW w AS (ORDER BY trade_date)
                ) t
                WHERE trade_date > ? AND trade_date <= ?
                  AND prev_shares IS NOT NULL AND prev_shares > 0
                  AND listed_shares IS DISTINCT FROM prev_shares
                ORDER BY trade_date
                """,
                (rs, rowNum) -> {
                    BigDecimal before = rs.getBigDecimal("prev_shares");
                    BigDecimal after = rs.getBigDecimal("listed_shares");
                    BigDecimal ratio = after.divide(before, PriceDiscontinuity.RATIO_SCALE,
                            RoundingMode.HALF_UP);
                    BigDecimal relative = relativeTo(row, ratio);
                    return new ShareJump(rs.getObject("trade_date", LocalDate.class),
                            before, after, ratio, relative,
                            relative != null && PriceDiscontinuity.explainsPriceGap(
                                    priceMultiple(row), ratio));
                },
                row.stockId(), row.effectiveDate(),
                row.effectiveDate().plusDays(LISTING_WINDOW_DAYS));
    }

    /** 효력일의 {@code close / prev_close}. 봉이 없으면 null이다. */
    private static BigDecimal priceMultiple(Row row) {
        if (row.close() == null || row.prevClose() == null || row.prevClose().signum() <= 0) {
            return null;
        }
        return row.close().divide(row.prevClose(), MC);
    }

    private static BigDecimal relativeTo(Row row, BigDecimal ratio) {
        BigDecimal multiple = priceMultiple(row);
        return multiple == null ? null : multiple.multiply(ratio, MC).setScale(4, RoundingMode.HALF_UP);
    }

    private static int boundedLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static final RowMapper<Row> ROW_MAPPER = (rs, rowNum) -> {
        BigDecimal prevClose = rs.getBigDecimal("prev_close");
        BigDecimal close = rs.getBigDecimal("close");
        BigDecimal prevShares = rs.getBigDecimal("prev_shares");
        BigDecimal shares = rs.getBigDecimal("listed_shares");

        BigDecimal observedRatio = close != null && prevClose != null && close.signum() > 0
                ? PriceDiscontinuity.observedRatio(close, prevClose)
                : null;
        BigDecimal shareRatio = prevShares != null && shares != null && prevShares.signum() > 0
                ? shares.divide(prevShares, PriceDiscontinuity.RATIO_SCALE,
                        RoundingMode.HALF_UP)
                : null;
        BigDecimal snapped = shareRatio == null
                ? null
                : ShareRatioSnap.snap(shareRatio).orElse(null);
        Boolean matches = snapped == null || close == null || prevClose == null
                ? null
                : PriceDiscontinuity.supportsShareRatio(snapped, close, prevClose);

        return new Row(
                rs.getLong("id"),
                rs.getLong("stock_id"),
                rs.getString("ticker"),
                rs.getString("stock_name"),
                rs.getObject("effective_date", LocalDate.class),
                rs.getObject("listing_date", LocalDate.class),
                rs.getObject("source_date", LocalDate.class),
                CorporateAction.Type.valueOf(rs.getString("type")),
                rs.getBigDecimal("ratio"),
                CorporateAction.DetectionSource.valueOf(rs.getString("detection_source")),
                CorporateActionStatus.valueOf(rs.getString("status")),
                rs.getString("source_form"),
                rs.getString("note"),
                instant(rs.getTimestamp("applied_at")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                prevClose, close, observedRatio, shareRatio, snapped, matches);
    };

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
