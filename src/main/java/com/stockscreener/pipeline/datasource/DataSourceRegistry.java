package com.stockscreener.pipeline.datasource;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 시장·지수 코드로 어댑터를 찾는다.
 *
 * <p>Spring이 각 포트를 구현한 모든 {@code @Component}를 주입한다. 그래서 소스를 교체할 때
 * 이 클래스를 <b>고치지 않는다</b> — 어댑터 클래스 하나를 갈아끼우면 끝이다. 그것이
 * {@code data-pipeline.md}의 "소스 교체 영향의 국소화"를 구조로 만드는 방식이며,
 * {@link com.stockscreener.indicator.IndicatorRegistry}가 지표에 대해 하는 것과 같다.
 *
 * <p>한 시장을 두 어댑터가 담당하는 상황을 <b>기동 시점에</b> 실패시킨다. 새벽 배치 도중
 * 비결정적으로 다른 소스가 뽑히는 것보다 애플리케이션이 뜨지 않는 편이 낫다.
 *
 * <p>어댑터가 하나도 없는 상태로 기동하는 것은 허용한다. API 키가 아직 없어 어댑터를 만들지
 * 못한 단계에서도 나머지 파이프라인을 테스트할 수 있어야 하기 때문이다. 대신 실제로 조회할 때
 * 명확한 예외를 던진다.
 */
@Component
public class DataSourceRegistry {

    private final Map<String, PriceDataSource> priceByMarket;
    private final Map<String, FinancialDataSource> financialByMarket;
    private final Map<String, FullStatementDataSource> fullStatementByMarket;
    private final Map<String, SecurityMasterDataSource> masterByMarket;
    private final Map<String, CompanyProfileDataSource> profileByMarket;
    private final Map<String, CorporateActionDataSource> corporateActionByMarket;
    private final Map<String, UniverseDataSource> universeByCode;
    private final Map<String, MarketIndexDataSource> marketIndexByCode;

    public DataSourceRegistry(
            List<PriceDataSource> priceSources,
            List<FinancialDataSource> financialSources,
            List<FullStatementDataSource> fullStatementSources,
            List<SecurityMasterDataSource> masterSources,
            List<CompanyProfileDataSource> profileSources,
            List<CorporateActionDataSource> corporateActionSources,
            List<UniverseDataSource> universeSources,
            List<MarketIndexDataSource> marketIndexSources) {
        this.priceByMarket = index(priceSources, PriceDataSource::supportedMarketCodes, "시세");
        this.financialByMarket = index(financialSources, FinancialDataSource::supportedMarketCodes, "재무");
        this.fullStatementByMarket =
                index(fullStatementSources, FullStatementDataSource::supportedMarketCodes, "전체재무제표");
        this.masterByMarket = index(masterSources, SecurityMasterDataSource::supportedMarketCodes, "종목마스터");
        this.profileByMarket = index(profileSources, CompanyProfileDataSource::supportedMarketCodes, "기업개황");
        this.corporateActionByMarket =
                index(corporateActionSources, CorporateActionDataSource::supportedMarketCodes, "기업행위");
        this.universeByCode = index(universeSources, UniverseDataSource::supportedUniverseCodes, "유니버스");
        this.marketIndexByCode =
                index(marketIndexSources, MarketIndexDataSource::supportedIndexCodes, "시장지수");
    }

