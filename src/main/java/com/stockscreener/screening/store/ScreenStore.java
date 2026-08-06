package com.stockscreener.screening.store;

import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code screen} · {@code condition_group} · {@code screen_condition}을 읽고 쓴다.
 *
 * <p>JPA 엔티티를 두지 않는 이유가 셋이다.
 * <ol>
 *   <li>도메인 타입({@link ConditionGroup} 트리)이 이미 있다. 엔티티를 만들면 한 개념에
 *       객체 모델이 둘이 되고, 그 사이의 변환이 새 버그 표면이 된다.
 *   <li>동적 쿼리가 없다. QueryDSL이 필요한 이유는 조건 번역 하나뿐이고 그것은
 *       {@code indicator_value}를 향한다. 여기는 고정 형태 문장 몇 개다.
 *   <li>쓰기가 "통째로 교체"다. {@code orphanRemoval}의 미묘한 의미론에 기대는 것보다
 *       {@code DELETE ... WHERE group_id = ?} 한 줄이 읽기 쉽다.
 * </ol>
 * {@code auth}·{@code indicator}가 이미 같은 선택을 했고, JPA 엔티티는 {@code stock}의 둘뿐인데
 * 그것들은 QueryDSL Q클래스가 필요해서 존재한다.
 *
 * <p>{@code ScreeningRepository}(조건 <b>실행</b>)와 이름이 비슷하지만 하는 일이 다르다 —
 * 여기는 조건 <b>정의</b>의 저장소다. 그래서 패키지를 나눴다.
 */
@Repository
public class ScreenStore {

    private final JdbcTemplate jdbcTemplate;

    public ScreenStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────

    private static final String SELECT_SCREEN = """
            SELECT s.id, s.code, s.name, s.description, s.screen_type, s.evaluation_mode,
                   s.owner_user_id, s.universe_code, s.is_active,
                   g.id AS group_id, g.logical_operator,
                   (SELECT count(*) FROM condition_group cg WHERE cg.screen_id = s.id) AS group_count
            FROM screen s
            JOIN condition_group g ON g.screen_id = s.id AND g.parent_group_id IS NULL
            """;

    /** 조건까지 채운 상세. */
    @Transactional(readOnly = true)
    public Optional<ScreenDefinition> findById(long id) {
        return findOne(SELECT_SCREEN + " WHERE s.id = ?", id);
    }

    /** 프리셋을 안정적 식별자로 찾는다. */
    @Transactional(readOnly = true)
    public Optional<ScreenDefinition> findByCode(String code) {
        return findOne(SELECT_SCREEN + " WHERE s.code = ? AND s.screen_type = 'PRESET'", code);
    }

    /**
     * 프리셋 코드로 id만 조회한다. 시더가 "만들 것인가 고칠 것인가"를 정할 때 쓴다.
     *
     * <p>{@link #findByCode}를 쓰지 않는 이유는 그쪽이 루트 조건 그룹과 INNER JOIN하기
     * 때문이다. 조건 그룹이 아직 없는 행을 못 보고 새로 만들려 들면 {@code uq_screen_code}에
     * 걸린다 — 시더가 기동을 막는 형태의 실패다.
     */
    @Transactional(readOnly = true)
    public Optional<Long> findIdByCode(String code) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM screen WHERE code = ?", Long.class, code);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * 선언에서 사라진 프리셋을 비활성으로 내린다.
     *
     * <p>IMPORTANT — {@code screen_type = 'PRESET'} 조건을 지우면 <b>사용자의 커스텀 화면이
     * 전부 비활성화된다.</b> 삭제가 아니라 비활성인 이유는 {@code watchlist_item.screen_id}가
     * FK로 붙어 있어 삭제가 실패하거나 사용자 데이터를 끊기 때문이다.
     *
     * @return 비활성으로 내린 행 수
     */
    @Transactional
    public int deactivatePresetsExcept(List<String> keepCodes) {
        if (keepCodes.isEmpty()) {
            return jdbcTemplate.update(
                    "UPDATE screen SET is_active = false, updated_at = now() "
                            + "WHERE screen_type = 'PRESET' AND is_active");
        }
        String placeholders = String.join(",", keepCodes.stream().map(c -> "?").toList());
        return jdbcTemplate.update(
                "UPDATE screen SET is_active = false, updated_at = now() "
                        + "WHERE screen_type = 'PRESET' AND is_active AND code NOT IN (" + placeholders + ")",
                keepCodes.toArray());
    }

    /**
     * 프리셋 전체와 주어진 사용자의 커스텀 화면.
     *
     * <p>조건을 채우지 않는다({@code conditions}가 null인 요약본이다). 목록 화면은 이름만
     * 보여주는데 화면마다 조건 쿼리를 더 돌면 N+1이 된다. 상세는 {@link #findById}가 준다.
     */
    @Transactional(readOnly = true)
    public List<ScreenSummary> listVisibleTo(long userId) {
        return jdbcTemplate.query("""
                SELECT id, code, name, description, screen_type, evaluation_mode,
                       owner_user_id, universe_code, is_active
                FROM screen
                WHERE is_active AND (screen_type = 'PRESET' OR owner_user_id = ?)
                ORDER BY screen_type, name
                """, SUMMARY_MAPPER, userId);
    }

