package com.stockscreener.indicator;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 등록된 계산기의 {@link IndicatorMeta}를 {@code indicator} 테이블로 투영한다.
 *
 * <p>메타데이터의 근거는 계산 클래스의 애너테이션이고, 이 테이블은 그 사본이다.
 * 그래야 지표 추가가 파일 하나로 끝나면서도 API와 조건 빌더 UI가 평범한 쿼리로 읽을 수 있다.
 *
 * <p>사라진 지표는 <b>삭제하지 않고</b> {@code is_active=false}로 둔다. 저장된 커스텀 화면이
 * 그 지표를 참조하고 있을 수 있고, 삭제하면 사용자의 화면이 깨진다.
 */
@Component
public class IndicatorCatalogSynchronizer {

    private final IndicatorRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    public IndicatorCatalogSynchronizer(IndicatorRegistry registry, JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 카탈로그를 현재 등록된 계산기 집합에 맞춘다.
     *
     * @return upsert된 지표 수
     */
    @Transactional
    public int synchronize() {
        List<String> activeCodes = new ArrayList<>();

        for (IndicatorCalculator calculator : registry.executionOrder()) {
            IndicatorMeta meta = AnnotationUtils.findAnnotation(calculator.getClass(), IndicatorMeta.class);
            if (meta == null) {
                throw new IllegalStateException(
                        calculator.getClass().getName() + "에 @IndicatorMeta가 없다. "
                                + "지표의 표시 정보는 계산 코드 옆에 두어야 빠뜨리지 않는다.");
            }
            upsert(calculator.code(), meta);
            activeCodes.add(calculator.code());
        }

        deactivateMissing(activeCodes);
        return activeCodes.size();
    }

    private void upsert(String code, IndicatorMeta meta) {
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, description, interpretation, category, value_type,
                                       unit, decimal_scale, higher_is_better, is_active, sort_order,
                                       created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, now(), now())
                ON CONFLICT (code) DO UPDATE SET
                    name             = EXCLUDED.name,
                    description      = EXCLUDED.description,
                    interpretation   = EXCLUDED.interpretation,
                    category         = EXCLUDED.category,
                    value_type       = EXCLUDED.value_type,
                    unit             = EXCLUDED.unit,
                    decimal_scale    = EXCLUDED.decimal_scale,
                    higher_is_better = EXCLUDED.higher_is_better,
                    is_active        = true,
                    sort_order       = EXCLUDED.sort_order,
                    updated_at       = now()
                """,
                code,
                meta.name(),
                emptyToNull(meta.description()),
                emptyToNull(meta.interpretation()),
                meta.category().name(),
                meta.valueType().name(),
                emptyToNull(meta.unit()),
                meta.decimalScale(),
                meta.direction().toHigherIsBetter(),
                meta.sortOrder());
    }

    private void deactivateMissing(List<String> activeCodes) {
        if (activeCodes.isEmpty()) {
            jdbcTemplate.update("UPDATE indicator SET is_active = false, updated_at = now() WHERE is_active");
            return;
        }
        String placeholders = String.join(",", activeCodes.stream().map(c -> "?").toList());
        jdbcTemplate.update(
                "UPDATE indicator SET is_active = false, updated_at = now() "
                        + "WHERE is_active AND code NOT IN (" + placeholders + ")",
                activeCodes.toArray());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