    private static <T> Map<String, T> index(
            List<T> sources, Function<T, Set<String>> keysOf, String label) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T source : sources) {
            Set<String> keys = keysOf.apply(source);
            if (keys == null || keys.isEmpty()) {
                throw new IllegalStateException(
                        label + " 소스가 담당 코드를 선언하지 않았다: " + source.getClass().getName());
            }
            for (String key : keys) {
                T previous = map.put(key, source);
                if (previous != null) {
                    throw new IllegalStateException(
                            label + " 소스가 '" + key + "'에 대해 중복 등록되었다: "
                                    + previous.getClass().getName() + ", " + source.getClass().getName()
                                    + " — 어느 쪽이 쓰일지 결정할 수 없다.");
                }
            }
        }
        return Map.copyOf(map);
    }

    /**
     * 해당 시장의 시세 소스.
     *
     * @throws DataSourceException 담당 어댑터가 없을 때. 이것을 예외로 만드는 이유는,
     *                             조용히 건너뛰면 그 시장의 종목이 통째로 갱신되지 않은 채
     *                             배치가 성공으로 끝나기 때문이다.
     */
    public PriceDataSource priceSource(String marketCode) throws DataSourceException {
        return require(priceByMarket, marketCode, "시세");
    }

    public FinancialDataSource financialSource(String marketCode) throws DataSourceException {
        return require(financialByMarket, marketCode, "재무");
    }

    /**
     * 해당 시장의 전체 재무제표 소스.
     *
     * <p>{@link #financialSource}와 <b>따로 있는 것이 정상이다.</b> 둘이 같은 시장을 담당해도
     * 포트 타입이 달라 위 중복 검사에 걸리지 않는다 — 요약본과 전체본은 조회 비용도 주는
     * 항목도 다르므로 하나로 합치면 일일 갱신이 필요하지도 않은 비용을 매번 치른다.
     * 근거는 {@link FullStatementDataSource}에 있다.
     */
    public FullStatementDataSource fullStatementSource(String marketCode) throws DataSourceException {
        return require(fullStatementByMarket, marketCode, "전체재무제표");
    }

    public SecurityMasterDataSource securityMasterSource(String marketCode) throws DataSourceException {
        return require(masterByMarket, marketCode, "종목마스터");
    }

    public CompanyProfileDataSource companyProfileSource(String marketCode) throws DataSourceException {
        return require(profileByMarket, marketCode, "기업개황");
    }

    /**
     * 해당 시장의 기업 행위 소스.
     *
     * <p>MUST NOT: 이것의 {@code Optional} 변형을 만들지 않는다. 국내 어댑터가
     * {@code @ConditionalOnApiKey}라 키가 없으면 <b>빈으로 등록조차 되지 않으므로</b>,
     * 비어 있음을 돌려주면 호출자가 0건을 처리하고 <b>성공으로 끝난다</b> — 수정계수가
     * 영영 1로 남는데 로그도 알림도 없다. 위 {@link #priceSource}의 이유와 같다.
     */
    public CorporateActionDataSource corporateActionSource(String marketCode)
            throws DataSourceException {
        return require(corporateActionByMarket, marketCode, "기업행위");
    }

    public UniverseDataSource universeSource(String universeCode) throws DataSourceException {
        return require(universeByCode, universeCode, "유니버스");
    }

    /**
     * 해당 지수의 소스.
     *
     * <p>{@link #corporateActionSource}와 같은 이유로 {@code Optional} 변형을 만들지 않는다.
     * 국내 어댑터가 {@code @ConditionalOnApiKey}라 키가 없으면 <b>빈으로 등록조차 되지 않고</b>,
     * 비어 있음을 돌려주면 호출자가 0건으로 성공한다 — 그러면 지수가 영영 안 들어오는데
     * <b>전 종목의 {@code ICHI_*}가 통째로 비는 형태</b>로만 드러난다.
     */
    public MarketIndexDataSource marketIndexSource(String indexCode) throws DataSourceException {
        return require(marketIndexByCode, indexCode, "시장지수");
    }

    /** 수집할 수 있는 지수 전체. 잡이 대상을 정할 때 쓴다. */
    public Set<String> marketIndexCodes() {
        return marketIndexByCode.keySet();
    }

    private static <T> T require(Map<String, T> map, String key, String label)
            throws DataSourceException {
        T source = map.get(key);
        if (source == null) {
            throw new DataSourceException(
                    DataSourceException.Kind.NOT_FOUND,
                    "registry",
                    label + " 소스가 등록되지 않았다: " + key + " — 등록된 코드: " + map.keySet());
        }
        return source;
    }

    /** 등록 여부만 확인한다. 배치가 대상 시장을 정할 때 쓴다. */
    public boolean hasPriceSource(String marketCode) {
        return priceByMarket.containsKey(marketCode);
    }

    public boolean hasFinancialSource(String marketCode) {
        return financialByMarket.containsKey(marketCode);
    }

    public Optional<UniverseDataSource> findUniverseSource(String universeCode) {
        return Optional.ofNullable(universeByCode.get(universeCode));
    }

    /** 시세를 수집할 수 있는 시장 전체. */
    public Set<String> priceCapableMarkets() {
        return priceByMarket.keySet();
    }
}
