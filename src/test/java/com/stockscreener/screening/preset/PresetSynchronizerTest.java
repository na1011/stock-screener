package com.stockscreener.screening.preset;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.screening.ComparisonOperator;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.ConditionOperand;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.IndicatorCondition;
import com.stockscreener.screening.store.ScreenDefinition;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시딩 <b>기제</b>를 검증한다 — 멱등성, 폐기 처리, 사용자 데이터 불간섭.
 *
 * <p>실제 프리셋 선언이 아니라 합성 프리셋을 쓴다. 여기서 볼 것은 "VALUE_KR의 조건이 맞는가"가
 * 아니라 "두 번 돌려도 같은가"이고, 실제 카탈로그를 쓰면 지표 23종이 모두 있어야 해서
 * 검증 대상과 무관한 픽스처가 커진다. 선언 자체는 {@link PresetCatalogTest}가 본다.
 */
@JpaSliceTest
@Import({ScreenStore.class, IndicatorCatalog.class})
class PresetSynchronizerTest {

    @Autowired
    private ScreenStore store;

    @Autowired
    private IndicatorCatalog indicatorCatalog;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, category, value_type)
                VALUES ('PER','PER','FUNDAMENTAL','RATIO'), ('PBR','PBR','FUNDAMENTAL','RATIO')
                ON CONFLICT (code) DO UPDATE SET is_active = true
                """);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES ('preset@example.com','테스트','GOOGLE','sub-preset',false,true) RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("두 번 실행해도 결과가 같다 — 재기동마다 프리셋이 늘어나지 않는다")
    void isIdempotent() {
        PresetSynchronizer synchronizer = synchronizerOf(preset("A", "가치주", "10"));

        synchronizer.synchronize();
        synchronizer.synchronize();

        assertThat(presetCount("A")).isEqualTo(1);
        assertThat(conditionCount("A")).isEqualTo(1);
    }

    @Test
    @DisplayName("조건을 바꿔 다시 돌리면 조건이 통째로 교체된다 — 배포로 반영된다는 뜻이다")
    void replacesConditionsOnRedeploy() {
        synchronizerOf(preset("A", "가치주", "10")).synchronize();
        synchronizerOf(preset("A", "가치주", "20")).synchronize();

        ScreenDefinition loaded = store.findByCode("A").orElseThrow();
        assertThat(loaded.conditions().conditions()).hasSize(1);
        assertThat(((ConditionOperand.Literal) loaded.conditions().conditions().get(0).operand()).value())
                .isEqualByComparingTo("20");
        assertThat(conditionCount("A")).isEqualTo(1);
    }

    @Test
    @DisplayName("IMPORTANT — 선언에서 사라진 프리셋은 비활성이 될 뿐 행이 남는다")
    void retiredPresetIsDeactivatedNotDeleted() {
        synchronizerOf(preset("A", "가치주", "10"), preset("B", "성장주", "5")).synchronize();
        synchronizerOf(preset("A", "가치주", "10")).synchronize();

        // watchlist_item.screen_id가 FK로 붙어 있어 삭제는 실패하거나 사용자 데이터를 끊는다.
        assertThat(presetCount("B")).isEqualTo(1);
        assertThat(isActive("B")).isFalse();
        assertThat(isActive("A")).isTrue();
    }

    @Test
    @DisplayName("사라졌다 돌아온 프리셋은 다시 활성이 된다")
    void returningPresetIsReactivated() {
        synchronizerOf(preset("A", "가치주", "10")).synchronize();
        synchronizerOf().synchronize();
        assertThat(isActive("A")).isFalse();

        synchronizerOf(preset("A", "가치주", "10")).synchronize();
        assertThat(isActive("A")).isTrue();
    }

    @Test
    @DisplayName("IMPORTANT — 사용자의 커스텀 화면을 건드리지 않는다")
    void leavesCustomScreensAlone() {
        ScreenDefinition custom = store.create(ScreenDefinition.custom(
                null, "내 화면", null, EvaluationMode.FILTER, userId, null,
                ConditionGroup.and(IndicatorCondition.of(
                        "PER", ComparisonOperator.LT, ConditionOperand.Literal.of("10")))));

        // 비활성화 UPDATE에 screen_type='PRESET' 조건이 빠지면 여기서 전부 꺼진다.
        synchronizerOf(preset("A", "가치주", "10")).synchronize();

        ScreenDefinition reloaded = store.findById(custom.id()).orElseThrow();
        assertThat(reloaded.active()).isTrue();
        assertThat(reloaded.conditions().conditions()).hasSize(1);
    }

    @Test
    @DisplayName("프리셋은 소유자 없이 저장되고 code로 찾을 수 있다")
    void presetsAreOwnerlessAndAddressableByCode() {
        synchronizerOf(preset("A", "가치주", "10")).synchronize();

        ScreenDefinition loaded = store.findByCode("A").orElseThrow();
        assertThat(loaded.ownerUserId()).isNull();
        assertThat(loaded.isPreset()).isTrue();
        assertThat(loaded.mode()).isEqualTo(EvaluationMode.SCORING);
    }

    private PresetSynchronizer synchronizerOf(ScreenPreset... presets) {
        return new PresetSynchronizer(List.of(presets), store, indicatorCatalog);
    }

    private static ScreenPreset preset(String code, String name, String threshold) {
        return new ScreenPreset(code, name, "설명", EvaluationMode.SCORING, null,
                ConditionGroup.and(IndicatorCondition.of(
                                "PER", ComparisonOperator.LT, ConditionOperand.Literal.of(threshold))
                        .withWeight(java.math.BigDecimal.ONE)));
    }

    private int presetCount(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM screen WHERE code = ?", Integer.class, code);
    }

    private int conditionCount(String code) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM screen_condition sc
                JOIN condition_group g ON g.id = sc.group_id
                JOIN screen s ON s.id = g.screen_id
                WHERE s.code = ?
                """, Integer.class, code);
    }

    private boolean isActive(String code) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM screen WHERE code = ?", Boolean.class, code));
    }
}
