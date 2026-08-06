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
    private final List<PercentileSpec> percentileSpecs;
    private final JdbcTemplate jdbcTemplate;

    public IndicatorCatalogSynchronizer(IndicatorRegistry registry,
                                        List<PercentileSpec> percentileSpecs,
                                        JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.percentileSpecs = List.copyOf(percentileSpecs);
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

        // 백분위 지표는 계산기가 없다(유니버스 횡단 집계라 IndicatorCalculator가 될 수 없다).
        // 그래도 카탈로그 행은 있어야 한다 — indicator_value의 FK가 이 행을 요구하고,
        // 여기 빠지면 아래 deactivateMissing이 방금 만든 행을 곧바로 비활성으로 돌린다.
        for (PercentileSpec spec : percentileSpecs) {
            if (!registry.isRegistered(spec.sourceCode())) {
                throw new IllegalStateException(
                        "백분위의 원본 지표가 등록되어 있지 않다: " + spec.sourceCode()
                                + " — 계산기가 사라졌거나 코드를 잘못 적었다.");
            }
            upsertPercentile(spec);
            activeCodes.add(spec.code());
        }

        deactivateMissing(activeCodes);
        return activeCodes.size();
    }

    /**
     * 백분위 지표의 카탈로그 행.
     *
     * <p>값이 항상 0~1의 비율이고 큰 쪽이 "원본 값이 크다"를 뜻하므로, 표시 속성은 원본과
     * 무관하게 고정이다. 유불리 방향을 {@code NEUTRAL}로 두는 이유는 원본 지표마다 다르고
     * ({@code PER_PCTL}은 낮은 쪽이, {@code ROE_PCTL}은 높은 쪽이 좋다) 그 판단은 원본
     * 지표의 {@code higher_is_better}가 이미 들고 있기 때문이다.
     */
    private void upsertPercentile(PercentileSpec spec) {
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, description, interpretation, category, value_type,
                                       unit, decimal_scale, higher_is_better, is_active, sort_order,
                                       default_column, source_indicator_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DERIVED', 'RATIO', NULL, 4, NULL, true, 900, false, ?, now(), now())
                ON CONFLICT (code) DO UPDATE SET
                    name                  = EXCLUDED.name,
                    description           = EXCLUDED.description,
                    interpretation        = EXCLUDED.interpretation,
                    is_active             = true,
                    source_indicator_code = EXCLUDED.source_indicator_code,
                    updated_at            = now()
                """,
                spec.code(), spec.name(),
                emptyToNull(spec.description()), emptyToNull(spec.interpretation()),
                // 부등호 방향 판단이 이 링크에 걸린다 — 화면은 원본의 higher_is_better를 보고
                // "상위 20%"가 >= 0.8인지 <= 0.2인지 정한다.
                spec.sourceCode());
    }

    private void upsert(String code, IndicatorMeta meta) {
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, description, interpretation, category, value_type,
                                       unit, decimal_scale, higher_is_better, is_active, sort_order,
                                       default_column, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?, now(), now())
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
                    default_column   = EXCLUDED.default_column,
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
                meta.sortOrder(),
                meta.defaultColumn());
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