    private Optional<ScreenDefinition> findOne(String sql, Object... args) {
        List<PartialScreen> rows = jdbcTemplate.query(sql, PARTIAL_MAPPER, args);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        PartialScreen row = rows.get(0);

        // IMPORTANT — 루트 그룹만 읽는 로더는 자식 그룹의 조건을 조용히 누락시킨다.
        // 그 화면은 더 느슨한 조건으로 실행되면서 아무 오류도 내지 않는다.
        // v2가 트리를 저장하기 시작하면 여기가 "읽을 수 없다"고 큰 소리를 낸다.
        if (row.groupCount() > 1) {
            throw new IllegalStateException(
                    "화면 %d에 중첩 조건 그룹이 있다(그룹 %d개). v1 로더는 루트만 읽으므로 "
                            .formatted(row.summary().id(), row.groupCount())
                            + "조건이 누락된 채 실행될 수 있다 — 트리 로딩을 구현하기 전에는 실행하지 않는다.");
        }

        return Optional.of(row.toDefinition(loadConditions(row.groupId(), row.logicalOperator())));
    }

    private ConditionGroup loadConditions(long groupId, ConditionGroup.LogicalOperator operator) {
        List<IndicatorCondition> conditions = jdbcTemplate.query("""
                SELECT indicator_code, operator, operand_type, literal_value, literal_value_2,
                       operand_indicator_code, operand_multiplier, weight
                FROM screen_condition
                WHERE group_id = ?
                ORDER BY sort_order, id
                """, CONDITION_MAPPER, groupId);

        return new ConditionGroup(operator, conditions, List.of());
    }

    // ── 쓰기 ──────────────────────────────────────────────────────────────

    /**
     * 새 화면을 만든다.
     *
     * @return 부여된 id를 담은 정의
     */
    @Transactional
    public ScreenDefinition create(ScreenDefinition definition) {
        long screenId;
        try {
            screenId = jdbcTemplate.queryForObject("""
                    INSERT INTO screen (code, name, description, screen_type, evaluation_mode,
                                        owner_user_id, universe_code, is_active, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                    RETURNING id
                    """, Long.class,
                    definition.code(), definition.name(), definition.description(),
                    definition.type().name(), definition.mode().name(),
                    definition.ownerUserId(), definition.universeCode(), definition.active());
        } catch (DuplicateKeyException e) {
            // uq_screen_owner_name. 호출자가 409로 옮긴다 — 사용자 입력 문제이지 서버 버그가 아니다.
            throw new DuplicateScreenNameException(definition.name(), e);
        }

        long groupId = insertRootGroup(screenId);
        insertConditions(groupId, definition.conditions());

        return new ScreenDefinition(screenId, definition.code(), definition.name(),
                definition.description(), definition.type(), definition.mode(),
                definition.ownerUserId(), definition.universeCode(), definition.active(),
                definition.conditions());
    }

    /**
     * 화면을 갱신한다. <b>조건은 통째로 교체된다.</b>
     *
     * <p>부분 패치를 지원하지 않는 이유는 조건 목록의 부분 갱신이 "3번을 지우고 5번을 고친다"류의
     * 인덱스 협상을 요구하기 때문이다. 그 상태에서 {@code sort_order}가 어긋나기 시작하고,
     * 화면에 보이는 순서와 저장된 순서가 갈린다.
     */
    @Transactional
    public void update(ScreenDefinition definition) {
        long screenId = definition.id();
        try {
            jdbcTemplate.update("""
                    UPDATE screen SET name = ?, description = ?, evaluation_mode = ?,
                                      universe_code = ?, is_active = ?, updated_at = now()
                    WHERE id = ?
                    """,
                    definition.name(), definition.description(), definition.mode().name(),
                    definition.universeCode(), definition.active(), screenId);
        } catch (DuplicateKeyException e) {
            throw new DuplicateScreenNameException(definition.name(), e);
        }

        long groupId = rootGroupId(screenId).orElseGet(() -> insertRootGroup(screenId));
        jdbcTemplate.update("DELETE FROM screen_condition WHERE group_id = ?", groupId);
        insertConditions(groupId, definition.conditions());
    }

    /** 화면을 지운다. 조건 그룹·조건은 {@code ON DELETE CASCADE}로 함께 사라진다. */
    @Transactional
    public boolean delete(long screenId) {
        return jdbcTemplate.update("DELETE FROM screen WHERE id = ?", screenId) > 0;
    }

