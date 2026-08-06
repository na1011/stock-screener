package com.stockscreener.screening.web;

import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.store.ScreenDefinition;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.screening.store.ScreenType;

/**
 * 저장된 화면.
 *
 * <p>{@code editable}을 서버가 계산해서 준다. 프론트가 {@code type == PRESET}으로 판단해도
 * 되지만, 규칙이 바뀌면(예: 관리자는 프리셋을 고칠 수 있게) 화면과 서버가 갈린다.
 * 판단의 정본은 인가를 아는 쪽에 둔다.
 */
public record ScreenResponse(
        Long id,
        String code,
        String name,
        String description,
        ScreenType type,
        EvaluationMode evaluationMode,
        String universeCode,
        boolean active,
        boolean editable,
        ConditionGroupPayload conditions
) {

    /** 조건까지 담은 상세. */
    public static ScreenResponse of(ScreenDefinition definition) {
        return new ScreenResponse(
                definition.id(), definition.code(), definition.name(), definition.description(),
                definition.type(), definition.mode(), definition.universeCode(), definition.active(),
                !definition.isPreset(),
                ConditionPayloadMapper.toPayload(definition.conditions()));
    }

    /** 목록용. 조건이 없다({@code conditions}가 null) — 목록 화면은 이름만 쓴다. */
    public static ScreenResponse of(ScreenStore.ScreenSummary summary) {
        return new ScreenResponse(
                summary.id(), summary.code(), summary.name(), summary.description(),
                summary.type(), summary.mode(), summary.universeCode(), summary.active(),
                summary.type() != ScreenType.PRESET,
                null);
    }
}
