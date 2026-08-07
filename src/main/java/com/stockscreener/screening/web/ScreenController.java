package com.stockscreener.screening.web;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.store.ScreenDefinition;
import com.stockscreener.screening.store.ScreenDefinitionValidator;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.watchlist.WatchlistStore;
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

    /** 삭제가 막혔을 때 <b>몇 건이 막고 있는지</b>를 세기 위해서만 쓴다. */
    private final WatchlistStore watchlist;

    public ScreenController(ScreenStore store, IndicatorCatalog indicatorCatalog,
                            WatchlistStore watchlist) {
        this.store = store;
        this.indicatorCatalog = indicatorCatalog;
        this.watchlist = watchlist;
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
        // watchlist_item.screen_id는 CASCADE도 SET NULL도 아니다(V3). SET NULL이었다면 화면을
        // 지운 순간 워치리스트가 조용히 알림을 멈추고, 사용자는 그것을 "조건에 안 맞았나 보다"로
        // 읽는다. 막고 이유를 말하는 편이 낫다.
        //
        // IMPORTANT — 건수를 삭제 **전에** 센다. 제약 위반이 난 뒤에 세면 그 트랜잭션은 이미
        // 중단 상태(PostgreSQL 25P02)라 다음 쿼리가 "current transaction is aborted"로 실패한다.
        // 이 함정은 바깥 트랜잭션이 있을 때만 나타나서, 운영은 멀쩡한데 테스트만 깨지거나
        // 그 반대가 된다 — 실제로 여기서 한 번 걸렸다.
        int watching = watchlist.countByScreen(id);
        if (watching > 0) {
            throw new ApiExceptions.ScreenInUseException(id, watching, null);
        }

        try {
            store.delete(id);
        } catch (DataIntegrityViolationException e) {
            // 위 검사와 삭제 사이에 누가 담았다. 흔치 않지만 500으로 나가서는 안 된다.
            // 건수를 다시 세지 않는 이유가 위 IMPORTANT에 있다.
            throw new ApiExceptions.ScreenInUseException(id, 0, e);
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
