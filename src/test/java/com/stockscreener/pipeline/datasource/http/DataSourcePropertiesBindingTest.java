package com.stockscreener.pipeline.datasource.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 두 번째 base URL이 <b>어느 이름으로 바인딩되는가</b>를 잠근다.
 *
 * <p>한 소스가 base URL을 둘 갖는 일이 실재한다 — Alpaca의 Trading/Market Data, 공공데이터포털의
 * 시세/지수, SEC의 {@code data.sec.gov}/{@code www.sec.gov}. 그것을 <b>소스 블록 안의 키로
 * 두면 조용히 무시된다</b>: {@link DataSourceProperties.Source}에 세터가 없고
 * {@code ignoreUnknownFields} 기본값이 삼키기 때문이다. 그 줄은 <b>아무 일도 하지 않으면서
 * 설정처럼 보이고</b>, 자바 기본값과 값이 같으면 증상조차 없다 — 실제로 그 상태였다.
 *
 * <p>이 테스트가 그 함정 자체를 단언한다. 없으면 다음 세션이 「설정에 있으니 바뀌겠지」로
 * 읽고 같은 줄을 되살린다.
 */
class DataSourcePropertiesBindingTest {

    private static final String CHANGED = "https://example.invalid";

    @Test
    @DisplayName("소스 블록 안에 둔 두 번째 base URL은 조용히 무시된다")
    void nestedSecondBaseUrlIsSilentlyIgnored() {
        DataSourceProperties bound = bind(Map.of(
                "stockscreener.datasource.alpaca.market-data-base-url", CHANGED));

        assertThat(bound.getAlpacaMarketDataBaseUrl())
                .as("Source에 세터가 없어 삼켜진다 — 예외도 경고도 없다")
                .isNotEqualTo(CHANGED)
                .isEqualTo("https://data.alpaca.markets");
    }

    @Test
    @DisplayName("최상위 이름으로 두면 바인딩된다 — 고쳐야 할 때 여기로 준다")
    void topLevelSecondBaseUrlBinds() {
        assertThat(bind(Map.of(
                "stockscreener.datasource.alpaca-market-data-base-url", CHANGED))
                .getAlpacaMarketDataBaseUrl()).isEqualTo(CHANGED);
        assertThat(bind(Map.of(
                "stockscreener.datasource.sec-edgar-files-base-url", CHANGED))
                .getSecEdgarFilesBaseUrl()).isEqualTo(CHANGED);
    }

    @Test
    @DisplayName("소스 블록의 키는 그대로 바인딩된다 — 위 함정이 중첩 자체의 문제가 아니다")
    void nestedSourceFieldsStillBind() {
        DataSourceProperties bound = bind(Map.of(
                "stockscreener.datasource.sec-edgar.api-key", "Test Runner test@example.com",
                "stockscreener.datasource.sec-edgar.permits-per-minute", "300"));

        assertThat(bound.getSecEdgar().hasApiKey()).isTrue();
        assertThat(bound.getSecEdgar().getPermitsPerMinute()).isEqualTo(300);
        assertThat(bound.getSecEdgar().getBaseUrl()).isEqualTo("https://data.sec.gov");
    }

    private static DataSourceProperties bind(Map<String, Object> properties) {
        MockEnvironment environment = new MockEnvironment();
        properties.forEach((k, v) -> environment.setProperty(k, String.valueOf(v)));
        return new Binder(ConfigurationPropertySources.get(environment))
                .bind("stockscreener.datasource", Bindable.of(DataSourceProperties.class))
                .orElseGet(DataSourceProperties::new);
    }
}
