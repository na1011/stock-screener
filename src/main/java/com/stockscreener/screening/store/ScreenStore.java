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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        // 통화별 임계를 먼저 모은다. 조건 행마다 따로 물으면 조건 수만큼 질의가 늘고,
        // 조인으로 붙이면 한 조건이 통화 수만큼 행이 되어 매퍼가 조건을 중복 생성한다.
        Map<Long, Map<String, ConditionOperand.Literal>> extras = new HashMap<>();
        jdbcTemplate.query("""
                SELECT t.condition_id, t.currency_code, t.literal_value, t.literal_value_2
                FROM screen_condition_threshold t
                JOIN screen_condition c ON c.id = t.condition_id
                WHERE c.group_id = ?
                """, rs -> {
            extras.computeIfAbsent(rs.getLong("condition_id"), k -> new LinkedHashMap<>())
                    .put(rs.getString("currency_code"), new ConditionOperand.Literal(
                            rs.getBigDecimal("literal_value"), rs.getBigDecimal("literal_value_2")));
        }, groupId);

        List<IndicatorCondition> conditions = jdbcTemplate.query("""
                SELECT id, indicator_code, operator, operand_type, literal_value, literal_value_2,
                       operand_indicator_code, operand_multiplier, weight, is_required,
                       threshold_currency
                FROM screen_condition
                WHERE group_id = ?
                ORDER BY sort_order, id
                """, (rs, rowNum) -> mapCondition(rs, extras), groupId);

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
        deriveThresholdCurrency(groupId, definition.universeCode());

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
        deriveThresholdCurrency(groupId, definition.universeCode());
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
        Map<Integer, Map<String, ConditionOperand.Literal>> extraByOrder = new HashMap<>();

        for (int i = 0; i < conditions.size(); i++) {
            IndicatorCondition condition = conditions.get(i);

            // sealed 인터페이스에 대한 switch다 — 컴파일러가 망라성을 검사하므로 피연산자 종류가
            // 늘면 여기가 컴파일 오류로 드러난다. 지표 코드에 대한 분기가 아니라는 점이 중요하다.
            String operandType;
            BigDecimal literal = null;
            BigDecimal literal2 = null;
            String refCode = null;
            BigDecimal multiplier = null;

            String baseCurrency = null;
            Map<String, ConditionOperand.Literal> otherCurrencies = Map.of();

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
                // 기준 통화 하나를 조건 행에 두고 나머지를 자식 표에 넣는다. 그래야
                // ck_cond_operand("리터럴이면 값이 NOT NULL")를 고치지 않아도 된다.
                case ConditionOperand.MarketLiteral market -> {
                    operandType = "LITERAL";
                    Map<String, ConditionOperand.Literal> byCurrency =
                            new LinkedHashMap<>(market.byCurrency());
                    String first = byCurrency.keySet().iterator().next();
                    ConditionOperand.Literal base = byCurrency.remove(first);
                    baseCurrency = first;
                    literal = base.value();
                    literal2 = base.upperBound();
                    otherCurrencies = byCurrency;
                }
            }

            batch.add(new Object[]{
                    groupId, condition.indicatorCode(), condition.operator().name(), operandType,
                    literal, literal2, refCode, multiplier, condition.weight(), condition.required(),
                    i, baseCurrency});
            extraByOrder.put(i, otherCurrencies);
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO screen_condition (group_id, indicator_code, operator, operand_type,
                                              literal_value, literal_value_2, operand_indicator_code,
                                              operand_multiplier, weight, is_required, sort_order,
                                              threshold_currency, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, batch);

        insertExtraThresholds(groupId, extraByOrder);
    }

    /**
     * 사용자가 만든 조건의 <b>통화 축을 서버가 유도한다.</b>
     *
     * <p>화면에 고르는 자리를 만들지 않는 이유가 이것이다 — 고를 수 있으면 잘못 고를 수 있고,
     * 그 오류는 <b>다른 시장에서만</b> 드러난다. 대신 「금액·가격 차원의 지표에 0이 아닌 리터럴을
     * 걸었다」는 사실에서 유도한다. 부호 조건({@code OCF_SUM_5Y &gt; 0})은 시장 중립이라 빠진다.
     *
     * <p>IMPORTANT — 이것이 없으면 <b>프리셋을 복사해 만든 사용자 화면이 원본보다 느슨해진다.</b>
     * 시더는 {@code PRESET}만 갱신하므로 사본은 옛 조건을 든 채 남는다(ADR-0072 §규칙 · P27 축①).
     *
     * <p>어느 통화인가는 그 화면의 조회 대상이 답한다. 대상을 안 정했으면 활성 조회 대상의
     * 통화가 하나일 때만 그것을 쓴다 — 갈리면 <b>아무것도 안 적는다.</b>
     *
     * <p><b>MUST NOT: 그 「안 적음」을 안전하다고 읽지 않는다.</b> 한때 여기에
     * <i>"그 경우 실행이 「조회 대상을 하나 골라야 한다」로 거절되므로 조용히 틀리지 않는다"</i>고
     * 적혀 있었고 <b>그것은 거짓이었다.</b> 거절은 <b>실행 시점의</b> 대상으로 판정하는데
     * ({@code ScreeningService}가 요청의 {@code universeCode}를 저장된 것보다 먼저 쓴다),
     * 대상 없이 저장한 화면을 <b>실행 시점에 미국으로 지정하면</b> 그때 통화가 하나뿐이라
     * 거절이 안 난다. 그리고 축이 안 붙은 조건은 {@code IndicatorCondition.resolveFor}가
     * <b>평범한 리터럴로 그대로 통과시켜</b> 원화 임계가 달러 값에 걸린다.
     * <b>지금 그 경로를 막는 것은 화면뿐이다</b> — 통화가 갈리면 조건 칩 바가 대상을 채워
     * 저장 요청이 {@code null}을 안 싣는다. API를 직접 부르면 열려 있다.
     *
     * <p><b>MUST: 술어를 {@code MarketScope.ACTIVE_CURRENCIES}와 같게 유지한다</b> — <b>편입이
     * 있는</b> 활성 유니버스만 센다. 갈리면 <b>저장이 보는 통화 집합과 실행이 보는 것이 달라진다.</b>
     * 실제로 갈려 있었다: 편입 조인이 없어 테스트 DB에서 저장은 {@code {KRW, USD}}를 보는데
     * 실행은 빈 집합을 봤다. 패키지가 달라 상수를 공유하지 못하므로 <b>사본이고, 한쪽을 고치면
     * 반대쪽도 고친다.</b>
     */
    private void deriveThresholdCurrency(long groupId, String universeCode) {
        List<String> currencies = universeCode == null
                ? jdbcTemplate.queryForList("""
                        SELECT DISTINCT m.currency_code FROM universe u
                        JOIN market m ON m.code = u.market_code
                        JOIN universe_membership um
                          ON um.universe_code = u.code AND um.effective_to IS NULL
                        WHERE u.is_active
                        """, String.class)
                : jdbcTemplate.queryForList("""
                        SELECT m.currency_code FROM universe u
                        JOIN market m ON m.code = u.market_code WHERE u.code = ?
                        """, String.class, universeCode);
        if (currencies.size() != 1) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE screen_condition sc
                SET threshold_currency = ?
                FROM indicator i
                WHERE i.code = sc.indicator_code
                  AND sc.group_id = ?
                  AND sc.threshold_currency IS NULL
                  AND sc.operand_type = 'LITERAL'
                  AND i.value_type IN ('CURRENCY', 'PRICE')
                  AND (sc.literal_value <> 0 OR coalesce(sc.literal_value_2, 0) <> 0)
                """, currencies.get(0), groupId);
    }

    /**
     * 기준 통화가 아닌 임계를 자식 표에 넣는다.
     *
     * <p>id를 되받으려고 조건을 한 행씩 넣지 않는다 — 배치를 잃는다. 대신 방금 넣은 행을
     * {@code sort_order}로 다시 찾는다. 그 값은 같은 그룹 안에서 유일하다(위 루프의 {@code i}).
     */
    private void insertExtraThresholds(
            long groupId, Map<Integer, Map<String, ConditionOperand.Literal>> extraByOrder) {
        if (extraByOrder.values().stream().allMatch(Map::isEmpty)) {
            return;
        }
        Map<Integer, Long> idByOrder = new HashMap<>();
        jdbcTemplate.query("SELECT id, sort_order FROM screen_condition WHERE group_id = ?",
                rs -> { idByOrder.put(rs.getInt("sort_order"), rs.getLong("id")); }, groupId);

        List<Object[]> rows = new ArrayList<>();
        extraByOrder.forEach((order, byCurrency) -> byCurrency.forEach((currency, value) ->
                rows.add(new Object[]{idByOrder.get(order), currency,
                        value.value(), value.upperBound()})));
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO screen_condition_threshold
                    (condition_id, currency_code, literal_value, literal_value_2)
                VALUES (?, ?, ?, ?)
                """, rows);
    }

    // ── 매핑 ──────────────────────────────────────────────────────────────

    /**
     * 조건 한 행을 도메인으로 되돌린다.
     *
     * <p>IMPORTANT — <b>컴파일러가 잡아주지 않는 자리다.</b> 쓰기 쪽 switch와 달리 여기는
     * 문자열 비교라, 컬럼을 더하고 이것을 잊으면 <b>저장은 되는데 읽을 때 조용히 기본값이 된다.</b>
     * {@code threshold_currency}가 그 형태로 새면 시장별 임계가 통째로 사라지고
     * <b>기준 통화의 값이 모든 시장에 걸린다</b> — 통화 축을 세운 목적과 정확히 반대다.
     */
    private static IndicatorCondition mapCondition(
            ResultSet rs, Map<Long, Map<String, ConditionOperand.Literal>> extras)
            throws SQLException {
        String indicatorCode = rs.getString("indicator_code");
        ComparisonOperator operator = ComparisonOperator.valueOf(rs.getString("operator"));

        // 합타입 복원. DB의 ck_cond_operand가 "둘 중 정확히 하나"를 이미 보장하므로
        // 여기서 다시 검사하지 않는다 — 제약을 믿지 못하면 제약을 둔 의미가 없다.
        ConditionOperand operand;
        if ("LITERAL".equals(rs.getString("operand_type"))) {
            ConditionOperand.Literal base = new ConditionOperand.Literal(
                    rs.getBigDecimal("literal_value"), rs.getBigDecimal("literal_value_2"));
            String baseCurrency = rs.getString("threshold_currency");
            if (baseCurrency == null) {
                // 시장 중립. 부호·개수·연수·백분위·상대비교만 여기 온다(P27 축①).
                operand = base;
            } else {
                Map<String, ConditionOperand.Literal> byCurrency = new LinkedHashMap<>();
                byCurrency.put(baseCurrency, base);
                byCurrency.putAll(extras.getOrDefault(rs.getLong("id"), Map.of()));
                operand = new ConditionOperand.MarketLiteral(byCurrency);
            }
        } else {
            operand = new ConditionOperand.IndicatorRef(
                    rs.getString("operand_indicator_code"), rs.getBigDecimal("operand_multiplier"));
        }

        return new IndicatorCondition(indicatorCode, operator, operand,
                rs.getBigDecimal("weight"), rs.getBoolean("is_required"));
    }

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
