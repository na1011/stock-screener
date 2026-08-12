package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 기업 행위 원장. <b>수정계수의 정본이다</b>(ADR-0049).
 *
 * <p>{@link AdjustmentRecalculator#rebuild}가 매번 이 원장 전체에서 계수를 다시 짓는다.
 * 그래서 여기 적는 것과 계수에 반영하는 것이 분리되고, 되돌리기가 "행 하나의 상태를 바꾸고
 * 다시 짓기"가 된다.
 *
 * <h2>왜 기록과 적용을 나누는가</h2>
 * <p>시세 수집은 15일 창으로 돌아 같은 날짜를 평일 11~12회 다시 본다(ADR-0042). 사건을 볼
 * 때마다 계수에 곱해 넣으면 계수가 <b>실행 횟수의 함수</b>가 되고, 5:1을 15회 곱하면
 * {@code numeric(18,10)}이 정확히 0이 되는데 아무 에러도 나지 않는다. 기록은 멱등하고
 * (UNIQUE 키), 적용은 누적이 아니라 재구축이므로 둘 다 몇 번을 돌려도 같다.
 */
@Component
public class CorporateActionLedger {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionLedger.class);

    /**
     * 같은 사건으로 볼 거래일 간격.
     *
     * <p>IMPORTANT — <b>달력일이 아니라 그 종목의 거래일</b>이다. 병합·감자는 변경상장 전에
     * 매매거래정지가 끼어(대한전선 2023은 11거래일이 비어 있다) 달력일로 재면 같은 사건이
     * 멀어 보인다. 소스 원값이 사건 종류마다 ±1~2일 어긋나므로(§10-B) 이 창이 없으면 같은
     * 사건이 두 행이 되고, <b>둘 다 승인되면 재구축이 두 계수를 곱해 정확히 두 번 적용한다.</b>
     */
    private static final int NEAR_DUPLICATE_TRADING_DAYS = 5;

    /**
     * 거래일 창과 함께 걸리는 달력 상한.
     *
     * <p>거래일만으로 재면 <b>봉이 하나도 없는 종목에서 모든 날짜 쌍이 0거래일</b>이 되어
     * 서로 몇 년 떨어진 별개 사건까지 같은 사건으로 삼킨다. 상장 전·상장폐지 종목이 그 모양이다.
     * 45일은 거래정지를 낀 일자 어긋남(대한전선 2023은 11거래일이 비어 있다)을 넉넉히 덮으면서
     * 그 퇴화를 막는 자리다.
     */
    private static final int NEAR_DUPLICATE_CALENDAR_DAYS = 45;

    private final JdbcTemplate jdbcTemplate;

    public CorporateActionLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 원장에 적은 결과. 호출자가 무엇이 일어났는지 알아야 알림을 걸 수 있다. */
    public enum Outcome {
        /** 새 행이 생겼다. */
        INSERTED,
        /** 후보였던 행을 공시가 덮었다. */
        UPGRADED,
        /** 이미 있고 덮을 이유가 없다. 같은 사건이 창 안에서 반복 관측된 정상 경로다. */
        IGNORED,
        /**
         * 며칠 어긋난 같은 사건일 가능성이 높아 적지 않았다.
         *
         * <p>MUST NOT: 이것을 조용히 버리지 않는다. 진짜 별개 사건이면 원장에 자리가 없어
         * 영영 반영되지 않고, 같은 사건이면 일자 보정이 어긋났다는 신호다. 둘 다 사람이 봐야 한다.
         */
        NEAR_DUPLICATE
    }

    /**
     * @param existingId {@link Outcome#NEAR_DUPLICATE}면 충돌한 기존 행, 아니면 이 행의 id
     */
    public record RecordResult(Outcome outcome, long existingId, LocalDate conflictingDate) {

        public boolean changed() {
            return outcome == Outcome.INSERTED || outcome == Outcome.UPGRADED;
        }
    }

    /** 원장의 한 행. 재구축이 읽는 형태다. */
    public record Entry(long id, LocalDate effectiveDate, CorporateAction.Type type,
                        BigDecimal ratio) {
    }

    private record ExistingRow(long id, String status) {
    }

    /**
     * 관측한 기업 행위를 원장에 적는다. 계수는 건드리지 않는다.
     *
     * <p>초기 상태는 탐지 경로가 정한다 — 공시는 {@code APPROVED}, 휴리스틱은 {@code OBSERVED}.
     * <b>이것이 "사람이 확인하지 않은 후보는 적용되지 않는다"를 지키는 유일한 지점이다</b>
     * (ADR-0049). 예전에는 적용부의 예외가 그 역할을 했고, 그 예외를 이 매핑이 대신한다.
     *
     * <p>IMPORTANT — <b>{@link CorporateAction.Type#UNKNOWN}은 공시가 알려줬어도
     * {@code APPROVED}로 올리지 않는다.</b> 공시가 보증하는 것은 <i>"그날 사건이 있었다"</i>이지
     * <i>"비율이 이것이다"</i>가 아니다. {@code APPROVED}로 넣으면 {@link #findApplicable}이
     * 종류로 걸러 적용되지 않으면서 <b>사람이 볼 후보 목록({@code OBSERVED})에서도 함께
     * 사라지고</b>, {@link #updateStatus}는 {@code ratio}를 못 고치므로 그 행은 영영 살아나지
     * 못한다. 어디에도 없는데 아무 에러도 나지 않는다.
     */
    @Transactional
    public RecordResult record(long stockId, CorporateAction action) {
        Optional<Long> near = findNearDuplicate(stockId, action.effectiveDate());
        if (near.isPresent()) {
            LocalDate existing = jdbcTemplate.queryForObject(
                    "SELECT effective_date FROM corporate_action WHERE id = ?",
                    LocalDate.class, near.get());
            log.warn("기업 행위가 기존 행과 {}거래일 안에 있어 적지 않았다. stockId={} 새 일자={} 기존 일자={} id={}"
                            + " — 같은 사건의 일자 보정이 어긋났거나 진짜 별개 사건이다. 사람이 갈라야 한다.",
                    NEAR_DUPLICATE_TRADING_DAYS, stockId, action.effectiveDate(), existing, near.get());
            return new RecordResult(Outcome.NEAR_DUPLICATE, near.get(), existing);
        }

        boolean disclosed = action.source() == CorporateAction.DetectionSource.SOURCE_DISCLOSURE;
        boolean typeKnown = action.type() != CorporateAction.Type.UNKNOWN;
        boolean trusted = disclosed && typeKnown;
        String status = trusted ? "APPROVED" : "OBSERVED";

        // 같은 일자의 기존 행을 먼저 본다. INSERT와 UPDATE를 사후에 가를 수단이 없기
        // 때문이다 — created_at = updated_at은 **같은 트랜잭션 안에서 둘 다 now()라** 갱신을
        // 신규로 읽고, 테스트는 전부 한 트랜잭션이다.
        Optional<ExistingRow> existing = findByEffectiveDate(stockId, action.effectiveDate());

        if (existing.isPresent()) {
            ExistingRow row = existing.get();
            // MUST NOT: 사람이 판정한 행을 덮지 않는다. 덮으면 승인·거부가 다음 수집에
            // 되돌려지고, 같은 후보가 매번 다시 올라온다.
            //
            // MUST NOT: UNKNOWN으로 덮지 않는다(trusted). 아래 UPDATE가 type과 ratio를 함께
            // 대입하므로, 종류를 모르는 공시가 휴리스틱이 관측한 종류·배수를 **지운다** —
            // 사람이 뒤에 볼 것이 종류 미상 행 하나뿐이 된다. 공시가 종류를 확정했을 때만
            // 덮을 값이 더 낫다.
            if (!"OBSERVED".equals(row.status()) || !trusted) {
                return new RecordResult(Outcome.IGNORED, row.id(), action.effectiveDate());
            }
            // 휴리스틱 후보를 공시가 덮는다. 창이 매일 도므로 휴리스틱이 대개 먼저 들어오고,
            // 그대로 두면 공시의 정확한 비율·일자가 흔적 없이 버려진다.
            jdbcTemplate.update("""
                    UPDATE corporate_action SET
                        listing_date = ?, source_date = ?, type = ?, ratio = ?,
                        detection_source = ?, status = ?, updated_at = now()
                    WHERE id = ?
                    """,
                    action.listingDate(), action.sourceDate(), action.type().name(),
                    action.ratio(), action.source().name(), status, row.id());
            log.info("휴리스틱 후보를 공시로 덮었다. stockId={} id={} {} type={} ratio={}",
                    stockId, row.id(), action.effectiveDate(), action.type(), action.ratio());
            return new RecordResult(Outcome.UPGRADED, row.id(), action.effectiveDate());
        }

        long id = jdbcTemplate.queryForObject("""
                INSERT INTO corporate_action
                    (stock_id, effective_date, listing_date, source_date,
                     type, ratio, detection_source, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                stockId, action.effectiveDate(), action.listingDate(), action.sourceDate(),
                action.type().name(), action.ratio(), action.source().name(), status);

        log.info("기업 행위를 원장에 적었다. stockId={} id={} {} type={} ratio={} status={} source={}",
                stockId, id, action.effectiveDate(), action.type(), action.ratio(),
                status, action.source());
        return new RecordResult(Outcome.INSERTED, id, action.effectiveDate());
    }

    /**
     * 계수에 반영할 사건. {@code effective_date} 오름차순이다.
     *
     * <p>MUST: {@code UNKNOWN}을 제외한다. 그 종류의 {@code ratio}는 관측된 가격 배수일 뿐
     * 계수가 아니다 — 인적분할·거래재개가 거기 들어오고, 그것을 적용하면 <b>틀린 계수가
     * 아무 에러 없이</b> 들어간다. 사람이 종류를 확정하거나 {@code UNSUPPORTED}로 보내야 한다.
     */
    @Transactional(readOnly = true)
    public List<Entry> findApplicable(long stockId) {
        return jdbcTemplate.query("""
                SELECT id, effective_date, type, ratio FROM corporate_action
                WHERE stock_id = ? AND status IN ('APPROVED', 'APPLIED') AND type <> 'UNKNOWN'
                ORDER BY effective_date
                """,
                (rs, rowNum) -> new Entry(
                        rs.getLong("id"),
                        rs.getObject("effective_date", LocalDate.class),
                        CorporateAction.Type.valueOf(rs.getString("type")),
                        rs.getBigDecimal("ratio")),
                stockId);
    }

    /** 계수에 반영됐음을 표시한다. 호출자가 재구축과 <b>같은 트랜잭션</b>에서 부른다. */
    public void markApplied(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                UPDATE corporate_action
                SET status = 'APPLIED', applied_at = coalesce(applied_at, now()), updated_at = now()
                WHERE id = ? AND status <> 'APPLIED'
                """, ids.stream().map(id -> new Object[]{id}).toList());
    }

    /**
     * 사람의 판정을 적는다.
     *
     * <p>{@code applied_at}은 지우지 않는다 — "한때 적용됐었다"가 사라지면 왜 그 종목의 계수가
     * 한 번 바뀌었다가 돌아왔는지 답할 수 없게 된다.
     */
    @Transactional
    public void updateStatus(long id, String status, String note) {
        int updated = jdbcTemplate.update("""
                UPDATE corporate_action SET status = ?, note = ?, updated_at = now() WHERE id = ?
                """, status, note, id);
        if (updated == 0) {
            throw new IllegalArgumentException("원장에 그 행이 없다. id=" + id);
        }
    }

    /**
     * {@code effectiveDate}에서 {@link #NEAR_DUPLICATE_TRADING_DAYS} 거래일 안에 있는 다른 행.
     *
     * <p>그 종목의 {@code price_daily}에 실제로 있는 봉을 세므로 거래정지 구간을 자연히 넘는다.
     * 공휴일 달력을 두지 않겠다는 결정이 여기서도 성립한다({@link PriceCalendar}와 같은 이유).
     */
    private Optional<ExistingRow> findByEffectiveDate(long stockId, LocalDate effectiveDate) {
        return jdbcTemplate.query(
                "SELECT id, status FROM corporate_action WHERE stock_id = ? AND effective_date = ?",
                (rs, rowNum) -> new ExistingRow(rs.getLong("id"), rs.getString("status")),
                stockId, effectiveDate).stream().findFirst();
    }

    private Optional<Long> findNearDuplicate(long stockId, LocalDate effectiveDate) {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT ca.id FROM corporate_action ca
                WHERE ca.stock_id = ? AND ca.effective_date <> ?
                  AND abs(ca.effective_date - ?) <= ?
                  AND (SELECT count(*) FROM price_daily p
                       WHERE p.stock_id = ca.stock_id
                         AND p.trade_date >  least(ca.effective_date, ?)
                         AND p.trade_date <= greatest(ca.effective_date, ?)) <= ?
                ORDER BY ca.effective_date
                LIMIT 1
                """, Long.class,
                stockId, effectiveDate, effectiveDate, NEAR_DUPLICATE_CALENDAR_DAYS,
                effectiveDate, effectiveDate, NEAR_DUPLICATE_TRADING_DAYS);
        return ids.stream().findFirst();
    }
}
