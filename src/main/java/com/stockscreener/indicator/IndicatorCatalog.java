package com.stockscreener.indicator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code indicator} 카탈로그를 읽는다.
 *
 * <p>{@link IndicatorRegistry}(계산기 모음)와 다르다. 레지스트리는 <b>계산기가 있는</b>
 * 지표만 알지만 백분위에는 계산기가 없다. 조건 빌더가 "PER 백분위"를 제시하려면 테이블을
 * 읽어야 하며, 그것이 {@code IndicatorCatalogSynchronizer}가 애너테이션을 테이블로 투영하는 이유다.
 *
 * <p>{@code is_active = false}인 지표는 내보내지 않는다. 폐기된 지표로 새 조건을 만들 수 있으면
 * 그 화면은 저장은 되지만 영원히 "판정 불가"만 낸다. 저장된 조건이 이미 참조하고 있는 경우는
 * 다르다 — 그래서 행을 지우지 않고 비활성으로 두는 것이다(ADR-0002).
 */
@Component
public class IndicatorCatalog {

    private static final String SELECT = """
            SELECT code, name, description, interpretation, category, value_type, unit,
                   decimal_scale, higher_is_better, default_column, sort_order, source_indicator_code,
                   bands
            FROM indicator
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * RowMapper가 static이 아닌 이유. 구간은 jsonb라 파싱이 필요하고, 파싱 형식을 아는 곳은
     * {@link IndicatorBandCodec} 하나여야 한다(그 이유는 코덱 주석에 있다).
     */
    private final RowMapper<IndicatorDescriptor> mapper;

    public IndicatorCatalog(JdbcTemplate jdbcTemplate, IndicatorBandCodec bandCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = (rs, rowNum) -> new IndicatorDescriptor(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("interpretation"),
                IndicatorCategory.valueOf(rs.getString("category")),
                IndicatorValueType.valueOf(rs.getString("value_type")),
                rs.getString("unit"),
                rs.getInt("decimal_scale"),
                // getBoolean은 NULL을 false로 바꿔버린다. "방향 없음"과 "낮은 쪽이 좋음"이
                // 같은 값이 되면 RSI가 PER처럼 다뤄진다.
                rs.getObject("higher_is_better", Boolean.class),
                rs.getBoolean("default_column"),
                rs.getInt("sort_order"),
                rs.getString("source_indicator_code"),
                bandCodec.fromJson(rs.getString("bands")));
    }

    /** 조건 빌더가 제시할 수 있는 지표 전부. 표시 순서대로 정렬된다. */
    @Transactional(readOnly = true)
    public List<IndicatorDescriptor> activeIndicators() {
        return jdbcTemplate.query(SELECT + " WHERE is_active ORDER BY sort_order, code", mapper);
    }

    /** 활성 지표 코드. 조건 검증이 쓴다. */
    @Transactional(readOnly = true)
    public Set<String> activeCodes() {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT code FROM indicator WHERE is_active", String.class));
    }

    /**
     * 주어진 코드들의 메타데이터. 응답 조립이 쓴다.
     *
     * <p>비활성 지표도 포함한다 — 저장된 화면이 이미 참조하고 있을 수 있고, 그 경우 화면은
     * "이 지표는 더 이상 제공되지 않는다"고 말할 수 있어야 한다. 값 없이 코드만 보이면
     * 사용자는 무엇이 잘못됐는지 알 수 없다.
     */
    @Transactional(readOnly = true)
    public Map<String, IndicatorDescriptor> describe(Set<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", codes.stream().map(c -> "?").toList());
        List<IndicatorDescriptor> rows = jdbcTemplate.query(
                SELECT + " WHERE code IN (" + placeholders + ")", mapper, codes.toArray());

        // LinkedHashMap으로 조회 순서를 유지한다. 응답의 컬럼 순서가 요청마다 달라지면
        // 프론트가 컬럼 위치를 기억할 수 없다.
        return rows.stream().collect(Collectors.toMap(
                IndicatorDescriptor::code, d -> d, (a, b) -> a, LinkedHashMap::new));
    }

    @Transactional(readOnly = true)
    public Optional<IndicatorDescriptor> find(String code) {
        List<IndicatorDescriptor> rows = jdbcTemplate.query(SELECT + " WHERE code = ?", mapper, code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
