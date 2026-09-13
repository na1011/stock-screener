package com.stockscreener.pipeline.support;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.CorporateActionDataSource;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테스트용 인메모리 기업 행위 소스.
 *
 * <p>MUST: {@code [from, to]}를 <b>{@code effectiveDate} 기준으로 거른다.</b> 포트 계약이
 * 구현체에 그것을 요구하므로({@link CorporateActionDataSource#fetchCorporateActions}), 거르지
 * 않는 더블은 <b>계약보다 느슨해</b> 어댑터의 창 위반을 통과시킨다. 소스 원값으로 거르면
 * 일자 보정 뒤 창 안으로 들어올 사건이 미리 잘리므로 기준은 효력일이다.
 */
public class FakeCorporateActionDataSource implements CorporateActionDataSource {

    private final Set<String> markets;
    private final Map<Long, List<CorporateAction>> actionsByStock = new HashMap<>();
    private final Map<Long, DataSourceException> failures = new HashMap<>();

    /** 호출된 종목. "봉이 없으면 부르지 않는다"를 재는 데 쓴다. */
    public final List<Long> fetchedStockIds = new ArrayList<>();

    /** 「안 담기로 해서 세기만 한 것」을 종목마다 주입한다. 키가 이유다. */
    public final java.util.Map<Long, java.util.Map<String, Integer>> notStoredByStock =
            new java.util.HashMap<>();

    public FakeCorporateActionDataSource(String... markets) {
        this.markets = Set.of(markets);
    }

    public FakeCorporateActionDataSource with(long stockId, CorporateAction action) {
        actionsByStock.computeIfAbsent(stockId, k -> new ArrayList<>()).add(action);
        return this;
    }

    public FakeCorporateActionDataSource failingWith(long stockId, DataSourceException exception) {
        failures.put(stockId, exception);
        return this;
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return markets;
    }

    @Override
    public String sourceName() {
        return "fake-corporate-action";
    }

    @Override
    public Fetched fetchCorporateActions(SecurityRef security,
                                                       LocalDate from, LocalDate to)
            throws DataSourceException {
        fetchedStockIds.add(security.stockId());
        DataSourceException failure = failures.get(security.stockId());
        if (failure != null) {
            throw failure;
        }
        return new Fetched(actionsByStock.getOrDefault(security.stockId(), List.of()).stream()
                .filter(action -> !action.effectiveDate().isBefore(from)
                        && !action.effectiveDate().isAfter(to))
                .toList(),
                notStoredByStock.getOrDefault(security.stockId(), java.util.Map.of()));
    }
}
