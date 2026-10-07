package com.stockscreener.screening.preset;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.pipeline.lock.CatalogWriteGate;
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
import java.util.Optional;
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
 *
 * <h2>같으면 문장 0 · 다르면 쓸 JVM인지 묻는다(ADR-0175)</h2>
 * <p>먼저 전부 읽어 비교하고({@link ScreenStore#storesAs} · {@link ScreenStore#presetsToDeactivate}) 차이가 있는 프리셋만
 * 쓴다. 차이가 있으면 쓰기 전에 {@link CatalogWriteGate}가 묻는다 — 프리셋 표는 쓰기 가드의 보호 제외 표라 트리거가 못
 * 막으므로, 다른 체크아웃의 bootRun·개발용 웹 bootRun이 작업 트리의 프리셋을 운영에 쓰는 것을 막는 자리가 여기뿐이다.
 * 예전에는 기동마다 프리셋 전부의 조건을 지우고 다시 넣었다(운영 {@code screen_condition} 삽입·삭제 각 3,515).
 */
@Component
public class PresetSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(PresetSynchronizer.class);

    private final List<ScreenPreset> presets;
    private final ScreenStore store;
    private final IndicatorCatalog indicatorCatalog;
    private final CatalogWriteGate writeGate;

    public PresetSynchronizer(List<ScreenPreset> presets, ScreenStore store,
                              IndicatorCatalog indicatorCatalog, CatalogWriteGate writeGate) {
        this.presets = List.copyOf(presets);
        this.store = store;
        this.indicatorCatalog = indicatorCatalog;
        this.writeGate = writeGate;
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
        List<ScreenDefinition> writes = new ArrayList<>();
        List<String> differences = new ArrayList<>();

        for (ScreenPreset preset : presets) {
            // 시더도 같은 검증을 통과한다. 여기를 건너뛰면 화면이 열리지 않는 프리셋이 생긴다.
            ScreenDefinitionValidator.validate(preset.conditions(), activeIndicators);

            ScreenDefinition definition = new ScreenDefinition(
                    store.findIdByCode(preset.code()).orElse(null),
                    preset.code(), preset.name(), preset.description(),
                    ScreenType.PRESET, preset.mode(), null, preset.universeCode(), true,
                    preset.conditions());
            codes.add(preset.code());

            if (definition.id() == null) {
                differences.add(preset.code() + " 신규");
                writes.add(definition);
                continue;
            }
            Optional<ScreenDefinition> stored = store.findByCode(preset.code());
            if (stored.isEmpty() || !store.storesAs(stored.get(), definition)) {
                differences.add(preset.code() + (stored.isEmpty() ? " 조건 그룹 없음" : " 바뀜"));
                writes.add(definition);
            }
        }
        List<String> retiring = store.presetsToDeactivate(codes);
        retiring.forEach(code -> differences.add(code + " 비활성"));

        if (differences.isEmpty()) {
            return codes.size();
        }
        writeGate.requireWritable("프리셋", differences);
        for (ScreenDefinition definition : writes) {
            if (definition.id() == null) {
                store.create(definition);
            } else {
                // 조건이 통째로 교체된다. 코드에서 프리셋을 고치면 배포로 반영된다는 뜻이다.
                store.update(definition);
            }
        }
        if (!retiring.isEmpty()) {
            int deactivated = store.deactivatePresetsExcept(codes);
            log.info("선언에서 사라진 프리셋 {}건을 비활성으로 내렸다(삭제하지 않았다).", deactivated);
        }
        log.info("프리셋 카탈로그 차이를 썼다: {}", differences);
        return codes.size();
    }
}
