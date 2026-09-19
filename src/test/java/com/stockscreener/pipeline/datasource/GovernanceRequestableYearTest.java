package com.stockscreener.pipeline.datasource;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.us.AlpacaDividendAdapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code bsnsYear}의 <b>뜻이 소스마다 다르다</b>는 것을 잠근다.
 *
 * <p>이 시험이 있는 이유는 <b>갈려도 아무것도 안 깨지기</b> 때문이다. 미국에 DART 규칙이
 * 실리면 역년 {@code asOf.getYear()}가 영영 요청되지 않고, 회계연도가 역년 둘 이상에 걸치는
 * 종목은 {@code AlpacaGovernanceFactMapper.coversWholeWindow}에 막혀 <b>매년 한 해 뒤처진다</b> —
 * 그런데 값은 결측이 아니라 <b>1년 낡은 배당을 오늘 종가로 나눈 그럴듯한 수</b>로 화면에
 * 남는다. 2026-09-20에 편입 1,000 중 <b>158종목</b>이 그 상태였고, 12월 결산 723종목이
 * <b>100% 최신</b>이라 축이 정확히 갈렸다.
 */
class GovernanceRequestableYearTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 20);

    /** 생성자가 base URL을 즉시 읽으므로 목이 필요하다. 이 시험은 HTTP를 한 번도 안 탄다. */
    private static AlpacaDividendAdapter alpacaDividend() {
        DataSourceProperties properties = mock(DataSourceProperties.class);
        when(properties.getAlpacaMarketDataBaseUrl()).thenReturn("https://example.invalid");
        DataSourceProperties.Source source = mock(DataSourceProperties.Source.class);
        // AlpacaHeaders.of가 Map.of를 쓰므로 null이면 NPE다. 이 시험은 HTTP를 안 타지만
        // 생성자가 헤더를 미리 만든다.
        when(source.getApiKey()).thenReturn("key");
        when(source.getSecretKey()).thenReturn("secret");
        when(properties.getAlpaca()).thenReturn(source);
        return new AlpacaDividendAdapter(mock(ExternalApiClientFactory.class), properties,
                mock(com.stockscreener.pipeline.datasource.http.RateLimiter.class));
    }

    /**
     * 기본값을 쓰는 소스. <b>구현을 흉내 내지 않고 인터페이스의 기본 구현을 그대로 태운다</b> —
     * 여기서 특정 어댑터를 쓰면 그 어댑터가 오버라이드하는 날 이 시험이 뜻을 잃는다.
     */
    private static final GovernanceDataSource DEFAULTS = new GovernanceDataSource() {
        @Override
        public java.util.Set<String> supportedMarketCodes() {
            return java.util.Set.of("KOSPI");
        }

        @Override
        public String sourceName() {
            return "test:defaults";
        }

        @Override
        public List<GovernanceReportRow> fetchReport(SecurityRef security,
                                                     GovernanceEndpoint endpoint,
                                                     int bsnsYear, String reportCode) {
            return List.of();
        }
    };

    @Test
    @DisplayName("기본은 작년이다 — DART의 bsnsYear는 사업보고서의 회계연도이고 올해분은 이듬해 3월에야 나온다")
    void defaultsToLastYear() {
        assertThat(DEFAULTS.latestRequestableYear(AS_OF)).isEqualTo(2025);
    }

    @Test
    @DisplayName("Alpaca 배당은 올해다 — 거기서 bsnsYear는 ex_date의 역년이고 배당은 그해에 이미 지급됐다")
    void alpacaDividendReachesThisYear() {
        assertThat(alpacaDividend().latestRequestableYear(AS_OF))
                .isEqualTo(2026);
    }

    @Test
    @DisplayName("둘이 정확히 한 해 갈린다 — 그 한 해가 52/53주 회계연도를 통째로 가른다")
    void theTwoDifferByExactlyOneYear() {
        // MUST: 이 부등식을 뒤집지 않는다. 기본값이 더 늦으면 새 소스가 말없이 붙었을 때
        // 거짓 「데이터 없음」 행이 쌓이고, 그 행은 커버리지 판정의 재료다.
        assertThat(alpacaDividend().latestRequestableYear(AS_OF))
                .isEqualTo(DEFAULTS.latestRequestableYear(AS_OF) + 1);
    }
}
