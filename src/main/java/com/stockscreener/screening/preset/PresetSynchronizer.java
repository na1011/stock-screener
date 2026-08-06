package com.stockscreener.screening.preset;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.screening.store.ScreenDefinition;
import com.stockscreener.screening.store.ScreenDefinitionValidator;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.screening.store.ScreenType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 코드에서 선언한 프리셋을 {@code screen} 테이블로 투영한다.
 *
 * <p>{@code IndicatorCatalogSynchronizer}와 같은 패턴이다 — 정본은 코드이고 테이블은 사본이며,
 * 사라진 것은 <b>삭제하지 않고 비활성</b>으로 내린다.
 *
 * <p>{@link ScreenStore}의 쓰기 경로와 {@link ScreenDefinitionValidator}를 그대로 쓴다.
 * 시더 전용 우회 경로를 하나라도 두면 v1 불변식을 어기는 프리셋이 DB에 생길 수 있고,
 * 그러면 <b>UI가 열지 못하는 화면</b>이 만들어진다.
 */
@Component
public class PresetSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(PresetSynchronizer.class);

    private final List<ScreenPreset> presets;
    private final ScreenStore store;
    private final IndicatorCatalog indicatorCatalog;

    public PresetSynchronizer(List<ScreenPreset> presets, ScreenStore store,
                              IndicatorCatalog indicatorCatalog) {
        this.presets = List.copyOf(presets);
        this.store = store;
        this.indicatorCatalog = indicatorCatalog;
    }

    /**
     * 프리셋을 현재 선언에 맞춘다. 여러 번 실행해도 결과가 같다.
     *
     * @return 투영한 프리셋 수
     */
    @Transactional
    public int synchronize() {
        Set<String> activeIndicators = indicatorCatalog.activeCodes();
        List<String> codes = new ArrayList<>(presets.size());

        for (ScreenPreset preset : presets) {
            // 시더도 같은 검증을 통과한다. 여기를 건너뛰면 화면이 열리지 않는 프리셋이 생긴다.
            ScreenDefinitionValidator.validate(preset.conditions(), activeIndicators);

            ScreenDefinition definition = new ScreenDefinition(
                    store.findIdByCode(preset.code()).orElse(null),
                    preset.code(), preset.name(), preset.description(),
                    ScreenType.PRESET, preset.mode(), null, preset.universeCode(), true,
                    preset.conditions());

            if (definition.id() == null) {
                store.create(definition);
            } else {
                // 조건이 통째로 교체된다. 코드에서 프리셋을 고치면 배포로 반영된다는 뜻이다.
                store.update(definition);
            }
            codes.add(preset.code());
        }

        int deactivated = store.deactivatePresetsExcept(codes);
        if (deactivated > 0) {
            log.info("선언에서 사라진 프리셋 {}건을 비활성으로 내렸다(삭제하지 않았다).", deactivated);
        }
        return codes.size();
    }
}
