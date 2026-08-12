package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.PriceDiscontinuity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** {@code ratio numeric(18,10)}의 정수부 상한. 넘으면 DB가 알아보기 힘든 오류를 낸다. */
    private static final BigDecimal RATIO_LIMIT = new BigDecimal("100000000");

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

    /**
     * 사람이 고치겠다는 것. <b>비어 있는 항목은 그대로 둔다.</b>
     *
     * @param status 비우면 판정을 미룬다. 값만 고치는 것이 정당한 경로다.
     */
    public record Judgement(CorporateActionStatus status, CorporateAction.Type type,
                            BigDecimal ratio, LocalDate effectiveDate, LocalDate listingDate,
                            String note) {

        boolean isEmpty() {
            return status == null && type == null && ratio == null
                    && effectiveDate == null && listingDate == null && note == null;
        }
    }

    /** 판정이 고칠 수 있는 것과 참조하는 것. */
    private record Row(long id, long stockId, LocalDate effectiveDate, LocalDate sourceDate,
                       CorporateAction.Type type, BigDecimal ratio) {
    }

    private record ExistingRow(long id, LocalDate effectiveDate, String status,
                               String detectionSource) {

        /** 아직 사람이 보지 않은 휴리스틱 후보인가. 공시가 덮어도 되는 유일한 상태다. */
        boolean isUnreviewedCandidate() {
            return CorporateActionStatus.OBSERVED.name().equals(status)
                    && CorporateAction.DetectionSource.HEURISTIC.name().equals(detectionSource);
        }
    }

    /** 원장에 그 행이 없다. 컨트롤러가 404로 옮긴다. */
    public static class CorporateActionNotFoundException extends RuntimeException {
        private final long id;

        public CorporateActionNotFoundException(long id) {
            super("원장에 그 행이 없다: " + id);
            this.id = id;
        }

        public long id() {
            return id;
        }
    }

    /** 판정이 원장의 불변식을 깬다. 컨트롤러가 400으로 옮긴다. */
    public static class InvalidJudgementException extends RuntimeException {
        private final String field;

        public InvalidJudgementException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
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
     * 사라진다</b> — 어디에도 없는데 아무 에러도 나지 않는다. {@link #judge}가 그 조합을 거절하는
     * 것도 같은 이유다.
     *
     * <p>IMPORTANT — <b>근접중복 판정보다 신뢰도 판정이 먼저다.</b> 반대로 두면 원장이
     * <b>선착순</b>이 된다. 소스가 최대 넉 달 늦으므로(api-spec §10-B) 그 사각지대의 사건은
     * <b>구조적으로 휴리스틱이 먼저</b> 들어오고, 나중에 온 공시가 며칠 어긋났다는 이유로
     * 버려진다 — 남는 것은 {@code UNKNOWN} 후보 하나이고 {@link #findApplicable}이 그것을
     * 제외한다. {@link #judge}가 생긴 지금은 사람이 되살릴 수 있지만, <b>그러려면 사람이 그
     * 사건의 종류와 비율을 직접 알아내야 한다</b> — 공시가 이미 답한 것을 버리고서.
     */
    @Transactional
    public RecordResult record(long stockId, CorporateAction action) {
        boolean disclosed = action.source() == CorporateAction.DetectionSource.SOURCE_DISCLOSURE;
        boolean typeKnown = action.type() != CorporateAction.Type.UNKNOWN;
        boolean trusted = disclosed && typeKnown;
        CorporateActionStatus status =
                trusted ? CorporateActionStatus.APPROVED : CorporateActionStatus.OBSERVED;

        Optional<ExistingRow> near = findNearDuplicate(stockId, action.effectiveDate());
        if (near.isPresent()) {
            Optional<RecordResult> moved = relocateCandidate(stockId, action, near.get(), trusted);
            if (moved.isPresent()) {
                return moved.get();
            }
            ExistingRow row = near.get();
            log.warn("기업 행위가 기존 행과 {}거래일 안에 있어 적지 않았다. stockId={} 새 일자={} 기존 일자={} id={}"
                            + " — 같은 사건의 일자 보정이 어긋났거나 진짜 별개 사건이다. 사람이 갈라야 한다.",
                    NEAR_DUPLICATE_TRADING_DAYS, stockId, action.effectiveDate(),
                    row.effectiveDate(), row.id());
            return new RecordResult(Outcome.NEAR_DUPLICATE, row.id(), row.effectiveDate());
        }

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
            if (!CorporateActionStatus.OBSERVED.name().equals(row.status()) || !trusted) {
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
                    action.ratio(), action.source().name(), status.name(), row.id());
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
                action.type().name(), action.ratio(), action.source().name(), status.name());

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
                WHERE stock_id = ? AND status IN (?, ?) AND type <> ?
                ORDER BY effective_date
                """,
                (rs, rowNum) -> new Entry(
                        rs.getLong("id"),
                        rs.getObject("effective_date", LocalDate.class),
                        CorporateAction.Type.valueOf(rs.getString("type")),
                        rs.getBigDecimal("ratio")),
                stockId, CorporateActionStatus.APPROVED.name(),
                CorporateActionStatus.APPLIED.name(), CorporateAction.Type.UNKNOWN.name());
    }

    /** 계수에 반영됐음을 표시한다. 호출자가 재구축과 <b>같은 트랜잭션</b>에서 부른다. */
    public void markApplied(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String applied = CorporateActionStatus.APPLIED.name();
        jdbcTemplate.batchUpdate("""
                UPDATE corporate_action
                SET status = ?, applied_at = coalesce(applied_at, now()), updated_at = now()
                WHERE id = ? AND status <> ?
                """, ids.stream().map(id -> new Object[]{applied, id, applied}).toList());
    }

    /**
     * 사람의 판정을 적는다. <b>상태만이 아니라 값까지 고친다</b>(ADR-0050).
     *
     * <p>비어 있는 항목은 그대로 둔다. {@code status}까지 비울 수 있는 것이 의도다 — 종류를 모르는
     * 행에 {@code listing_date}만 채우고 판정은 미루는 것이 실제로 필요하다.
     *
     * <p>{@code applied_at}은 지우지 않는다 — "한때 적용됐었다"가 사라지면 왜 그 종목의 계수가
     * 한 번 바뀌었다가 돌아왔는지 답할 수 없게 된다.
     *
     * <p>MUST NOT: 여기서 부호 규칙을 다시 쓰지 않는다. 검증은 {@link CorporateAction}의 컴팩트
     * 생성자를 <b>실제로 통과시켜</b> 한다 — {@code Type.allows}만 부르면 <b>부호만 보고 양수를
     * 안 본다.</b> {@code ratio = -5}는 그것을 통과해 DB 제약에서 터지고, catch-all이 없으므로
     * 500이 된다.
     */
    @Transactional
    public void judge(long id, Judgement judgement) {
        Row row = findById(id).orElseThrow(() -> new CorporateActionNotFoundException(id));
        if (judgement.isEmpty()) {
            throw new InvalidJudgementException("judgement", "고칠 것이 하나도 없다.");
        }
        if (judgement.status() != null && !judgement.status().humanAssignable()) {
            throw new InvalidJudgementException("status",
                    "사람이 찍을 수 있는 상태가 아니다: " + judgement.status()
                            + " — APPLIED는 재구축만이, OBSERVED는 수집만이 만든다.");
        }

        CorporateAction.Type type = merged(judgement, row);
        BigDecimal ratio = mergedRatio(judgement, row);
        LocalDate effectiveDate =
                judgement.effectiveDate() != null ? judgement.effectiveDate() : row.effectiveDate();

        if (judgement.status() == CorporateActionStatus.APPROVED
                && type == CorporateAction.Type.UNKNOWN) {
            // findApplicable이 UNKNOWN을 거르므로 이 조합은 적용도 안 되면서 OBSERVED 대기열에서도
            // 사라진다. 어디에도 없는데 아무 에러도 나지 않는다.
            throw new InvalidJudgementException("type",
                    "종류를 모르는 행은 승인할 수 없다. 종류와 비율을 함께 정하거나"
                            + " UNSUPPORTED로 보내라.");
        }
        // 컴팩트 생성자가 부호·양수를 함께 판정한다. V21의 ck_ca_ratio_sign과 같은 판정이다.
        new CorporateAction(effectiveDate, judgement.listingDate(), row.sourceDate(), type, ratio,
                CorporateAction.DetectionSource.HEURISTIC);

        if (!effectiveDate.equals(row.effectiveDate())) {
            requireFreeSlot(row, effectiveDate);
        }

        jdbcTemplate.update("""
                UPDATE corporate_action SET
                    status         = coalesce(?, status),
                    type           = ?,
                    ratio          = ?,
                    effective_date = ?,
                    listing_date   = coalesce(?, listing_date),
                    note           = coalesce(?, note),
                    updated_at     = now()
                WHERE id = ?
                """,
                judgement.status() != null ? judgement.status().name() : null,
                type.name(), ratio, effectiveDate, judgement.listingDate(), judgement.note(), id);

        log.info("사람이 원장을 판정했다. id={} status={} type={} ratio={} 효력일={} 신주상장={}",
                id, judgement.status(), type, ratio, effectiveDate, judgement.listingDate());
    }

    /**
     * MUST: {@code type}과 {@code ratio}는 같이 오거나 같이 비운다. 하나만 고치면 <b>관측된 가격
     * 배수에 종류 딱지가 붙거나</b> 그 반대가 되고, 그 행은 승인되는 순간 틀린 계수가 된다.
     */
    private static CorporateAction.Type merged(Judgement judgement, Row row) {
        requirePaired(judgement);
        return judgement.type() != null ? judgement.type() : row.type();
    }

    private static BigDecimal mergedRatio(Judgement judgement, Row row) {
        requirePaired(judgement);
        if (judgement.ratio() == null) {
            return row.ratio();
        }
        if (judgement.ratio().compareTo(RATIO_LIMIT) >= 0) {
            // numeric(18,10)의 정수부 상한. 넘으면 DB가 알아보기 힘든 오류를 낸다.
            throw new InvalidJudgementException("ratio",
                    "비율이 numeric(18,10)의 상한을 넘는다: " + judgement.ratio());
        }
        // 저장 스케일로 먼저 접는다. 접기 전에 검증하면 저장된 값이 검증한 값과 달라진다.
        return judgement.ratio().setScale(PriceDiscontinuity.RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static void requirePaired(Judgement judgement) {
        if ((judgement.type() == null) != (judgement.ratio() == null)) {
            throw new InvalidJudgementException("ratio",
                    "종류와 비율은 함께 고쳐야 한다. 하나만 바꾸면 관측된 가격 배수에 종류 딱지가"
                            + " 붙거나 그 반대가 된다.");
        }
    }

    /**
     * 효력일을 옮길 자리가 비었는가.
     *
     * <p>{@link #relocateCandidate}가 쓰는 두 판정을 그대로 재사용한다 — {@code UNIQUE}는 같은
     * 날짜만 막고, 며칠 어긋난 두 행이 <b>둘 다 승인되면 재구축이 두 계수를 곱해 정확히 두 번
     * 적용한다.</b> 손으로 옮기는 길에도 같은 가드가 있어야 한다.
     */
    private void requireFreeSlot(Row row, LocalDate effectiveDate) {
        if (findByEffectiveDate(row.stockId(), effectiveDate).isPresent()) {
            throw new InvalidJudgementException("effectiveDate",
                    "그 날짜에 이미 다른 행이 있다: " + effectiveDate);
        }
        Optional<ExistingRow> near = findNearDuplicate(row.stockId(), effectiveDate);
        if (near.isPresent() && near.get().id() != row.id()) {
            throw new InvalidJudgementException("effectiveDate",
                    "그 날짜가 기존 행(" + near.get().effectiveDate() + ")과 "
                            + NEAR_DUPLICATE_TRADING_DAYS + "거래일 안이다 — 같은 사건이 두 행이 되면"
                            + " 재구축이 두 계수를 곱한다.");
        }
    }

    /**
     * 며칠 어긋난 휴리스틱 후보를 공시가 <b>제자리로 옮기며</b> 덮는다. 덮지 못하면 비어 있음.
     *
     * <p>같은 일자의 {@code UPGRADED} 경로와 뜻이 같고 <b>{@code effective_date}까지 옮기는</b>
     * 것만 다르다. 소스 원값이 사건 종류마다 반대쪽으로 ±1~2일 어긋나므로(api-spec §10-B)
     * 두 경로가 같은 사건에 서로 다른 날짜를 낸다 — 날짜가 정확히 일치할 때만 덮으면
     * <b>공시의 정확한 비율·일자가 흔치 않은 경로에서만 조용히 버려진다.</b>
     *
     * <p>MUST: 옮길 자리가 비었을 때만 옮긴다. {@code UNIQUE (stock_id, effective_date)}가
     * 있어 차 있으면 제약 위반으로 그 종목이 통째로 롤백된다. <b>이 가드는 {@link #record}
     * 경로만으로는 발화하지 않는다</b> — 근접중복 규칙 자체가 근접한 두 행의 공존을 막기
     * 때문이다. 남겨 두는 이유는 <b>원장이 사람의 승인 경로이기도 해서</b>(ADR-0049) 손으로
     * 넣은 행이 실재할 수 있고, 그때 터지는 자리가 종목 하나의 롤백이기 때문이다.
     *
     * <p>MUST NOT: 사람이 판정한 행이나 공시가 이미 채운 행을 옮기지 않는다
     * ({@link ExistingRow#isUnreviewedCandidate()}). 판정을 되돌리면 같은 후보가 매번 다시 올라온다.
     */
    private Optional<RecordResult> relocateCandidate(long stockId, CorporateAction action,
                                                     ExistingRow near, boolean trusted) {
        if (!trusted || !near.isUnreviewedCandidate()
                || findByEffectiveDate(stockId, action.effectiveDate()).isPresent()) {
            return Optional.empty();
        }
        jdbcTemplate.update("""
                UPDATE corporate_action SET
                    effective_date = ?, listing_date = ?, source_date = ?, type = ?, ratio = ?,
                    detection_source = ?, status = ?, updated_at = now()
                WHERE id = ?
                """,
                action.effectiveDate(), action.listingDate(), action.sourceDate(),
                action.type().name(), action.ratio(), action.source().name(),
                CorporateActionStatus.APPROVED.name(), near.id());
        log.info("휴리스틱 후보를 공시로 덮으며 효력일을 옮겼다. stockId={} id={} {} → {} type={} ratio={}",
                stockId, near.id(), near.effectiveDate(), action.effectiveDate(),
                action.type(), action.ratio());
        return Optional.of(new RecordResult(Outcome.UPGRADED, near.id(), action.effectiveDate()));
    }

    private Optional<Row> findById(long id) {
        return jdbcTemplate.query("""
                SELECT id, stock_id, effective_date, source_date, type, ratio
                FROM corporate_action WHERE id = ?
                """,
                (rs, rowNum) -> new Row(
                        rs.getLong("id"),
                        rs.getLong("stock_id"),
                        rs.getObject("effective_date", LocalDate.class),
                        rs.getObject("source_date", LocalDate.class),
                        CorporateAction.Type.valueOf(rs.getString("type")),
                        rs.getBigDecimal("ratio")),
                id).stream().findFirst();
    }

    private Optional<ExistingRow> findByEffectiveDate(long stockId, LocalDate effectiveDate) {
        return jdbcTemplate.query("""
                SELECT id, effective_date, status, detection_source FROM corporate_action
                WHERE stock_id = ? AND effective_date = ?
                """, ROW_MAPPER, stockId, effectiveDate).stream().findFirst();
    }

    /**
     * {@code effectiveDate}에서 {@link #NEAR_DUPLICATE_TRADING_DAYS} 거래일 안에 있는 다른 행.
     *
     * <p>그 종목의 {@code price_daily}에 실제로 있는 봉을 세므로 거래정지 구간을 자연히 넘는다.
     * 공휴일 달력을 두지 않겠다는 결정이 여기서도 성립한다({@link PriceCalendar}와 같은 이유).
     */
    private Optional<ExistingRow> findNearDuplicate(long stockId, LocalDate effectiveDate) {
        return jdbcTemplate.query("""
                SELECT ca.id, ca.effective_date, ca.status, ca.detection_source
                FROM corporate_action ca
                WHERE ca.stock_id = ? AND ca.effective_date <> ?
                  AND abs(ca.effective_date - ?) <= ?
                  AND (SELECT count(*) FROM price_daily p
                       WHERE p.stock_id = ca.stock_id
                         AND p.trade_date >  least(ca.effective_date, ?)
                         AND p.trade_date <= greatest(ca.effective_date, ?)) <= ?
                ORDER BY ca.effective_date
                LIMIT 1
                """, ROW_MAPPER,
                stockId, effectiveDate, effectiveDate, NEAR_DUPLICATE_CALENDAR_DAYS,
                effectiveDate, effectiveDate, NEAR_DUPLICATE_TRADING_DAYS).stream().findFirst();
    }

    private static final RowMapper<ExistingRow> ROW_MAPPER = (rs, rowNum) -> new ExistingRow(
            rs.getLong("id"),
            rs.getObject("effective_date", LocalDate.class),
            rs.getString("status"),
            rs.getString("detection_source"));
}
