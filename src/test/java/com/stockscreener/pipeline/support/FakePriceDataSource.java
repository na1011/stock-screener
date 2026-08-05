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
        fetchedStockIds.clear();
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
        DataSourceException failure = failures.get(security.stockId());
        if (failure != null) {
            throw failure;
        }
        return barsByStock.getOrDefault(security.stockId(), List.of()).stream()
                .filter(bar -> !bar.tradeDate().isBefore(from) && !bar.tradeDate().isAfter(to))
                .toList();
    }

    @Override
    public List<CorporateAction> fetchCorporateActions(SecurityRef security, LocalDate from, LocalDate to) {
        return actionsByStock.getOrDefault(security.stockId(), List.of());
    }
}
