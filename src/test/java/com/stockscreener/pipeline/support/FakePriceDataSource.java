package com.stockscreener.pipeline.support;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.PriceDataSource;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테스트용 인메모리 시세 소스.
 *
 * <p>API 키 없이 파이프라인 전체를 검증하기 위한 것이다. 1~7단계 검증의 대부분이 이
 * 계층에서 이뤄지며, 실제 어댑터가 붙어도 이 테스트들은 그대로 유효하다 —
 * 포트 계약을 검증하지 벤더를 검증하지 않기 때문이다.
 */
public class FakePriceDataSource implements PriceDataSource {

    private final Set<String> markets;
    private final Map<Long, List<RawPriceBar>> barsByStock = new HashMap<>();
    private final Map<Long, List<CorporateAction>> actionsByStock = new HashMap<>();
    private final Map<Long, DataSourceException> failures = new HashMap<>();

    /** 호출된 종목을 기록한다. "재실행이 성공 종목을 다시 호출하지 않는지" 검증에 쓴다. */
    public final List<Long> fetchedStockIds = new ArrayList<>();

    /** 마지막으로 요청된 조회 시작일. 적재 기간이 실제로 넓어졌는지 확인할 때 쓴다. */
    public LocalDate lastFrom;

    /** 받은 호출 하나. 창 콜과 구멍 구간 콜이 정확히 어떤 범위로 나갔는지 볼 때 쓴다. */
    public record Call(long stockId, LocalDate from, LocalDate to) {
    }

    /** 호출을 순서대로 기록한다. */
    public final List<Call> calls = new ArrayList<>();

    private final Map<Long, LocalDate> failingBefore = new HashMap<>();
    private final Map<Long, DataSourceException> failingBeforeWith = new HashMap<>();

    public FakePriceDataSource(String... markets) {
        this.markets = Set.of(markets);
    }

    public FakePriceDataSource withBars(long stockId, List<RawPriceBar> bars) {
        barsByStock.put(stockId, bars);
        return this;
    }

    public FakePriceDataSource withCorporateAction(long stockId, CorporateAction action) {
        actionsByStock.computeIfAbsent(stockId, k -> new ArrayList<>()).add(action);
        return this;
    }

    public FakePriceDataSource failingWith(long stockId, DataSourceException exception) {
        failures.put(stockId, exception);
        return this;
    }

    /**
     * {@code from}이 {@code before}보다 앞인 호출만 실패시킨다 — 창 콜은 성공하고 그 앞의 구멍 구간 콜만
     * 실패하는 상황을 만든다.
     */
    public FakePriceDataSource failingBefore(long stockId, LocalDate before, DataSourceException exception) {
        failingBefore.put(stockId, before);
        failingBeforeWith.put(stockId, exception);
        return this;
    }

    /** 실패를 해제한다. 재실행이 이번엔 성공하는 상황을 만들 때 쓴다. */
    public FakePriceDataSource clearFailure(long stockId) {
        failures.remove(stockId);
        return this;
    }

    /** 상태를 초기화한다. 컨텍스트가 캐시되므로 테스트마다 필요하다. */
    public void reset() {
        barsByStock.clear();
        actionsByStock.clear();
        failures.clear();
        failingBefore.clear();
        failingBeforeWith.clear();
        fetchedStockIds.clear();
        calls.clear();
        lastFrom = null;
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return markets;
    }

    @Override
    public String sourceName() {
        return "fake-price";
    }

    @Override
    public List<RawPriceBar> fetchDailyBars(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException {
        fetchedStockIds.add(security.stockId());
        calls.add(new Call(security.stockId(), from, to));
        lastFrom = from;
        DataSourceException failure = failures.get(security.stockId());
        if (failure != null) {
            throw failure;
        }
        LocalDate before = failingBefore.get(security.stockId());
        if (before != null && from.isBefore(before)) {
            throw failingBeforeWith.get(security.stockId());
        }
        return barsByStock.getOrDefault(security.stockId(), List.of()).stream()
                .filter(bar -> !bar.tradeDate().isBefore(from) && !bar.tradeDate().isAfter(to))
                .toList();
    }

    /**
     * MUST: {@code [from, to]}를 <b>거른다.</b> 실제 소스는 한 번의 호출이 5년 이력을 주므로,
     * 거르지 않는 더블은 <b>포트 계약보다 느슨해</b> 어댑터의 창 위반을 통과시킨다
     * (ADR-0049가 이 자리를 지목했다). 기준은 {@code effectiveDate}다 — 계수가 걸리는
     * 날짜가 그것이고, 소스 원값으로 거르면 보정 뒤 창 안으로 들어올 사건이 미리 잘린다.
     */
    @Override
    public List<CorporateAction> fetchCorporateActions(SecurityRef security, LocalDate from, LocalDate to) {
        return actionsByStock.getOrDefault(security.stockId(), List.of()).stream()
                .filter(action -> !action.effectiveDate().isBefore(from)
                        && !action.effectiveDate().isAfter(to))
                .toList();
    }
}
