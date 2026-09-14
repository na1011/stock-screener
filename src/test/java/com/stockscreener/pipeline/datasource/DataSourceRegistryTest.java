package com.stockscreener.pipeline.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 라우팅과 <b>기동 시점 실패</b>를 검증한다.
 *
 * <p>한 시장을 두 어댑터가 담당하는 상황이 새벽 배치 도중 비결정적으로 드러나는 것보다
 * 애플리케이션이 뜨지 않는 편이 낫다는 것이 이 클래스의 설계 의도다.
 */
class DataSourceRegistryTest {

    @Test
    @DisplayName("시장 코드로 시세 소스를 찾는다")
    void routesPriceSourceByMarket() throws Exception {
        PriceDataSource kr = new StubPriceSource("kr-source", Set.of("KOSPI", "KOSDAQ"));
        PriceDataSource us = new StubPriceSource("us-source", Set.of("US"));
        DataSourceRegistry registry = registryOf(List.of(kr, us));

        assertThat(registry.priceSource("KOSPI")).isSameAs(kr);
        assertThat(registry.priceSource("KOSDAQ")).isSameAs(kr);
        assertThat(registry.priceSource("US")).isSameAs(us);
    }

    @Test
    @DisplayName("같은 시장을 두 소스가 담당하면 기동에 실패한다")
    void failsFastOnDuplicateMarket() {
        PriceDataSource first = new StubPriceSource("a", Set.of("KOSPI"));
        PriceDataSource second = new StubPriceSource("b", Set.of("KOSPI"));

        assertThatThrownBy(() -> registryOf(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KOSPI")
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("담당 코드를 선언하지 않은 소스는 기동에 실패한다")
    void failsFastOnEmptyMarketDeclaration() {
        assertThatThrownBy(() -> registryOf(List.of(new StubPriceSource("empty", Set.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("담당 코드");
    }

    @Test
    @DisplayName("등록되지 않은 시장을 조회하면 예외다 — 조용히 건너뛰면 그 시장이 통째로 누락된다")
    void throwsForUnregisteredMarket() {
        DataSourceRegistry registry = registryOf(List.of(new StubPriceSource("kr", Set.of("KOSPI"))));

        assertThatThrownBy(() -> registry.priceSource("US"))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("US");
        assertThat(registry.hasPriceSource("US")).isFalse();
        assertThat(registry.hasPriceSource("KOSPI")).isTrue();
    }

    @Test
    @DisplayName("어댑터가 하나도 없어도 기동한다 — 키가 없는 단계에서도 나머지를 테스트해야 한다")
    void bootsWithNoAdapters() {
        DataSourceRegistry registry = registryOf(List.of());

        assertThat(registry.priceCapableMarkets()).isEmpty();
        assertThat(registry.findUniverseSource("SP500")).isEmpty();
    }

    @Test
    @DisplayName("같은 시장을 재무 소스 둘이 담당해도 포트가 다르면 공존한다")
    void twoFinancialPortsCoexistOnTheSameMarket() throws Exception {
        // 중복 검사의 단위는 (포트 타입, 시장)이다. 요약본과 전체본은 주는 것도 조회 비용도
        // 달라 포트를 나눴고, 그래서 둘 다 KOSPI를 담당해도 기동이 막히지 않아야 한다.
        // 이것이 깨지면 운영에서만 드러난다 — 두 어댑터가 @ConditionalOnApiKey라
        // 키가 없는 CI에서는 아예 등록되지 않아 충돌 자체가 보이지 않는다.
        FinancialDataSource summary = new StubFinancialSource("summary", Set.of("KOSPI", "KOSDAQ"));
        FullStatementDataSource full = new StubFullStatementSource("full", Set.of("KOSPI", "KOSDAQ"));

        DataSourceRegistry registry = new DataSourceRegistry(
                List.of(), List.of(summary), List.of(full), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of(),
                List.of());

        assertThat(registry.financialSource("KOSPI")).isSameAs(summary);
        assertThat(registry.fullStatementSource("KOSPI")).isSameAs(full);
    }

    @Test
    @DisplayName("전체 재무제표 소스도 같은 시장에 둘이면 기동에 실패한다")
    void failsFastOnDuplicateFullStatementMarket() {
        FullStatementDataSource first = new StubFullStatementSource("a", Set.of("KOSPI"));
        FullStatementDataSource second = new StubFullStatementSource("b", Set.of("KOSPI"));

        assertThatThrownBy(() -> new DataSourceRegistry(
                List.of(), List.of(), List.of(first, second), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KOSPI")
                .hasMessageContaining("중복");
    }

    private static DataSourceRegistry registryOf(List<PriceDataSource> priceSources) {
        return new DataSourceRegistry(
                priceSources, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private record StubPriceSource(String name, Set<String> markets) implements PriceDataSource {
        @Override
        public Set<String> supportedMarketCodes() {
            return markets;
        }

        @Override
        public String sourceName() {
            return name;
        }

        @Override
        public List<RawPriceBar> fetchDailyBars(SecurityRef security, LocalDate from, LocalDate to) {
            return List.of();
        }
    }

    private record StubFinancialSource(String name, Set<String> markets)
            implements FinancialDataSource {
        @Override
        public Set<String> supportedMarketCodes() {
            return markets;
        }

        @Override
        public String sourceName() {
            return name;
        }

        @Override
        public FinancialFetch fetchReports(SecurityRef security, int fromFiscalYear) {
            return FinancialFetch.of(List.of());
        }
    }

    private record StubFullStatementSource(String name, Set<String> markets)
            implements FullStatementDataSource {
        @Override
        public Set<String> supportedMarketCodes() {
            return markets;
        }

        @Override
        public String sourceName() {
            return name;
        }

        @Override
        public FullStatementResult fetchStatements(SecurityRef security, int fromFiscalYear,
                                                   int toFiscalYear, Set<StatementPeriod> periods) {
            return new FullStatementResult(List.of(), 0, 0);
        }
    }
}
