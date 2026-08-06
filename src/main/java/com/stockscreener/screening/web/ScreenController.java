package com.stockscreener.screening.web;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.store.ScreenDefinition;
import com.stockscreener.screening.store.ScreenDefinitionValidator;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.web.ApiExceptions;
import com.stockscreener.web.CurrentUserId;

import jakarta.validation.Valid;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 저장된 화면 CRUD. 프리셋은 읽기 전용이고 커스텀은 소유자만 다룬다.
 *
 * <p>인가 규칙 둘이 의도적으로 다르다.
 * <ul>
 *   <li><b>남의 커스텀 화면 → 404.</b> 403은 "있지만 네 것이 아니다"를 알려주어 존재 여부를
 *       탐색할 수 있게 한다.
 *   <li><b>프리셋 쓰기 → 403.</b> 프리셋은 목록에서 이미 보이므로 없는 척할 이유가 없다.
 *       "왜 안 되는지"를 알려주는 편이 낫다.
 * </ul>
 */
@RestController
@RequestMapping("/api/screens")
public class ScreenController {

    private final ScreenStore store;
    private final IndicatorCatalog indicatorCatalog;

    public ScreenController(ScreenStore store, IndicatorCatalog indicatorCatalog) {
        this.store = store;
        this.indicatorCatalog = indicatorCatalog;
    }

    @GetMapping
    public List<ScreenResponse> list(@CurrentUserId long userId) {
        return store.listVisibleTo(userId).stream().map(ScreenResponse::of).toList();
    }

    @GetMapping("/{id}")
    public ScreenResponse get(@CurrentUserId long userId, @PathVariable long id) {
        return ScreenResponse.of(load(id, userId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenResponse create(@CurrentUserId long userId, @Valid @RequestBody ScreenRequest request) {
        ConditionGroup conditions = validated(request);

        try {
            return ScreenResponse.of(store.create(ScreenDefinition.custom(
                    null, request.name(), request.description(), request.evaluationMode(),
                    userId, request.universeCode(), conditions)));
        } catch (ScreenStore.DuplicateScreenNameException e) {
            throw new ApiExceptions.DuplicateScreenNameException(e.screenName(), e);
        }
    }

    @PutMapping("/{id}")
    public ScreenResponse update(@CurrentUserId long userId, @PathVariable long id,
                                 @Valid @RequestBody ScreenRequest request) {
        ScreenDefinition existing = load(id, userId);
        requireEditable(existing);

        ConditionGroup conditions = validated(request);

        try {
            store.update(ScreenDefinition.custom(
                    id, request.name(), request.description(), request.evaluationMode(),
                    userId, request.universeCode(), conditions));
        } catch (ScreenStore.DuplicateScreenNameException e) {
            throw new ApiExceptions.DuplicateScreenNameException(e.screenName(), e);
        }
        return ScreenResponse.of(load(id, userId));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId long userId, @PathVariable long id) {
        requireEditable(load(id, userId));
        try {
            store.delete(id);
        } catch (DataIntegrityViolationException e) {
            // watchlist_item.screen_id는 CASCADE가 아니다. Phase 4에는 워치리스트 행이 없지만,
            // 500으로 나가면 Phase 6에서 원인을 찾느라 시간을 쓴다.
            throw new ApiExceptions.ScreenInUseException(id, e);
        }
    }

    /**
     * 조건을 도메인으로 옮기고 v1 불변식을 확인한다.
     *
     * <p>{@code ScreenDefinitionValidator}가 던지는 예외는 조건 인덱스를 들고 있다 —
     * 그것을 400으로 옮겨야 화면이 문제가 된 줄을 강조할 수 있다.
     */
    private ConditionGroup validated(ScreenRequest request) {
        ConditionGroup conditions = ConditionPayloadMapper.toDomain(request.conditions());
        try {
            ScreenDefinitionValidator.validate(conditions, indicatorCatalog.activeCodes());
        } catch (ScreenDefinitionValidator.ScreenValidationException e) {
            throw ConditionPayloadMapper.toApiException(e);
        }
        return conditions;
    }

    private ScreenDefinition load(long id, long userId) {
        return store.findById(id)
                .filter(d -> d.isPreset() || Long.valueOf(userId).equals(d.ownerUserId()))
                .orElseThrow(() -> new ApiExceptions.ScreenNotFoundException(id));
    }

    private static void requireEditable(ScreenDefinition definition) {
        if (definition.isPreset()) {
            throw new ApiExceptions.PresetReadOnlyException(definition.code());
        }
    }
}
