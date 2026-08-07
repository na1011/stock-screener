package com.stockscreener.watchlist.web;

import com.stockscreener.screening.store.ScreenDefinition;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.stock.StockReader;
import com.stockscreener.watchlist.WatchlistItem;
import com.stockscreener.watchlist.WatchlistStore;
import com.stockscreener.web.ApiExceptions;
import com.stockscreener.web.CurrentUserId;

import jakarta.validation.Valid;

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
 * 워치리스트 CRUD. spec §3의 "관심 종목을 저장해 지속적으로 추적한다"가 여기다.
 *
 * <p>인가 규칙이 {@code ScreenController}와 같다 — <b>남의 항목은 404다.</b> 403은
 * "있지만 네 것이 아니다"를 알려주어 다른 사용자의 관심종목 존재 여부를 탐색할 수 있게 한다.
 * 지인이 쓰는 서비스라도 계정별 경계는 유지한다.
 *
 * <p>알림 조건({@code screenId})에 <b>남의 화면을 걸 수 없다.</b> 걸 수 있으면 존재하지
 * 않는 id와 존재하는 남의 id를 응답으로 구분할 수 있게 되어 같은 탐색이 가능해진다.
 */
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistStore store;
    private final ScreenStore screenStore;
    private final StockReader stockReader;

    public WatchlistController(WatchlistStore store, ScreenStore screenStore,
                               StockReader stockReader) {
        this.store = store;
        this.screenStore = screenStore;
        this.stockReader = stockReader;
    }

    @GetMapping
    public List<WatchlistResponse> list(@CurrentUserId long userId) {
        return store.listRows(userId).stream().map(WatchlistResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistResponse create(@CurrentUserId long userId,
                                    @Valid @RequestBody WatchlistRequest request) {
        requireStock(request.stockId());
        requireUsableScreen(request.screenId(), userId);

        try {
            WatchlistItem created = store.create(
                    userId, request.stockId(), request.screenId(), request.memo());
            return findRow(created.id(), userId);
        } catch (WatchlistStore.AlreadyWatchedException e) {
            throw new ApiExceptions.AlreadyWatchedException(e.stockId(), e);
        }
    }

    /**
     * 알림 조건과 메모를 바꾼다.
     *
     * <p>종목은 바꿀 수 없다 — 다른 종목을 추적하는 것은 다른 항목을 담는 것이다.
     * 바꿀 수 있게 하면 {@code uq_watchlist_user_stock}와 부딪히는 경로가 하나 더 생긴다.
     */
    @PutMapping("/{id}")
    public WatchlistResponse update(@CurrentUserId long userId, @PathVariable long id,
                                    @Valid @RequestBody WatchlistRequest request) {
        requireUsableScreen(request.screenId(), userId);

        if (!store.update(id, userId, request.screenId(), request.memo())) {
            throw new ApiExceptions.WatchlistItemNotFoundException(id);
        }
        return findRow(id, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId long userId, @PathVariable long id) {
        if (!store.delete(id, userId)) {
            throw new ApiExceptions.WatchlistItemNotFoundException(id);
        }
    }

    /**
     * 담으려는 종목이 실제로 있는지 본다.
     *
     * <p>FK가 이미 막지만, 그대로 두면 {@code DataIntegrityViolationException}이 500으로
     * 나간다. 없는 종목을 담는 것은 사용자 입력 문제이므로 404가 맞다.
     *
     * <p>기준일을 오늘로 넘기는 이유는 여기서 필요한 것이 <b>존재 여부</b>뿐이기 때문이다.
     * {@code findById}는 시세를 LEFT JOIN하므로 그날 시세가 없어도 행을 준다.
     */
    private void requireStock(long stockId) {
        stockReader.findById(stockId, java.time.LocalDate.now())
                .orElseThrow(() -> new ApiExceptions.StockNotFoundException(stockId));
    }

    /**
     * 알림 조건으로 쓸 수 있는 화면인지 본다. {@code null}은 "알림 없이 추적만"이라 통과한다.
     *
     * <p>{@code ScreeningService.isVisibleTo}와 같은 판정이다 — 프리셋이거나 본인 화면.
     * 비활성 프리셋도 거절한다: 시더가 선언에서 뺀 프리셋은 더 이상 유지되지 않는 전략이고,
     * 그것을 새 알림 조건으로 거는 것은 사용자가 원한 바가 아니다.
     */
    private void requireUsableScreen(Long screenId, long userId) {
        if (screenId == null) {
            return;
        }
        ScreenDefinition definition = screenStore.findById(screenId)
                .filter(d -> d.isPreset() ? d.active() : Long.valueOf(userId).equals(d.ownerUserId()))
                .orElseThrow(() -> new ApiExceptions.ScreenNotFoundException(screenId));
        // 조건이 하나도 없는 화면은 알림 조건이 될 수 없다. 모든 종목이 매일 충족한다.
        if (definition.conditions().conditions().isEmpty()) {
            throw ApiExceptions.invalidField("screenId",
                    "조건이 없는 화면은 알림 조건으로 쓸 수 없다: " + definition.name());
        }
    }

    /**
     * 방금 쓴 항목을 목록 형태로 다시 읽는다.
     *
     * <p>종목명·화면명을 응답에 실으려면 조인이 필요하고, 그 조인은 목록 쿼리에 이미 있다.
     * 여기서 따로 조립하면 목록과 단건의 모양이 갈리기 시작한다.
     */
    private WatchlistResponse findRow(long id, long userId) {
        return store.listRows(userId).stream()
                .filter(row -> row.item().id() == id)
                .findFirst()
                .map(WatchlistResponse::of)
                .orElseThrow(() -> new ApiExceptions.WatchlistItemNotFoundException(id));
    }
}