    private Optional<Long> rootGroupId(long screenId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM condition_group WHERE screen_id = ? AND parent_group_id IS NULL",
                Long.class, screenId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    private long insertRootGroup(long screenId) {
        // uq_group_root(부분 유니크)가 화면당 루트 그룹이 정확히 하나임을 DB에서 보장한다.
        return jdbcTemplate.queryForObject("""
                INSERT INTO condition_group (screen_id, parent_group_id, logical_operator, sort_order, created_at)
                VALUES (?, NULL, 'AND', 0, now())
                RETURNING id
                """, Long.class, screenId);
    }

    private void insertConditions(long groupId, ConditionGroup group) {
        List<IndicatorCondition> conditions = group.conditions();
        List<Object[]> batch = new ArrayList<>(conditions.size());

        for (int i = 0; i < conditions.size(); i++) {
            IndicatorCondition condition = conditions.get(i);

            // sealed 인터페이스에 대한 switch다 — 컴파일러가 망라성을 검사하므로 피연산자 종류가
            // 늘면 여기가 컴파일 오류로 드러난다. 지표 코드에 대한 분기가 아니라는 점이 중요하다.
            String operandType;
            BigDecimal literal = null;
            BigDecimal literal2 = null;
            String refCode = null;
            BigDecimal multiplier = null;

            switch (condition.operand()) {
                case ConditionOperand.Literal value -> {
                    operandType = "LITERAL";
                    literal = value.value();
                    literal2 = value.upperBound();
                }
                case ConditionOperand.IndicatorRef ref -> {
                    operandType = "INDICATOR";
                    refCode = ref.indicatorCode();
                    multiplier = ref.multiplier();
                }
            }

            batch.add(new Object[]{
                    groupId, condition.indicatorCode(), condition.operator().name(), operandType,
                    literal, literal2, refCode, multiplier, condition.weight(), i});
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO screen_condition (group_id, indicator_code, operator, operand_type,
                                              literal_value, literal_value_2, operand_indicator_code,
                                              operand_multiplier, weight, sort_order, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, batch);
    }

    // ── 매핑 ──────────────────────────────────────────────────────────────

    private static final RowMapper<IndicatorCondition> CONDITION_MAPPER = (rs, rowNum) -> {
        String indicatorCode = rs.getString("indicator_code");
        ComparisonOperator operator = ComparisonOperator.valueOf(rs.getString("operator"));

        // 합타입 복원. DB의 ck_cond_operand가 "둘 중 정확히 하나"를 이미 보장하므로
        // 여기서 다시 검사하지 않는다 — 제약을 믿지 못하면 제약을 둔 의미가 없다.
        ConditionOperand operand = "LITERAL".equals(rs.getString("operand_type"))
                ? new ConditionOperand.Literal(
                        rs.getBigDecimal("literal_value"), rs.getBigDecimal("literal_value_2"))
                : new ConditionOperand.IndicatorRef(
                        rs.getString("operand_indicator_code"), rs.getBigDecimal("operand_multiplier"));

        return new IndicatorCondition(indicatorCode, operator, operand, rs.getBigDecimal("weight"));
    };

    private static final RowMapper<ScreenSummary> SUMMARY_MAPPER =
            (rs, rowNum) -> mapSummary(rs);

    private static final RowMapper<PartialScreen> PARTIAL_MAPPER = (rs, rowNum) -> new PartialScreen(
            mapSummary(rs),
            rs.getLong("group_id"),
            ConditionGroup.LogicalOperator.valueOf(rs.getString("logical_operator")),
            rs.getInt("group_count"));

    private static ScreenSummary mapSummary(ResultSet rs) throws SQLException {
        // getObject(Long.class)를 쓴다. getLong + wasNull()은 wasNull이 "직전에 읽은 컬럼"을
        // 가리키므로, 생성자 인자 안에서 쓰면 그 사이에 읽은 다른 컬럼을 보게 된다.
        // 그러면 프리셋의 소유자가 null이 아니라 0이 되고, 그 0은 아무 사용자도 아니다.
        return new ScreenSummary(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                ScreenType.valueOf(rs.getString("screen_type")),
                EvaluationMode.valueOf(rs.getString("evaluation_mode")),
                rs.getObject("owner_user_id", Long.class),
                rs.getString("universe_code"),
                rs.getBoolean("is_active"));
    }

    /** 조건을 채우기 전의 중간 상태. 밖으로 나가지 않는다. */
    private record PartialScreen(ScreenSummary summary, long groupId,
                                 ConditionGroup.LogicalOperator logicalOperator, int groupCount) {

        ScreenDefinition toDefinition(ConditionGroup conditions) {
            return new ScreenDefinition(summary.id(), summary.code(), summary.name(),
                    summary.description(), summary.type(), summary.mode(), summary.ownerUserId(),
                    summary.universeCode(), summary.active(), conditions);
        }
    }

    /** 목록용 요약. 조건이 없다. */
    public record ScreenSummary(
            long id, String code, String name, String description,
            ScreenType type, EvaluationMode mode,
            Long ownerUserId, String universeCode, boolean active) {
    }

    /** {@code uq_screen_owner_name} 위반. HTTP 계층이 409로 옮긴다. */
    public static class DuplicateScreenNameException extends RuntimeException {
        private final String name;

        public DuplicateScreenNameException(String name, Throwable cause) {
            super("같은 이름의 화면이 이미 있다: " + name, cause);
            this.name = name;
        }

        public String screenName() {
            return name;
        }
    }
}
